package com.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.entity.dto.ProbeHistory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 探测历史 Mapper：BaseMapper 提供 CRUD + 分页能力。
 */
@Mapper
public interface ProbeHistoryMapper extends BaseMapper<ProbeHistory> {
}
