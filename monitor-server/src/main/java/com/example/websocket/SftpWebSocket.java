package com.example.websocket;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.entity.dto.ClientSsh;
import com.example.mapper.ClientSshMapper;
import com.example.utils.CryptoUtils;
import jakarta.annotation.Resource;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SFTP WebSocket 端点，提供轻量目录浏览和小文件传输能力。
 */
@Slf4j
@Component
@ServerEndpoint("/sftp/{clientId}")
public class SftpWebSocket {

    private static final int SSH_CONNECT_TIMEOUT_MS = 10_000;
    private static final int SSH_IO_TIMEOUT_MS = 10_000;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_TRANSFER_BYTES = 10 * 1024 * 1024;
    private static final int MAX_TEXT_MESSAGE_SIZE = MAX_TRANSFER_BYTES * 2;

    private static ClientSshMapper sshMapper;
    private static CryptoUtils cryptoUtils;
    private static final Map<Session, SftpConnection> sessionMap = new ConcurrentHashMap<>();

    /**
     * 注入 SSH 配置 Mapper，供 WebSocket 端点静态访问。
     *
     * @param sshMapper SSH配置Mapper
     */
    @Resource
    public void setSshMapper(ClientSshMapper sshMapper) {
        SftpWebSocket.sshMapper = sshMapper;
    }

    /**
     * 注入密码加解密工具，供 WebSocket 端点静态访问。
     *
     * @param cryptoUtils 密码加解密工具
     */
    @Resource
    public void setCryptoUtils(CryptoUtils cryptoUtils) {
        SftpWebSocket.cryptoUtils = cryptoUtils;
    }

    /**
     * 建立 SFTP WebSocket 后创建 SSH/SFTP 连接。
     *
     * @param session WebSocket会话
     * @param clientId 客户端ID
     * @throws IOException IO异常
     */
    @OnOpen
    public void onOpen(Session session, @PathParam("clientId") String clientId) throws IOException {
        session.setMaxTextMessageBufferSize(MAX_TEXT_MESSAGE_SIZE);
        log.info("正在尝试建立 SFTP 连接，客户端ID: {}, 会话ID: {}", clientId, session.getId());
        ClientSsh ssh = sshMapper.selectById(clientId);
        if (ssh == null) {
            session.close(new CloseReason(CloseReason.CloseCodes.CANNOT_ACCEPT, "无法识别此主机"));
            return;
        }
        this.createSftpConnection(session, ssh);
    }

    /**
     * 处理前端 SFTP 操作请求。
     *
     * @param session WebSocket会话
     * @param message JSON文本消息
     */
    @OnMessage
    public void onMessage(Session session, String message) {
        SftpConnection connection = sessionMap.get(session);
        if (connection == null) {
            this.sendError(session, "SFTP连接不存在");
            return;
        }
        try {
            JSONObject request = JSONObject.parseObject(message);
            String action = request.getString("action");
            switch (action == null ? "" : action) {
                case "list" -> this.handleList(session, connection, request.getString("path"));
                case "download" -> this.handleDownload(session, connection, request.getString("path"));
                case "upload" -> this.handleUpload(
                        session,
                        connection,
                        request.getString("path"),
                        request.getString("contentBase64"));
                case "mkdir" -> this.handleMkdir(session, connection, request.getString("path"));
                case "delete" -> this.handleDelete(
                        session,
                        connection,
                        request.getString("path"),
                        request.getBooleanValue("directory"));
                default -> this.sendError(session, "不支持的SFTP操作");
            }
        } catch (Exception e) {
            log.warn("处理 SFTP 消息失败，sessionId={}, reason={}", session.getId(), e.getMessage());
            this.sendError(session, this.resolveErrorMessage(e));
        }
    }

    /**
     * WebSocket 关闭时释放 SFTP 连接。
     *
     * @param session WebSocket会话
     */
    @OnClose
    public void onClose(Session session) {
        SftpConnection connection = sessionMap.remove(session);
        if (connection != null) {
            connection.close();
            log.info("SFTP 连接已关闭，host={}", connection.targetHost);
        }
    }

    /**
     * WebSocket 异常时释放连接资源。
     *
     * @param session WebSocket会话
     * @param error 异常
     */
    @OnError
    public void onError(Session session, Throwable error) {
        log.error("SFTP WebSocket 连接出现错误", error);
        this.onClose(session);
    }

    /**
     * 创建 SSH/SFTP 连接并绑定到 WebSocket 会话。
     *
     * @param wsSession WebSocket会话
     * @param ssh SSH配置
     * @throws IOException IO异常
     */
    private void createSftpConnection(Session wsSession, ClientSsh ssh) throws IOException {
        SSHClient client = new SSHClient();
        client.addHostKeyVerifier(new PromiscuousVerifier());
        client.setConnectTimeout(SSH_CONNECT_TIMEOUT_MS);
        client.setTimeout(SSH_IO_TIMEOUT_MS);
        try {
            client.connect(ssh.getIp(), ssh.getPort());
            String password = cryptoUtils == null ? ssh.getPassword() : cryptoUtils.decrypt(ssh.getPassword());
            client.authPassword(ssh.getUsername(), password);
            SFTPClient sftp = client.newSFTPClient();
            SftpConnection connection = new SftpConnection(ssh.getIp(), client, sftp);
            sessionMap.put(wsSession, connection);
            this.handleList(wsSession, connection, ".");
            log.info("SFTP 连接已创建，host={}, port={}, user={}", ssh.getIp(), ssh.getPort(), ssh.getUsername());
        } catch (Exception e) {
            SftpConnection connection = sessionMap.remove(wsSession);
            if (connection != null) {
                connection.close();
            } else {
                this.closeClientQuietly(client);
            }
            String reason = this.resolveErrorMessage(e);
            log.error("建立 SFTP 连接失败，host={}, port={}, user={}", ssh.getIp(), ssh.getPort(), ssh.getUsername(), e);
            wsSession.close(new CloseReason(CloseReason.CloseCodes.CANNOT_ACCEPT, reason));
        }
    }

    /**
     * 返回目录列表。
     *
     * @param session WebSocket会话
     * @param connection SFTP连接
     * @param path 远端路径
     * @throws IOException IO异常
     */
    private void handleList(Session session, SftpConnection connection, String path) throws IOException {
        String targetPath = this.defaultPath(path);
        String canonicalPath = connection.sftp.canonicalize(targetPath);
        List<RemoteResourceInfo> resources = connection.sftp.ls(canonicalPath).stream()
                .filter(info -> !".".equals(info.getName()) && !"..".equals(info.getName()))
                .sorted(Comparator
                        .comparing(RemoteResourceInfo::isDirectory).reversed()
                        .thenComparing(RemoteResourceInfo::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        JSONArray entries = new JSONArray();
        for (RemoteResourceInfo info : resources) {
            JSONObject item = new JSONObject();
            item.put("name", info.getName());
            item.put("path", info.getPath());
            item.put("directory", info.isDirectory());
            item.put("regularFile", info.isRegularFile());
            item.put("size", info.getAttributes().getSize());
            item.put("modifiedAt", info.getAttributes().getMtime() * 1000L);
            entries.add(item);
        }
        JSONObject response = this.baseResponse("list");
        response.put("path", canonicalPath);
        response.put("entries", entries);
        this.send(session, response);
    }

    /**
     * 下载小文件并以 base64 响应。
     *
     * @param session WebSocket会话
     * @param connection SFTP连接
     * @param path 远端文件路径
     * @throws IOException IO异常
     */
    private void handleDownload(Session session, SftpConnection connection, String path) throws IOException {
        String targetPath = this.requirePath(path);
        long size = connection.sftp.size(targetPath);
        if (size > MAX_TRANSFER_BYTES) {
            this.sendError(session, "文件超过10MiB，请等待分片传输版本");
            return;
        }
        byte[] content = this.readRemoteFile(connection.sftp, targetPath, size);
        JSONObject response = this.baseResponse("download");
        response.put("path", targetPath);
        response.put("name", this.fileName(targetPath));
        response.put("contentBase64", Base64.getEncoder().encodeToString(content));
        this.send(session, response);
    }

    /**
     * 上传小文件到远端路径。
     *
     * @param session WebSocket会话
     * @param connection SFTP连接
     * @param path 远端文件路径
     * @param contentBase64 文件内容 base64
     * @throws IOException IO异常
     */
    private void handleUpload(Session session,
                              SftpConnection connection,
                              String path,
                              String contentBase64) throws IOException {
        String targetPath = this.requirePath(path);
        if (contentBase64 == null || contentBase64.isBlank()) {
            this.sendError(session, "上传内容为空");
            return;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(contentBase64.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            this.sendError(session, "上传内容格式非法");
            return;
        }
        if (bytes.length > MAX_TRANSFER_BYTES) {
            this.sendError(session, "文件超过10MiB，请等待分片传输版本");
            return;
        }
        try (RemoteFile remoteFile = connection.sftp.open(
                targetPath,
                EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))) {
            remoteFile.write(0, bytes, 0, bytes.length);
        }
        this.sendSuccess(session, "上传成功");
        this.handleList(session, connection, this.parentPath(targetPath));
    }

    /**
     * 创建远端目录。
     *
     * @param session WebSocket会话
     * @param connection SFTP连接
     * @param path 远端目录路径
     * @throws IOException IO异常
     */
    private void handleMkdir(Session session, SftpConnection connection, String path) throws IOException {
        String targetPath = this.requirePath(path);
        connection.sftp.mkdirs(targetPath);
        this.sendSuccess(session, "目录已创建");
        this.handleList(session, connection, this.parentPath(targetPath));
    }

    /**
     * 删除远端文件或空目录。
     *
     * @param session WebSocket会话
     * @param connection SFTP连接
     * @param path 远端路径
     * @param directory 是否目录
     * @throws IOException IO异常
     */
    private void handleDelete(Session session,
                              SftpConnection connection,
                              String path,
                              boolean directory) throws IOException {
        String targetPath = this.requirePath(path);
        if (directory) {
            connection.sftp.rmdir(targetPath);
        } else {
            connection.sftp.rm(targetPath);
        }
        this.sendSuccess(session, "删除成功");
        this.handleList(session, connection, this.parentPath(targetPath));
    }

    /**
     * 读取远端文件内容。
     *
     * @param sftp SFTP客户端
     * @param path 远端文件路径
     * @param size 文件大小
     * @return 文件字节数组
     * @throws IOException IO异常
     */
    private byte[] readRemoteFile(SFTPClient sftp, String path, long size) throws IOException {
        try (RemoteFile file = sftp.open(path, EnumSet.of(OpenMode.READ));
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) size)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long offset = 0;
            int read;
            while ((read = file.read(offset, buffer, 0, buffer.length)) > 0) {
                output.write(buffer, 0, read);
                offset += read;
            }
            return output.toByteArray();
        }
    }

    /**
     * 构建基础响应对象。
     *
     * @param type 响应类型
     * @return JSON响应
     */
    private JSONObject baseResponse(String type) {
        JSONObject response = new JSONObject();
        response.put("type", type);
        return response;
    }

    /**
     * 发送成功消息。
     *
     * @param session WebSocket会话
     * @param message 消息
     */
    private void sendSuccess(Session session, String message) {
        JSONObject response = this.baseResponse("success");
        response.put("message", message);
        this.send(session, response);
    }

    /**
     * 发送错误消息。
     *
     * @param session WebSocket会话
     * @param message 错误消息
     */
    private void sendError(Session session, String message) {
        JSONObject response = this.baseResponse("error");
        response.put("message", message);
        this.send(session, response);
    }

    /**
     * 发送 JSON 文本。
     *
     * @param session WebSocket会话
     * @param payload JSON响应
     */
    private void send(Session session, JSONObject payload) {
        try {
            if (session.isOpen()) {
                session.getBasicRemote().sendText(payload.toJSONString());
            }
        } catch (IOException e) {
            log.warn("发送 SFTP 响应失败，sessionId={}, reason={}", session.getId(), e.getMessage());
        }
    }

    /**
     * 解析用户可读错误消息。
     *
     * @param throwable 异常
     * @return 错误消息
     */
    private String resolveErrorMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null) {
            return "SFTP操作失败";
        }
        if (throwable instanceof net.schmizz.sshj.userauth.UserAuthException || message.contains("Auth fail")) {
            return "登录 SSH 失败，用户名或密码错误";
        }
        if (throwable instanceof ConnectException || message.contains("Connection refused")) {
            return "连接被拒绝，可能未开启 SSH 服务或端口未放通";
        }
        if (throwable instanceof SocketTimeoutException || message.contains("timed out")) {
            return "连接超时，请检查网络或防火墙设置";
        }
        if (message.contains("No such file")) {
            return "文件或目录不存在";
        }
        if (message.contains("Permission denied")) {
            return "没有远端文件权限";
        }
        return message;
    }

    /**
     * 校验并返回非空路径。
     *
     * @param path 路径
     * @return 非空路径
     */
    private String requirePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }
        return path;
    }

    /**
     * 返回默认路径。
     *
     * @param path 路径
     * @return 默认路径
     */
    private String defaultPath(String path) {
        return path == null || path.isBlank() ? "." : path;
    }

    /**
     * 获取文件名。
     *
     * @param path 路径
     * @return 文件名
     */
    private String fileName(String path) {
        int index = path.lastIndexOf('/');
        return index >= 0 ? path.substring(index + 1) : path;
    }

    /**
     * 获取父路径。
     *
     * @param path 路径
     * @return 父路径
     */
    private String parentPath(String path) {
        if (path == null || path.isBlank() || ".".equals(path)) {
            return ".";
        }
        String normalized = path.replaceAll("/+$", "");
        if (normalized.isEmpty() || "/".equals(normalized)) {
            return "/";
        }
        int index = normalized.lastIndexOf('/');
        if (index == 0) {
            return "/";
        }
        if (index < 0) {
            return ".";
        }
        return normalized.substring(0, index);
    }

    /**
     * 安静关闭 SSHClient。
     *
     * @param client SSHClient
     */
    private void closeClientQuietly(SSHClient client) {
        try {
            client.disconnect();
            client.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * SFTP 连接包装。
     */
    private static class SftpConnection {
        private final String targetHost;
        private final SSHClient client;
        private final SFTPClient sftp;

        /**
         * 构造 SFTP 连接包装。
         *
         * @param targetHost 目标主机
         * @param client SSH客户端
         * @param sftp SFTP客户端
         */
        private SftpConnection(String targetHost, SSHClient client, SFTPClient sftp) {
            this.targetHost = targetHost;
            this.client = client;
            this.sftp = sftp;
        }

        /**
         * 关闭 SFTP 和 SSH 资源。
         */
        private void close() {
            try {
                sftp.close();
            } catch (Exception ignored) {
            }
            try {
                client.disconnect();
                client.close();
            } catch (Exception ignored) {
            }
        }
    }
}
