package com.example.config;

import com.example.controller.SseController;
import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.entity.vo.response.AlertHistoryVO;
import jakarta.annotation.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 单机模式下的 SSE 事件总线实现，直接调用当前进程的 SseController 推送。
 */
@Component
@ConditionalOnMissingBean(name = "redisSseEventBus")
public class LocalSseEventBus implements SseEventBus {

    @Lazy
    @Resource
    private SseController sseController;

    /**
     * 向本地 SSE 控制器发布客户端列表变更事件。
     */
    @Override
    public void publishClientList() {
        sseController.pushClientList();
    }

    /**
     * 向本地 SSE 控制器发布客户端运行时数据事件。
     *
     * @param clientId 客户端ID
     * @param vo 运行时数据
     */
    @Override
    public void publishRuntime(int clientId, RuntimeDetailVO vo) {
        sseController.pushRuntime(clientId, vo);
    }

    /**
     * 向本地 SSE 控制器发布告警触发事件。
     *
     * @param vo 告警历史 VO
     */
    @Override
    public void publishAlertFired(AlertHistoryVO vo) {
        sseController.pushAlertFired(vo);
    }
}
