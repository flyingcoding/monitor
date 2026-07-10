package com.example.tsdb;

import com.example.entity.dto.RuntimeData;
import com.example.entity.vo.request.RuntimeDetailVO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;

/**
 * {@link RuntimeDataMapper} 的 provider 无关映射测试。
 */
class RuntimeDataMapperTest {

    /**
     * 两种 TSDB provider 必须共享同一客户端 ID 和采样时间戳转换规则。
     */
    @Test
    void shouldMapClientIdTimestampAndRuntimeFields() {
        RuntimeDetailVO source = new RuntimeDetailVO();
        source.setTimestamp(1_700_000_000_123L);
        source.setCpuUsage(0.42);
        source.setMemoryUsage(128.0);

        RuntimeData result = RuntimeDataMapper.fromRuntimeDetail(42, source);

        Assertions.assertNotNull(result);
        Assertions.assertEquals(42, result.getClientId());
        Assertions.assertEquals(Instant.ofEpochMilli(1_700_000_000_123L), result.getTimestamp());
        Assertions.assertEquals(0.42, result.getCpuUsage());
        Assertions.assertEquals(128.0, result.getMemoryUsage());
        Assertions.assertNull(RuntimeDataMapper.fromRuntimeDetail(42, null));
    }
}
