package com.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.entity.dto.Client;
import org.apache.ibatis.annotations.Mapper;

/**
 * @program: monitor
 * @description: 客户端Mapper接口
 * @author: 王贝强
 * @create: 2024-07-13 16:39
 */
@Mapper
public interface ClientMapper extends BaseMapper<Client> {
}
