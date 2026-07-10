package com.example.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.entity.dto.Account;
import com.example.entity.vo.request.ConfirmResetVO;
import com.example.entity.vo.request.CreateSubAccountVO;
import com.example.entity.vo.request.EmailResetVO;
import com.example.entity.vo.request.ModifyEmailVO;
import com.example.entity.vo.response.SubAccountVO;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.List;

public interface AccountService extends IService<Account>, UserDetailsService {
    Account findAccountByNameOrEmail(String text);
    String registerEmailVerifyCode(String type, String email, String address);
    String resetEmailAccountPassword(EmailResetVO info);
    String resetConfirm(ConfirmResetVO info);
    boolean changePassword(int id,String oldPass,String newPass);
    void createSubAccount(CreateSubAccountVO vo);
    boolean deleteSubAccount(int uid);
    List<SubAccountVO> listSubAccount();
    String modifyEmail(int uid, ModifyEmailVO vo);

    /**
     * 根据 OIDC 回调信息解析或创建账号（v1.2 D3）。
     *
     * <p>策略矩阵（取决于 {@code monitor.oidc.*} 配置）：
     * <ol>
     *   <li>{@code requireEmailVerified=true} 且 {@code emailVerified=false} → 抛 {@code email_not_verified}</li>
     *   <li>{@code linkExistingByEmail=true} 且按 email 命中已有账号 → 返回该账号</li>
     *   <li>{@code autoCreateUser=true} → 新建账号（{@code defaultRole}，密码 null），返回新账号</li>
     *   <li>否则 → 抛 {@code account_not_found}</li>
     * </ol>
     *
     * <p>本方法不创建 {@code account_oidc_binding} 行；调用方负责调 {@link AccountOidcBindingService#upsert}。
     *
     * @param provider      OIDC Provider 名（registrationId）
     * @param subject       OIDC sub
     * @param email         IdP 返回的 email（可能为 null）
     * @param emailVerified IdP 返回的 email_verified（null 视为 false）
     * @return 解析或创建后的账号实体
     * @throws com.example.config.security.oidc.OidcLoginException 业务策略拒绝时抛出
     */
    Account resolveOrCreateByOidc(String provider, String subject, String email, Boolean emailVerified);
}
