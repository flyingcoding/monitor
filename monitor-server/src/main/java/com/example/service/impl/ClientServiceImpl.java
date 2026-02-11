package com.example.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.entity.dto.Client;
import com.example.entity.dto.ClientDetail;
import com.example.entity.dto.ClientSsh;
import com.example.entity.vo.request.*;
import com.example.entity.vo.response.*;
import com.example.mapper.ClientDetailMapper;
import com.example.mapper.ClientMapper;
import com.example.mapper.ClientSshMapper;
import com.example.service.ClientService;
import com.example.utils.influxDBUtils;
import com.example.controller.SseController;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
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
    influxDBUtils influx;

    @Lazy
    @Resource
    private SseController sseController;

    @Resource
    private ClientDetailMapper clientDetailMapper;
    @Resource
    private ClientSshMapper clientSshMapper;

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
            Client client = new Client(id, "未命名主机", token, "cn", "未命名节点", new Date());
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
        sseController.pushClientList();
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
        sseController.pushClientList();
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
        influx.writeRuntimeData(client.getId(), vo);
        sseController.pushRuntime(client.getId(), vo);
        sseController.pushClientList();
    }

    @Override
    public List<ClientPreviewVO> listClients() {
        return clientIdCache.asMap().values().stream().map(client -> {
            ClientPreviewVO vo = client.asViewObject(ClientPreviewVO.class);
            BeanUtils.copyProperties(clientDetailMapper.selectById(client.getId()), vo);
            RuntimeDetailVO runtime = currentRuntime.getIfPresent(client.getId());
            if (this.isOnline(client.getId())) {
                if (runtime != null) BeanUtils.copyProperties(runtime, vo);
                vo.setOnline(true);
            }
            return vo;
        }).toList();
    }

    @Override
    public List<ClientSimpleVO> listSimpleClients() {
        return clientIdCache.asMap().values().stream().map(client -> {
            ClientSimpleVO vo = client.asViewObject(ClientSimpleVO.class);
            BeanUtils.copyProperties(clientDetailMapper.selectById(vo.getId()), vo);
            return vo;
        }).toList();
    }

    @Override
    public void renameClient(RenameClientVO vo) {
        this.update(Wrappers.<Client>update().eq("id", vo.getId()).set("name", vo.getName()));
        this.initClientCache();
    }

    @Override
    public ClientDetailsVO clientDetails(int clientId) {
        Client cachedClient = clientIdCache.getIfPresent(clientId);
        if (cachedClient == null) return null;
        ClientDetailsVO client = cachedClient.asViewObject(ClientDetailsVO.class);
        BeanUtils.copyProperties(clientDetailMapper.selectById(clientId), client);
        client.setOnline(this.isOnline(clientId));
        return client;
    }

    @Override
    public void renameNode(RenameNodeVO vo) {
        this.update(Wrappers.<Client>update().eq("id", vo.getId())
                .set("location", vo.getLocation()).set("node", vo.getNode()));
        this.initClientCache();
    }

    @Override
    public RuntimeHistoryVO clientRuntimeDetailsHistory(int clientId) {
        RuntimeHistoryVO vo = influx.readRuntimeHistory(clientId);
        ClientDetail detail = clientDetailMapper.selectById(clientId);
        BeanUtils.copyProperties(detail, vo);
        return vo;
    }

    @Override
    public RuntimeDetailVO clientRuntimeDetailsNow(int clientId) {
        return currentRuntime.getIfPresent(clientId);
    }

    @Override
    public void deleteClient(int clientId) {
        this.removeById(clientId);
        baseMapper.deleteById(clientId);
        this.initClientCache();
        currentRuntime.invalidate(clientId);
        heartbeatMap.remove(clientId);
    }

    @Override
    public void saveSshConnection(SshConnectVO vo) {
        Client client = clientIdCache.getIfPresent(vo.getId());
        if (client == null) return;
        ClientSsh clientSsh = new ClientSsh();
        BeanUtils.copyProperties(vo, clientSsh);
        if (Objects.nonNull(clientSshMapper.selectById(client.getId())))
            clientSshMapper.updateById(clientSsh);
        else
            clientSshMapper.insert(clientSsh);
    }

    @Override
    public SshSettingsVO getSshSetting(int clientId) {
        ClientSsh clientSsh = clientSshMapper.selectById(clientId);
        SshSettingsVO vo;
        if (clientSsh == null) {
            ClientDetail detail = clientDetailMapper.selectById(clientId);
            vo = new SshSettingsVO();
            vo.setIp(detail.getIp());
        } else vo = clientSsh.asViewObject(SshSettingsVO.class);
        return vo;
    }

    private boolean isOnline(int clientId) {
        Long lastHeartbeat = heartbeatMap.get(clientId);
        if (lastHeartbeat != null && System.currentTimeMillis() - lastHeartbeat < 60 * 1000)
            return true;
        RuntimeDetailVO runtime = currentRuntime.getIfPresent(clientId);
        return runtime != null && System.currentTimeMillis() - runtime.getTimestamp() < 60 * 1000;
    }

    private void addClientCache(Client client) {
        clientIdCache.put(client.getId(), client);
        clientTokenCache.put(client.getToken(), client);
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
