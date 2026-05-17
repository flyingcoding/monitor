package com.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.entity.dto.StatusPageConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 公开状态页配置 Mapper。
 *
 * <p>单行表（{@code id = 1}）。常用方法为 {@code selectById(1)} 与 {@code updateById}。
 */
@Mapper
public interface StatusPageConfigMapper extends BaseMapper<StatusPageConfig> {
}
