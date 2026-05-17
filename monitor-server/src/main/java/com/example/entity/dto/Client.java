package com.example.entity.dto;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.entity.BaseData;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * @program: monitor
 * @description: 客户端数据实体类
 * @author: 王贝强
 * @create: 2024-07-13 16:37
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("client")
public class Client implements BaseData {
    @TableId
    Integer id;
    String name;
    String token;
    String location;
    String node;
    Date registerTime;
    /**
     * 公开状态页展示用别名（与内部 {@code name} 解耦，避免泄露内部命名）；
     * 仅供 {@code /api/status/*} 与状态页 admin 配置使用。
     */
    String displayName;
}
