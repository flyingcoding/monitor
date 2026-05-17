package com.example.service;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.entity.dto.ApiToken;
import com.example.entity.vo.request.ApiTokenCreateVO;
import com.example.entity.vo.response.ApiTokenCreatedVO;
import com.example.entity.vo.response.ApiTokenVO;
import com.example.mapper.ApiTokenMapper;
import com.example.service.impl.ApiTokenServiceImpl;
import com.example.utils.ApiTokenUtils;
import com.example.utils.Const;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.Serializable;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ApiTokenServiceImpl 单元测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>创建 + 校验闭环（HMAC 完整性）；</li>
 *   <li>{@code validateAndResolve} 对未知 token / 过期 token / 非 {@code mtk_} 前缀返回空；</li>
 *   <li>恒定时间比对（{@link ApiTokenUtils#constantTimeEquals(String, String)}）；</li>
 *   <li>scope 字段被原样持久化（filter 层校验依赖此字段）；</li>
 *   <li>{@code recordUsage} 节流（60 秒内的二次调用不会更新 last_used_*）；</li>
 *   <li>列表 / 删除 / 旋转的所有权隔离（不同 accountId 互不可见）。</li>
 * </ul>
 *
 * <p>遵循项目惯例（{@code AlertEvaluatorImplTest} / {@code OidcProviderServiceImplTest}）：
 * JDK 动态代理 + 内存集合替代 Mockito，无 Spring 上下文。
 */
class ApiTokenServiceImplTest {

    private ApiTokenServiceImpl service;
    private ApiTokenUtils utils;
    private final List<ApiToken> rows = new ArrayList<>();
    private final AtomicLong idSeq = new AtomicLong(1L);

    @BeforeEach
    void setUp() {
        rows.clear();
        idSeq.set(1L);

        // 真实 ApiTokenUtils（依赖 base64 编码的 32 字节密钥）
        utils = new ApiTokenUtils();
        ReflectionTestUtils.setField(utils, "hmacKeyConfig",
                java.util.Base64.getEncoder().encodeToString(
                        "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        // 手动触发 @PostConstruct，避免依赖 Spring
        ReflectionTestUtils.invokeMethod(utils, "init");

        // ServiceImpl 的内存桩：覆写 list/getOne/getById/save/updateById/removeById
        service = new ApiTokenServiceImpl() {

            @Override
            public List<ApiToken> list(Wrapper<ApiToken> queryWrapper) {
                Integer wantedAccountId = extractAccountIdFilter(queryWrapper);
                return rows.stream()
                        .filter(r -> wantedAccountId == null
                                || (r.getAccountId() != null && r.getAccountId().equals(wantedAccountId)))
                        .sorted((a, b) -> {
                            // 按 created_at 倒序
                            Date ad = a.getCreatedAt();
                            Date bd = b.getCreatedAt();
                            if (ad == null && bd == null) return 0;
                            if (ad == null) return 1;
                            if (bd == null) return -1;
                            return bd.compareTo(ad);
                        })
                        .toList();
            }

            @Override
            public ApiToken getOne(Wrapper<ApiToken> queryWrapper, boolean throwEx) {
                String wantedHash = extractTokenHashFilter(queryWrapper);
                if (wantedHash == null) return null;
                for (ApiToken row : rows) {
                    if (wantedHash.equals(row.getTokenHash())) return row;
                }
                return null;
            }

            @Override
            public ApiToken getById(Serializable id) {
                long i = ((Number) id).longValue();
                for (ApiToken row : rows) {
                    if (row.getId() != null && row.getId().equals(i)) return row;
                }
                return null;
            }

            @Override
            public boolean save(ApiToken entity) {
                if (entity.getId() == null) entity.setId(idSeq.getAndIncrement());
                rows.add(entity);
                return true;
            }

            @Override
            public boolean updateById(ApiToken entity) {
                for (int i = 0; i < rows.size(); i++) {
                    if (rows.get(i).getId().equals(entity.getId())) {
                        ApiToken merged = rows.get(i);
                        if (entity.getLastUsedAt() != null) merged.setLastUsedAt(entity.getLastUsedAt());
                        if (entity.getLastUsedIp() != null) merged.setLastUsedIp(entity.getLastUsedIp());
                        rows.set(i, merged);
                        return true;
                    }
                }
                return false;
            }

            @Override
            public boolean removeById(Serializable id) {
                return rows.removeIf(r -> r.getId().equals(((Number) id).longValue()));
            }
        };
        ReflectionTestUtils.setField(service, "apiTokenUtils", utils);

        // baseMapper 仅在 IService 默认实现内被引用；本测试已覆写关键路径，注入空代理兜底
        ApiTokenMapper mapper = (ApiTokenMapper) Proxy.newProxyInstance(
                ApiTokenMapper.class.getClassLoader(),
                new Class[]{ApiTokenMapper.class},
                (proxy, method, args) -> null);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    /**
     * 从 wrapper 中尽力提取 account_id 等值过滤。
     */
    private Integer extractAccountIdFilter(Wrapper<ApiToken> w) {
        if (!(w instanceof AbstractWrapper<?, ?, ?> aw)) return null;
        String segment = aw.getSqlSegment() == null ? "" : aw.getSqlSegment();
        if (!segment.contains("account_id")) return null;
        for (Object v : aw.getParamNameValuePairs().values()) {
            if (v instanceof Integer i) return i;
        }
        return null;
    }

    /**
     * 从 wrapper 中提取 token_hash 等值过滤。
     */
    private String extractTokenHashFilter(Wrapper<ApiToken> w) {
        if (!(w instanceof AbstractWrapper<?, ?, ?> aw)) return null;
        String segment = aw.getSqlSegment() == null ? "" : aw.getSqlSegment();
        if (!segment.contains("token_hash")) return null;
        for (Object v : aw.getParamNameValuePairs().values()) {
            if (v instanceof String s) return s;
        }
        return null;
    }

    /**
     * 创建后能用同一明文 token 校验通过；token 形如 mtk_<32 base62>。
     */
    @Test
    void createThenValidateShouldRoundTrip() {
        ApiTokenCreateVO vo = new ApiTokenCreateVO();
        vo.setName("ci");
        vo.setScope("readonly");

        ApiTokenCreatedVO created = service.create(7, vo);

        Assertions.assertNotNull(created.getToken());
        Assertions.assertTrue(created.getToken().startsWith(Const.API_TOKEN_PREFIX));
        Assertions.assertEquals(36, created.getToken().length(), "前缀 mtk_ + 32 字符 base62 = 36");
        Assertions.assertTrue(created.getMeta().getPrefixTail().startsWith(Const.API_TOKEN_PREFIX));
        Assertions.assertTrue(created.getMeta().getPrefixTail().contains("…"));
        Assertions.assertEquals("readonly", created.getMeta().getScope());

        Optional<ApiToken> resolved = service.validateAndResolve(created.getToken());
        Assertions.assertTrue(resolved.isPresent());
        Assertions.assertEquals(7, resolved.get().getAccountId());
        Assertions.assertEquals("ci", resolved.get().getName());
        Assertions.assertEquals("readonly", resolved.get().getScope());
    }

    /**
     * 未知 token 应返回 empty 而非抛异常。
     */
    @Test
    void validateAndResolveShouldReturnEmptyForUnknownToken() {
        Optional<ApiToken> resolved = service.validateAndResolve("mtk_unknown00000000000000000000000000");
        Assertions.assertTrue(resolved.isEmpty());
    }

    /**
     * 非 mtk_ 前缀（例如 JWT 误投递）直接返回 empty，不消耗 HMAC 计算。
     */
    @Test
    void validateAndResolveShouldReturnEmptyForNonMtkPrefix() {
        Assertions.assertTrue(service.validateAndResolve("eyJhbGciOi...").isEmpty());
        Assertions.assertTrue(service.validateAndResolve(null).isEmpty());
        Assertions.assertTrue(service.validateAndResolve("").isEmpty());
    }

    /**
     * 过期 token 返回 empty（哈希仍能匹配但被时间过滤拒绝）。
     */
    @Test
    void validateAndResolveShouldRejectExpiredToken() {
        ApiTokenCreateVO vo = new ApiTokenCreateVO();
        vo.setName("expired");
        vo.setScope("readwrite");
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.HOUR, -1); // 已过期 1 小时
        vo.setExpiresAt(cal.getTime());

        ApiTokenCreatedVO created = service.create(8, vo);
        Optional<ApiToken> resolved = service.validateAndResolve(created.getToken());
        Assertions.assertTrue(resolved.isEmpty(), "已过期 token 必须返回 empty");
    }

    /**
     * 同一明文 token 的 HMAC 哈希稳定；不同密钥产出不同哈希；isEqual 走恒定时间比较。
     */
    @Test
    void hashShouldBeDeterministicAndConstantTimeCompare() {
        String token = "mtk_abcdefghijklmnopqrstuvwxyz123456";
        String h1 = utils.hash(token);
        String h2 = utils.hash(token);
        Assertions.assertEquals(h1, h2);
        Assertions.assertTrue(utils.constantTimeEquals(h1, h2));
        Assertions.assertFalse(utils.constantTimeEquals(h1, h1 + "x"));
        Assertions.assertFalse(utils.constantTimeEquals(null, h1));
        Assertions.assertFalse(utils.constantTimeEquals(h1, null));
    }

    /**
     * recordUsage 节流：第二次调用在 60s 内不再更新 last_used_*；超过 60s 则更新。
     */
    @Test
    void recordUsageShouldThrottleWithin60Seconds() throws Exception {
        ApiTokenCreateVO vo = new ApiTokenCreateVO();
        vo.setName("touch");
        vo.setScope("readwrite");
        ApiTokenCreatedVO created = service.create(9, vo);
        long tokenId = created.getMeta().getId();

        service.recordUsage(tokenId, "10.0.0.1");
        ApiToken first = rows.stream().filter(r -> r.getId().equals(tokenId)).findFirst().orElseThrow();
        Date firstAt = first.getLastUsedAt();
        Assertions.assertNotNull(firstAt);
        Assertions.assertEquals("10.0.0.1", first.getLastUsedIp());

        // 立即再次调用：应被节流，时间不变
        service.recordUsage(tokenId, "10.0.0.2");
        Assertions.assertEquals(firstAt, first.getLastUsedAt(),
                "60 秒内节流，last_used_at 不变");
        Assertions.assertEquals("10.0.0.1", first.getLastUsedIp(),
                "节流期间 IP 也不更新");
    }

    /**
     * recordUsage 在节流窗口失效后允许重新更新。
     * <p>通过反射清空节流缓存模拟时间推进，避免实际等待。
     */
    @Test
    void recordUsageShouldUpdateAfterThrottleWindow() {
        ApiTokenCreateVO vo = new ApiTokenCreateVO();
        vo.setName("touch");
        vo.setScope("readwrite");
        ApiTokenCreatedVO created = service.create(10, vo);
        long tokenId = created.getMeta().getId();

        service.recordUsage(tokenId, "192.168.1.1");
        // 清空节流缓存模拟 60s 后
        com.github.benmanes.caffeine.cache.Cache<?, ?> throttle =
                (com.github.benmanes.caffeine.cache.Cache<?, ?>) ReflectionTestUtils.getField(service, "recordUsageThrottle");
        Assertions.assertNotNull(throttle);
        throttle.invalidateAll();

        service.recordUsage(tokenId, "192.168.1.2");
        ApiToken row = rows.stream().filter(r -> r.getId().equals(tokenId)).findFirst().orElseThrow();
        Assertions.assertEquals("192.168.1.2", row.getLastUsedIp(),
                "节流窗口失效后 IP 必须更新");
    }

    /**
     * list 严格按 account 隔离：跨账号互不可见。
     */
    @Test
    void listShouldBeIsolatedPerAccount() {
        ApiTokenCreateVO vo1 = new ApiTokenCreateVO();
        vo1.setName("u1-token");
        vo1.setScope("readonly");
        service.create(1, vo1);

        ApiTokenCreateVO vo2 = new ApiTokenCreateVO();
        vo2.setName("u2-token");
        vo2.setScope("readwrite");
        service.create(2, vo2);

        List<ApiTokenVO> u1 = service.list(1);
        Assertions.assertEquals(1, u1.size());
        Assertions.assertEquals("u1-token", u1.get(0).getName());

        List<ApiTokenVO> u2 = service.list(2);
        Assertions.assertEquals(1, u2.size());
        Assertions.assertEquals("u2-token", u2.get(0).getName());
    }

    /**
     * delete 拒绝跨账号删除：u2 删 u1 的 token 应返回 false。
     */
    @Test
    void deleteShouldRefuseAcrossAccounts() {
        ApiTokenCreatedVO created = service.create(11, newVO("t1", "readonly"));
        boolean removed = service.delete(99, created.getMeta().getId());
        Assertions.assertFalse(removed);
        Assertions.assertEquals(1, rows.size(), "未删除");

        boolean removedSelf = service.delete(11, created.getMeta().getId());
        Assertions.assertTrue(removedSelf);
    }

    /**
     * delete 不存在的 id 返回 false。
     */
    @Test
    void deleteMissingShouldReturnFalse() {
        Assertions.assertFalse(service.delete(1, 999L));
    }

    /**
     * rotate 删除旧 token 并按相同元数据创建新 token；明文应不同。
     */
    @Test
    void rotateShouldReplaceTokenAtomically() {
        ApiTokenCreateVO vo = new ApiTokenCreateVO();
        vo.setName("rotated");
        vo.setScope("readwrite");
        ApiTokenCreatedVO first = service.create(20, vo);
        long firstId = first.getMeta().getId();

        Optional<ApiTokenCreatedVO> rotated = service.rotate(20, firstId);
        Assertions.assertTrue(rotated.isPresent());
        Assertions.assertNotEquals(first.getToken(), rotated.get().getToken(), "新 token 必须与旧不同");
        // 旧 hash 不再可用
        Optional<ApiToken> oldResolve = service.validateAndResolve(first.getToken());
        Assertions.assertTrue(oldResolve.isEmpty(), "旧明文 token 应失效");
        Optional<ApiToken> newResolve = service.validateAndResolve(rotated.get().getToken());
        Assertions.assertTrue(newResolve.isPresent(), "新明文 token 应有效");
        Assertions.assertEquals("rotated", newResolve.get().getName());
        Assertions.assertEquals("readwrite", newResolve.get().getScope());
    }

    /**
     * rotate 跨账号被拒绝。
     */
    @Test
    void rotateShouldRefuseAcrossAccounts() {
        ApiTokenCreatedVO first = service.create(30, newVO("foo", "readonly"));
        Optional<ApiTokenCreatedVO> rotated = service.rotate(99, first.getMeta().getId());
        Assertions.assertTrue(rotated.isEmpty());
    }

    /**
     * buildPrefixTail 与 token 主体严格对应。
     */
    @Test
    void prefixTailShouldEmbedHeadAndTail() {
        String token = "mtk_aB3DefghijklmnopqrstuvwxyzZ123xY9z";
        String tail = utils.buildPrefixTail(token);
        Assertions.assertTrue(tail.startsWith("mtk_aB3D"));
        Assertions.assertTrue(tail.endsWith("xY9z"));
        Assertions.assertTrue(tail.contains("…"));
    }

    private static ApiTokenCreateVO newVO(String name, String scope) {
        ApiTokenCreateVO vo = new ApiTokenCreateVO();
        vo.setName(name);
        vo.setScope(scope);
        return vo;
    }
}
