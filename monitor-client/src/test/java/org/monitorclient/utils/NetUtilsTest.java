package org.monitorclient.utils;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.monitorclient.entity.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies the actual agent HTTP request and strict RestBean response contract. */
class NetUtilsTest {
    /** Sends the exact new route and raw Authorization header to a loopback fixture. */
    @Test
    void shouldUseMigratedEndpointAndRestBeanSuccess() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> token = new AtomicReference<>();
        server.createContext("/monitor/heartbeat", exchange -> {
            requests.incrementAndGet();
            token.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"code\":200,\"message\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            NetUtils net = new NetUtils();
            net.setConfig(new ConnectionConfig("http://127.0.0.1:" + server.getAddress().getPort(), "fixture-token"));
            net.sendHeartbeat();
            net.flushCachedData();
            assertEquals(1, requests.get());
            assertEquals("fixture-token", token.get());
        } finally { server.stop(0); }
    }

    /** Uses heartbeat recognition to avoid consuming a registration token on every restart. */
    @Test
    void shouldRecognizeExistingTokenBeforeRegistration() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger registrations = new AtomicInteger();
        AtomicInteger heartbeatStatus = new AtomicInteger(200);
        server.createContext("/monitor/heartbeat", exchange -> {
            byte[] body = ("{\"code\":" + heartbeatStatus.get() + "}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/monitor/register", exchange -> {
            registrations.incrementAndGet();
            byte[] body = "{\"code\":200}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            NetUtils net = new NetUtils();
            String address = "http://127.0.0.1:" + server.getAddress().getPort();
            assertTrue(net.registerToServer(address, "fixture-token"));
            assertEquals(0, registrations.get());
            heartbeatStatus.set(401);
            assertTrue(net.registerToServer(address, "new-fixture-token"));
            assertEquals(1, registrations.get());
            heartbeatStatus.set(503);
            assertFalse(net.registerToServer(address, "unknown-token"));
            assertEquals(1, registrations.get(), "server outage must not trigger registration");
        } finally { server.stop(0); }
    }

    /** Rejects legacy success codes and missing code fields instead of dropping buffered samples. */
    @Test
    void shouldRequireExplicitOriginalSuccessCode() {
        assertTrue(Response.parse("{\"code\":200}").success());
        assertFalse(Response.parse("{\"code\":0}").success());
        assertThrows(IllegalArgumentException.class, () -> Response.parse("{}"));
        assertThrows(IllegalArgumentException.class, () -> Response.parse("{\"code\":null}"));
    }
}
