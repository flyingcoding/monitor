package com.example.controller.otlp;

/**
 * OTLP/HTTP 接收链路共享常量。
 */
public final class OtlpConstants {

    /** OTLP/HTTP metrics 标准接收路径。 */
    public static final String METRICS_PATH = "/v1/metrics";

    /** 客户端 token 鉴权 header。 */
    public static final String AUTH_HEADER = "X-Monitor-Token";

    private OtlpConstants() {
        // 常量类，禁止实例化
    }
}
