package com.example.service.impl;

import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.config.security.oidc.OidcLoginErrorCode;
import com.example.config.security.oidc.OidcLoginException;
import com.example.config.security.oidc.OidcProperties;
import com.example.entity.dto.Account;
import com.example.entity.dto.AccountOidcBinding;
import com.example.entity.vo.request.ConfirmResetVO;
import com.example.entity.vo.request.CreateSubAccountVO;
import com.example.entity.vo.request.EmailResetVO;
import com.example.entity.vo.request.ModifyEmailVO;
import com.example.entity.vo.response.SubAccountVO;
import com.example.mapper.AccountMapper;
import com.example.mapper.AccountOidcBindingMapper;
import com.example.service.AccountService;
import com.example.utils.Const;
import com.example.utils.FlowUtils;
import com.example.utils.PasswordPolicyValidator;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 账户信息处理相关服务
 */
@Slf4j
@Service
public class AccountServiceImpl extends ServiceImpl<AccountMapper, Account> implements AccountService {

    //验证邮件发送冷却时间限制，秒为单位
    @Value("${spring.web.verify.mail-limit}")
    int verifyLimit;

    @Resource
    AmqpTemplate rabbitTemplate;

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    PasswordEncoder passwordEncoder;

    @Resource
    FlowUtils flow;

    @Resource
    OidcProperties oidcProperties;

    @Resource
    AccountOidcBindingMapper accountOidcBindingMapper;

    @Resource
    PasswordPolicyValidator passwordPolicyValidator;

    /**
     * 从数据库中通过用户名或邮箱查找用户详细信息
     * @param username 用户名
     * @return 用户详细信息
     * @throws UsernameNotFoundException 如果用户未找到则抛出此异常
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Account account = this.findAccountByNameOrEmail(username);
        if(account == null)
            throw new UsernameNotFoundException("用户名或密码错误");
        // OIDC 自动建号场景下 password 为 null：返回非法 BCrypt 哈希作占位，
        // BCryptPasswordEncoder.matches(*, "$2a$10$invalid") 返回 false（不抛 NPE），
        // 实际效果是该账号无法通过表单登录，必须通过 OIDC 或"忘记密码"先设置密码。
        String password = account.getPassword();
        if (password == null || password.isBlank()) {
            password = "$2a$10$oidc_only_no_password_placeholder_xxxxxxxxxxxxxxxxxxxxxx";
        }
        return User
                .withUsername(username)
                .password(password)
                .roles(account.getRole())
                .build();
    }

    /**
     * 生成注册验证码存入Redis中，并将邮件发送请求提交到消息队列等待发送
     * @param type 类型
     * @param email 邮件地址
     * @param address 请求IP地址
     * @return 操作结果，null表示正常，否则为错误原因
     */
    public String registerEmailVerifyCode(String type, String email, String address){
        synchronized (address.intern()) {
            if(!this.verifyLimit(address))
                return "请求频繁，请稍后再试";
            Random random = new Random();
            int code = random.nextInt(899999) + 100000;
            Map<String, Object> data = Map.of("type",type,"email", email, "code", code);
            rabbitTemplate.convertAndSend(Const.MQ_MAIL, data);
            stringRedisTemplate.opsForValue()
                    .set(Const.VERIFY_EMAIL_DATA + email, String.valueOf(code), 3, TimeUnit.MINUTES);
            return null;
        }
    }


    /**
     * 邮件验证码重置密码操作，需要检查验证码是否正确
     * @param info 重置基本信息
     * @return 操作结果，null表示正常，否则为错误原因
     */
    @Override
    public String resetEmailAccountPassword(EmailResetVO info) {
        String verify = resetConfirm(new ConfirmResetVO(info.getEmail(), info.getCode()));
        if(verify != null) return verify;
        String policyError = passwordPolicyValidator.validate(info.getPassword());
        if (policyError != null) return policyError;
        String email = info.getEmail();
        String password = passwordEncoder.encode(info.getPassword());
        boolean update = this.update().eq("email", email).set("password", password).update();
        if(update) {
            this.deleteEmailVerifyCode(email);
        }
        return update ? null : "更新失败，请联系管理员";
    }

    /**
     * 重置密码确认操作，验证验证码是否正确
     * @param info 验证基本信息
     * @return 操作结果，null表示正常，否则为错误原因
     */
    @Override
    public String resetConfirm(ConfirmResetVO info) {
        String email = info.getEmail();
        String code = this.getEmailVerifyCode(email);
        if(code == null) return "请先获取验证码";
        if(!code.equals(info.getCode())) return "验证码错误，请重新输入";
        return null;
    }

    @Override
    public boolean changePassword(int id, String oldPass, String newPass) {
        Account account=this.getById(id);
        String password=account.getPassword();
        if (!passwordEncoder.matches(oldPass,password))
            return false;
        // v1.2 AC13：修改密码同样受密码策略约束；策略不通过时拒绝。
        if (passwordPolicyValidator.validate(newPass) != null) {
            return false;
        }
        this.update(Wrappers.<Account>update().eq("id",id).set("password",passwordEncoder.encode(newPass)));
        return true;
    }

    @Override
    public void createSubAccount(CreateSubAccountVO vo) {
        Account account=findAccountByNameOrEmail(vo.getEmail());
        if (account!=null)
            throw new IllegalArgumentException("邮箱已被使用！");
        account=findAccountByNameOrEmail(vo.getUsername());
        if (account!=null)
            throw new IllegalArgumentException("用户名已被使用！");
        // v1.2 AC13：注册子账号同样受密码策略约束。
        String policyError = passwordPolicyValidator.validate(vo.getPassword());
        if (policyError != null) {
            throw new IllegalArgumentException(policyError);
        }
        account=new Account(null,vo.getUsername(),passwordEncoder.encode(vo.getPassword())
                ,vo.getEmail(),Const.ROLE_DEFAULT, JSONArray.copyOf(vo.getClients()).toString(),new Date(),Boolean.TRUE);
        this.save(account);
    }

    @Override
    public void deleteSubAccount(int uid) {
        this.removeById(uid);
    }

    @Override
    public List<SubAccountVO> listSubAccount() {
        return this.list(Wrappers.<Account>query().eq("role",Const.ROLE_DEFAULT))
                .stream().map(account -> {
                    SubAccountVO vo=new SubAccountVO();
                    BeanUtils.copyProperties(account,vo,"clients");
                    vo.setClients(JSONArray.parse(account.getClients()));
                    return vo;
                }).toList();
    }

    @Override
    public String modifyEmail(int uid, ModifyEmailVO vo) {
        String code=getEmailVerifyCode(vo.getEmail());
        if (code==null) return "请先获取验证码！";
        if(!code.equals(vo.getCode())) return "验证码错误，请重新输入！";
        this.deleteEmailVerifyCode(vo.getEmail());
        Account account=this.findAccountByNameOrEmail(vo.getEmail());
        if (account!=null&&account.getId()!= uid) return "该邮箱已被他人绑定，请更换邮箱！";
        this.update().set("email",vo.getEmail())
                .eq("id",uid).update();
        return null;
    }

    /**
     * 移除Redis中存储的邮件验证码
     * @param email 电邮
     */
    private void deleteEmailVerifyCode(String email){
        String key = Const.VERIFY_EMAIL_DATA + email;
        stringRedisTemplate.delete(key);
    }

    /**
     * 获取Redis中存储的邮件验证码
     * @param email 电邮
     * @return 验证码
     */
    private String getEmailVerifyCode(String email){
        String key = Const.VERIFY_EMAIL_DATA + email;
        return stringRedisTemplate.opsForValue().get(key);
    }

    /**
     * 针对IP地址进行邮件验证码获取限流
     * @param address 地址
     * @return 是否通过验证
     */
    private boolean verifyLimit(String address) {
        String key = Const.VERIFY_EMAIL_LIMIT + address;
        return flow.limitOnceCheck(key, verifyLimit);
    }

    /**
     * 通过用户名或邮件地址查找用户
     * @param text 用户名或邮件
     * @return 账户实体
     */
    public Account findAccountByNameOrEmail(String text){
        return this.query()
                .eq("username", text).or()
                .eq("email", text)
                .one();
    }

    /**
     * 按邮箱精确查找账号（OIDC 邮箱关联路径使用）。
     *
     * @param email 邮箱
     * @return 账号实体；找不到返回 null
     */
    protected Account findAccountByEmail(String email) {
        if (email == null || email.isBlank()) return null;
        return this.query().eq("email", email).one();
    }

    /**
     * 根据 OIDC 回调信息解析或创建账号（v1.2 D3）。
     *
     * <p>策略矩阵（取自 {@link OidcProperties}）：
     * <ol>
     *   <li>(provider, sub) 已绑定 → 直接返回所属账号；</li>
     *   <li>{@code requireEmailVerified=true} 且 emailVerified=false → 拒绝；</li>
     *   <li>{@code linkExistingByEmail=true} 且 email 命中 → 返回该账号；</li>
     *   <li>{@code autoCreateUser=true} → 新建账号（默认角色，无密码）；</li>
     *   <li>否则 → 抛 {@code account_not_found}。</li>
     * </ol>
     */
    @Override
    public Account resolveOrCreateByOidc(String provider, String subject, String email, Boolean emailVerified) {
        // 1. 已绑定 → 直接返回
        AccountOidcBinding existing = accountOidcBindingMapper.selectOne(
                Wrappers.<AccountOidcBinding>query()
                        .eq("provider_name", provider)
                        .eq("subject", subject));
        if (existing != null && existing.getAccountId() != null) {
            Account bound = this.getById(existing.getAccountId());
            if (bound != null) {
                return bound;
            }
            // 绑定行存在但账号被删，落入新流程
            log.warn("OIDC 绑定 (provider={}, sub={}) 指向账号 {} 已被删除，落入解析流程",
                    provider, subject, existing.getAccountId());
        }

        // 2. 邮件校验
        boolean verified = Boolean.TRUE.equals(emailVerified);
        if (oidcProperties.isRequireEmailVerified() && !verified) {
            throw new OidcLoginException(OidcLoginErrorCode.EMAIL_NOT_VERIFIED,
                    "OIDC IdP 未验证邮箱，禁止登录");
        }

        // 3. 按邮箱匹配老账号
        if (oidcProperties.isLinkExistingByEmail() && email != null && !email.isBlank() && verified) {
            Account byEmail = this.findAccountByEmail(email);
            if (byEmail != null) {
                return byEmail;
            }
        }

        // 4. 是否允许自动建号
        if (!oidcProperties.isAutoCreateUser()) {
            throw new OidcLoginException(OidcLoginErrorCode.ACCOUNT_NOT_FOUND,
                    "未找到对应账号，请联系管理员开通");
        }
        if (email == null || email.isBlank()) {
            throw new OidcLoginException(OidcLoginErrorCode.EMAIL_MISSING,
                    "OIDC IdP 未返回邮箱，无法创建账号");
        }
        // 5. 自动建号；username 用邮箱前缀+随机后缀避免冲突；password 留空
        String username = generateUsername(email);
        String role = oidcProperties.getDefaultRole();
        if (role == null || role.isBlank()) {
            role = Const.ROLE_DEFAULT;
        }
        Account created = new Account(
                null, username, null, email, role, JSONArray.of().toString(), new Date(), Boolean.TRUE);
        this.save(created);
        log.info("OIDC 自动建号 id={} email={} provider={}", created.getId(), email, provider);
        return created;
    }

    /**
     * 生成不冲突的本地用户名：邮箱前缀 + 短随机后缀；超过最大长度时截断。
     *
     * @param email 邮箱
     * @return 唯一的用户名
     */
    private String generateUsername(String email) {
        String prefix = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
        prefix = prefix.replaceAll("[^A-Za-z0-9_]", "_");
        for (int attempt = 0; attempt < 5; attempt++) {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
            String candidate = (prefix + "_" + suffix);
            if (candidate.length() > 32) {
                candidate = candidate.substring(0, 32);
            }
            if (this.findAccountByNameOrEmail(candidate) == null) {
                return candidate;
            }
        }
        // 极端兜底
        return "oidc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
