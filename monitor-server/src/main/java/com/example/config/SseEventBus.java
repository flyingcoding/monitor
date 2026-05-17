package com.example.config;

import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.entity.vo.response.AlertHistoryVO;

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

    /**
     * 发布新触发的告警事件，按订阅者权限过滤后推送 {@code alert-fired} 事件给前端。
     *
     * @param vo 告警历史 VO
     */
    void publishAlertFired(AlertHistoryVO vo);
}
