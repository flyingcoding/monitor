package com.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.entity.dto.ClientDetail;
import org.apache.ibatis.annotations.Mapper;

/**
 * @program: monitor
 * @description: 服务器数据Mapper接口类
 * @author: 王贝强
 * @create: 2024-07-15 12:29
 */
@Mapper
public interface ClientDetailMapper extends BaseMapper<ClientDetail> {
}
