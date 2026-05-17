package com.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.entity.dto.AlertHistory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AlertHistoryMapper extends BaseMapper<AlertHistory> {
}
