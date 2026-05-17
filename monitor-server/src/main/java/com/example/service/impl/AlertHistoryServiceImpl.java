package com.example.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.entity.dto.AlertHistory;
import com.example.mapper.AlertHistoryMapper;
import com.example.service.AlertHistoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Date;
import java.util.List;

/**
 * 告警历史 Service 实现。提供分页 + 多条件筛选 + 按 fired_at 倒序的查询能力。
 * Controller 层负责权限范围过滤（管理员/子账户）。
 */
@Slf4j
@Service
public class AlertHistoryServiceImpl extends ServiceImpl<AlertHistoryMapper, AlertHistory> implements AlertHistoryService {

    @Override
    public IPage<AlertHistory> queryHistory(Collection<Integer> clientIds,
                                            Integer singleClientId,
                                            String level,
                                            String status,
                                            Date from,
                                            Date to,
                                            int page,
                                            int size) {
        LambdaQueryWrapper<AlertHistory> wrapper = Wrappers.<AlertHistory>lambdaQuery()
                .orderByDesc(AlertHistory::getFiredAt);
        if (clientIds != null) {
            if (clientIds.isEmpty()) {
                // 非管理员无任何可见客户端，直接返回空页
                Page<AlertHistory> emptyPage = new Page<>(page, size);
                emptyPage.setRecords(List.of());
                emptyPage.setTotal(0);
                return emptyPage;
            }
            wrapper.in(AlertHistory::getClientId, clientIds);
        }
        if (singleClientId != null) {
            wrapper.eq(AlertHistory::getClientId, singleClientId);
        }
        if (level != null && !level.isBlank()) {
            wrapper.eq(AlertHistory::getLevel, level);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(AlertHistory::getStatus, status);
        }
        if (from != null) {
            wrapper.ge(AlertHistory::getFiredAt, from);
        }
        if (to != null) {
            wrapper.le(AlertHistory::getFiredAt, to);
        }
        return this.page(new Page<>(page, size), wrapper);
    }
}
