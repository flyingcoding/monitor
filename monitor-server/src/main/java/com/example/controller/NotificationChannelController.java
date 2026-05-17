package com.example.controller;

import com.example.entity.RestBean;
import com.example.entity.alert.AlertEvent;
import com.example.entity.alert.AlertLevel;
import com.example.entity.alert.AlertMetric;
import com.example.entity.alert.AlertOperator;
import com.example.entity.dto.NotificationChannel;
import com.example.entity.vo.request.NotificationChannelCreateVO;
import com.example.entity.vo.request.NotificationChannelUpdateVO;
import com.example.entity.vo.response.NotificationChannelVO;
import com.example.service.PermissionService;
import com.example.service.impl.NotificationChannelServiceImpl;
import com.example.service.notification.NotificationChannelSender;
import com.example.utils.Const;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知通道管理接口。仅管理员可访问；管理员校验通过 {@link PermissionService#isAdmin(String)} 完成，
 * 与 AlertRuleController / AlertHistoryController 的鉴权模式保持一致。
 * <p>
 * 敏感字段（_enc 后缀）处理流程：
 * <ul>
 *   <li>创建时由 {@link NotificationChannelServiceImpl#encryptSensitive(Map)} 加密入库。</li>
 *   <li>查询返回的 VO 通过 {@link NotificationChannelVO#getMaskedConfig()} 自动遮罩为 "***"。</li>
 *   <li>更新时若收到 "***" 占位符则保留旧密文；否则视为新明文重新加密。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/notification/channel")
public class NotificationChannelController {

    @Resource
    private NotificationChannelServiceImpl notificationChannelService;

    @Resource
    private PermissionService permissionService;

    @Resource
    private List<NotificationChannelSender> notificationChannelSenders;

    /**
     * 列出所有通知通道，敏感字段会被 VO 自动遮罩。
     *
     * @param userRole 当前用户角色
     * @return 通道列表
     */
    @GetMapping
    public RestBean<List<NotificationChannelVO>> list(@RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        List<NotificationChannelVO> data = notificationChannelService.list()
                .stream()
                .map(this::toVO)
                .toList();
        return RestBean.success(data);
    }

    /**
     * 查询单个通道详情，敏感字段会被 VO 自动遮罩。
     *
     * @param id 通道ID
     * @param userRole 当前用户角色
     * @return 通道详情
     */
    @GetMapping("/{id}")
    public RestBean<NotificationChannelVO> detail(@PathVariable Long id,
                                                  @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        NotificationChannel channel = notificationChannelService.getById(id);
        if (channel == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "通知通道不存在");
        }
        return RestBean.success(toVO(channel));
    }

    /**
     * 创建通知通道。config 中的 _enc 后缀字段会在入库前加密。
     *
     * @param vo 创建请求
     * @param userRole 当前用户角色
     * @return 创建后的通道
     */
    @PostMapping
    public RestBean<NotificationChannelVO> create(@RequestBody @Valid NotificationChannelCreateVO vo,
                                                  @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        NotificationChannel entity = new NotificationChannel();
        entity.setName(vo.getName());
        entity.setType(vo.getType());
        entity.setEnabled(vo.getEnabled());
        Map<String, Object> config = vo.getConfig() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(vo.getConfig());
        notificationChannelService.encryptSensitive(config);
        entity.setConfig(config);
        entity.setCreatedAt(new Date());
        notificationChannelService.save(entity);
        log.info("管理员创建通知通道 id={} type={}", entity.getId(), entity.getType());
        return RestBean.success(toVO(entity));
    }

    /**
     * 更新通知通道。前端回传 "***" 占位的 _enc 字段视为未修改，保留旧密文；
     * 其他 _enc 字段视为新明文并加密。
     *
     * @param id 通道ID
     * @param vo 更新请求
     * @param userRole 当前用户角色
     * @return 更新后的通道
     */
    @PutMapping("/{id}")
    public RestBean<NotificationChannelVO> update(@PathVariable Long id,
                                                  @RequestBody @Valid NotificationChannelUpdateVO vo,
                                                  @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        NotificationChannel existing = notificationChannelService.getById(id);
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "通知通道不存在");
        }
        Map<String, Object> oldConfig = existing.getConfig() == null
                ? new LinkedHashMap<>()
                : existing.getConfig();
        Map<String, Object> newConfig = vo.getConfig() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(vo.getConfig());
        notificationChannelService.preserveExistingEnc(newConfig, oldConfig);
        notificationChannelService.encryptSensitive(newConfig);

        existing.setName(vo.getName());
        existing.setType(vo.getType());
        existing.setEnabled(vo.getEnabled());
        existing.setConfig(newConfig);
        notificationChannelService.updateById(existing);
        log.info("管理员更新通知通道 id={} type={}", existing.getId(), existing.getType());
        return RestBean.success(toVO(existing));
    }

    /**
     * 删除通知通道。被任何告警规则的 channel_ids 引用时拒绝删除，避免规则失效。
     *
     * @param id 通道ID
     * @param userRole 当前用户角色
     * @return 删除结果
     */
    @DeleteMapping("/{id}")
    public RestBean<Void> delete(@PathVariable Long id,
                                 @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        NotificationChannel existing = notificationChannelService.getById(id);
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "通知通道不存在");
        }
        if (notificationChannelService.isReferenced(id)) {
            return RestBean.failure(HttpStatus.CONFLICT.value(), "通道正被告警规则引用，请先解绑");
        }
        notificationChannelService.removeById(id);
        log.info("管理员删除通知通道 id={}", id);
        return RestBean.success();
    }

    /**
     * 发送测试通知。同步调用对应 {@link NotificationChannelSender}（不经 RabbitMQ）以即时反馈成败。
     * 解密 _enc 字段后传入 sender；失败信息直接返回前端便于排错。
     *
     * @param id 通道ID
     * @param userRole 当前用户角色
     * @return 发送结果
     */
    @PostMapping("/{id}/test")
    public RestBean<String> test(@PathVariable Long id,
                                 @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        NotificationChannel channel = notificationChannelService.getById(id);
        if (channel == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "通知通道不存在");
        }
        NotificationChannelSender sender = resolveSender(channel.getType());
        if (sender == null) {
            return RestBean.failure(400, "未找到对应类型的通知发送器: " + channel.getType());
        }
        Map<String, Object> decryptedConfig = notificationChannelService.decryptSensitive(channel.getConfig());
        AlertEvent event = buildTestAlertEvent(channel.getId());
        try {
            sender.send(event, decryptedConfig);
            log.info("管理员触发测试通知 channelId={} type={}", channel.getId(), channel.getType());
            return RestBean.success("测试通知已发送");
        } catch (Exception e) {
            log.warn("测试通知发送失败 channelId={} type={} reason={}",
                    channel.getId(), channel.getType(), e.getMessage());
            return RestBean.failure(500, "发送失败: " + e.getMessage());
        }
    }

    /**
     * 将实体映射为响应 VO；config 序列化时由 {@link NotificationChannelVO#getMaskedConfig()} 自动遮罩 _enc 字段。
     *
     * @param entity 通道实体
     * @return 响应 VO
     */
    private NotificationChannelVO toVO(NotificationChannel entity) {
        NotificationChannelVO vo = new NotificationChannelVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setType(entity.getType());
        vo.setEnabled(entity.getEnabled());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setConfig(entity.getConfig());
        return vo;
    }

    /**
     * 在已注入的 {@link NotificationChannelSender} Bean 列表中按 type 路由到对应实现。
     *
     * @param type 通道类型
     * @return 匹配的发送器，未找到返回 null
     */
    private NotificationChannelSender resolveSender(String type) {
        if (type == null) {
            return null;
        }
        List<NotificationChannelSender> senders = notificationChannelSenders == null
                ? new ArrayList<>()
                : notificationChannelSenders;
        for (NotificationChannelSender sender : senders) {
            if (type.equals(sender.type())) {
                return sender;
            }
        }
        return null;
    }

    /**
     * 构造一条测试用 AlertEvent；sender 仅依据 config 与基础字段渲染。
     *
     * @param channelId 通道ID
     * @return 测试事件
     */
    private AlertEvent buildTestAlertEvent(Long channelId) {
        return AlertEvent.builder()
                .ruleId(0L)
                .historyId(0L)
                .clientId(0)
                .clientName("test-client")
                .metric(AlertMetric.CPU.getColumn())
                .operator(AlertOperator.GT.getColumn())
                .threshold(80.0)
                .currentValue(99.0)
                .level(AlertLevel.INFO.getColumn())
                .message("这是一条测试通知")
                .firedAt(LocalDateTime.now())
                .channelIds(List.of(channelId))
                .build();
    }
}
