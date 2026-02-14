package com.example.websocket;

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
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
@ServerEndpoint("/terminal/{clientId}")
public class TerminalWebSocket {

    private static final int SSH_CONNECT_TIMEOUT_MS = 10_000;
    private static final int SSH_IO_TIMEOUT_MS = 10_000;

    private static ClientSshMapper sshMapper;
    private static CryptoUtils cryptoUtils;

    /**
     * 注入 SSH 配置 Mapper，供 WebSocket 端点静态访问。
     *
     * @param sshMapper SSH配置Mapper
     */
    @Resource
    public void setSshMapper(ClientSshMapper sshMapper) {
        TerminalWebSocket.sshMapper = sshMapper;
    }

    /**
     * 注入密码加解密工具，供 WebSocket 端点静态访问。
     *
     * @param cryptoUtils 密码加解密工具
     */
    @Resource
    public void setCryptoUtils(CryptoUtils cryptoUtils) {
        TerminalWebSocket.cryptoUtils = cryptoUtils;
    }

    private static final Map<Session, ShellConnection> sessionMap = new ConcurrentHashMap<>();

    /**
     * 建立 WebSocket 后创建 SSH 连接。
     *
     * @param session WebSocket会话
     * @param clientId 客户端ID
     * @throws Exception 连接异常
     */
    @OnOpen
    public void onOpen(Session session, @PathParam("clientId") String clientId) throws Exception {
        log.info("正在尝试建立 WebSocket 终端连接，客户端ID: {}, 会话ID: {}", clientId, session.getId());
        ClientSsh ssh = sshMapper.selectById(clientId);
        if (ssh == null) {
            session.close(new CloseReason(CloseReason.CloseCodes.CANNOT_ACCEPT, "无法识别此主机"));
            return;
        }
        this.createSshConnection(session, ssh, ssh.getIp());
    }

    /**
     * 将前端输入透传到 SSH Shell。
     *
     * @param session WebSocket会话
     * @param message 输入内容
     * @throws IOException IO异常
     */
    @OnMessage
    public void onMessage(Session session, String message) throws IOException {
        ShellConnection shell = sessionMap.get(session);
        if (shell == null) {
            return;
        }
        OutputStream output = shell.output;
        output.write(message.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    /**
     * WebSocket 关闭时释放 SSH 会话资源。
     *
     * @param session WebSocket会话
     * @throws IOException IO异常
     */
    @OnClose
    public void onClose(Session session) throws IOException {
        ShellConnection shell = sessionMap.remove(session);
        if (shell != null) {
            shell.close();
            log.info("主机 {} 的 SSH 连接已断开", shell.targetHost);
        }
    }

    /**
     * WebSocket 异常时关闭会话。
     *
     * @param session WebSocket会话
     * @param error 异常信息
     * @throws IOException IO异常
     */
    @OnError
    public void onError(Session session, Throwable error) throws IOException {
        log.error("用户 WebSocket 连接出现错误", error);
        session.close();
    }

    /**
     * 创建 SSH 连接并绑定到当前 WebSocket 会话。
     *
     * @param wsSession WebSocket会话
     * @param ssh SSH配置
     * @param ip 目标IP
     * @throws IOException 连接或关闭异常
     */
    private void createSshConnection(Session wsSession, ClientSsh ssh, String ip) throws IOException {
        SSHClient client = new SSHClient();
        // 宽松主机校验策略，保持与历史行为兼容。
        client.addHostKeyVerifier(new PromiscuousVerifier());
        client.setConnectTimeout(SSH_CONNECT_TIMEOUT_MS);
        client.setTimeout(SSH_IO_TIMEOUT_MS);
        try {
            client.connect(ip, ssh.getPort());
            String password = cryptoUtils == null ? ssh.getPassword() : cryptoUtils.decrypt(ssh.getPassword());
            client.authPassword(ssh.getUsername(), password);

            net.schmizz.sshj.connection.channel.direct.Session sshSession = client.startSession();
            sshSession.allocatePTY("xterm", 80, 24, 0, 0, Collections.emptyMap());
            net.schmizz.sshj.connection.channel.direct.Session.Shell shell = sshSession.startShell();

            sessionMap.put(wsSession, new ShellConnection(wsSession, ip, client, sshSession, shell));
            log.info("主机 {} 的 SSH 连接已创建", ip);
        } catch (Exception e) {
            log.error("建立 SSH 连接失败，host={}, port={}, user={}", ip, ssh.getPort(), ssh.getUsername(), e);
            this.closeClientQuietly(client);
            String reason = this.resolveErrorMessage(e);
            wsSession.close(new CloseReason(CloseReason.CloseCodes.CANNOT_ACCEPT, reason));
        }
    }

    /**
     * 根据异常类型解析更友好的错误提示。
     *
     * @param throwable 异常
     * @return 错误消息
     */
    private String resolveErrorMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null) {
            return "连接 SSH 失败";
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
        if (message.contains("UnknownHostException") || message.contains("No such host")) {
            return "无法解析主机地址，请检查 IP 是否正确";
        }
        return message;
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
     * 终端会话包装，管理 SSH shell 的读写和生命周期。
     */
    private static class ShellConnection {
        private final Session wsSession;
        private final String targetHost;
        private final SSHClient client;
        private final net.schmizz.sshj.connection.channel.direct.Session sshSession;
        private final net.schmizz.sshj.connection.channel.direct.Session.Shell shell;
        private final InputStream input;
        private final OutputStream output;
        private final ExecutorService readerExecutor;

        /**
         * 构建终端连接并启动读取循环。
         *
         * @param wsSession WebSocket会话
         * @param targetHost 目标主机
         * @param client SSH客户端
         * @param sshSession SSH会话
         * @param shell shell通道
         * @throws IOException IO异常
         */
        private ShellConnection(Session wsSession,
                                String targetHost,
                                SSHClient client,
                                net.schmizz.sshj.connection.channel.direct.Session sshSession,
                                net.schmizz.sshj.connection.channel.direct.Session.Shell shell) throws IOException {
            this.wsSession = wsSession;
            this.targetHost = targetHost;
            this.client = client;
            this.sshSession = sshSession;
            this.shell = shell;
            this.input = shell.getInputStream();
            this.output = shell.getOutputStream();
            this.readerExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "ssh-shell-reader-" + wsSession.getId());
                thread.setDaemon(true);
                return thread;
            });
            this.readerExecutor.submit(this::read);
        }

        /**
         * 持续读取 SSH 输出并推送到 WebSocket 客户端。
         */
        private void read() {
            try {
                InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
                char[] buffer = new char[8 * 1024];
                int i;
                while ((i = reader.read(buffer)) != -1) {
                    if (!wsSession.isOpen()) {
                        break;
                    }
                    wsSession.getBasicRemote().sendText(new String(buffer, 0, i));
                }
            } catch (Exception e) {
                log.error("读取 SSH 输入流时出现问题", e);
            }
        }

        /**
         * 关闭当前连接占用的所有资源。
         */
        private void close() {
            try {
                input.close();
            } catch (Exception ignored) {
            }
            try {
                output.close();
            } catch (Exception ignored) {
            }
            try {
                shell.close();
            } catch (Exception ignored) {
            }
            try {
                sshSession.close();
            } catch (Exception ignored) {
            }
            try {
                client.disconnect();
                client.close();
            } catch (Exception ignored) {
            }
            readerExecutor.shutdownNow();
        }
    }
}
