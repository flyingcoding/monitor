package com.example.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.entity.dto.ProbeHistory;
import com.example.entity.dto.ProbeTask;
import com.example.entity.vo.request.ProbeTaskCreateVO;
import com.example.entity.vo.request.ProbeTaskUpdateVO;
import com.example.entity.vo.response.ProbeHistoryVO;
import com.example.entity.vo.response.ProbeTaskVO;
import com.example.mapper.ProbeHistoryMapper;
import com.example.mapper.ProbeTaskMapper;
import com.example.mapper.struct.ProbeStructMapper;
import com.example.service.ProbeService;
import com.example.utils.CryptoUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务探测任务管理实现。
 *
 * <p>敏感字段加密：复用 {@link CryptoUtils} AES-256-GCM 处理 headers JSON 串与 basic_auth_password。
 *
 * <p>channel_ids 列存 JSON 数组字符串（如 {@code "[1,2,3]"}），与 alert_rule.channel_ids 的
 * MySQL JSON 列实现方式不同（probe_task.channel_ids 为 VARCHAR(255)），由本类手动 Jackson 解析。
 */
@Slf4j
@Service
public class ProbeServiceImpl extends ServiceImpl<ProbeTaskMapper, ProbeTask> implements ProbeService {

    /** 与 NotificationChannel 同款占位符；前端编辑时未修改的敏感字段以 {@code "***"} 回传。 */
    public static final String MASK_PLACEHOLDER = "***";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> HEADERS_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Long>> CHANNEL_IDS_TYPE = new TypeReference<>() {};

    @Resource
    private CryptoUtils cryptoUtils;

    @Resource
    private ProbeStructMapper probeStructMapper;

    @Resource
    private ProbeHistoryMapper probeHistoryMapper;

    @Override
    public List<ProbeTaskVO> listAll() {
        return this.list(new QueryWrapper<ProbeTask>().orderByDesc("id"))
                .stream()
                .map(this::toTaskVO)
                .toList();
    }

    @Override
    public ProbeTaskVO create(ProbeTaskCreateVO vo) {
        if (this.nameExists(vo.getName(), null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "探测任务名称已存在");
        }
        ProbeTask entity = new ProbeTask();
        entity.setName(vo.getName());
        entity.setType(vo.getType());
        entity.setTarget(vo.getTarget());
        entity.setIntervalSec(vo.getIntervalSec());
        entity.setTimeoutSec(vo.getTimeoutSec());
        entity.setExpectedStatusCode(vo.getExpectedStatusCode());
        entity.setExpectedBodyPattern(vo.getExpectedBodyPattern());
        entity.setHeadersEnc(encryptHeaders(vo.getHeaders()));
        entity.setBasicAuthUsername(blankToNull(vo.getBasicAuthUsername()));
        entity.setBasicAuthPasswordEnc(encryptBasicAuthPassword(vo.getBasicAuthPassword()));
        entity.setSslWarnDays(vo.getSslWarnDays() == null ? 30 : vo.getSslWarnDays());
        entity.setConsecutiveFailuresThreshold(vo.getConsecutiveFailuresThreshold());
        entity.setChannelIds(serializeChannelIds(vo.getChannelIds()));
        entity.setEnabled(vo.getEnabled());
        entity.setCreatedAt(new Date());
        entity.setUpdatedAt(new Date());
        this.save(entity);
        log.info("探测任务创建 id={} name={} type={}", entity.getId(), entity.getName(), entity.getType());
        return toTaskVO(entity);
    }

    @Override
    public ProbeTaskVO update(Long id, ProbeTaskUpdateVO vo) {
        ProbeTask existing = this.getById(id);
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "探测任务不存在");
        }
        if (this.nameExists(vo.getName(), id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "探测任务名称已存在");
        }
        existing.setName(vo.getName());
        existing.setType(vo.getType());
        existing.setTarget(vo.getTarget());
        existing.setIntervalSec(vo.getIntervalSec());
        existing.setTimeoutSec(vo.getTimeoutSec());
        existing.setExpectedStatusCode(vo.getExpectedStatusCode());
        existing.setExpectedBodyPattern(vo.getExpectedBodyPattern());

        // headers：null → 保留；空 Map → 清空；其他 → 加密
        Map<String, String> incomingHeaders = vo.getHeaders();
        if (incomingHeaders == null) {
            // 保留旧 headersEnc：什么都不做（NOT_NULL 默认策略会跳过；但本字段是 ALWAYS，因此手动赋回旧值）
            existing.setHeadersEnc(existing.getHeadersEnc());
        } else if (incomingHeaders.isEmpty()) {
            existing.setHeadersEnc(null);
        } else {
            Map<String, String> merged = mergeMaskedHeaders(incomingHeaders, decryptHeadersSafe(existing));
            existing.setHeadersEnc(encryptHeaders(merged));
        }

        existing.setBasicAuthUsername(blankToNull(vo.getBasicAuthUsername()));

        // basic_auth_password：null 或 "***" → 保留；空字符串 → 清空；其他 → 加密
        String incomingPwd = vo.getBasicAuthPassword();
        if (incomingPwd == null || MASK_PLACEHOLDER.equals(incomingPwd)) {
            // 保留：不修改 basicAuthPasswordEnc
            existing.setBasicAuthPasswordEnc(existing.getBasicAuthPasswordEnc());
        } else if (incomingPwd.isEmpty()) {
            existing.setBasicAuthPasswordEnc(null);
        } else {
            existing.setBasicAuthPasswordEnc(cryptoUtils.encrypt(incomingPwd));
        }

        existing.setSslWarnDays(vo.getSslWarnDays() == null ? existing.getSslWarnDays() : vo.getSslWarnDays());
        existing.setConsecutiveFailuresThreshold(vo.getConsecutiveFailuresThreshold());
        existing.setChannelIds(serializeChannelIds(vo.getChannelIds()));
        existing.setEnabled(vo.getEnabled());
        existing.setUpdatedAt(new Date());
        this.updateById(existing);
        log.info("探测任务更新 id={} name={}", existing.getId(), existing.getName());
        return toTaskVO(existing);
    }

    @Override
    public boolean delete(Long id) {
        ProbeTask existing = this.getById(id);
        if (existing == null) {
            return false;
        }
        boolean removed = this.removeById(id);
        if (removed) {
            log.info("探测任务删除 id={} name={}", id, existing.getName());
        }
        return removed;
    }

    @Override
    public IPage<ProbeHistoryVO> listHistory(Long taskId, int page, int size) {
        if (taskId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "taskId 必填");
        }
        if (page < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page 必须 >= 1");
        }
        if (size < 1 || size > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size 必须在 1~200 之间");
        }
        Page<ProbeHistory> pageReq = new Page<>(page, size);
        IPage<ProbeHistory> result = probeHistoryMapper.selectPage(pageReq,
                new QueryWrapper<ProbeHistory>().eq("task_id", taskId).orderByDesc("executed_at"));
        IPage<ProbeHistoryVO> mapped = result.convert(probeStructMapper::toHistoryVO);
        return mapped;
    }

    @Override
    public Map<String, String> resolveHeaders(ProbeTask task) {
        if (task == null || task.getHeadersEnc() == null || task.getHeadersEnc().isBlank()) {
            return Collections.emptyMap();
        }
        try {
            String json = cryptoUtils.decrypt(task.getHeadersEnc());
            if (json == null || json.isBlank()) {
                return Collections.emptyMap();
            }
            Map<String, String> headers = OBJECT_MAPPER.readValue(json, HEADERS_TYPE);
            return headers == null ? Collections.emptyMap() : headers;
        } catch (Exception e) {
            log.warn("探测任务 id={} headers 解密/解析失败：{}", task.getId(), e.getMessage());
            return Collections.emptyMap();
        }
    }

    @Override
    public String resolveBasicAuthPassword(ProbeTask task) {
        if (task == null || task.getBasicAuthPasswordEnc() == null || task.getBasicAuthPasswordEnc().isBlank()) {
            return null;
        }
        try {
            return cryptoUtils.decrypt(task.getBasicAuthPasswordEnc());
        } catch (Exception e) {
            log.warn("探测任务 id={} basic_auth_password 解密失败：{}", task.getId(), e.getMessage());
            return null;
        }
    }

    @Override
    public List<Long> resolveChannelIds(ProbeTask task) {
        if (task == null || task.getChannelIds() == null || task.getChannelIds().isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<Long> ids = OBJECT_MAPPER.readValue(task.getChannelIds(), CHANNEL_IDS_TYPE);
            return ids == null ? Collections.emptyList() : ids;
        } catch (Exception e) {
            log.warn("探测任务 id={} channel_ids 解析失败：{}", task.getId(), e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 内部转换：DTO → VO，并负责脱敏 headers、回填 channelIds 与 hasBasicAuthPassword。
     *
     * @param entity 探测任务实体
     * @return 响应 VO
     */
    private ProbeTaskVO toTaskVO(ProbeTask entity) {
        ProbeTaskVO vo = probeStructMapper.toTaskVO(entity);
        vo.setHeaders(maskedHeaders(entity));
        vo.setChannelIds(resolveChannelIds(entity));
        vo.setHasBasicAuthPassword(entity.getBasicAuthPasswordEnc() != null
                && !entity.getBasicAuthPasswordEnc().isBlank());
        return vo;
    }

    /**
     * 解密 headers 并把所有 value 脱敏为 {@value #MASK_PLACEHOLDER}；解密失败时返回空 Map。
     */
    private Map<String, String> maskedHeaders(ProbeTask entity) {
        Map<String, String> raw = resolveHeaders(entity);
        if (raw.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, String> masked = new LinkedHashMap<>(raw.size());
        for (Map.Entry<String, String> e : raw.entrySet()) {
            masked.put(e.getKey(), MASK_PLACEHOLDER);
        }
        return masked;
    }

    /**
     * 编辑表单合并：value 为 {@code "***"} 的项用旧 Map 的对应明文回填，其余保留新值。
     *
     * @param incoming 前端提交的 headers（含 {@code "***"} 占位）
     * @param previous Service 解密后的旧 headers 明文
     * @return 合并结果
     */
    private Map<String, String> mergeMaskedHeaders(Map<String, String> incoming,
                                                   Map<String, String> previous) {
        Map<String, String> merged = new LinkedHashMap<>(incoming.size());
        for (Map.Entry<String, String> e : incoming.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (MASK_PLACEHOLDER.equals(value) && previous.containsKey(key)) {
                merged.put(key, previous.get(key));
            } else {
                merged.put(key, value);
            }
        }
        return merged;
    }

    /**
     * 序列化 Map → JSON 字符串再加密；空 Map 返回 null（列设为 NULL）。
     */
    private String encryptHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        try {
            String json = OBJECT_MAPPER.writeValueAsString(headers);
            return cryptoUtils.encrypt(json);
        } catch (Exception e) {
            log.warn("headers 序列化加密失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 加密 Basic Auth 密码；空字符串/null 返回 null。
     */
    private String encryptBasicAuthPassword(String password) {
        if (password == null || password.isEmpty()) {
            return null;
        }
        if (MASK_PLACEHOLDER.equals(password)) {
            return null;
        }
        return cryptoUtils.encrypt(password);
    }

    /**
     * channel_ids 序列化为 JSON 数组字符串；空 List 返回 null。
     */
    private String serializeChannelIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(ids);
        } catch (Exception e) {
            log.warn("channel_ids 序列化失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 解密 headers 但容忍失败，返回空 Map 不影响更新流程。
     */
    private Map<String, String> decryptHeadersSafe(ProbeTask task) {
        try {
            return new LinkedHashMap<>(resolveHeaders(task));
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * 空白字符串归一为 null，便于 NOT_NULL 与 ALWAYS 策略统一处理。
     */
    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * 名称是否已被占用（更新时排除自身）。
     */
    private boolean nameExists(String name, Long excludeId) {
        if (name == null || name.isBlank()) {
            return false;
        }
        QueryWrapper<ProbeTask> q = new QueryWrapper<ProbeTask>().eq("name", name);
        if (excludeId != null) {
            q.ne("id", excludeId);
        }
        Long count = this.baseMapper.selectCount(q);
        return count != null && count > 0;
    }

    /**
     * 便捷方法：保存一条 probe_history 记录，供 ProbeScheduler 调用。
     *
     * @param history 探测历史
     * @return 是否保存成功
     */
    public boolean saveHistory(ProbeHistory history) {
        if (history == null) {
            return false;
        }
        try {
            int affected = probeHistoryMapper.insert(history);
            return affected > 0;
        } catch (Exception e) {
            log.warn("probe_history 写入失败 taskId={} reason={}",
                    history.getTaskId(), e.getMessage());
            return false;
        }
    }

    /**
     * 便捷方法：删除指定时刻之前的历史，供 {@link ProbeHistoryCleanupJob} 调用。
     *
     * @param cutoff 截止时间（在此之前的将被删除）
     * @return 被删除的行数
     */
    public int deleteHistoryBefore(Date cutoff) {
        if (cutoff == null) {
            return 0;
        }
        try {
            return probeHistoryMapper.delete(new QueryWrapper<ProbeHistory>()
                    .lt("executed_at", cutoff));
        } catch (Exception e) {
            log.warn("清理 probe_history 失败：{}", e.getMessage());
            return 0;
        }
    }

    /**
     * 暴露给外部组件用于批量加载启用任务（不经过 VO 转换）。
     *
     * @return 已启用的探测任务实体列表
     */
    public List<ProbeTask> listEnabledTasks() {
        try {
            return this.list(new QueryWrapper<ProbeTask>().eq("enabled", 1));
        } catch (Exception e) {
            log.warn("加载启用的 probe_task 失败：{}", e.getMessage());
            return new ArrayList<>();
        }
    }
}
