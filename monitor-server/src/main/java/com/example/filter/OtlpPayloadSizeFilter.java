package com.example.filter;

import com.example.controller.otlp.OtlpConstants;
import com.example.entity.RestBean;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * OTLP payload 大小限制过滤器。
 *
 * <p>在 Spring MVC 把 {@code @RequestBody byte[]} / {@code String} 读入内存前，
 * 对 {@code POST /v1/metrics} 做有界读取；超过配置上限立即返回 413，避免超大请求体进入 controller。
 */
@Slf4j
@Component
public class OtlpPayloadSizeFilter extends OncePerRequestFilter {

    @Value("${monitor.otlp.max-payload-size:4194304}")
    private int maxPayloadBytes;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !OtlpConstants.METRICS_PATH.equals(request.getServletPath());
    }

    /**
     * 对 OTLP 请求体执行最大字节数限制，未超限时用缓存后的 request 继续下游链路。
     *
     * @param request     HTTP 请求
     * @param response    HTTP 响应
     * @param filterChain 下游过滤器链
     * @throws ServletException Servlet 异常
     * @throws IOException      IO 异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > maxPayloadBytes) {
            this.writeTooLarge(response, contentLength);
            return;
        }
        try {
            byte[] body = this.readBoundedBody(request);
            filterChain.doFilter(new CachedBodyRequest(request, body), response);
        } catch (PayloadTooLargeException e) {
            this.writeTooLarge(response, -1);
        }
    }

    /**
     * 读取请求体，最多允许 {@link #maxPayloadBytes} 字节。
     *
     * @param request HTTP 请求
     * @return 已缓存的请求体
     * @throws IOException 读取异常或超过限制
     */
    private byte[] readBoundedBody(HttpServletRequest request) throws IOException {
        int limit = Math.max(maxPayloadBytes, 0);
        int initialCapacity = request.getContentLengthLong() > 0
                ? (int) Math.min(request.getContentLengthLong(), limit)
                : 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream(initialCapacity);
        ServletInputStream input = request.getInputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (total + read > limit) {
                throw new PayloadTooLargeException();
            }
            out.write(buffer, 0, read);
            total += read;
        }
        return out.toByteArray();
    }

    /**
     * 写出统一 {@link RestBean} 结构的 413 响应。
     *
     * @param response      HTTP 响应
     * @param contentLength Content-Length；未知时为 -1
     * @throws IOException 写响应异常
     */
    private void writeTooLarge(HttpServletResponse response, long contentLength) throws IOException {
        log.warn("OTLP payload 超过 {} 字节上限，contentLength={}", maxPayloadBytes, contentLength);
        response.setStatus(413);
        response.setContentType("application/json;charset=utf-8");
        response.getWriter().write(RestBean.failure(413, "OTLP payload 超过上限").asJsonString());
    }

    /**
     * 已缓存 body 的请求包装器，保证 controller 仍可通过 {@code @RequestBody} 读取原始载荷。
     */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body == null ? new byte[0] : body;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedBodyServletInputStream(body);
        }

        @Override
        public BufferedReader getReader() {
            String encoding = this.getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(this.getInputStream(), charset));
        }
    }

    /**
     * 基于 byte[] 的 ServletInputStream 实现。
     */
    private static final class CachedBodyServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream input;

        private CachedBodyServletInputStream(byte[] body) {
            this.input = new ByteArrayInputStream(body);
        }

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
            if (readListener == null) {
                throw new IllegalArgumentException("ReadListener must not be null");
            }
            try {
                if (this.isFinished()) {
                    readListener.onAllDataRead();
                } else {
                    readListener.onDataAvailable();
                }
            } catch (IOException e) {
                readListener.onError(e);
            }
        }

        @Override
        public int read() {
            return input.read();
        }
    }

    /**
     * 请求体超过限制时的内部控制流异常。
     */
    private static final class PayloadTooLargeException extends IOException {
    }
}
