package com.example.controller;

import com.example.entity.RestBean;
import com.example.entity.dto.AlertRule;
import com.example.entity.vo.request.AlertRuleCreateVO;
import com.example.entity.vo.request.AlertRuleUpdateVO;
import com.example.entity.vo.response.AlertRuleVO;
import com.example.mapper.struct.AlertStructMapper;
import com.example.service.AlertRuleService;
import com.example.service.PermissionService;
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
        alertStructMapper.updateRule(vo, rule);
        alertRuleService.updateById(rule);
        log.info("更新告警规则成功，id={}", id);
        AlertRule reloaded = alertRuleService.getById(id);
        return RestBean.success(alertStructMapper.toRuleVO(reloaded));
    }

    /**
     * 删除告警规则。
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
