package com.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.entity.dto.ApiToken;
import org.apache.ibatis.annotations.Mapper;

/**
 * API Token MyBatis-Plus Mapper。
 *
 * <p>查询路径主要通过 {@code BaseMapper#selectOne(Wrapper)} 按 {@code token_hash}（唯一索引）等值查找；
 * 控制器/服务层全部使用 {@link com.baomidou.mybatisplus.core.conditions.query.QueryWrapper}，不写自定义 SQL。
 */
@Mapper
public interface ApiTokenMapper extends BaseMapper<ApiToken> {
}
