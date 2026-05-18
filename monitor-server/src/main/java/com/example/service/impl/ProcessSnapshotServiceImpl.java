package com.example.service.impl;

import com.example.config.SseEventBus;
import com.example.entity.vo.request.ProcessSnapshotVO;
import com.example.entity.vo.response.ProcessSnapshotResponseVO;
import com.example.service.ProcessSnapshotService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@link ProcessSnapshotService} 实现。
 * <p>
 * Caffeine 缓存 TTL 30 秒（与客户端 10s 上报周期 + 网络抖动余量匹配，避免前端拉到旧数据）。
 * 缓存命中时直接返回；未命中时返回 null（前端 tab 显示"暂无数据"）。
 * SSE 推送失败仅记录日志，不影响 cache 写入。
 */
@Slf4j
@Service
public class ProcessSnapshotServiceImpl implements ProcessSnapshotService {

    /** 缓存上限 1000 客户端，TTL 30 秒。 */
    private final Cache<Integer, ProcessSnapshotResponseVO> snapshotCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build();

    @Lazy
    @Resource
    private SseEventBus sseEventBus;

    @Override
    public void ingest(Integer clientId, ProcessSnapshotVO vo) {
        if (clientId == null || vo == null) {
            return;
        }
        ProcessSnapshotResponseVO response = toResponse(clientId, vo);
        snapshotCache.put(clientId, response);
        try {
            sseEventBus.publishProcessSnapshot(clientId, response);
        } catch (Exception e) {
            log.warn("进程快照 SSE 推送失败 clientId={}, reason={}", clientId, e.getMessage());
        }
    }

    @Override
    public ProcessSnapshotResponseVO getLatest(Integer clientId) {
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
    private ProcessSnapshotResponseVO toResponse(Integer clientId, ProcessSnapshotVO vo) {
        ProcessSnapshotResponseVO response = new ProcessSnapshotResponseVO();
        response.setClientId(clientId);
        response.setUpdatedAt(new Date());
        response.setTimestamp(vo.getTimestamp());
        response.setTop10ByCpu(mapInfoList(vo.getTop10ByCpu()));
        response.setTop10ByMemory(mapInfoList(vo.getTop10ByMemory()));
        Map<String, Boolean> watched = vo.getWatchedPatterns();
        response.setWatchedPatterns(watched == null ? Collections.emptyMap() : new LinkedHashMap<>(watched));
        return response;
    }

    /**
     * 拷贝并映射进程信息列表。
     *
     * @param source 请求 VO 中的进程列表
     * @return 响应 VO 列表（永不为 null）
     */
    private List<ProcessSnapshotResponseVO.ProcessInfoResponseVO> mapInfoList(
            List<ProcessSnapshotVO.ProcessInfoVO> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        List<ProcessSnapshotResponseVO.ProcessInfoResponseVO> mapped = new ArrayList<>(source.size());
        for (ProcessSnapshotVO.ProcessInfoVO info : source) {
            if (info == null) continue;
            ProcessSnapshotResponseVO.ProcessInfoResponseVO target =
                    new ProcessSnapshotResponseVO.ProcessInfoResponseVO();
            target.setName(info.getName());
            target.setPid(info.getPid());
            target.setCpuPercent(info.getCpuPercent());
            target.setMemoryBytes(info.getMemoryBytes());
            mapped.add(target);
        }
        return mapped;
    }
}
