package com.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.entity.dto.NotificationChannel;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface NotificationChannelMapper extends BaseMapper<NotificationChannel> {
}
