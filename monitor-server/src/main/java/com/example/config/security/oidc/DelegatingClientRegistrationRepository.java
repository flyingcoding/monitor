package com.example.config.security.oidc;

import com.example.entity.dto.OidcProvider;
import com.example.service.OidcProviderService;
import com.example.service.impl.OidcProviderServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientProperties;
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientPropertiesMapper;
import org.springframework.context.event.EventListener;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 混合 ClientRegistrationRepository（v1.2 D2，方案 C）：DB 优先，yaml 兜底。
 *
 * <ul>
 *   <li>从 {@code oidc_provider} 表读 {@code enabled=1} 的 Provider 装配 ClientRegistration；</li>
 *   <li>从 {@code spring.security.oauth2.client.registration.*} yaml 装配兜底 Provider（适合 dev / 演示）；</li>
 *   <li>同名 registrationId 时 DB 覆盖 yaml；</li>
 *   <li>Caffeine 缓存 5 分钟；CRUD 后通过事件刷新；</li>
 *   <li>客户端密钥经 {@link OidcProviderService#resolveClientSecret(OidcProvider)} 解密后装配，
 *       明文仅活在 JVM 堆内。</li>
 * </ul>
 *
 * <p>Spring Security 在 {@code findByRegistrationId(id)} 返回 null 时，对该 id 的 OAuth2 入口
 * 路径会以 404 响应；零 Provider 仍可启动而不抛 BeanCreationException。
 */
@Slf4j
public class DelegatingClientRegistrationRepository implements ClientRegistrationRepository, Iterable<ClientRegistration> {

    private static final String CACHE_KEY = "_all";

    private final OidcProviderService oidcProviderService;
    private final ObjectProvider<OAuth2ClientProperties> oauth2ClientPropertiesProvider;
    private final OidcProperties oidcProperties;
    private final Cache<String, Map<String, ClientRegistration>> cache;

    public DelegatingClientRegistrationRepository(OidcProviderService oidcProviderService,
                                                  ObjectProvider<OAuth2ClientProperties> oauth2ClientPropertiesProvider,
                                                  OidcProperties oidcProperties) {
        this.oidcProviderService = oidcProviderService;
        this.oauth2ClientPropertiesProvider = oauth2ClientPropertiesProvider;
        this.oidcProperties = oidcProperties;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))
                .maximumSize(64)
                .build();
    }

    /**
     * 监听 Provider 表 CRUD 事件，主动失效缓存。
     */
    @EventListener
    public void onProviderChanged(OidcProviderServiceImpl.ProviderChangedEvent event) {
        log.info("OIDC Provider 变更，刷新 ClientRegistration 缓存");
        cache.invalidateAll();
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        if (registrationId == null) {
            return null;
        }
        return loadAll().get(registrationId);
    }

    /**
     * Iterable 接口主要用于 Spring 内部 endpoint discovery；非热点路径，直接复用缓存。
     */
    @Override
    public java.util.Iterator<ClientRegistration> iterator() {
        return loadAll().values().iterator();
    }

    /**
     * 读取并合并 DB 与 yaml 来源；缓存命中时 O(1) 返回。
     *
     * <p>P1-2 修复：{@code monitor.oidc.enabled=false} 时直接返回空 Map 并缓存，
     * 关闭 yaml / DB 全部 Provider，避免 {@code GET /oauth2/authorization/<name>} 仍可猜测命中。
     * 这与 {@code OidcProviderPublicController} 的前端按钮过滤共同形成"全局 kill-switch"。
     */
    private Map<String, ClientRegistration> loadAll() {
        Map<String, ClientRegistration> cached = cache.getIfPresent(CACHE_KEY);
        if (cached != null) {
            return cached;
        }
        if (oidcProperties != null && !oidcProperties.isEnabled()) {
            Map<String, ClientRegistration> empty = Map.of();
            cache.put(CACHE_KEY, empty);
            return empty;
        }
        Map<String, ClientRegistration> merged = new LinkedHashMap<>();

        // yaml 兜底（dev 友好；prod 通常为空）
        OAuth2ClientProperties yamlProps = oauth2ClientPropertiesProvider.getIfAvailable();
        if (yamlProps != null) {
            try {
                Map<String, ClientRegistration> yamlMap = new OAuth2ClientPropertiesMapper(yamlProps).asClientRegistrations();
                if (yamlMap != null) {
                    merged.putAll(yamlMap);
                }
            } catch (Exception e) {
                log.warn("加载 yaml OIDC Provider 失败: {}", e.getMessage());
            }
        }

        // DB 优先：覆盖 yaml 的同名 registrationId
        try {
            for (OidcProvider p : oidcProviderService.list()) {
                if (p.getEnabled() == null || !p.getEnabled()) {
                    continue;
                }
                ClientRegistration reg = buildRegistration(p);
                if (reg != null) {
                    merged.put(reg.getRegistrationId(), reg);
                }
            }
        } catch (Exception e) {
            log.error("加载 DB OIDC Provider 失败，链路退化为仅 yaml", e);
        }

        cache.put(CACHE_KEY, merged);
        return merged;
    }

    /**
     * 用 OIDC Discovery 自动发现端点，再覆写 clientId / clientSecret / scopes。
     *
     * @param p Provider 实体
     * @return 构建好的 ClientRegistration；构建失败返回 null
     */
    private ClientRegistration buildRegistration(OidcProvider p) {
        if (p.getName() == null || p.getIssuerUrl() == null || p.getClientId() == null) {
            log.warn("OIDC Provider id={} 缺少关键字段，跳过", p.getId());
            return null;
        }
        String plainSecret = oidcProviderService.resolveClientSecret(p);
        if (plainSecret == null) {
            log.warn("OIDC Provider id={} name={} client_secret 不可用，跳过", p.getId(), p.getName());
            return null;
        }
        try {
            return ClientRegistrations.fromIssuerLocation(p.getIssuerUrl())
                    .registrationId(p.getName())
                    .clientId(p.getClientId())
                    .clientSecret(plainSecret)
                    .scope(parseScopes(p.getScopes()))
                    .build();
        } catch (Exception e) {
            log.error("OIDC Provider id={} name={} 装配失败（issuer={}）", p.getId(), p.getName(), p.getIssuerUrl(), e);
            return null;
        }
    }

    /**
     * 把数据库存的逗号分隔 scope 字符串转为 Set，保持插入顺序。
     */
    private Collection<String> parseScopes(String raw) {
        Set<String> result = new HashSet<>();
        if (raw == null || raw.isBlank()) {
            result.add("openid");
            result.add("profile");
            result.add("email");
            return result;
        }
        for (String s : raw.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        if (!result.contains("openid")) {
            // OIDC 必须有 openid scope，否则不是 OIDC 而是普通 OAuth2
            result.add("openid");
        }
        return result;
    }

    /**
     * 用于单元测试的备用构造：直接注入 in-memory yaml registrations。
     */
    static InMemoryClientRegistrationRepository wrapInMemory(Map<String, ClientRegistration> regs) {
        return new InMemoryClientRegistrationRepository(regs.values().stream().toList());
    }
}
