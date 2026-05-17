package com.example.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.entity.dto.Client;
import com.example.entity.dto.StatusPageConfig;
import com.example.entity.vo.request.StatusPageConfigUpdateVO;
import com.example.entity.vo.response.StatusPageClientVO;
import com.example.entity.vo.response.StatusPageConfigVO;
import com.example.entity.vo.response.StatusPageSummaryVO;
import com.example.mapper.StatusPageConfigMapper;
import com.example.service.ClientService;
import com.example.service.StatusPageService;
import com.example.utils.InfluxDbUtils;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 公开状态页服务实现（v1.2 prd R20–R27 / D6）。
 *
 * <h3>缓存策略</h3>
 * <p>{@link #getCachedSummary()} 使用单 key Caffeine 缓存（{@link #SUMMARY_KEY}）TTL 30 秒。
 * 这意味着 30 秒窗口内无论多少次状态页访问，InfluxDB 只会被查询一次（满足 PRD AC10）。
 * 写入路径 {@link #updateConfig} 主动 {@link Cache#invalidate(Object)} 让下一次 GET 立刻反映新配置。
 *
 * <h3>InfluxDB 失败兜底</h3>
 * <p>{@link #computeSummary()} 在调用 {@link InfluxDbUtils#readAvailabilityBuckets(int)} 抛出异常时，
 * 不让 {@code /api/status/summary} 整体 500：单个 client 走 {@code null} availability 兜底，
 * 前端展示"数据不足"占位。日志记录第一次失败用以辨别原因。
 *
 * <h3>敏感字段隔离</h3>
 * <p>{@link #toClientVO} 显式构造每个字段；新增内部 {@code Client} / {@code RuntimeDetailVO}
 * 字段不会自动泄露到 {@link StatusPageClientVO}。CPU/内存/磁盘/IP/OS 等指标永不进入此处。
 */
@Slf4j
@Service
public class StatusPageServiceImpl
        extends ServiceImpl<StatusPageConfigMapper, StatusPageConfig>
        implements StatusPageService {

    /**
     * 配置表单行主键（迁移种子值）。
     */
    private static final int SINGLETON_ID = 1;

    /**
     * 缓存 key：固定常量，所有访客共享同一缓存条目。
     */
    private static final String SUMMARY_KEY = "summary";

    @Resource
    private ClientService clientService;

    @Resource
    private InfluxDbUtils influxDbUtils;

    @Value("${monitor.status-page.cache-ttl-seconds:30}")
    private long cacheTtlSeconds;

    /**
     * 单 key 缓存；通过 {@code expireAfterWrite} 保证 30s 强一致兜底。
     * <p>构造时延迟到 {@code @PostConstruct}-style 的 getter 内（{@link #summaryCache()}），
     * 这样可以根据 {@code application.yml} 注入的 {@link #cacheTtlSeconds} 动态生效。
     */
    private volatile Cache<String, StatusPageSummaryVO> summaryCache;

    private Cache<String, StatusPageSummaryVO> summaryCache() {
        Cache<String, StatusPageSummaryVO> local = summaryCache;
        if (local == null) {
            synchronized (this) {
                local = summaryCache;
                if (local == null) {
                    local = Caffeine.newBuilder()
                            .expireAfterWrite(Math.max(cacheTtlSeconds, 1), TimeUnit.SECONDS)
                            .maximumSize(2)
                            .build();
                    summaryCache = local;
                }
            }
        }
        return local;
    }

    @Override
    public StatusPageSummaryVO getCachedSummary() {
        return summaryCache().get(SUMMARY_KEY, k -> computeSummary());
    }

    @Override
    public StatusPageConfigVO getAdminConfig() {
        StatusPageConfig config = loadOrInit();
        return toAdminVO(config);
    }

    @Override
    public StatusPageConfigVO updateConfig(StatusPageConfigUpdateVO vo) {
        StatusPageConfig config = loadOrInit();
        if (vo.getTitle() != null) {
            config.setTitle(vo.getTitle());
        }
        config.setSubtitle(vo.getSubtitle());
        config.setBrandColor(vo.getBrandColor());
        config.setLogoUrl(vo.getLogoUrl());
        config.setClientIds(serializeClientIds(vo.getClientIds()));
        config.setEnabled(Boolean.TRUE.equals(vo.getEnabled()));
        config.setUpdatedAt(new Date());
        this.updateById(config);
        summaryCache().invalidateAll();
        log.info("公开状态页配置已更新：enabled={} clientIds={} title={}",
                config.getEnabled(), config.getClientIds(), config.getTitle());
        return toAdminVO(config);
    }

    /**
     * 实际计算汇总；仅在 {@link #summaryCache()} 缓存未命中时调用。
     *
     * @return 汇总 VO（永不返回 null，禁用时返回标题 + 空客户端列表）
     */
    StatusPageSummaryVO computeSummary() {
        StatusPageConfig config = loadOrInit();
        StatusPageSummaryVO vo = new StatusPageSummaryVO();
        vo.setTitle(config.getTitle());
        vo.setSubtitle(config.getSubtitle());
        vo.setBrandColor(config.getBrandColor());
        vo.setLogoUrl(config.getLogoUrl());
        vo.setGeneratedAt(System.currentTimeMillis());
        vo.setClients(new ArrayList<>());

        if (!Boolean.TRUE.equals(config.getEnabled())) {
            log.debug("公开状态页已禁用，返回空客户端列表");
            return vo;
        }
        List<Integer> publicIds = resolvePublicClientIds(config);
        if (publicIds.isEmpty()) {
            return vo;
        }
        double availabilitySum = 0.0;
        int counted = 0;
        for (Integer id : publicIds) {
            Client client = clientService.findClientById(id);
            if (client == null) {
                continue;
            }
            StatusPageClientVO clientVO = toClientVO(client);
            if (clientVO.getAvailability24h() != null) {
                availabilitySum += clientVO.getAvailability24h();
                counted++;
            }
            vo.getClients().add(clientVO);
        }
        if (counted > 0) {
            vo.setOverallAvailability(availabilitySum / counted);
        }
        return vo;
    }

    /**
     * 解析公开客户端 ID 列表。{@code clientIds == null} 时表示"公开所有"（默认行为，初始化场景）；
     * 空字符串显式表示"公开零个"（管理员主动清空）。
     *
     * @param config 配置实体
     * @return 客户端 ID 列表（顺序与配置一致；id == null 表示 null 公开所有 → 取所有客户端）
     */
    private List<Integer> resolvePublicClientIds(StatusPageConfig config) {
        if (config.getClientIds() == null) {
            // 默认公开所有客户端
            return clientService.list().stream().map(Client::getId).toList();
        }
        if (config.getClientIds().isBlank()) {
            return List.of();
        }
        Set<Integer> dedup = new LinkedHashSet<>();
        for (String token : config.getClientIds().split(",")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            try {
                dedup.add(Integer.parseInt(t));
            } catch (NumberFormatException ignore) {
                // 容错跳过损坏的 csv 项
            }
        }
        return new ArrayList<>(dedup);
    }

    /**
     * 单 client 转 VO；严格白名单字段映射；InfluxDB 异常时单点降级，不影响其他 client。
     */
    private StatusPageClientVO toClientVO(Client client) {
        StatusPageClientVO vo = new StatusPageClientVO();
        vo.setDisplayName(client.getDisplayName() == null || client.getDisplayName().isBlank()
                ? client.getName()
                : client.getDisplayName());
        vo.setOnline(clientService.isClientOnline(client.getId()));
        vo.setLastSeenSecondsAgo(clientService.lastSeenSecondsAgo(client.getId()));
        try {
            double[] buckets = influxDbUtils.readAvailabilityBuckets(client.getId());
            if (buckets.length == 0) {
                vo.setAvailability24h(null);
                vo.setBuckets(List.of());
                return vo;
            }
            double sum = 0.0;
            List<Double> list = new ArrayList<>(buckets.length);
            for (double b : buckets) {
                sum += b;
                list.add(b);
            }
            vo.setAvailability24h(sum / buckets.length);
            vo.setBuckets(list);
        } catch (Exception e) {
            log.warn("InfluxDB 状态页可用率查询失败 clientId={} reason={}",
                    client.getId(), e.getMessage());
            vo.setAvailability24h(null);
            vo.setBuckets(List.of());
        }
        return vo;
    }

    /**
     * 读取（或在异常情况下生成临时占位）配置行。{@code V3} 迁移已经播种 id=1 行，
     * 此处 fallback 仅用于测试场景或迁移失败时不让 status page 整体 500。
     */
    private StatusPageConfig loadOrInit() {
        StatusPageConfig config = this.getById(SINGLETON_ID);
        if (config == null) {
            log.warn("公开状态页配置缺失（id=1），回退到只读默认配置；请确认 Flyway V3 是否成功执行");
            config = new StatusPageConfig();
            config.setId(SINGLETON_ID);
            config.setTitle("Service Status");
            config.setEnabled(Boolean.TRUE);
        }
        return config;
    }

    /**
     * 序列化客户端 ID 列表为 CSV；{@code null} 输入保留 null（"未配置"语义），
     * 空列表写空字符串（"明确清空"语义）。
     */
    private String serializeClientIds(List<Integer> ids) {
        if (ids == null) return null;
        if (ids.isEmpty()) return "";
        // 去重 + 保序
        List<String> tokens = new ArrayList<>(new LinkedHashSet<>(ids)
                .stream()
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .toList());
        return String.join(",", tokens);
    }

    /**
     * 反序列化 CSV → {@code List<Integer>}。{@code null} 与空字符串保留各自语义。
     */
    private List<Integer> deserializeClientIds(String csv) {
        if (csv == null) return null;
        if (csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> {
                    try {
                        return Integer.parseInt(s);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * 管理员视图 VO 转换：附加全量客户端候选列表。
     */
    private StatusPageConfigVO toAdminVO(StatusPageConfig config) {
        StatusPageConfigVO vo = new StatusPageConfigVO();
        vo.setId(config.getId());
        vo.setTitle(config.getTitle());
        vo.setSubtitle(config.getSubtitle());
        vo.setBrandColor(config.getBrandColor());
        vo.setLogoUrl(config.getLogoUrl());
        vo.setClientIds(deserializeClientIds(config.getClientIds()));
        vo.setEnabled(config.getEnabled());
        vo.setUpdatedAt(config.getUpdatedAt());
        vo.setAvailableClients(clientService.list().stream()
                .map(client -> {
                    StatusPageConfigVO.StatusPageCandidateClientVO c =
                            new StatusPageConfigVO.StatusPageCandidateClientVO();
                    c.setId(client.getId());
                    c.setName(client.getName());
                    c.setDisplayName(client.getDisplayName());
                    return c;
                })
                .toList());
        return vo;
    }
}
