package com.example.mapper.struct;

import com.example.entity.dto.ProbeHistory;
import com.example.entity.dto.ProbeTask;
import com.example.entity.vo.response.ProbeHistoryVO;
import com.example.entity.vo.response.ProbeTaskVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * 服务探测 DTO ↔ VO 映射器。
 *
 * <p>探测任务的敏感字段（{@code headersEnc} / {@code basicAuthPasswordEnc} / {@code channelIds}）
 * 不直接由 MapStruct 转换 —— {@code headers} 由 Service 层解密 + 脱敏后填入；
 * {@code hasBasicAuthPassword} 由 Service 层根据密文是否存在显式赋值；
 * {@code channelIds} 由 Service 层从 JSON 字符串反序列化。
 */
@Mapper(componentModel = "spring")
public interface ProbeStructMapper {

    /**
     * 将探测任务实体映射为响应 VO（不含 headers / channelIds / hasBasicAuthPassword，由 Service 层填充）。
     *
     * @param task 探测任务实体
     * @return 响应 VO
     */
    @Mapping(target = "headers", ignore = true)
    @Mapping(target = "channelIds", ignore = true)
    @Mapping(target = "hasBasicAuthPassword", ignore = true)
    ProbeTaskVO toTaskVO(ProbeTask task);

    /**
     * 将探测历史实体映射为响应 VO。
     *
     * @param history 历史实体
     * @return 响应 VO
     */
    ProbeHistoryVO toHistoryVO(ProbeHistory history);
}
