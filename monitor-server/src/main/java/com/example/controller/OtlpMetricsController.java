package com.example.controller;

import com.example.controller.otlp.OtlpMetricParser;
import com.example.entity.RestBean;
import com.example.entity.dto.Client;
import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.service.ClientService;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * OTLP/HTTP 指标接收端点 (v2.0-alpha)。
 *
 * <p>遵循 OTel 协议路径 {@code POST /v1/metrics}，同时接受 Protobuf 与 JSON 编码
 * （决策 D5：HTTP/Protobuf + HTTP/JSON，不上 gRPC）。
 *
 * <h3>鉴权</h3>
 * <p>采用 {@code X-Monitor-Token} header 复用客户端注册 token（决策 D2），不走 JWT 链路；
 * SecurityConfiguration 对 {@code /v1/metrics} permitAll，所有校验在控制器内完成。
 *
 * <h3>写入链路</h3>
 * <p>解析后的 {@link RuntimeDetailVO} 通过 {@link ClientService#updateRuntimeDetail} 流入，
 * 与客户端直传 {@code /monitor/runtime} 路径共享 cache + heartbeat + Influx 写入 + SSE 推送 +
 * AlertEvaluator 触发（决策 D6）。
 *
 * <h3>容错</h3>
 * <ul>
 *   <li>无 token / token 无效 → 401，{@code RestBean.unauthorized}</li>
 *   <li>payload 超过 {@code monitor.otlp.max-payload-size} → 413</li>
 *   <li>protobuf/JSON 解析失败 → 400</li>
 *   <li>未识别 metric → 不报错，counter 自增 + WARN（D3）</li>
 *   <li>{@code host.name} 与 token 绑定 client 名称不一致 → WARN，仍写入 token 对应 client（D2）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/v1/metrics")
public class OtlpMetricsController {

    /** 鉴权 header 名（与 docs/v2.0-alpha-otlp.md 文档对齐）。 */
    public static final String AUTH_HEADER = "X-Monitor-Token";

    @Resource
    private ClientService clientService;

    @Value("${monitor.otlp.enabled:true}")
    private boolean enabled;

    @Value("${monitor.otlp.max-payload-size:4194304}")
    private int maxPayloadBytes;

    /**
     * 接收 OTLP/Protobuf 编码的指标请求。
     *
     * @param token   {@code X-Monitor-Token} header
     * @param payload protobuf 字节
     * @return 200 表示已接受；4xx 表示鉴权或解析失败
     */
    @PostMapping(consumes = "application/x-protobuf")
    public ResponseEntity<RestBean<Void>> ingestProtobuf(
            @RequestHeader(value = AUTH_HEADER, required = false) String token,
            @RequestBody byte[] payload) {
        if (!enabled) {
            return ResponseEntity.status(503).body(RestBean.failure(503, "OTLP 端点未启用"));
        }
        if (payload != null && payload.length > maxPayloadBytes) {
            return tooLarge();
        }
        ExportMetricsServiceRequest request;
        try {
            request = ExportMetricsServiceRequest.parseFrom(payload == null ? new byte[0] : payload);
        } catch (InvalidProtocolBufferException e) {
            log.warn("OTLP protobuf 解析失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(RestBean.failure(400, "OTLP protobuf 解析失败"));
        }
        return process(token, request);
    }

    /**
     * 接收 OTLP/JSON 编码的指标请求（OTel HTTP/JSON 规范的 protobuf JSON 表达）。
     *
     * @param token {@code X-Monitor-Token} header
     * @param body  OTLP JSON 字符串
     * @return 200 表示已接受；4xx 表示鉴权或解析失败
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RestBean<Void>> ingestJson(
            @RequestHeader(value = AUTH_HEADER, required = false) String token,
            @RequestBody(required = false) String body) {
        if (!enabled) {
            return ResponseEntity.status(503).body(RestBean.failure(503, "OTLP 端点未启用"));
        }
        if (body != null && body.getBytes(StandardCharsets.UTF_8).length > maxPayloadBytes) {
            return tooLarge();
        }
        ExportMetricsServiceRequest.Builder builder = ExportMetricsServiceRequest.newBuilder();
        try {
            JsonFormat.parser().ignoringUnknownFields().merge(body == null ? "{}" : body, builder);
        } catch (Exception e) {
            log.warn("OTLP JSON 解析失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(RestBean.failure(400, "OTLP JSON 解析失败"));
        }
        return process(token, builder.build());
    }

    /**
     * 共享的鉴权 + 解析 + 写入流程。
     */
    private ResponseEntity<RestBean<Void>> process(String token, ExportMetricsServiceRequest request) {
        if (token == null || token.isBlank()) {
            return ResponseEntity.status(401).body(RestBean.unauthorized("缺少 X-Monitor-Token"));
        }
        Client client = clientService.findClientByToken(token);
        if (client == null) {
            return ResponseEntity.status(401).body(RestBean.unauthorized("X-Monitor-Token 无效"));
        }
        List<OtlpMetricParser.Result> results = OtlpMetricParser.parse(request);
        if (results.isEmpty()) {
            log.warn("OTLP 请求未携带任何 monitor.client.* metric clientId={}", client.getId());
            return ResponseEntity.ok(RestBean.success());
        }
        for (OtlpMetricParser.Result result : results) {
            this.crossCheckHostName(result.getHostName(), client);
            this.logUnknownMetrics(result.getUnknownMetricCounts(), client.getId());
            RuntimeDetailVO vo = result.getRuntime();
            clientService.updateRuntimeDetail(vo, client);
        }
        return ResponseEntity.ok(RestBean.success());
    }

    /**
     * 把 OTel resource {@code host.name} 与 token 绑定的 client 名称交叉对照。
     *
     * <p>决策 D2：不匹配仅 WARN 不阻塞写入，避免主机改名/未同步导致采集中断。
     */
    private void crossCheckHostName(String hostName, Client client) {
        if (hostName == null || hostName.isBlank()) {
            return;
        }
        if (!Objects.equals(hostName, client.getName())) {
            log.warn("OTLP host.name 与 token 绑定 client 不一致 host.name={} clientId={} clientName={}",
                    hostName, client.getId(), client.getName());
        }
    }

    /**
     * 把未识别 metric 计数打 WARN 日志，供调试 Collector 配置使用。
     */
    private void logUnknownMetrics(Map<String, Long> counts, int clientId) {
        if (counts == null || counts.isEmpty()) {
            return;
        }
        counts.forEach((name, count) ->
                log.warn("OTLP 收到未识别 metric clientId={} name={} count={}", clientId, name, count));
    }

    private ResponseEntity<RestBean<Void>> tooLarge() {
        log.warn("OTLP payload 超过 {} 字节上限", maxPayloadBytes);
        return ResponseEntity.status(413).body(RestBean.failure(413, "OTLP payload 超过上限"));
    }
}
