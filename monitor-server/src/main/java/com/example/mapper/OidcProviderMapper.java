package com.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.entity.dto.OidcProvider;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OidcProviderMapper extends BaseMapper<OidcProvider> {
}
