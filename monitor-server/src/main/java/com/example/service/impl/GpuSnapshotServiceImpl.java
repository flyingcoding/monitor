package com.example.service.impl;

import com.example.config.SseEventBus;
import com.example.entity.vo.request.GpuSnapshotVO;
import com.example.entity.vo.request.GpuStatVO;
import com.example.entity.vo.response.GpuSnapshotResponseVO;
import com.example.service.GpuSnapshotService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@link GpuSnapshotService} 实现。
 * <p>
 * Caffeine 缓存 TTL 30 秒（与客户端 10s 上报周期 + 网络抖动余量匹配，避免前端拉到旧数据）。
 * 缓存命中时直接返回；未命中时返回 null（前端 tab 显示"暂无数据"）。
 * SSE 推送失败仅记录日志，不影响 cache 写入。
 */
@Slf4j
@Service
public class GpuSnapshotServiceImpl implements GpuSnapshotService {

    /** 缓存上限 1000 客户端，TTL 30 秒。 */
    private final Cache<Integer, GpuSnapshotResponseVO> snapshotCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build();

    @Lazy
    @Resource
    private SseEventBus sseEventBus;

    @Override
    public void ingest(Integer clientId, GpuSnapshotVO vo) {
        if (clientId == null || vo == null) {
            return;
        }
        GpuSnapshotResponseVO response = toResponse(clientId, vo);
        snapshotCache.put(clientId, response);
        try {
            sseEventBus.publishGpuSnapshot(clientId, response);
        } catch (Exception e) {
            log.warn("GPU 快照 SSE 推送失败 clientId={}, reason={}", clientId, e.getMessage());
        }
    }

    @Override
    public GpuSnapshotResponseVO getLatest(Integer clientId) {
        if (clientId == null) {
            return null;
        }
        return snapshotCache.getIfPresent(clientId);
    }

    /**
     * 把客户端请求 VO 转为响应 VO，过滤 null 元素并设置 updatedAt 时间戳。
     *
     * @param clientId 客户端ID
     * @param vo 请求 VO
     * @return 响应 VO
     */
    private GpuSnapshotResponseVO toResponse(Integer clientId, GpuSnapshotVO vo) {
        GpuSnapshotResponseVO response = new GpuSnapshotResponseVO();
        response.setClientId(clientId);
        response.setUpdatedAt(Instant.now());
        List<GpuStatVO> gpus = vo.getGpus();
        if (gpus == null || gpus.isEmpty()) {
            response.setGpus(Collections.emptyList());
            return response;
        }
        List<GpuStatVO> sanitized = new ArrayList<>(gpus.size());
        for (GpuStatVO source : gpus) {
            if (source != null) {
                sanitized.add(source);
            }
        }
        response.setGpus(sanitized);
        return response;
    }
}
