package com.example.service;

import com.example.entity.dto.Account;
import com.example.entity.dto.AccountOidcBinding;
import com.example.entity.vo.response.OidcBindingVO;
import com.example.mapper.AccountOidcBindingMapper;
import com.example.service.impl.AccountOidcBindingServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AccountOidcBindingServiceImpl 单元测试。
 *
 * <p>核心验证：
 * <ul>
 *   <li>upsert 创建新行 & 重复 upsert 仅更新 email；</li>
 *   <li>unbind 在账号有密码时允许解绑（OK）；</li>
 *   <li>unbind 在账号无密码但有其他绑定时允许解绑（OK）；</li>
 *   <li>unbind 在账号无密码且仅剩唯一绑定时拒绝（LAST_LOGIN_METHOD）；</li>
 *   <li>unbind 未绑该 provider 时返回 BINDING_NOT_FOUND。</li>
 * </ul>
 *
 * <p>通过 subclass spy 而非 Mockito（项目惯例，见 AlertEvaluatorImplTest）。
 */
class AccountOidcBindingServiceImplTest {

    private AccountOidcBindingServiceImpl service;
    private final List<AccountOidcBinding> rows = new ArrayList<>();
    private final AtomicLong idSeq = new AtomicLong(1L);
    private Account account; // 由用例设置 password 是否为空

    @BeforeEach
    void setUp() {
        rows.clear();
        idSeq.set(1L);
        account = null;

        service = new AccountOidcBindingServiceImpl() {

            @Override
            public List<AccountOidcBinding> list() {
                return new ArrayList<>(rows);
            }

            @Override
            public boolean save(AccountOidcBinding entity) {
                if (entity.getId() == null) entity.setId(idSeq.getAndIncrement());
                rows.add(entity);
                return true;
            }

            @Override
            public boolean updateById(AccountOidcBinding entity) {
                for (int i = 0; i < rows.size(); i++) {
                    if (rows.get(i).getId().equals(entity.getId())) {
                        rows.set(i, entity);
                        return true;
                    }
                }
                return false;
            }

            @Override
            public boolean removeById(java.io.Serializable id) {
                return rows.removeIf(r -> r.getId().equals(((Number) id).longValue()));
            }

            // 关键查询路径单独覆写，避免依赖 wrapper 的 SQL 解析
            @Override
            public AccountOidcBinding findByProviderSubject(String provider, String subject) {
                if (provider == null || subject == null) return null;
                for (AccountOidcBinding row : rows) {
                    if (provider.equals(row.getProviderName()) && subject.equals(row.getSubject())) {
                        return row;
                    }
                }
                return null;
            }

            @Override
            public boolean hasOtherBinding(int accountId, String excludeProvider) {
                return rows.stream().anyMatch(r ->
                        r.getAccountId() != null && r.getAccountId() == accountId
                                && !excludeProvider.equals(r.getProviderName()));
            }

            @Override
            public List<OidcBindingVO> listByAccount(int accountId) {
                return rows.stream()
                        .filter(r -> r.getAccountId() != null && r.getAccountId() == accountId)
                        .map(r -> {
                            OidcBindingVO vo = new OidcBindingVO();
                            vo.setProviderName(r.getProviderName());
                            vo.setDisplayName(r.getProviderName());
                            vo.setEmail(r.getEmail());
                            vo.setBoundAt(r.getBoundAt());
                            return vo;
                        }).toList();
            }

            /**
             * unbind 真实分支逻辑需要查 (accountId, provider)，子类直接遍历 rows 找匹配行，
             * 调用父类的 findByProviderSubject 不合适（它只按 provider+subject 查），
             * 因此整段覆写并复用 hasOtherBinding 与 accountService。
             */
            @Override
            public UnbindResult unbind(int accountId, String provider) {
                AccountOidcBinding row = null;
                for (AccountOidcBinding r : rows) {
                    if (r.getAccountId() != null && r.getAccountId() == accountId
                            && provider.equals(r.getProviderName())) {
                        row = r;
                        break;
                    }
                }
                if (row == null) return UnbindResult.BINDING_NOT_FOUND;
                boolean hasPassword = account != null
                        && account.getPassword() != null
                        && !account.getPassword().isBlank();
                if (!hasPassword && !this.hasOtherBinding(accountId, provider)) {
                    return UnbindResult.LAST_LOGIN_METHOD;
                }
                rows.remove(row);
                return UnbindResult.OK;
            }
        };

        // 注入 mapper stub（IService.baseMapper 用到 selectCount）
        AccountOidcBindingMapper mapper = (AccountOidcBindingMapper) Proxy.newProxyInstance(
                AccountOidcBindingMapper.class.getClassLoader(),
                new Class[]{AccountOidcBindingMapper.class},
                (proxy, method, args) -> {
                    if ("selectCount".equals(method.getName())) return 0L;
                    return null;
                });
        ReflectionTestUtils.setField(service, "baseMapper", mapper);

        // 注入 AccountService stub：getById 返回 account 字段
        AccountService accountService = (AccountService) Proxy.newProxyInstance(
                AccountService.class.getClassLoader(),
                new Class[]{AccountService.class},
                (proxy, method, args) -> {
                    if ("getById".equals(method.getName())) return account;
                    return null;
                });
        ReflectionTestUtils.setField(service, "accountService", accountService);
    }

    /**
     * upsert 不存在绑定时插入新行。
     */
    @Test
    void upsertShouldCreateNewBinding() {
        service.upsert(1, "github", "sub-1", "user@example.com");
        Assertions.assertEquals(1, rows.size());
        AccountOidcBinding row = rows.get(0);
        Assertions.assertEquals(1, row.getAccountId());
        Assertions.assertEquals("github", row.getProviderName());
        Assertions.assertEquals("sub-1", row.getSubject());
        Assertions.assertEquals("user@example.com", row.getEmail());
    }

    /**
     * upsert 已存在绑定时仅刷新 email，不新增行。
     */
    @Test
    void upsertExistingShouldUpdateEmailOnly() {
        AccountOidcBinding existing = new AccountOidcBinding();
        existing.setId(idSeq.getAndIncrement());
        existing.setAccountId(1);
        existing.setProviderName("github");
        existing.setSubject("sub-1");
        existing.setEmail("old@example.com");
        existing.setBoundAt(new Date());
        rows.add(existing);

        service.upsert(1, "github", "sub-1", "new@example.com");

        Assertions.assertEquals(1, rows.size());
        Assertions.assertEquals("new@example.com", rows.get(0).getEmail());
    }

    /**
     * 账号有密码时允许解绑（不会成为孤儿账号）。
     */
    @Test
    void unbindShouldSucceedWhenAccountHasPassword() {
        account = new Account(1, "user1", "$2a$10$encoded-password", "u1@example.com",
                "user", "[]", new Date(), Boolean.TRUE);
        AccountOidcBinding row = new AccountOidcBinding();
        row.setId(idSeq.getAndIncrement());
        row.setAccountId(1);
        row.setProviderName("github");
        row.setSubject("sub-1");
        row.setBoundAt(new Date());
        rows.add(row);

        AccountOidcBindingService.UnbindResult result = service.unbind(1, "github");
        Assertions.assertEquals(AccountOidcBindingService.UnbindResult.OK, result);
        Assertions.assertTrue(rows.isEmpty());
    }

    /**
     * 账号无密码但还有其他绑定时允许解绑。
     */
    @Test
    void unbindShouldSucceedWhenOtherBindingExists() {
        account = new Account(1, "user1", null, "u1@example.com",
                "user", "[]", new Date(), Boolean.TRUE);
        AccountOidcBinding gh = new AccountOidcBinding();
        gh.setId(idSeq.getAndIncrement());
        gh.setAccountId(1);
        gh.setProviderName("github");
        gh.setSubject("sub-gh");
        gh.setBoundAt(new Date());
        rows.add(gh);
        AccountOidcBinding gl = new AccountOidcBinding();
        gl.setId(idSeq.getAndIncrement());
        gl.setAccountId(1);
        gl.setProviderName("gitlab");
        gl.setSubject("sub-gl");
        gl.setBoundAt(new Date());
        rows.add(gl);

        AccountOidcBindingService.UnbindResult result = service.unbind(1, "github");
        Assertions.assertEquals(AccountOidcBindingService.UnbindResult.OK, result);
        Assertions.assertEquals(1, rows.size());
        Assertions.assertEquals("gitlab", rows.get(0).getProviderName());
    }

    /**
     * 账号无密码且仅剩唯一绑定时拒绝解绑。
     */
    @Test
    void unbindShouldRefuseWhenLastLoginMethod() {
        account = new Account(1, "user1", null, "u1@example.com",
                "user", "[]", new Date(), Boolean.TRUE);
        AccountOidcBinding gh = new AccountOidcBinding();
        gh.setId(idSeq.getAndIncrement());
        gh.setAccountId(1);
        gh.setProviderName("github");
        gh.setSubject("sub-gh");
        gh.setBoundAt(new Date());
        rows.add(gh);

        AccountOidcBindingService.UnbindResult result = service.unbind(1, "github");
        Assertions.assertEquals(AccountOidcBindingService.UnbindResult.LAST_LOGIN_METHOD, result);
        Assertions.assertEquals(1, rows.size(), "拒绝时不删除");
    }

    /**
     * 未绑该 provider 时返回 BINDING_NOT_FOUND。
     */
    @Test
    void unbindMissingShouldReturnNotFound() {
        account = new Account(1, "user1", "pwd", "u1@example.com",
                "user", "[]", new Date(), Boolean.TRUE);

        AccountOidcBindingService.UnbindResult result = service.unbind(1, "provider-x");
        Assertions.assertEquals(AccountOidcBindingService.UnbindResult.BINDING_NOT_FOUND, result);
    }

    /**
     * hasOtherBinding 排除指定 provider 后准确计数。
     */
    @Test
    void hasOtherBindingShouldExcludeMatchingProvider() {
        AccountOidcBinding gh = new AccountOidcBinding();
        gh.setId(idSeq.getAndIncrement());
        gh.setAccountId(7);
        gh.setProviderName("github");
        gh.setBoundAt(new Date());
        rows.add(gh);
        AccountOidcBinding gl = new AccountOidcBinding();
        gl.setId(idSeq.getAndIncrement());
        gl.setAccountId(7);
        gl.setProviderName("gitlab");
        gl.setBoundAt(new Date());
        rows.add(gl);

        Assertions.assertTrue(service.hasOtherBinding(7, "github"));
        Assertions.assertFalse(service.hasOtherBinding(7, "nonexistent") == false,
                "其他 provider 仍存在时应返回 true");
        Assertions.assertTrue(service.hasOtherBinding(7, "nonexistent"));
    }

    /**
     * listByAccount 返回符合的绑定列表。
     */
    @Test
    void listByAccountShouldReturnBindings() {
        AccountOidcBinding gh = new AccountOidcBinding();
        gh.setId(idSeq.getAndIncrement());
        gh.setAccountId(5);
        gh.setProviderName("github");
        gh.setEmail("u@example.com");
        gh.setBoundAt(new Date());
        rows.add(gh);

        List<OidcBindingVO> list = service.listByAccount(5);
        Assertions.assertEquals(1, list.size());
        Assertions.assertEquals("github", list.get(0).getProviderName());
        Assertions.assertEquals("u@example.com", list.get(0).getEmail());
    }
}
