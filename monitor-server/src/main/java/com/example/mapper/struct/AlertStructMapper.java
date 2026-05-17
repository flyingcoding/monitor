package com.example.mapper.struct;

import com.example.entity.dto.AlertHistory;
import com.example.entity.dto.AlertRule;
import com.example.entity.vo.request.AlertRuleCreateVO;
import com.example.entity.vo.request.AlertRuleUpdateVO;
import com.example.entity.vo.response.AlertHistoryVO;
import com.example.entity.vo.response.AlertRuleVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * 告警规则与告警历史 DTO/VO 的编译期映射器。
 */
@Mapper(componentModel = "spring")
public interface AlertStructMapper {

    /**
     * 将告警规则实体映射为响应 VO。
     *
     * @param rule 规则实体
     * @return 响应 VO
     */
    AlertRuleVO toRuleVO(AlertRule rule);

    /**
     * 将创建请求 VO 映射为告警规则实体。id / createdAt / updatedAt 留空由数据库默认值填充。
     *
     * @param vo 创建请求 VO
     * @return 规则实体
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    AlertRule toRule(AlertRuleCreateVO vo);

    /**
     * 将更新请求 VO 拷贝到已加载的规则实体上（PUT 完整编辑语义）。
     * <p>
     * 严格不可变字段：
     * <ul>
     *   <li>{@code id} —— 来自路径参数，请求体不参与；</li>
     *   <li>{@code createdAt} / {@code updatedAt} —— 由数据库或框架管理；</li>
     *   <li>{@code silenceUntil} —— 静默期只能通过 {@code POST /api/alert/rule/{id}/silence}
     *       端点修改。{@link AlertRuleUpdateVO} 中也已移除该字段作为双保险，
     *       即便有人重新加回字段或反序列化路径漏掉校验，也不会把已有静默期被普通编辑/启停操作清空。</li>
     * </ul>
     * 其余字段（含 {@code clientId} / {@code channelIds} 等）允许使用 null 覆盖目标，
     * 以支持"客户端从指定主机改回全局规则"等清空语义。
     *
     * @param vo     更新请求 VO
     * @param target 目标实体（已通过 selectById 加载）
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "silenceUntil", ignore = true)
    void updateRule(AlertRuleUpdateVO vo, @org.mapstruct.MappingTarget AlertRule target);

    /**
     * 将告警历史实体映射为响应 VO。ruleName / metric 在 controller 层根据 ruleId 反查 alert_rule 后填充。
     *
     * @param history 历史实体
     * @return 响应 VO
     */
    @Mapping(target = "ruleName", ignore = true)
    @Mapping(target = "metric", ignore = true)
    AlertHistoryVO toHistoryVO(AlertHistory history);
}
