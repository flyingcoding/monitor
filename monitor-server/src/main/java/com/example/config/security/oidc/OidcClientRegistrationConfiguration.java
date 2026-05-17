package com.example.config.security.oidc;

import com.example.service.OidcProviderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * OIDC ClientRegistrationRepository 装配（v1.2，方案 C：DB 优先 + yaml 兜底）。
 *
 * <p>由 {@link DelegatingClientRegistrationRepository} 提供混合视图：
 * <ul>
 *   <li>{@code oidc_provider} 表读 enabled Provider（管理员动态配置）；</li>
 *   <li>yaml {@code spring.security.oauth2.client.registration.*} 作为本地开发兜底；</li>
 *   <li>零 Provider 时返回空 repository，{@code findByRegistrationId} 返回 null，
 *       Spring Security 对 /oauth2/authorization/{any} 自动 404。</li>
 * </ul>
 *
 * <p>{@link OAuth2ClientProperties} 通过 ObjectProvider 注入，启动时如未提供 yaml 配置也不报错。
 */
@Slf4j
@Configuration
public class OidcClientRegistrationConfiguration {

    /**
     * 混合 ClientRegistrationRepository。
     *
     * @param oidcProviderService              Provider DB 读写
     * @param oauth2ClientPropertiesProvider   yaml Provider（可选）
     * @return ClientRegistrationRepository
     */
    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(
            OidcProviderService oidcProviderService,
            ObjectProvider<OAuth2ClientProperties> oauth2ClientPropertiesProvider) {
        log.info("OIDC ClientRegistrationRepository 装配混合实现（DB 优先 + yaml 兜底）");
        return new DelegatingClientRegistrationRepository(oidcProviderService, oauth2ClientPropertiesProvider);
    }
}
