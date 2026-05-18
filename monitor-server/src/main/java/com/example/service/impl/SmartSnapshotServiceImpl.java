package com.example.service.impl;

import com.example.config.SseEventBus;
import com.example.entity.vo.request.SmartSnapshotVO;
import com.example.entity.vo.request.SmartStatVO;
import com.example.entity.vo.response.SmartSnapshotResponseVO;
import com.example.service.SmartSnapshotService;
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
 * {@link SmartSnapshotService} 实现。
 * <p>
 * Caffeine 缓存 TTL 30 秒（与客户端 10s 上报周期 + 网络抖动余量匹配，避免前端拉到旧数据）。
 * 缓存命中时直接返回；未命中时返回 null（前端 tab 显示"暂无数据"）。
 * SSE 推送失败仅记录日志，不影响 cache 写入。
 */
@Slf4j
@Service
public class SmartSnapshotServiceImpl implements SmartSnapshotService {

    /** 缓存上限 1000 客户端，TTL 30 秒。 */
    private final Cache<Integer, SmartSnapshotResponseVO> snapshotCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build();

    @Lazy
    @Resource
    private SseEventBus sseEventBus;

    @Override
    public void ingest(Integer clientId, SmartSnapshotVO vo) {
        if (clientId == null || vo == null) {
            return;
        }
        SmartSnapshotResponseVO response = toResponse(clientId, vo);
        snapshotCache.put(clientId, response);
        try {
            sseEventBus.publishSmartSnapshot(clientId, response);
        } catch (Exception e) {
            log.warn("SMART 快照 SSE 推送失败 clientId={}, reason={}", clientId, e.getMessage());
        }
    }

    @Override
    public SmartSnapshotResponseVO getLatest(Integer clientId) {
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
    private SmartSnapshotResponseVO toResponse(Integer clientId, SmartSnapshotVO vo) {
        SmartSnapshotResponseVO response = new SmartSnapshotResponseVO();
        response.setClientId(clientId);
        response.setUpdatedAt(new Date());
        List<SmartStatVO> disks = vo.getDisks();
        if (disks == null || disks.isEmpty()) {
            response.setDisks(Collections.emptyList());
            return response;
        }
        List<SmartSnapshotResponseVO.SmartStatResponseVO> mapped = new ArrayList<>(disks.size());
        for (SmartStatVO source : disks) {
            if (source == null) continue;
            SmartSnapshotResponseVO.SmartStatResponseVO target =
                    new SmartSnapshotResponseVO.SmartStatResponseVO();
            target.setDevice(source.getDevice());
            target.setModelName(source.getModelName());
            target.setNvme(source.isNvme());
            target.setReallocatedSector(source.getReallocatedSector());
            target.setCurrentPending(source.getCurrentPending());
            target.setOfflineUncorrectable(source.getOfflineUncorrectable());
            target.setMediaErrors(source.getMediaErrors());
            target.setTemperatureCelsius(source.getTemperatureCelsius());
            target.setCritical(source.isCritical());
            mapped.add(target);
        }
        response.setDisks(mapped);
        return response;
    }
}
