package com.example.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.entity.dto.StatusPageConfig;
import com.example.entity.vo.request.StatusPageConfigUpdateVO;
import com.example.entity.vo.response.StatusPageConfigVO;
import com.example.entity.vo.response.StatusPageSummaryVO;

/**
 * 公开状态页服务接口（v1.2 prd Agent C）。
 *
 * <p>分公开消费者（{@code GET /api/status/summary}）和管理员配置（{@code GET / PUT /api/status/config}）。
 * 前者带 Caffeine 30s 缓存 + 失败兜底，后者直接读写 DB 并失效缓存。
 */
public interface StatusPageService extends IService<StatusPageConfig> {

    /**
     * 获取状态页缓存视图。30 秒内多次调用只触发一次 InfluxDB 查询（PRD AC10）。
     *
     * <p>当 {@code config.enabled == false} 时仍返回 title/subtitle 但客户端列表为空，
     * 便于前端显示"状态页未启用"。
     *
     * @return 状态页汇总（含品牌信息与客户端列表）
     */
    StatusPageSummaryVO getCachedSummary();

    /**
     * 读取管理员视图配置（含候选客户端列表）。
     *
     * @return 配置 + 候选客户端
     */
    StatusPageConfigVO getAdminConfig();

    /**
     * 写入状态页配置；写入成功后失效 {@link #getCachedSummary()} 缓存。
     *
     * @param vo 更新请求
     * @return 更新后的配置（含候选客户端列表）
     */
    StatusPageConfigVO updateConfig(StatusPageConfigUpdateVO vo);
}
