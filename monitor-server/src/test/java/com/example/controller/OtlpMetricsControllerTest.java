package com.example.controller;

import com.example.controller.otlp.OtlpMetricParser;
import com.example.entity.RestBean;
import com.example.entity.dto.Client;
import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.service.ClientService;
import com.google.protobuf.util.JsonFormat;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.Gauge;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.resource.v1.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * {@link OtlpMetricsController} 单元测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>无 X-Monitor-Token / token 无效 → 401；</li>
 *   <li>Protobuf 成功路径 → 200 且 ClientService.updateRuntimeDetail 被调用；</li>
 *   <li>JSON 成功路径 → 200；</li>
 *   <li>protobuf 解析失败 → 400；</li>
 *   <li>JSON 解析失败 → 400；</li>
 *   <li>payload 超限 → 413；</li>
 *   <li>未识别 metric 不报错（counter 仅记日志），但基础 metric 不完整时不写入；</li>
 *   <li>host.name 不匹配仍写入 token 对应 client。</li>
 * </ul>
 *
 * <p>沿用项目惯例：JDK 动态代理伪造 {@link ClientService}，{@link ReflectionTestUtils} 注入。
 */
class OtlpMetricsControllerTest {

    private OtlpMetricsController controller;
    private final List<RuntimeDetailVO> capturedVOs = new ArrayList<>();
    private final List<Client> capturedClients = new ArrayList<>();
    private Client knownClient;

    @BeforeEach
    void setUp() {
        capturedVOs.clear();
        capturedClients.clear();
        knownClient = new Client(1001, "host-01", "token-good", "cn", "node-1", new Date(), null);

        ClientService clientService = (ClientService) Proxy.newProxyInstance(
                ClientService.class.getClassLoader(),
                new Class[]{ClientService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findClientByToken" -> {
                        String token = (String) args[0];
                        yield "token-good".equals(token) ? knownClient : null;
                    }
                    case "updateRuntimeDetail" -> {
                        capturedVOs.add((RuntimeDetailVO) args[0]);
                        capturedClients.add((Client) args[1]);
                        yield null;
                    }
                    default -> null;
                });

        controller = new OtlpMetricsController();
        ReflectionTestUtils.setField(controller, "clientService", clientService);
        ReflectionTestUtils.setField(controller, "enabled", true);
        ReflectionTestUtils.setField(controller, "maxPayloadBytes", 4 * 1024 * 1024);
    }

    @Test
    void protobufWithoutTokenShouldReturn401() {
        ResponseEntity<RestBean<Void>> resp = controller.ingestProtobuf(null, validProtobuf());
        Assertions.assertEquals(401, resp.getStatusCode().value());
        Assertions.assertTrue(capturedVOs.isEmpty());
    }

    @Test
    void protobufWithBlankTokenShouldReturn401() {
        ResponseEntity<RestBean<Void>> resp = controller.ingestProtobuf("  ", validProtobuf());
        Assertions.assertEquals(401, resp.getStatusCode().value());
    }

    @Test
    void protobufWithInvalidTokenShouldReturn401() {
        ResponseEntity<RestBean<Void>> resp = controller.ingestProtobuf("token-bad", validProtobuf());
        Assertions.assertEquals(401, resp.getStatusCode().value());
        Assertions.assertTrue(capturedVOs.isEmpty());
    }

    @Test
    void protobufHappyPathShouldRouteToClientService() {
        ResponseEntity<RestBean<Void>> resp = controller.ingestProtobuf("token-good", validProtobuf());
        Assertions.assertEquals(200, resp.getStatusCode().value());
        Assertions.assertEquals(1, capturedVOs.size());
        Assertions.assertEquals(0.55, capturedVOs.get(0).getCpuUsage(), 1e-9);
        Assertions.assertSame(knownClient, capturedClients.get(0));
    }

    @Test
    void jsonHappyPathShouldRouteToClientService() throws Exception {
        ExportMetricsServiceRequest req = buildRequest("host-01", 0.42);
        String json = JsonFormat.printer().print(req);
        ResponseEntity<RestBean<Void>> resp = controller.ingestJson("token-good", json);
        Assertions.assertEquals(200, resp.getStatusCode().value());
        Assertions.assertEquals(1, capturedVOs.size());
        Assertions.assertEquals(0.42, capturedVOs.get(0).getCpuUsage(), 1e-9);
    }

    @Test
    void invalidProtobufShouldReturn400() {
        ResponseEntity<RestBean<Void>> resp = controller.ingestProtobuf("token-good", new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff});
        Assertions.assertEquals(400, resp.getStatusCode().value());
        Assertions.assertTrue(capturedVOs.isEmpty());
    }

    @Test
    void invalidJsonShouldReturn400() {
        ResponseEntity<RestBean<Void>> resp = controller.ingestJson("token-good", "{not valid json");
        Assertions.assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void payloadOverLimitShouldReturn413() {
        ReflectionTestUtils.setField(controller, "maxPayloadBytes", 16);
        ResponseEntity<RestBean<Void>> resp = controller.ingestProtobuf("token-good", validProtobuf());
        Assertions.assertEquals(413, resp.getStatusCode().value());
        Assertions.assertTrue(capturedVOs.isEmpty());
    }

    @Test
    void disabledEndpointShouldReturn503() {
        ReflectionTestUtils.setField(controller, "enabled", false);
        ResponseEntity<RestBean<Void>> resp = controller.ingestProtobuf("token-good", validProtobuf());
        Assertions.assertEquals(503, resp.getStatusCode().value());
    }

    @Test
    void unknownMetricsShouldNotBlockWrite() {
        long ts = nowNs();
        ExportMetricsServiceRequest req = ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(ResourceMetrics.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(stringAttr("host.name", "host-01")))
                        .addScopeMetrics(baseRuntimeMetrics(0.3, ts)
                                .addMetrics(gauge("system.unknown", 99.0, ts))))
                .build();
        ResponseEntity<RestBean<Void>> resp = controller.ingestProtobuf("token-good", req.toByteArray());
        Assertions.assertEquals(200, resp.getStatusCode().value());
        Assertions.assertEquals(1, capturedVOs.size());
        Assertions.assertEquals(0.3, capturedVOs.get(0).getCpuUsage(), 1e-9);
    }

    @Test
    void mismatchedHostNameShouldStillWriteToTokenClient() {
        ExportMetricsServiceRequest req = ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(ResourceMetrics.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(stringAttr("host.name", "other-host")))
                        .addScopeMetrics(baseRuntimeMetrics(0.7, nowNs())))
                .build();
        ResponseEntity<RestBean<Void>> resp = controller.ingestProtobuf("token-good", req.toByteArray());
        Assertions.assertEquals(200, resp.getStatusCode().value());
        Assertions.assertEquals(1, capturedVOs.size());
        Assertions.assertSame(knownClient, capturedClients.get(0),
                "host.name 不匹配仅 WARN，决策 D2：仍写入 token 对应 client");
    }

    @Test
    void partialBaseMetricsShouldReturn200WithoutCallingService() {
        ExportMetricsServiceRequest req = ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(ResourceMetrics.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(stringAttr("host.name", "host-01")))
                        .addScopeMetrics(ScopeMetrics.newBuilder()
                                .addMetrics(gauge(OtlpMetricParser.NAMESPACE + "cpu_usage", 0.8, nowNs()))))
                .build();
        ResponseEntity<RestBean<Void>> resp = controller.ingestProtobuf("token-good", req.toByteArray());
        Assertions.assertEquals(200, resp.getStatusCode().value());
        Assertions.assertTrue(capturedVOs.isEmpty(),
                "基础 7 项不完整时不能写入 runtime，避免缺失字段被 primitive 默认值 0 污染");
    }

    @Test
    void allUnknownMetricsShouldReturn200WithoutCallingService() {
        ExportMetricsServiceRequest req = ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(ResourceMetrics.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(stringAttr("host.name", "host-01")))
                        .addScopeMetrics(ScopeMetrics.newBuilder()
                                .addMetrics(gauge("system.cpu.utilization", 0.8, nowNs()))))
                .build();
        ResponseEntity<RestBean<Void>> resp = controller.ingestProtobuf("token-good", req.toByteArray());
        Assertions.assertEquals(200, resp.getStatusCode().value());
        Assertions.assertTrue(capturedVOs.isEmpty(),
                "全未知 metric 只记录 WARN，不应刷新 cache/heartbeat/Influx");
    }

    @Test
    void emptyRequestShouldReturn200WithoutCallingService() {
        ExportMetricsServiceRequest req = ExportMetricsServiceRequest.newBuilder().build();
        ResponseEntity<RestBean<Void>> resp = controller.ingestProtobuf("token-good", req.toByteArray());
        Assertions.assertEquals(200, resp.getStatusCode().value());
        Assertions.assertTrue(capturedVOs.isEmpty(),
                "空请求不应触发 updateRuntimeDetail（避免污染 cache + heartbeat）");
    }

    private static byte[] validProtobuf() {
        return buildRequest("host-01", 0.55).toByteArray();
    }

    private static ExportMetricsServiceRequest buildRequest(String hostName, double cpuValue) {
        long ts = nowNs();
        return ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(ResourceMetrics.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(stringAttr("host.name", hostName)))
                        .addScopeMetrics(baseRuntimeMetrics(cpuValue, ts)))
                .build();
    }

    private static ScopeMetrics.Builder baseRuntimeMetrics(double cpuValue, long timeUnixNano) {
        return ScopeMetrics.newBuilder()
                .addMetrics(gauge(OtlpMetricParser.NAMESPACE + "cpu_usage", cpuValue, timeUnixNano))
                .addMetrics(gauge(OtlpMetricParser.NAMESPACE + "memory_used_gb", 8.0, timeUnixNano))
                .addMetrics(gauge(OtlpMetricParser.NAMESPACE + "disk_used_gb", 120.0, timeUnixNano))
                .addMetrics(gauge(OtlpMetricParser.NAMESPACE + "network_upload_kbps", 12.5, timeUnixNano))
                .addMetrics(gauge(OtlpMetricParser.NAMESPACE + "network_download_kbps", 25.0, timeUnixNano))
                .addMetrics(gauge(OtlpMetricParser.NAMESPACE + "disk_read_mbps", 1.5, timeUnixNano))
                .addMetrics(gauge(OtlpMetricParser.NAMESPACE + "disk_write_mbps", 2.5, timeUnixNano));
    }

    private static long nowNs() {
        return System.currentTimeMillis() * 1_000_000L;
    }

    private static KeyValue stringAttr(String key, String value) {
        return KeyValue.newBuilder()
                .setKey(key)
                .setValue(AnyValue.newBuilder().setStringValue(value))
                .build();
    }

    private static Metric gauge(String name, double value, long timeUnixNano) {
        return Metric.newBuilder()
                .setName(name)
                .setGauge(Gauge.newBuilder()
                        .addDataPoints(NumberDataPoint.newBuilder()
                                .setTimeUnixNano(timeUnixNano)
                                .setAsDouble(value)))
                .build();
    }
}
