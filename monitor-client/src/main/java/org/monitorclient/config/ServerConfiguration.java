package org.monitorclient.config;

import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.monitorclient.entity.ConnectionConfig;
import org.monitorclient.utils.MonitorUtils;
import org.monitorclient.utils.NetUtils;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Scanner;

@Slf4j
@Configuration
public class ServerConfiguration implements ApplicationRunner {

    @Resource
    NetUtils net;

    @Resource
    MonitorUtils monitor;

    @Override
    public void run(ApplicationArguments args) {
        log.info("正在向服务端更新基本信息。。。");
        net.updateBaseDetails(monitor.monitorBaseDetail());
    }

    @Bean
    ConnectionConfig connectionConfig(ApplicationArguments args) {
        log.info("正在读取服务端连接配置。。。");
        // 优先级：环境变量 → 命令行参数 → 本地文件 → 交互式输入
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

    private ConnectionConfig readFromEnv() {
        String server = System.getenv("MONITOR_SERVER");
        String token = System.getenv("MONITOR_TOKEN");
        if (server != null && token != null && !server.isEmpty() && !token.isEmpty()) {
            log.info("从环境变量读取到服务端配置");
            ConnectionConfig config = new ConnectionConfig(server, token);
            if (net.registerToServer(server, token)) {
                this.saveConfigurationToFile(config);
                return config;
            }
            log.warn("环境变量中的服务端配置注册失败，尝试其他配置方式");
        }
        return null;
    }

    private ConnectionConfig readFromArgs(ApplicationArguments args) {
        List<String> serverArgs = args.getOptionValues("server");
        List<String> tokenArgs = args.getOptionValues("token");
        if (serverArgs != null && !serverArgs.isEmpty() && tokenArgs != null && !tokenArgs.isEmpty()) {
            String server = serverArgs.get(0);
            String token = tokenArgs.get(0);
            log.info("从命令行参数读取到服务端配置");
            ConnectionConfig config = new ConnectionConfig(server, token);
            if (net.registerToServer(server, token)) {
                this.saveConfigurationToFile(config);
                return config;
            }
            log.warn("命令行参数中的服务端配置注册失败，尝试其他配置方式");
        }
        return null;
    }

    private ConnectionConfig readFromScreen() {
        Scanner scanner = new Scanner(System.in);
        String address, token;
        do {
            log.info("请输入需要连接的服务端地址：(例如'http://192.168.0.100:8080')");
            address = scanner.nextLine();
            log.info("请输入由服务端生成的用于注册客户端的token密钥：");
            token = scanner.nextLine();
        } while (!net.registerToServer(address, token));
        ConnectionConfig config = new ConnectionConfig(address, token);
        this.saveConfigurationToFile(config);
        return config;
    }

    private void saveConfigurationToFile(ConnectionConfig config) {
        File dir = new File("config");
        if (!dir.exists() && dir.mkdir())
            log.info("服务端配置目录config创建成功！");
        File file = new File("config/server.json");
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(JSONObject.from(config).toJSONString());
        } catch (IOException e) {
            log.error("服务端配置信息保存出错", e);
        }
        log.info("服务端配置信息保存成功！");
    }

    private ConnectionConfig readFromLocalJSONFile() {
        File configurationFile = new File("config/server.json");
        if (configurationFile.exists()) {
            try (FileInputStream stream = new FileInputStream(configurationFile)) {
                String raw = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                return JSONObject.parseObject(raw).to(ConnectionConfig.class);
            } catch (IOException e) {
                log.error("读取配置文件出错", e);
            }
        }
        return null;
    }
}
