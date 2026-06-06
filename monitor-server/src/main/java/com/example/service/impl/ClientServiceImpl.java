package com.example.service.impl;

import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.entity.dto.Account;
import com.example.entity.dto.AlertHistory;
import com.example.entity.dto.AlertRule;
import com.example.entity.dto.Client;
import com.example.entity.dto.ClientDetail;
import com.example.entity.dto.ClientSsh;
import com.example.entity.dto.StatusPageConfig;
import com.example.entity.vo.request.*;
import com.example.entity.vo.response.*;
import com.example.mapper.AccountMapper;
import com.example.mapper.AlertHistoryMapper;
import com.example.mapper.AlertRuleMapper;
import com.example.mapper.ClientDetailMapper;
import com.example.mapper.ClientMapper;
import com.example.mapper.ClientSshMapper;
import com.example.mapper.StatusPageConfigMapper;
import com.example.mapper.struct.ClientStructMapper;
import com.example.service.AlertEvaluator;
import com.example.service.ClientService;
import com.example.config.SseEventBus;
import com.example.service.StatusPageService;
import com.example.tsdb.TimeSeriesAdapter;
import com.example.utils.CryptoUtils;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ClientServiceImpl extends ServiceImpl<ClientMapper, Client> implements ClientService {

    private String registerToken = this.createNewToken();

    private final Cache<Integer, Client> clientIdCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .build();
    private final Cache<String, Client> clientTokenCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .build();
    private final Cache<Integer, RuntimeDetailVO> currentRuntime = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();
    private final Map<Integer, Long> heartbeatMap = new ConcurrentHashMap<>();

    @Resource
    TimeSeriesAdapter influx;

    @Lazy
    @Resource
    private SseEventBus sseEventBus;

    @Resource
    private ClientDetailMapper clientDetailMapper;
    @Resource
    private ClientSshMapper clientSshMapper;
    @Resource
    private AlertRuleMapper alertRuleMapper;
    @Resource
    private AlertHistoryMapper alertHistoryMapper;
    @Resource
    private StatusPageConfigMapper statusPageConfigMapper;
    @Resource
    private AccountMapper accountMapper;
    @Resource
    private ClientStructMapper clientStructMapper;
    @Resource
    private CryptoUtils cryptoUtils;

    @Lazy
    @Resource
    private AlertEvaluator alertEvaluator;

    @Lazy
    @Resource
    private StatusPageService statusPageService;

    @PostConstruct
    public void initClientCache() {
        clientIdCache.invalidateAll();
        clientTokenCache.invalidateAll();
        this.list().forEach(this::addClientCache);
    }

    @Override
    public boolean registerClient(String token) {
        if (this.registerToken.equals(token)) {
            int id = this.randomClientId();
            Client client = new Client(id, "未命名主机", token, "cn", "未命名节点", new Date(), null);
            if (this.save(client)) {
                this.registerToken = this.createNewToken();
                this.addClientCache(client);
                log.info("主机注册成功，Id：{}", id);
                return true;
            }
        }
        return false;
    }

    @Override
    public String getToken() {
        return registerToken;
    }

    @Override
    public void updateHeartbeat(Client client) {
        heartbeatMap.put(client.getId(), System.currentTimeMillis());
        sseEventBus.publishClientList();
    }

    /**
     * 处理客户端主动下线，清理本地缓存并实时通知前端刷新在线状态。
     *
     * @param client 主机信息
     */
    @Override
    public void clientOffline(Client client) {
        heartbeatMap.remove(client.getId());
        currentRuntime.invalidate(client.getId());
        sseEventBus.publishClientList();
        log.info("客户端 {} 已主动下线", client.getId());
    }

    @Override
    public Client findClientById(int id) {
        return clientIdCache.getIfPresent(id);
    }

    /**
     * 通过 token 查询客户端，优先使用本地缓存，未命中时回源数据库并回填缓存。
     *
     * @param token 客户端注册 token
     * @return 客户端信息，未找到时返回 null
     */
    @Override
    public Client findClientByToken(String token) {
        if (token == null || token.isBlank()) return null;
        Client cachedClient = clientTokenCache.getIfPresent(token);
        if (cachedClient != null) return cachedClient;
        Client dbClient = baseMapper.selectOne(Wrappers.<Client>lambdaQuery()
                .eq(Client::getToken, token)
                .last("limit 1"));
        if (dbClient != null) {
            this.addClientCache(dbClient);
        }
        return dbClient;
    }

    @Override
    public void updateClientDetail(ClientDetailVO vo, Client client) {
        ClientDetail clientDetail = new ClientDetail();
        BeanUtils.copyProperties(vo, clientDetail);
        clientDetail.setId(client.getId());
        if (Objects.nonNull(clientDetailMapper.selectById(client.getId()))) {
            clientDetailMapper.updateById(clientDetail);
        } else {
            clientDetailMapper.insert(clientDetail);
        }
    }

    @Override
    public void updateRuntimeDetail(RuntimeDetailVO vo, Client client) {
        currentRuntime.put(client.getId(), vo);
        heartbeatMap.put(client.getId(), System.currentTimeMillis());
        influx.writeRuntime(client.getId(), vo);
        this.publishRuntimeSideEffects(vo, client);
    }

    /**
     * 批量处理运行时数据：TSDB 走一次批量写入，本地缓存 / SSE / 告警仍逐条保持旧语义。
     *
     * @param batch  运行时数据批次
     * @param client 当前客户端
     */
    @Override
    public void updateRuntimeDetails(List<RuntimeDetailVO> batch, Client client) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        List<RuntimeDetailVO> validBatch = batch.stream()
                .filter(Objects::nonNull)
                .toList();
        if (validBatch.isEmpty()) {
            return;
        }
        for (RuntimeDetailVO vo : validBatch) {
            currentRuntime.put(client.getId(), vo);
            heartbeatMap.put(client.getId(), System.currentTimeMillis());
        }
        influx.writeRuntimeBatch(client.getId(), validBatch);
        for (RuntimeDetailVO vo : validBatch) {
            this.publishRuntimeSideEffects(vo, client);
        }
    }

    /**
     * 发布运行时数据相关副作用，保持单条与批量上报的 SSE / 告警行为一致。
     *
     * @param vo     运行时数据
     * @param client 当前客户端
     */
    private void publishRuntimeSideEffects(RuntimeDetailVO vo, Client client) {
        sseEventBus.publishRuntime(client.getId(), vo);
        sseEventBus.publishClientList();
        alertEvaluator.evaluate(client.getId(), vo);
    }

    /**
     * 汇总客户端基础信息、详情信息和实时状态，返回用于管理页展示的列表数据。
     *
     * @return 客户端预览列表
     */
    @Override
    public List<ClientPreviewVO> listClients() {
        return clientIdCache.asMap().values().stream().map(client -> {
            ClientPreviewVO vo = clientStructMapper.toPreviewVO(client);
            ClientDetail detail = clientDetailMapper.selectById(client.getId());
            if (detail != null) {
                BeanUtils.copyProperties(detail, vo);
            }
            RuntimeDetailVO runtime = currentRuntime.getIfPresent(client.getId());
            if (this.isOnline(client.getId())) {
                if (runtime != null) BeanUtils.copyProperties(runtime, vo);
                vo.setOnline(true);
            }
            return vo;
        }).toList();
    }

    /**
     * 返回用于权限配置的客户端简要列表信息。
     *
     * @return 客户端简要列表
     */
    @Override
    public List<ClientSimpleVO> listSimpleClients() {
        return clientIdCache.asMap().values().stream().map(client -> {
            ClientSimpleVO vo = clientStructMapper.toSimpleVO(client);
            ClientDetail detail = clientDetailMapper.selectById(vo.getId());
            if (detail != null) {
                BeanUtils.copyProperties(detail, vo);
            }
            return vo;
        }).toList();
    }

    @Override
    public void renameClient(RenameClientVO vo) {
        this.update(Wrappers.<Client>update().eq("id", vo.getId()).set("name", vo.getName()));
        this.initClientCache();
    }

    /**
     * 查询指定客户端详情并补充在线状态。
     *
     * @param clientId 客户端ID
     * @return 客户端详情
     */
    @Override
    public ClientDetailsVO clientDetails(int clientId) {
        Client cachedClient = clientIdCache.getIfPresent(clientId);
        if (cachedClient == null) return null;
        ClientDetailsVO client = clientStructMapper.toDetailsVO(cachedClient);
        ClientDetail detail = clientDetailMapper.selectById(clientId);
        if (detail != null) {
            BeanUtils.copyProperties(detail, client);
        }
        client.setOnline(this.isOnline(clientId));
        return client;
    }

    @Override
    public void renameNode(RenameNodeVO vo) {
        this.update(Wrappers.<Client>update().eq("id", vo.getId())
                .set("location", vo.getLocation()).set("node", vo.getNode()));
        this.initClientCache();
    }

    /**
     * 按时间范围查询客户端历史运行时数据并附加基础硬件信息。
     *
     * @param clientId 客户端ID
     * @param from     查询起始时间（含）
     * @param to       查询截止时间（含）
     * @return 运行时历史数据
     */
    @Override
    public RuntimeHistoryVO clientRuntimeDetailsHistory(int clientId, Instant from, Instant to) {
        RuntimeHistoryVO vo = influx.readRuntimeHistory(clientId, from, to);
        ClientDetail detail = clientDetailMapper.selectById(clientId);
        if (detail != null) {
            BeanUtils.copyProperties(detail, vo);
        }
        return vo;
    }

    @Override
    public RuntimeDetailVO clientRuntimeDetailsNow(int clientId) {
        return currentRuntime.getIfPresent(clientId);
    }

    @Override
    @Transactional
    public void deleteClient(int clientId) {
        Client existing = this.getById(clientId);
        if (existing == null) {
            this.invalidateUnknownLocalClientState(clientId);
            return;
        }

        clientDetailMapper.deleteById(clientId);
        clientSshMapper.deleteById(clientId);
        alertHistoryMapper.delete(Wrappers.<AlertHistory>lambdaQuery()
                .eq(AlertHistory::getClientId, clientId));
        alertRuleMapper.delete(Wrappers.<AlertRule>lambdaQuery()
                .eq(AlertRule::getClientId, clientId));

        boolean statusPageChanged = removeClientFromStatusPageConfig(clientId);
        int accountReferencesRemoved = removeClientFromAccountPermissions(clientId);
        boolean removed = this.removeById(clientId);

        this.invalidateLocalClientState(clientId, existing.getToken());
        if (statusPageChanged) {
            statusPageService.evictSummaryCache();
        }
        sseEventBus.publishClientList();
        log.info("客户端删除完成 clientId={} removed={} accountReferencesRemoved={} tsdbHistoryRetained=true",
                clientId, removed, accountReferencesRemoved);
    }

    /**
     * 保存客户端SSH连接配置，并在入库前对密码进行加密。
     *
     * @param vo SSH连接参数
     */
    @Override
    public void saveSshConnection(SshConnectVO vo) {
        Client client = clientIdCache.getIfPresent(vo.getId());
        if (client == null) return;
        ClientSsh clientSsh = new ClientSsh();
        BeanUtils.copyProperties(vo, clientSsh);
        clientSsh.setPassword(cryptoUtils.encrypt(vo.getPassword()));
        if (Objects.nonNull(clientSshMapper.selectById(client.getId())))
            clientSshMapper.updateById(clientSsh);
        else
            clientSshMapper.insert(clientSsh);
    }

    /**
     * 读取客户端SSH连接配置，并在返回前将密码解密为前端可回显内容。
     *
     * @param clientId 客户端ID
     * @return SSH配置
     */
    @Override
    public SshSettingsVO getSshSetting(int clientId) {
        ClientSsh clientSsh = clientSshMapper.selectById(clientId);
        SshSettingsVO vo;
        if (clientSsh == null) {
            ClientDetail detail = clientDetailMapper.selectById(clientId);
            vo = new SshSettingsVO();
            if (detail != null) {
                vo.setIp(detail.getIp());
            }
        } else {
            vo = clientStructMapper.toSshSettingsVO(clientSsh);
            vo.setPassword(cryptoUtils.decrypt(clientSsh.getPassword()));
        }
        return vo;
    }

    /**
     * 查询需要执行主动健康探测的客户端集合。
     *
     * @param staleThresholdMs 心跳/运行时数据过期阈值
     * @return 需要探测的客户端列表
     */
    @Override
    public List<Client> listHealthCheckCandidates(long staleThresholdMs) {
        long now = System.currentTimeMillis();
        return clientIdCache.asMap().values().stream()
                .filter(client -> {
                    Long lastHeartbeat = heartbeatMap.get(client.getId());
                    if (lastHeartbeat != null && now - lastHeartbeat <= staleThresholdMs) {
                        return false;
                    }
                    RuntimeDetailVO runtime = currentRuntime.getIfPresent(client.getId());
                    return runtime == null || now - runtime.getTimestamp() > staleThresholdMs;
                })
                .toList();
    }

    /**
     * 查询客户端SSH配置，供主动健康探测使用。
     *
     * @param clientId 客户端ID
     * @return SSH配置，未配置时返回null
     */
    @Override
    public ClientSsh findClientSsh(int clientId) {
        return clientSshMapper.selectById(clientId);
    }

    /**
     * 强制将客户端标记为离线并推送最新列表。
     *
     * @param clientId 客户端ID
     */
    @Override
    public void forceClientOffline(int clientId) {
        heartbeatMap.remove(clientId);
        currentRuntime.invalidate(clientId);
        sseEventBus.publishClientList();
        log.warn("客户端 {} 在主动健康检查后被标记为离线", clientId);
    }

    private boolean isOnline(int clientId) {
        Long lastHeartbeat = heartbeatMap.get(clientId);
        if (lastHeartbeat != null && System.currentTimeMillis() - lastHeartbeat < 60 * 1000)
            return true;
        RuntimeDetailVO runtime = currentRuntime.getIfPresent(clientId);
        return runtime != null && System.currentTimeMillis() - runtime.getTimestamp() < 60 * 1000;
    }

    @Override
    public boolean isClientOnline(int clientId) {
        return this.isOnline(clientId);
    }

    @Override
    public Long lastSeenSecondsAgo(int clientId) {
        long now = System.currentTimeMillis();
        Long lastHeartbeat = heartbeatMap.get(clientId);
        RuntimeDetailVO runtime = currentRuntime.getIfPresent(clientId);
        long candidate = -1L;
        if (lastHeartbeat != null) {
            candidate = lastHeartbeat;
        }
        if (runtime != null && runtime.getTimestamp() > candidate) {
            candidate = runtime.getTimestamp();
        }
        if (candidate <= 0) {
            return null;
        }
        long delta = now - candidate;
        return delta < 0 ? 0L : delta / 1000L;
    }

    private void addClientCache(Client client) {
        clientIdCache.put(client.getId(), client);
        clientTokenCache.put(client.getToken(), client);
    }

    /**
     * Invalidate all local cache state for a deleted client.
     *
     * @param clientId client ID
     * @param token    client token, nullable when unknown
     */
    private void invalidateLocalClientState(int clientId, String token) {
        clientIdCache.invalidate(clientId);
        if (token != null && !token.isBlank()) {
            clientTokenCache.invalidate(token);
        }
        currentRuntime.invalidate(clientId);
        heartbeatMap.remove(clientId);
    }

    /**
     * Invalidate local state when the client row is already gone and the token is unknown.
     *
     * @param clientId client ID
     */
    private void invalidateUnknownLocalClientState(int clientId) {
        clientIdCache.invalidate(clientId);
        clientTokenCache.invalidateAll();
        currentRuntime.invalidate(clientId);
        heartbeatMap.remove(clientId);
    }

    /**
     * Remove the deleted client ID from the public status-page configuration.
     *
     * @param clientId client ID
     * @return whether the configuration changed
     */
    private boolean removeClientFromStatusPageConfig(int clientId) {
        StatusPageConfig config = statusPageConfigMapper.selectById(1);
        if (config == null || config.getClientIds() == null || config.getClientIds().isBlank()) {
            return false;
        }
        List<String> retained = Arrays.stream(config.getClientIds().split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .filter(token -> {
                    try {
                        return Integer.parseInt(token) != clientId;
                    } catch (NumberFormatException ignore) {
                        return true;
                    }
                })
                .distinct()
                .toList();
        String next = String.join(",", retained);
        if (Objects.equals(next, config.getClientIds())) {
            return false;
        }
        statusPageConfigMapper.update(null, Wrappers.<StatusPageConfig>update()
                .eq("id", config.getId())
                .set("client_ids", next));
        return true;
    }

    /**
     * Remove the deleted client ID from sub-account permission lists.
     *
     * @param clientId client ID
     * @return number of updated accounts
     */
    private int removeClientFromAccountPermissions(int clientId) {
        List<Account> accounts = accountMapper.selectList(Wrappers.<Account>lambdaQuery()
                .isNotNull(Account::getClients));
        int changed = 0;
        for (Account account : accounts) {
            List<Integer> current = parseAccountClientIds(account);
            if (!current.contains(clientId)) {
                continue;
            }
            List<Integer> retained = current.stream()
                    .filter(Objects::nonNull)
                    .filter(id -> id != clientId)
                    .distinct()
                    .toList();
            accountMapper.update(null, Wrappers.<Account>update()
                    .eq("id", account.getId())
                    .set("clients", JSONArray.copyOf(retained).toString()));
            changed++;
        }
        return changed;
    }

    /**
     * Parse account client-permission JSON while tolerating corrupt legacy values.
     *
     * @param account account row
     * @return client ID list
     */
    private List<Integer> parseAccountClientIds(Account account) {
        if (account == null || account.getClients() == null || account.getClients().isBlank()) {
            return List.of();
        }
        try {
            return JSONArray.parseArray(account.getClients()).toList(Integer.class);
        } catch (Exception e) {
            log.warn("账号客户端权限解析失败 accountId={} reason={}", account.getId(), e.getMessage());
            return List.of();
        }
    }

    private int randomClientId() {
        return new Random().nextInt(90000000) + 10000000;
    }

    private String createNewToken() {
        String CHARACTERS = "abcdefghijhlmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder builder = new StringBuilder(24);
        for (int i = 0; i < 24; i++) {
            builder.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
        }
        return builder.toString();
    }
}
