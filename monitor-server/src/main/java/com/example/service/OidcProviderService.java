package com.example.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.entity.dto.OidcProvider;
import com.example.entity.vo.request.OidcProviderCreateVO;
import com.example.entity.vo.request.OidcProviderUpdateVO;
import com.example.entity.vo.response.OidcProviderPublicVO;
import com.example.entity.vo.response.OidcProviderVO;

import java.util.List;

/**
 * OIDC Provider 管理服务。
 *
 * <p>负责：CRUD、密钥加密、查询 enabled provider 给登录页使用、CRUD 变更后通知
 * {@code DelegatingClientRegistrationRepository} 刷新缓存。
 */
public interface OidcProviderService extends IService<OidcProvider> {

    List<OidcProviderVO> listAll();

    /**
     * 仅返回 {@code enabled=true} 的 Provider，并裁剪为公开视图字段。
     */
    List<OidcProviderPublicVO> listEnabledPublic();

    OidcProviderVO create(OidcProviderCreateVO vo);

    OidcProviderVO update(Long id, OidcProviderUpdateVO vo);

    /**
     * 删除一个 Provider。
     *
     * @param id Provider ID
     * @return 已删除返回 true；未找到返回 false
     */
    boolean delete(Long id);

    /**
     * 解密某 Provider 的 client secret（仅在装配 ClientRegistration 时调用）。
     *
     * @param provider 已加载的 Provider 实体
     * @return 明文 client secret
     */
    String resolveClientSecret(OidcProvider provider);
}
