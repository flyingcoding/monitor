package com.example.tsdb;

import com.example.entity.dto.RuntimeData;
import com.example.entity.vo.request.RuntimeDetailVO;
import org.springframework.beans.BeanUtils;

import java.util.Date;

/**
 * 将运行时上报 VO 转换为两个 TSDB provider 共用的 measurement DTO。
 */
final class RuntimeDataMapper {

    private RuntimeDataMapper() {
    }

    /**
     * 为指定客户端构造带统一时间戳语义的运行时 measurement。
     *
     * @param clientId 客户端 ID
     * @param vo 运行时上报数据
     * @return measurement DTO；输入为空时返回 null
     */
    static RuntimeData fromRuntimeDetail(int clientId, RuntimeDetailVO vo) {
        if (vo == null) {
            return null;
        }
        RuntimeData data = new RuntimeData();
        BeanUtils.copyProperties(vo, data);
        data.setClientId(clientId);
        data.setTimestamp(new Date(vo.getTimestamp()).toInstant());
        return data;
    }
}
