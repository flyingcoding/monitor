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
     * 将更新请求 VO 拷贝到已加载的规则实体上。id / createdAt 不可变。
     *
     * @param vo     更新请求 VO
     * @param target 目标实体（已通过 selectById 加载）
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
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
