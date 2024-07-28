package com.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.entity.dto.ClientSsh;
import org.apache.ibatis.annotations.Mapper;

/**
 * @program: monitor
 * @description: 客户端远程SSH信息Mapper
 * @author: 王贝强
 * @create: 2024-07-27 09:42
 */
@Mapper
public interface ClientSshMapper extends BaseMapper<ClientSsh> {
}
