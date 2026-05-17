package com.example.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.example.entity.dto.AlertHistory;

import java.util.Collection;
import java.util.Date;

/**
 * 告警历史 Service 接口。实现类由 Agent C（告警 Controller 模块）落地。
 * 提供 IService 基础 CRUD 与分页 + 多条件筛选查询。
 */
public interface AlertHistoryService extends IService<AlertHistory> {

    /**
     * 分页查询告警历史，按 fired_at 倒序返回；任一筛选条件为空表示不限制该维度。
     *
     * @param clientIds      允许访问的客户端ID集合；{@code null} 表示不按客户端筛选（管理员视图），
     *                       空集合表示无任何可见客户端，返回空页
     * @param singleClientId 前端筛选的单一客户端ID；{@code null} 表示不筛选
     * @param level          告警等级；{@code null} 或空表示不筛选
     * @param status         告警状态；{@code null} 或空表示不筛选
     * @param from           触发时间起始（含）；{@code null} 表示不限
     * @param to             触发时间截止（含）；{@code null} 表示不限
     * @param page           页码（>=1）
     * @param size           每页大小（1~100）
     * @return 分页结果
     */
    IPage<AlertHistory> queryHistory(Collection<Integer> clientIds,
                                     Integer singleClientId,
                                     String level,
                                     String status,
                                     Date from,
                                     Date to,
                                     int page,
                                     int size);
}
