package com.example.service.impl;

import com.example.config.SseEventBus;
import com.example.entity.vo.request.SystemdSnapshotVO;
import com.example.entity.vo.request.SystemdUnitStatVO;
import com.example.entity.vo.response.SystemdSnapshotResponseVO;
import com.example.service.SystemdSnapshotService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@link SystemdSnapshotService} 实现。
 * <p>
 * Caffeine 缓存 TTL 30 秒（与客户端 10s 上报周期 + 网络抖动余量匹配，避免前端拉到旧数据）。
 * 缓存命中时直接返回；未命中时返回 null（前端 tab 显示"暂无数据"）。
 * SSE 推送失败仅记录日志，不影响 cache 写入。
 */
@Slf4j
@Service
public class SystemdSnapshotServiceImpl implements SystemdSnapshotService {

    /** 缓存上限 1000 客户端，TTL 30 秒。 */
    private final Cache<Integer, SystemdSnapshotResponseVO> snapshotCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build();

    @Lazy
    @Resource
    private SseEventBus sseEventBus;

    @Override
    public void ingest(Integer clientId, SystemdSnapshotVO vo) {
        if (clientId == null || vo == null) {
            return;
        }
        SystemdSnapshotResponseVO response = toResponse(clientId, vo);
        snapshotCache.put(clientId, response);
        try {
            sseEventBus.publishSystemdSnapshot(clientId, response);
        } catch (Exception e) {
            log.warn("systemd 快照 SSE 推送失败 clientId={}, reason={}", clientId, e.getMessage());
        }
    }

    @Override
    public SystemdSnapshotResponseVO getLatest(Integer clientId) {
        if (clientId == null) {
            return null;
        }
        return snapshotCache.getIfPresent(clientId);
    }

    /**
     * 把客户端请求 VO 转为响应 VO（避免请求 VO 直接泄漏到响应层）。
     *
     * @param clientId 客户端ID
     * @param vo 请求 VO
     * @return 响应 VO
     */
    private SystemdSnapshotResponseVO toResponse(Integer clientId, SystemdSnapshotVO vo) {
        SystemdSnapshotResponseVO response = new SystemdSnapshotResponseVO();
        response.setClientId(clientId);
        response.setUpdatedAt(new Date());
        List<SystemdUnitStatVO> units = vo.getUnits();
        if (units == null || units.isEmpty()) {
            response.setUnits(Collections.emptyList());
            return response;
        }
        List<SystemdSnapshotResponseVO.SystemdUnitStatResponseVO> mapped = new ArrayList<>(units.size());
        for (SystemdUnitStatVO source : units) {
            if (source == null) continue;
            SystemdSnapshotResponseVO.SystemdUnitStatResponseVO target =
                    new SystemdSnapshotResponseVO.SystemdUnitStatResponseVO();
            target.setName(source.getName());
            target.setLoadState(source.getLoadState());
            target.setActiveState(source.getActiveState());
            target.setSubState(source.getSubState());
            target.setDescription(source.getDescription());
            target.setHealthy(source.isHealthy());
            mapped.add(target);
        }
        response.setUnits(mapped);
        return response;
    }
}
