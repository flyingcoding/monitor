package com.example.entity.vo.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

import java.util.List;

/**
 * 状态页配置更新请求（管理员独占）。
 *
 * <p>{@code clientIds} 显式传 {@code null} = "公开所有客户端"；显式传空数组 = "公开零个"。
 * 字段层级与 {@code StatusPageConfig} DTO 解耦：DTO 内存为 CSV 字符串，
 * 这里以 {@code List<Integer>} 形式接收，由 Service 层负责序列化为 CSV。
 */
@Data
public class StatusPageConfigUpdateVO {

    @Length(max = 128)
    String title;

    @Length(max = 255)
    String subtitle;

    @Length(max = 32)
    String brandColor;

    @Length(max = 255)
    String logoUrl;

    /**
     * 公开客户端 ID 集合。{@code null} 表示公开所有；空数组表示公开零个。
     */
    List<Integer> clientIds;

    @NotNull
    Boolean enabled;
}
