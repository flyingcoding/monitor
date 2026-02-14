package com.example.config;

import com.example.entity.vo.request.RuntimeDetailVO;

/**
 * SSE 事件总线抽象。
 *
 * 默认实现为单机内存推送，后续可替换为 Redis Pub/Sub 等跨实例实现。
 */
public interface SseEventBus {

    /**
     * 发布客户端列表变更事件。
     */
    void publishClientList();

    /**
     * 发布指定客户端的运行时数据事件。
     *
     * @param clientId 客户端ID
     * @param vo 运行时数据
     */
    void publishRuntime(int clientId, RuntimeDetailVO vo);
}
