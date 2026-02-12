package com.example.mapper.struct;

import com.example.entity.dto.Client;
import com.example.entity.dto.ClientSsh;
import com.example.entity.vo.response.ClientDetailsVO;
import com.example.entity.vo.response.ClientPreviewVO;
import com.example.entity.vo.response.ClientSimpleVO;
import com.example.entity.vo.response.SshSettingsVO;
import org.mapstruct.Mapper;

/**
 * 客户端相关 DTO -> VO 的编译期映射器。
 */
@Mapper(componentModel = "spring")
public interface ClientStructMapper {

    /**
     * 将客户端基础信息映射为列表预览VO。
     *
     * @param client 客户端实体
     * @return 预览VO
     */
    ClientPreviewVO toPreviewVO(Client client);

    /**
     * 将客户端基础信息映射为详情VO。
     *
     * @param client 客户端实体
     * @return 详情VO
     */
    ClientDetailsVO toDetailsVO(Client client);

    /**
     * 将客户端基础信息映射为简要VO。
     *
     * @param client 客户端实体
     * @return 简要VO
     */
    ClientSimpleVO toSimpleVO(Client client);

    /**
     * 将SSH实体映射为SSH设置VO。
     *
     * @param ssh SSH实体
     * @return SSH设置VO
     */
    SshSettingsVO toSshSettingsVO(ClientSsh ssh);
}
