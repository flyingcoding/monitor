package com.example.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link OtlpPayloadSizeFilter} 单元测试。
 */
class OtlpPayloadSizeFilterTest {

    private OtlpPayloadSizeFilter filter;

    @BeforeEach
    void setUp() {
        filter = new OtlpPayloadSizeFilter();
        ReflectionTestUtils.setField(filter, "maxPayloadBytes", 16);
    }

    @Test
    void oversizedContentLengthShouldReturn413BeforeChain() throws Exception {
        MockHttpServletRequest request = otlpRequest("12345678901234567");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        Assertions.assertEquals(413, response.getStatus());
        Assertions.assertFalse(invoked.get(), "超限请求不能继续进入下游 filter/controller");
        Assertions.assertTrue(response.getContentAsString().contains("OTLP payload 超过上限"));
    }

    @Test
    void chunkedOversizedBodyShouldReturn413AfterBoundedRead() throws Exception {
        MockHttpServletRequest base = otlpRequest("12345678901234567");
        HttpServletRequest request = unknownLengthRequest(base, "12345678901234567".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        Assertions.assertEquals(413, response.getStatus());
        Assertions.assertFalse(invoked.get(), "未知 Content-Length 的超限请求也不能进入 controller");
    }

    @Test
    void allowedOtlpBodyShouldBeAvailableForDownstreamRead() throws Exception {
        byte[] expected = "small-body".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = otlpRequest("small-body");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<byte[]> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        Assertions.assertEquals(200, response.getStatus());
        Assertions.assertArrayEquals(expected, captured.get());
    }

    @Test
    void nonOtlpRequestShouldSkipFilter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/other");
        request.setContent("12345678901234567".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        Assertions.assertTrue(invoked.get(), "非 /v1/metrics 请求不受 OTLP payload filter 影响");
        Assertions.assertEquals(200, response.getStatus());
    }

    private static MockHttpServletRequest otlpRequest(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/metrics");
        request.setServletPath("/v1/metrics");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static FilterChain capturingChain(AtomicReference<byte[]> captured) {
        return new FilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response) throws IOException, ServletException {
                captured.set(request.getInputStream().readAllBytes());
            }
        };
    }

    private static HttpServletRequest unknownLengthRequest(HttpServletRequest request, byte[] body) {
        return new HttpServletRequestWrapper(request) {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public ServletInputStream getInputStream() {
                return servletInputStream(body);
            }
        };
    }

    private static ServletInputStream servletInputStream(byte[] body) {
        return new ServletInputStream() {
            private final ByteArrayInputStream input = new ByteArrayInputStream(body);

            @Override
            public boolean isFinished() {
                return input.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // 测试同步读取路径不使用异步 listener
            }

            @Override
            public int read() {
                return input.read();
            }
        };
    }
}
