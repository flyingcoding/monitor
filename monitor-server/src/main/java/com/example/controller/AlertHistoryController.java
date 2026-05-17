package com.example.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.entity.RestBean;
import com.example.entity.alert.AlertStatus;
import com.example.entity.dto.AlertHistory;
import com.example.entity.dto.AlertRule;
import com.example.entity.vo.response.AlertHistoryVO;
import com.example.mapper.struct.AlertStructMapper;
import com.example.service.AlertHistoryService;
import com.example.service.AlertRuleService;
import com.example.service.PermissionService;
import com.example.utils.Const;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 告警历史查询与状态变更接口。
 * <p>
 * 权限：管理员可查看 / 操作全部告警历史，子账户仅可查看 / 操作其可见客户端范围内的告警。
 */
@Slf4j
@RestController
@RequestMapping("/api/alert/history")
public class AlertHistoryController {

    @Resource
    private AlertHistoryService alertHistoryService;

    @Resource
    private AlertRuleService alertRuleService;

    @Resource
    private PermissionService permissionService;

    @Resource
    private AlertStructMapper alertStructMapper;

    /**
     * 分页查询告警历史，按 fired_at 倒序返回。
     *
     * @param page 页码（>=1，默认 1）
     * @param size 每页数量（1~100，默认 20）
     * @param clientId 可选客户端ID筛选
     * @param level 可选等级筛选（info / warning / critical）
     * @param status 可选状态筛选（firing / resolved / acknowledged）
     * @param from 触发时间起始（含），可空
     * @param to 触发时间截止（含），可空
     * @param userId 当前用户ID
     * @param userRole 当前用户角色
     * @return 分页结果
     */
    @GetMapping("")
    public RestBean<AlertHistoryPageVO> list(@RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size,
                                             @RequestParam(required = false) Integer clientId,
                                             @RequestParam(required = false) String level,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false)
                                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Date from,
                                             @RequestParam(required = false)
                                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Date to,
                                             @RequestAttribute(Const.ATTR_USER_ID) int userId,
                                             @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (page < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page 必须 >= 1");
        }
        if (size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size 必须在 1~100 之间");
        }
        // 非管理员限定到自己可见的客户端集合；clientId 参数若超出范围将得不到任何记录
        List<Integer> allowed = permissionService.isAdmin(userRole) ? null : permissionService.accessClientIds(userId);
        if (!permissionService.isAdmin(userRole) && clientId != null && !allowed.contains(clientId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该主机的告警历史");
        }

        IPage<AlertHistory> result = alertHistoryService.queryHistory(allowed, clientId, level, status, from, to, page, size);

        List<AlertHistory> records = result.getRecords();
        Map<Long, AlertRule> ruleCache = this.loadRules(records);
        List<AlertHistoryVO> vos = records.stream().map(h -> {
            AlertHistoryVO vo = alertStructMapper.toHistoryVO(h);
            if (h.getRuleId() != null) {
                AlertRule rule = ruleCache.get(h.getRuleId());
                if (rule != null) {
                    vo.setRuleName(rule.getName());
                    vo.setMetric(rule.getMetric());
                }
            }
            return vo;
        }).toList();

        AlertHistoryPageVO body = new AlertHistoryPageVO();
        body.setRecords(vos);
        body.setTotal(result.getTotal());
        body.setPage(result.getCurrent());
        body.setSize(result.getSize());
        return RestBean.success(body);
    }

    /**
     * 查询单条告警历史详情。
     *
     * @param id 历史ID
     * @param userId 当前用户ID
     * @param userRole 当前用户角色
     * @return 历史 VO
     */
    @GetMapping("/{id}")
    public RestBean<AlertHistoryVO> detail(@PathVariable Long id,
                                           @RequestAttribute(Const.ATTR_USER_ID) int userId,
                                           @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        AlertHistory history = this.requireHistoryWithPermission(id, userId, userRole);
        AlertHistoryVO vo = alertStructMapper.toHistoryVO(history);
        if (history.getRuleId() != null) {
            AlertRule rule = alertRuleService.getById(history.getRuleId());
            if (rule != null) {
                vo.setRuleName(rule.getName());
                vo.setMetric(rule.getMetric());
            }
        }
        return RestBean.success(vo);
    }

    /**
     * 确认告警，将状态置为 acknowledged 并记录确认人与时间。
     *
     * @param id 历史ID
     * @param userId 当前用户ID
     * @param userRole 当前用户角色
     * @return 操作结果
     */
    @PostMapping("/{id}/ack")
    public RestBean<Void> ack(@PathVariable Long id,
                              @RequestAttribute(Const.ATTR_USER_ID) int userId,
                              @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        AlertHistory history = this.requireHistoryWithPermission(id, userId, userRole);
        if (AlertStatus.RESOLVED.getColumn().equals(history.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "告警已恢复，无需确认");
        }
        history.setStatus(AlertStatus.ACKNOWLEDGED.getColumn());
        history.setAckedBy(userId);
        history.setAckedAt(new Date());
        alertHistoryService.updateById(history);
        log.info("用户 {} 确认告警 {}", userId, id);
        return RestBean.success();
    }

    /**
     * 关闭告警，将状态置为 resolved 并记录关闭时间。
     *
     * @param id 历史ID
     * @param userId 当前用户ID
     * @param userRole 当前用户角色
     * @return 操作结果
     */
    @PostMapping("/{id}/close")
    public RestBean<Void> close(@PathVariable Long id,
                                @RequestAttribute(Const.ATTR_USER_ID) int userId,
                                @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        AlertHistory history = this.requireHistoryWithPermission(id, userId, userRole);
        history.setStatus(AlertStatus.RESOLVED.getColumn());
        history.setResolvedAt(new Date());
        alertHistoryService.updateById(history);
        log.info("用户 {} 关闭告警 {}", userId, id);
        return RestBean.success();
    }

    /**
     * 加载历史记录涉及的所有规则实体，避免 N+1 查询。
     * 返回 ruleId -> AlertRule 缓存，供调用方读取 name / metric 等字段。
     *
     * @param histories 历史列表
     * @return ruleId -> AlertRule 缓存
     */
    private Map<Long, AlertRule> loadRules(List<AlertHistory> histories) {
        if (histories.isEmpty()) {
            return Map.of();
        }
        Set<Long> ruleIds = new HashSet<>();
        for (AlertHistory h : histories) {
            if (h.getRuleId() != null) {
                ruleIds.add(h.getRuleId());
            }
        }
        if (ruleIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, AlertRule> cache = new HashMap<>();
        for (AlertRule rule : alertRuleService.listByIds(ruleIds)) {
            cache.put(rule.getId(), rule);
        }
        return cache;
    }

    /**
     * 加载告警历史并校验当前用户访问权限；不存在时 404，无权访问时 403。
     *
     * @param id 历史ID
     * @param userId 当前用户ID
     * @param userRole 当前用户角色
     * @return 已校验权限的历史实体
     */
    private AlertHistory requireHistoryWithPermission(Long id, int userId, String userRole) {
        AlertHistory history = alertHistoryService.getById(id);
        if (history == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "告警记录不存在");
        }
        if (!permissionService.canAccessClient(userId, userRole, history.getClientId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该告警记录");
        }
        return history;
    }

    /**
     * 分页响应体。
     */
    @Data
    public static class AlertHistoryPageVO {
        List<AlertHistoryVO> records;
        long total;
        long page;
        long size;
    }
}
