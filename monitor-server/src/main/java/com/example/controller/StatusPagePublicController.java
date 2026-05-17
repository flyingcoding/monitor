package com.example.controller;

import com.example.entity.RestBean;
import com.example.entity.vo.response.StatusPageSummaryVO;
import com.example.service.StatusPageService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开状态页接口（PRD R22 / AC9 / AC10）。
 *
 * <p>本控制器对应路径 {@code /api/status/**} 已在 {@link com.example.config.SecurityConfiguration}
 * 中声明为 {@code permitAll}，无需登录即可访问。{@link com.example.filter.ApiTokenFilter}
 * 在请求未携带 mtk_ 前缀 token 时短路放行，也不会影响此处。
 *
 * <p>HTTP 缓存：响应附 {@code Cache-Control: max-age=15, public}，配合服务端 30 秒 Caffeine 缓存
 * 形成两级缓冲。前端轮询 30s 一次即可，无需 SSE。
 */
@Slf4j
@RestController
@RequestMapping("/api/status")
public class StatusPagePublicController {

    @Resource
    private StatusPageService statusPageService;

    /**
     * 状态页汇总。匿名访问；响应体不包含任何主机敏感字段（参见 {@link StatusPageSummaryVO}）。
     *
     * @param response Servlet 响应（用于设置浏览器缓存头）
     * @return {@link RestBean} 包装的状态页 VO
     */
    @GetMapping("/summary")
    public RestBean<StatusPageSummaryVO> summary(HttpServletResponse response) {
        response.setHeader("Cache-Control", "public, max-age=15");
        return RestBean.success(statusPageService.getCachedSummary());
    }
}
