package com.example.controller;

import com.example.entity.RestBean;
import com.example.entity.dto.AlertRule;
import com.example.entity.vo.request.AlertRuleCreateVO;
import com.example.entity.vo.request.AlertRuleUpdateVO;
import com.example.entity.vo.response.AlertRuleVO;
import com.example.mapper.struct.AlertStructMapper;
import com.example.service.AlertRuleService;
import com.example.service.PermissionService;
import com.example.service.impl.AlertWindowCache;
import com.example.utils.Const;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 告警规则管理接口，仅管理员可访问。
 * 提供规则的列表 / 详情 / 增 / 删 / 改 / 静默操作。
 */
@Slf4j
@RestController
@RequestMapping("/api/alert/rule")
public class AlertRuleController {

    /** 静默时长上限：一周（分钟）。 */
    private static final int MAX_SILENCE_MINUTES = 7 * 24 * 60;

    @Resource
    private AlertRuleService alertRuleService;

    @Resource
    private PermissionService permissionService;

    @Resource
    private AlertStructMapper alertStructMapper;

    @Resource
    private AlertWindowCache alertWindowCache;

    /**
     * 查询告警规则列表，可按客户端筛选。
     *
     * @param clientId 可选客户端筛选；非空时仅返回该 client 的规则
     * @param userRole 当前用户角色
     * @return 规则 VO 列表
     */
    @GetMapping("")
    public RestBean<List<AlertRuleVO>> list(@RequestParam(required = false) Integer clientId,
                                            @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        List<AlertRule> rules = alertRuleService.list(Wrappers.<AlertRule>lambdaQuery()
                .eq(clientId != null, AlertRule::getClientId, clientId)
                .orderByDesc(AlertRule::getId));
        List<AlertRuleVO> vos = rules.stream().map(alertStructMapper::toRuleVO).toList();
        return RestBean.success(vos);
    }

    /**
     * 查询单条规则详情。
     *
     * @param id 规则ID
     * @param userRole 当前用户角色
     * @return 规则 VO
     */
    @GetMapping("/{id}")
    public RestBean<AlertRuleVO> detail(@PathVariable Long id,
                                        @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        AlertRule rule = alertRuleService.getById(id);
        if (rule == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "规则不存在");
        }
        return RestBean.success(alertStructMapper.toRuleVO(rule));
    }

    /**
     * 创建告警规则。客户端ID为空表示全局规则；其余字段由 VO 上的注解校验。
     *
     * @param vo 创建请求 VO
     * @param userRole 当前用户角色
     * @return 新建规则 VO（含数据库分配的 id）
     */
    @PostMapping("")
    public RestBean<AlertRuleVO> create(@RequestBody @Valid AlertRuleCreateVO vo,
                                        @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        AlertRule rule = alertStructMapper.toRule(vo);
        alertRuleService.save(rule);
        log.info("创建告警规则成功，id={}, name={}, metric={}", rule.getId(), rule.getName(), rule.getMetric());
        // 重新查询以获取数据库默认值填充的 created_at / updated_at
        AlertRule persisted = alertRuleService.getById(rule.getId());
        return RestBean.success(alertStructMapper.toRuleVO(persisted));
    }

    /**
     * 更新告警规则。
     * <p>
     * 一致性收尾：管理员对规则的关键属性做出变更后，旧告警在评估器主循环里不会再被遍历到
     * （比如禁用、改作用域），无法走"持续不满足 → 自动 resolve"路径，因此必须在更新点
     * 显式批量 resolve 旧活跃告警，并清空滑动窗口缓存，让后续评估按新规则重新积累：
     * <ul>
     *   <li>enabled 由 true → false：resolve 该规则全部活跃告警（reason="规则已禁用"）</li>
     *   <li>clientId 变化（含 null↔value）：resolve <b>旧</b> clientId 范围的活跃告警
     *       （reason="规则作用域已变更"），新作用域由后续评估自然处理</li>
     *   <li>metric / operator / threshold / durationSec 变化：resolve 该规则全部活跃告警
     *       （reason="规则条件已变更"），避免旧告警以旧阈值语义滞留</li>
     * </ul>
     *
     * @param id 规则ID
     * @param vo 更新请求 VO
     * @param userRole 当前用户角色
     * @return 更新后的规则 VO
     */
    @PutMapping("/{id}")
    public RestBean<AlertRuleVO> update(@PathVariable Long id,
                                        @RequestBody @Valid AlertRuleUpdateVO vo,
                                        @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        AlertRule rule = alertRuleService.getById(id);
        if (rule == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "规则不存在");
        }
        // 保留快照用于差异判断（在 mapper 覆盖前抓取）
        Boolean oldEnabled = rule.getEnabled();
        Integer oldClientId = rule.getClientId();
        String oldMetric = rule.getMetric();
        String oldOperator = rule.getOperator();
        Double oldThreshold = rule.getThreshold();
        Integer oldDurationSec = rule.getDurationSec();

        alertStructMapper.updateRule(vo, rule);
        alertRuleService.updateById(rule);
        log.info("更新告警规则成功，id={}", id);

        // 关键变更后清空滑动窗口，避免旧样本以旧阈值语义影响新规则评估
        boolean scopeChanged = !Objects.equals(oldClientId, rule.getClientId());
        boolean conditionChanged = !Objects.equals(oldMetric, rule.getMetric())
                || !Objects.equals(oldOperator, rule.getOperator())
                || !Objects.equals(oldThreshold, rule.getThreshold())
                || !Objects.equals(oldDurationSec, rule.getDurationSec());
        boolean disabled = Boolean.TRUE.equals(oldEnabled) && Boolean.FALSE.equals(rule.getEnabled());

        // 按变更矩阵执行批量 resolve（按"最严重影响"优先，避免重复处理）
        if (disabled) {
            alertRuleService.resolveActivesByRule(id, null, "规则已禁用");
            alertWindowCache.clearByRule(id);
        } else if (scopeChanged) {
            // 仅 resolve 旧作用域的活跃告警；新作用域上的告警让后续评估按新规则自然产生
            alertRuleService.resolveActivesByRule(id, oldClientId, "规则作用域已变更");
            alertWindowCache.clearByRule(id);
        } else if (conditionChanged) {
            alertRuleService.resolveActivesByRule(id, null, "规则条件已变更");
            alertWindowCache.clearByRule(id);
        }

        AlertRule reloaded = alertRuleService.getById(id);
        return RestBean.success(alertStructMapper.toRuleVO(reloaded));
    }

    /**
     * 删除告警规则。
     * <p>
     * 一致性收尾：评估器主循环不再加载已删除规则，活跃告警会永远停在 firing/acknowledged
     * 而无法恢复；删除前先批量 resolve 该规则全部活跃告警（reason="规则已删除"），
     * 同时清空滑动窗口缓存。注意：alert_history.rule_id 不做外键 cascade，删除后旧
     * 历史 ruleId 仍然指向已不存在的规则，前端按 ruleName 缺失时显示"已删除规则"即可。
     *
     * @param id 规则ID
     * @param userRole 当前用户角色
     * @return 操作结果
     */
    @DeleteMapping("/{id}")
    public RestBean<Void> delete(@PathVariable Long id,
                                 @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        // 先 resolve 再删除：保证删除发生时活跃告警已被正确收尾
        alertRuleService.resolveActivesByRule(id, null, "规则已删除");
        alertWindowCache.clearByRule(id);
        boolean removed = alertRuleService.removeById(id);
        if (!removed) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "规则不存在");
        }
        log.info("删除告警规则成功，id={}", id);
        return RestBean.success();
    }

    /**
     * 临时静默告警规则。在 silence_until 截止前不再触发新告警。
     *
     * @param id 规则ID
     * @param minutes 静默分钟数（1~10080）
     * @param userRole 当前用户角色
     * @return 操作结果
     */
    @PostMapping("/{id}/silence")
    public RestBean<AlertRuleVO> silence(@PathVariable Long id,
                                         @RequestParam(defaultValue = "60") Integer minutes,
                                         @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        if (minutes == null || minutes < 1 || minutes > MAX_SILENCE_MINUTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "静默时长必须在 1 ~ " + MAX_SILENCE_MINUTES + " 分钟之间");
        }
        AlertRule rule = alertRuleService.getById(id);
        if (rule == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "规则不存在");
        }
        Date until = Date.from(Instant.now().plus(minutes, ChronoUnit.MINUTES));
        rule.setSilenceUntil(until);
        alertRuleService.updateById(rule);
        log.info("静默告警规则成功，id={}, until={}", id, until);
        AlertRule reloaded = alertRuleService.getById(id);
        return RestBean.success(alertStructMapper.toRuleVO(reloaded));
    }
}
