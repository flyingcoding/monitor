package com.example.entity.vo.request;

import lombok.Data;
import org.hibernate.validator.constraints.Length;

/**
 * @program: monitor
 * @description: 客户端ssh连接VO
 * @author: 王贝强
 * @create: 2024-07-27 09:51
 */
@Data
public class SshConnectVO {
    int id;
    String ip;
    int port;
    @Length(min = 1)
    String username;
    String password;
}
