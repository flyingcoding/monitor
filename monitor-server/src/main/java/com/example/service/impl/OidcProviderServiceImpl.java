package com.example.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.entity.dto.OidcProvider;
import com.example.entity.vo.request.OidcProviderCreateVO;
import com.example.entity.vo.request.OidcProviderUpdateVO;
import com.example.entity.vo.response.OidcProviderPublicVO;
import com.example.entity.vo.response.OidcProviderVO;
import com.example.mapper.OidcProviderMapper;
import com.example.service.OidcProviderService;
import com.example.utils.CryptoUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Date;
import java.util.List;

/**
 * OIDC Provider 管理服务实现。
 *
 * <p>client_secret 复用现有 {@link CryptoUtils}（AES-256-GCM, {@code security.ssh.encrypt-key}）。
 * 写入时调用 {@link CryptoUtils#encrypt(String)} 自动加 {@code ENC:} 前缀；
 * 读取时由 {@link CryptoUtils#decrypt(String)} 解密。
 *
 * <p>每次 CRUD 后发布 {@link ProviderChangedEvent}，由 {@code DelegatingClientRegistrationRepository}
 * 监听以刷新缓存（弱耦合，便于单元测试和扩展）。
 */
@Slf4j
@Service
public class OidcProviderServiceImpl
        extends ServiceImpl<OidcProviderMapper, OidcProvider>
        implements OidcProviderService {

    @Resource
    private CryptoUtils cryptoUtils;

    /**
     * 用 ObjectProvider 包裹避免循环依赖；事件发布器在所有 Spring 上下文中始终存在。
     */
    @Resource
    private ObjectProvider<ApplicationEventPublisher> eventPublisherProvider;

    @Override
    public List<OidcProviderVO> listAll() {
        return this.list(new QueryWrapper<OidcProvider>().orderByAsc("id"))
                .stream()
                .map(this::toAdminVO)
                .toList();
    }

    @Override
    public List<OidcProviderPublicVO> listEnabledPublic() {
        return this.list(new QueryWrapper<OidcProvider>()
                .eq("enabled", 1)
                .orderByAsc("id"))
                .stream()
                .map(p -> {
                    OidcProviderPublicVO vo = new OidcProviderPublicVO();
                    vo.setName(p.getName());
                    vo.setDisplayName(p.getDisplayName() == null ? p.getName() : p.getDisplayName());
                    vo.setIconUrl(p.getIconUrl());
                    return vo;
                })
                .toList();
    }

    @Override
    public OidcProviderVO create(OidcProviderCreateVO vo) {
        if (this.nameExists(vo.getName(), null)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Provider 名已存在");
        }
        OidcProvider entity = new OidcProvider();
        entity.setName(vo.getName());
        entity.setDisplayName(vo.getDisplayName());
        entity.setIconUrl(vo.getIconUrl());
        entity.setIssuerUrl(vo.getIssuerUrl());
        entity.setClientId(vo.getClientId());
        entity.setClientSecretEnc(cryptoUtils.encrypt(vo.getClientSecret()));
        entity.setScopes(vo.getScopes());
        entity.setEnabled(vo.getEnabled());
        entity.setCreatedAt(new Date());
        entity.setUpdatedAt(new Date());
        this.save(entity);
        log.info("OIDC Provider 创建 id={} name={}", entity.getId(), entity.getName());
        publishChanged();
        return toAdminVO(entity);
    }

    @Override
    public OidcProviderVO update(Long id, OidcProviderUpdateVO vo) {
        OidcProvider existing = this.getById(id);
        if (existing == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Provider 不存在");
        }
        existing.setDisplayName(vo.getDisplayName());
        existing.setIconUrl(vo.getIconUrl());
        existing.setIssuerUrl(vo.getIssuerUrl());
        existing.setClientId(vo.getClientId());
        // 留空表示沿用旧密钥（对齐 NotificationChannelServiceImpl.preserveExistingEnc 的语义）
        if (vo.getClientSecret() != null && !vo.getClientSecret().isBlank()) {
            existing.setClientSecretEnc(cryptoUtils.encrypt(vo.getClientSecret()));
        }
        existing.setScopes(vo.getScopes());
        existing.setEnabled(vo.getEnabled());
        existing.setUpdatedAt(new Date());
        this.updateById(existing);
        log.info("OIDC Provider 更新 id={} name={}", existing.getId(), existing.getName());
        publishChanged();
        return toAdminVO(existing);
    }

    @Override
    public boolean delete(Long id) {
        OidcProvider existing = this.getById(id);
        if (existing == null) {
            return false;
        }
        boolean removed = this.removeById(id);
        if (removed) {
            log.info("OIDC Provider 删除 id={} name={}", id, existing.getName());
            publishChanged();
        }
        return removed;
    }

    @Override
    public String resolveClientSecret(OidcProvider provider) {
        if (provider == null || provider.getClientSecretEnc() == null) {
            return null;
        }
        try {
            return cryptoUtils.decrypt(provider.getClientSecretEnc());
        } catch (Exception e) {
            log.error("OIDC Provider id={} name={} client_secret 解密失败", provider.getId(), provider.getName(), e);
            return null;
        }
    }

    /**
     * 检查 name 是否已存在（更新场景排除自身 ID）。
     */
    private boolean nameExists(String name, Long excludeId) {
        QueryWrapper<OidcProvider> q = new QueryWrapper<OidcProvider>().eq("name", name);
        if (excludeId != null) {
            q.ne("id", excludeId);
        }
        Long count = this.baseMapper.selectCount(q);
        return count != null && count > 0;
    }

    private OidcProviderVO toAdminVO(OidcProvider entity) {
        OidcProviderVO vo = new OidcProviderVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setDisplayName(entity.getDisplayName());
        vo.setIconUrl(entity.getIconUrl());
        vo.setIssuerUrl(entity.getIssuerUrl());
        vo.setClientId(entity.getClientId());
        vo.setScopes(entity.getScopes());
        vo.setEnabled(entity.getEnabled());
        vo.setHasSecret(entity.getClientSecretEnc() != null && !entity.getClientSecretEnc().isBlank());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }

    /**
     * 发布 Provider 表变更事件；忽略发布失败（缓存最坏延迟生效）。
     */
    private void publishChanged() {
        ApplicationEventPublisher publisher = eventPublisherProvider.getIfAvailable();
        if (publisher != null) {
            publisher.publishEvent(new ProviderChangedEvent(this));
        }
    }

    /**
     * Provider 表变更事件。{@code DelegatingClientRegistrationRepository} 监听以刷新缓存。
     */
    public static final class ProviderChangedEvent {

        private final Object source;

        public ProviderChangedEvent(Object source) {
            this.source = source;
        }

        public Object getSource() {
            return source;
        }
    }
}
