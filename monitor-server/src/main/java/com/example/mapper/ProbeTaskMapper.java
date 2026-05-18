package com.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.entity.dto.ProbeTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 探测任务 Mapper：MyBatis-Plus BaseMapper 即可满足 CRUD 需求。
 */
@Mapper
public interface ProbeTaskMapper extends BaseMapper<ProbeTask> {
}
