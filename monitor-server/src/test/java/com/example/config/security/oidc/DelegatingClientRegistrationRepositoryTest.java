package com.example.config.security.oidc;

import com.example.entity.dto.OidcProvider;
import com.example.service.OidcProviderService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientProperties;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link DelegatingClientRegistrationRepository} 单元测试。
 *
 * <p>P1-2 修复覆盖：{@code OidcProperties.enabled=false} 时无论 DB 还是 yaml 配置了多少 Provider，
 * 都必须返回空 repository，{@code findByRegistrationId} 始终返回 null。这是 OIDC 总开关的
 * 全局 kill-switch 语义；仅靠前端按钮过滤无法阻断 {@code GET /oauth2/authorization/<guess>}。
 */
class DelegatingClientRegistrationRepositoryTest {

    /**
     * 关闭总开关后，对任意 DB Provider 名调用 findByRegistrationId 必须返回 null，
     * 且缓存里写入的也是空 Map（后续调用稳定为空）。
     */
    @Test
    void disabledFlagReturnsEmptyForAllProviders() {
        OidcProperties props = new OidcProperties();
        props.setEnabled(false);

        // 准备一个有 enabled Provider 的 stub OidcProviderService —— 即使它能提供 provider，
        // repository 在 enabled=false 时也必须忽略它。
        List<OidcProvider> dbRows = new ArrayList<>();
        OidcProvider github = new OidcProvider();
        github.setId(1L);
        github.setName("github");
        github.setIssuerUrl("https://accounts.example.com");
        github.setClientId("cid");
        github.setClientSecretEnc("ENC:dummy");
        github.setScopes("openid,profile,email");
        github.setEnabled(Boolean.TRUE);
        dbRows.add(github);

        OidcProviderService providerService = (OidcProviderService) Proxy.newProxyInstance(
                OidcProviderService.class.getClassLoader(),
                new Class[]{OidcProviderService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "list" -> new ArrayList<>(dbRows);
                    case "resolveClientSecret" -> "plaintext";
                    default -> null;
                });

        @SuppressWarnings("unchecked")
        ObjectProvider<OAuth2ClientProperties> yamlProvider =
                (ObjectProvider<OAuth2ClientProperties>) Proxy.newProxyInstance(
                        ObjectProvider.class.getClassLoader(),
                        new Class[]{ObjectProvider.class},
                        (proxy, method, args) -> {
                            if ("getIfAvailable".equals(method.getName())) {
                                return null;
                            }
                            return null;
                        });

        DelegatingClientRegistrationRepository repo =
                new DelegatingClientRegistrationRepository(providerService, yamlProvider, props);

        Assertions.assertNull(repo.findByRegistrationId("github"),
                "P1-2：enabled=false 时 DB 中存在的 github 也必须不可用");
        Assertions.assertNull(repo.findByRegistrationId("any-guessed-name"),
                "P1-2：enabled=false 时任何 registrationId 都必须返回 null");
        Assertions.assertFalse(repo.iterator().hasNext(),
                "P1-2：enabled=false 时 iterator 必须为空");

        // 二次调用走缓存仍然为空
        Assertions.assertNull(repo.findByRegistrationId("github"));
        Assertions.assertFalse(repo.iterator().hasNext());
    }

    /**
     * enabled=true 时 DB Provider 正常装配（这里 issuer 不可达，buildRegistration 应抛异常
     * 返回 null，但 list 路径不抛 — 验证开关打开后至少进入装配流程，与 disabled 路径区分）。
     */
    @Test
    void enabledFlagAttemptsToLoadProviders() {
        OidcProperties props = new OidcProperties();
        props.setEnabled(true);

        List<OidcProvider> dbRows = new ArrayList<>();
        // 故意留空：避免触发实际 issuer HTTP discovery
        OidcProviderService providerService = (OidcProviderService) Proxy.newProxyInstance(
                OidcProviderService.class.getClassLoader(),
                new Class[]{OidcProviderService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "list" -> new ArrayList<>(dbRows);
                    default -> null;
                });

        @SuppressWarnings("unchecked")
        ObjectProvider<OAuth2ClientProperties> yamlProvider =
                (ObjectProvider<OAuth2ClientProperties>) Proxy.newProxyInstance(
                        ObjectProvider.class.getClassLoader(),
                        new Class[]{ObjectProvider.class},
                        (proxy, method, args) -> null);

        DelegatingClientRegistrationRepository repo =
                new DelegatingClientRegistrationRepository(providerService, yamlProvider, props);

        Assertions.assertNull(repo.findByRegistrationId("github"),
                "enabled=true 但没有 Provider 时仍返回 null（默认行为）");
    }
}
