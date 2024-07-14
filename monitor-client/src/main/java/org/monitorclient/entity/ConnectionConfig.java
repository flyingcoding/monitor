package org.monitorclient.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @program: monitor
 * @description: 连接配置类
 * @author: 王贝强
 * @create: 2024-07-14 17:39
 */
@Data
@AllArgsConstructor
public class ConnectionConfig {
    String address;
    String token;
}
