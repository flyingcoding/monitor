package org.monitorclient.config;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.monitorclient.entity.ConnectionConfig;
import org.monitorclient.utils.NetUtils;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Protects offline supervisor restarts and original one-time registration behavior. */
class ServerConfigurationTest {
    @TempDir Path directory;

    /** Restarts offline with an exact persisted credential match without re-registering. */
    @Test
    void shouldReuseMatchingCredentialsWithoutNetworkOnRestart() throws Exception {
        Path path = directory.resolve("server.json");
        ConnectionConfig original = new ConnectionConfig("http://fixture.invalid:8001", "original-token");
        Files.writeString(path, JSON.toJSONString(original));
        NetUtils net = new NetUtils() {
            /** Any registration attempt would rotate or reject an already used token. */
            @Override public boolean registerToServer(String address, String token) { fail("must not contact server on matching restart"); return false; }
        };
        ConnectionConfig loaded = new ServerConfiguration(net, path.toFile(),
                Map.of("MONITOR_SERVER", original.getAddress(), "MONITOR_TOKEN", original.getToken())).loadConfig(new String[0]);
        assertEquals(original, loaded);
    }

    /** Preserves first-install registration and persists the successful credential. */
    @Test
    void shouldRegisterAndPersistOnFirstInstall() throws Exception {
        Path path = directory.resolve("server.json");
        AtomicInteger registrations = new AtomicInteger();
        NetUtils net = new NetUtils() {
            /** Simulates the original server accepting its one-time registration token. */
            @Override public boolean registerToServer(String address, String token) { registrations.incrementAndGet(); return true; }
        };
        ConnectionConfig loaded = new ServerConfiguration(net, path.toFile(), Map.of())
                .loadConfig(new String[]{"--server=http://fixture.invalid:8001", "--token=registration-token"});
        assertEquals(1, registrations.get());
        assertEquals(loaded, JSON.parseObject(Files.readString(path), ConnectionConfig.class));
    }
}
