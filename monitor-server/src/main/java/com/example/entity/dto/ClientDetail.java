package com.example.entity.dto;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * @program: monitor
 * @description: 服务器数据实体类
 * @author: 王贝强
 * @create: 2024-07-15 12:15
 */
@Data
@TableName("client_detail")
public class ClientDetail {
    @TableId
    Integer id;
    String osArch;
    String osName;
    String osVersion;
    int osBit;
    String cpuName;
    int cpuCore;
    double memory;
    double disk;
    String ip;
    /**
     * v1.3：客户端采集能力 JSON。
     * <p>
     * 由客户端启动时根据 {@code application.properties} 配置与系统工具探测结果生成，随 {@code /monitor/detail}
     * 上报。结构示例：{@code {"gpu":{"enabled":true,"available":true,"deviceCount":2}, "smart":{...}, "systemd":{...},
     * "process":{...}}}。admin 在 Manage 页面读出 capabilities 字段显示能力徽章。
     */
    String capabilitiesJson;
}
