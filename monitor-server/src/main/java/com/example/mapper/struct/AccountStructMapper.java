package com.example.mapper.struct;

import com.example.entity.dto.Account;
import com.example.entity.vo.response.AuthorizeVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * 用户账号 DTO -> 登录响应 VO 的编译期映射器。
 */
@Mapper(componentModel = "spring")
public interface AccountStructMapper {

    /**
     * 将账号实体映射为登录响应对象。
     *
     * @param account 账号实体
     * @return 登录响应VO
     */
    @Mapping(target = "token", ignore = true)
    @Mapping(target = "expire", ignore = true)
    AuthorizeVO toAuthorizeVO(Account account);
}
