package org.monitorclient.config;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.monitorclient.entity.ConnectionConfig;
import org.monitorclient.utils.NetUtils;
import org.monitorclient.utils.RetryUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;

@Slf4j
public class ServerConfiguration {

    private static final String CONFIG_DIR = "config";
    private static final String CONFIG_FILE = CONFIG_DIR + "/server.json";

    private final NetUtils net;

    /**
     * 构造配置加载器。
     *
     * @param net 网络工具
     */
    public ServerConfiguration(NetUtils net) {
        this.net = net;
    }

    /**
     * 按优先级加载连接配置：环境变量 -> 命令行参数 -> 本地文件 -> 交互输入。
     *
     * @param args 启动参数
     * @return 可用连接配置，加载失败返回 null
     */
    public ConnectionConfig loadConfig(String[] args) {
        log.info("正在读取服务端连接配置...");
        ConnectionConfig config = this.readFromEnv();
        if (config == null) {
            config = this.readFromArgs(args);
        }
        if (config == null) {
            config = this.readFromLocalJSONFile();
        }
        if (config == null) {
            config = this.readFromScreen();
        }
        return config;
    }

    /**
     * 从环境变量读取并完成首次注册。
     *
     * @return 可用连接配置，失败返回 null
     */
    private ConnectionConfig readFromEnv() {
        String server = System.getenv("MONITOR_SERVER");
        String token = System.getenv("MONITOR_TOKEN");
        if (isBlank(server) || isBlank(token)) {
            return null;
        }
        log.info("从环境变量读取到服务端配置");
        return this.registerAndPersist(server, token, "环境变量");
    }

    /**
     * 从命令行参数读取并完成首次注册。
     *
     * @param args 启动参数
     * @return 可用连接配置，失败返回 null
     */
    private ConnectionConfig readFromArgs(String[] args) {
        Map<String, String> options = this.parseArgs(args);
        String server = options.get("server");
        String token = options.get("token");
        if (isBlank(server) || isBlank(token)) {
            return null;
        }
        log.info("从命令行参数读取到服务端配置");
        return this.registerAndPersist(server, token, "命令行参数");
    }

    /**
     * 从本地配置文件读取连接信息。
     *
     * @return 可用连接配置，失败返回 null
     */
    private ConnectionConfig readFromLocalJSONFile() {
        File configurationFile = new File(CONFIG_FILE);
        if (!configurationFile.exists()) {
            return null;
        }
        try (FileInputStream stream = new FileInputStream(configurationFile)) {
            String raw = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            ConnectionConfig config = JSONObject.parseObject(raw, ConnectionConfig.class);
            if (config == null || isBlank(config.getAddress()) || isBlank(config.getToken())) {
                log.warn("本地配置文件格式无效，准备进入交互输入");
                return null;
            }
            log.info("从本地配置文件读取到服务端配置");
            return config;
        } catch (IOException e) {
            log.error("读取配置文件出错", e);
            return null;
        }
    }

    /**
     * 通过终端交互读取连接信息并完成注册。
     *
     * @return 可用连接配置
     */
    private ConnectionConfig readFromScreen() {
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                log.info("请输入需要连接的服务端地址：(例如'http://192.168.0.100:8001')");
                String address = scanner.nextLine();
                log.info("请输入由服务端生成的用于注册客户端的 token：");
                String token = scanner.nextLine();
                ConnectionConfig config = this.registerAndPersist(address, token, "交互输入");
                if (config != null) {
                    return config;
                }
                log.warn("交互输入的服务端配置注册失败，请重试");
            }
        }
    }

    /**
     * 注册客户端并将成功配置持久化到本地文件。
     *
     * @param server 服务端地址
     * @param token 注册 token
     * @param source 配置来源
     * @return 成功时返回配置，失败返回 null
     */
    private ConnectionConfig registerAndPersist(String server, String token, String source) {
        ConnectionConfig config = new ConnectionConfig(server, token);
        try {
            RetryUtils.retryWithBackoff(() -> {
                if (!net.registerToServer(server, token)) {
                    throw new RuntimeException("服务端注册失败");
                }
                return true;
            }, source + "注册");
            this.saveConfigurationToFile(config);
            return config;
        } catch (Exception e) {
            log.warn("{}中的服务端配置重试后仍注册失败", source);
            return null;
        }
    }

    /**
     * 将配置持久化到本地 JSON 文件。
     *
     * @param config 连接配置
     */
    private void saveConfigurationToFile(ConnectionConfig config) {
        File dir = new File(CONFIG_DIR);
        if (!dir.exists() && dir.mkdir()) {
            log.info("服务端配置目录 {} 创建成功", CONFIG_DIR);
        }
        File file = new File(CONFIG_FILE);
        try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
            writer.write(JSONObject.from(config).toJSONString());
            log.info("服务端配置信息保存成功");
        } catch (IOException e) {
            log.error("服务端配置信息保存出错", e);
        }
    }

    /**
     * 解析启动参数，兼容 --k=v 与 --k v 两种格式。
     *
     * @param args 启动参数
     * @return 参数映射表
     */
    private Map<String, String> parseArgs(String[] args) {
        Map<String, String> result = new HashMap<>();
        if (args == null || args.length == 0) {
            return result;
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                continue;
            }
            if (arg.contains("=")) {
                String[] parts = arg.substring(2).split("=", 2);
                if (parts.length == 2 && !isBlank(parts[0]) && !isBlank(parts[1])) {
                    result.put(parts[0], parts[1]);
                }
                continue;
            }
            String key = arg.substring(2);
            if (isBlank(key)) {
                continue;
            }
            if (i + 1 < args.length && !Objects.requireNonNull(args[i + 1]).startsWith("--")) {
                result.put(key, args[i + 1]);
                i++;
            }
        }
        return result;
    }

    /**
     * 判断字符串是否为空或仅包含空白字符。
     *
     * @param value 待判断字符串
     * @return 是否为空白
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
