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
import java.util.Scanner;

/**
 * @program: monitor
 * @description: 服务器配置类
 * @author: 王贝强
 * @create: 2024-07-14 17:36
 */
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
    ConnectionConfig connectionConfig(){
        log.info("正在读取服务端连接配置。。。");
        ConnectionConfig config=this.readFromLocalJSONFile();
        if (config==null){
            config=this.readFromScreen();
        }
        System.out.println(monitor.monitorBaseDetail());
        return config;
    }

    private ConnectionConfig readFromScreen(){
        Scanner scanner=new Scanner(System.in);
        String address,token;
        do{
            log.info("请输入需要连接的服务端地址：(例如'http://192.168.0.100:8080')");
            address = scanner.nextLine();
            log.info("请输入由服务端生成的用于注册客户端的token密钥：");
            token =scanner.nextLine();
        }while (!net.registerToServer(address,token));
        ConnectionConfig config=new ConnectionConfig(address,token);
        this.saveConfigurationToFile(config);
        return config;
    }

    private void saveConfigurationToFile(ConnectionConfig config){
        File dir=new File("config");
        if (!dir.exists()&&dir.mkdir())
            log.info("服务端配置目录config创建成功！");
        File file=new File("config/server.json");
        try(FileWriter writer=new FileWriter(file)){
            writer.write(JSONObject.from(config).toJSONString());
        }catch (IOException e){
            log.error("服务端配置信息保存出错",e);
        }
        log.info("服务端配置信息保存成功！");
    }
    private ConnectionConfig readFromLocalJSONFile(){
        File configurationFile=new File("config/server.json");
        if (configurationFile.exists()){
            try(FileInputStream stream=new FileInputStream(configurationFile)){
                String raw=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
                return JSONObject.parseObject(raw).to(ConnectionConfig.class);
            }catch (IOException e){
                log.error("读取配置文件出错",e);
            }
        }
        return null;
    }

}
