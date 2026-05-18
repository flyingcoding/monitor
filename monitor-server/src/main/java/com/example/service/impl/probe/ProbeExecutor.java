package com.example.service.impl.probe;

import com.example.entity.dto.ProbeTask;

/**
 * 探测执行器接口。每种探测类型一个实现类，由 {@link com.example.service.impl.ProbeScheduler}
 * 按 {@link ProbeTask#getType()} 路由调用。
 */
public interface ProbeExecutor {

    /**
     * 执行一次探测。
     *
     * @param task          探测任务实体
     * @param decryptedHeaders 已解密的 HTTP Headers（仅 HTTP 探测使用，其他实现可忽略）
     * @param decryptedBasicPwd 已解密的 Basic Auth 密码（仅 HTTP 探测使用）
     * @return 探测结果
     */
    ProbeResult execute(ProbeTask task,
                        java.util.Map<String, String> decryptedHeaders,
                        String decryptedBasicPwd);
}
