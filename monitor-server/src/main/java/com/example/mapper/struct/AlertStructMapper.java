package com.example.mapper.struct;

import com.example.entity.dto.AlertHistory;
import com.example.entity.dto.AlertRule;
import com.example.entity.vo.request.AlertRuleCreateVO;
import com.example.entity.vo.request.AlertRuleUpdateVO;
import com.example.entity.vo.response.AlertHistoryVO;
import com.example.entity.vo.response.AlertRuleVO;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValuePropertyMappingStrategy;

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
     * 将更新请求 VO 拷贝到已加载的规则实体上。
     * <p>
     * 严格不可变字段：{@code id} / {@code createdAt} / {@code updatedAt} 由数据库或框架管理。
     * <p>
     * {@code silenceUntil} 显式 ignore（双保险）：
     * <ul>
     *   <li>{@link AlertRuleUpdateVO} 中已移除该字段（强约束）；</li>
     *   <li>这里再次 ignore，确保即使后续有人重新加回字段或反序列化路径漏掉校验，
     *       也不会把已有静默期被普通编辑/启停操作（toggleEnabled）清空。</li>
     *   <li>静默期只能通过 {@code POST /api/alert/rule/{id}/silence} 端点修改。</li>
     * </ul>
     * 同时设置 {@code nullValuePropertyMappingStrategy=IGNORE}：
     * 当前端任何字段未提交（null）时不要把已有值清空。这与 PATCH 语义一致，
     * 适合启停开关 / 表单局部更新场景。
     *
     * @param vo     更新请求 VO
     * @param target 目标实体（已通过 selectById 加载）
     */
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "silenceUntil", ignore = true)
    void updateRule(AlertRuleUpdateVO vo, @org.mapstruct.MappingTarget AlertRule target);

    /**
     * 将告警历史实体映射为响应 VO。ruleName 在 controller 层填充。
     *
     * @param history 历史实体
     * @return 响应 VO
     */
    @Mapping(target = "ruleName", ignore = true)
    AlertHistoryVO toHistoryVO(AlertHistory history);
}
