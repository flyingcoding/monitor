package com.example.controller;

import com.example.entity.dto.Account;
import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.entity.vo.response.ClientPreviewVO;
import com.example.service.AccountService;
import com.example.service.ClientService;
import com.example.utils.Const;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@RestController
@RequestMapping("/api/sse")
public class SseController {

    @Resource
    ClientService clientService;
    @Resource
    AccountService accountService;

    private final List<ClientListSubscriber> clientListEmitters = new CopyOnWriteArrayList<>();
    private final Map<Integer, List<SseEmitter>> runtimeEmitters = new ConcurrentHashMap<>();

    private record ClientListSubscriber(int userId, String userRole, SseEmitter emitter) {}

    /**
     * 订阅主机列表变更事件，按照当前用户权限返回可见主机列表。
     *
     * @param token    前端通过 query 透传的 JWT token
     * @param userId   当前用户ID
     * @param userRole 当前用户角色
     * @return SSE 发射器
     */
    @GetMapping("/clients")
    public SseEmitter subscribeClients(@RequestParam String token,
                                       @RequestAttribute(Const.ATTR_USER_ID) int userId,
                                       @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        SseEmitter emitter = new SseEmitter(0L);
        clientListEmitters.add(new ClientListSubscriber(userId, userRole, emitter));
        emitter.onCompletion(() -> removeClientListEmitter(emitter));
        emitter.onTimeout(() -> removeClientListEmitter(emitter));
        emitter.onError(e -> removeClientListEmitter(emitter));
        try {
            List<ClientPreviewVO> allClients = clientService.listClients();
            emitter.send(SseEmitter.event()
                    .name("clients")
                    .data(this.filterClientsByPermission(allClients, userId, userRole)));
        } catch (IOException e) {
            removeClientListEmitter(emitter);
        }
        return emitter;
    }

    /**
     * 订阅指定主机的实时运行数据，在建立连接前进行权限校验。
     *
     * @param clientId 目标主机ID
     * @param token    前端通过 query 透传的 JWT token
     * @param userId   当前用户ID
     * @param userRole 当前用户角色
     * @return SSE 发射器
     */
    @GetMapping("/runtime/{clientId}")
    public SseEmitter subscribeRuntime(@PathVariable int clientId,
                                       @RequestParam String token,
                                       @RequestAttribute(Const.ATTR_USER_ID) int userId,
                                       @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (!this.permissionCheck(userId, userRole, clientId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该主机");
        }
        SseEmitter emitter = new SseEmitter(0L);
        runtimeEmitters.computeIfAbsent(clientId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeRuntimeEmitter(clientId, emitter));
        emitter.onTimeout(() -> removeRuntimeEmitter(clientId, emitter));
        emitter.onError(e -> removeRuntimeEmitter(clientId, emitter));
        RuntimeDetailVO current = clientService.clientRuntimeDetailsNow(clientId);
        if (current != null) {
            try {
                emitter.send(SseEmitter.event().name("runtime").data(current));
            } catch (IOException e) {
                removeRuntimeEmitter(clientId, emitter);
            }
        }
        return emitter;
    }

    /**
     * 向所有主机列表订阅者推送最新数据，并按订阅者权限过滤可见主机。
     */
    public void pushClientList() {
        List<ClientPreviewVO> allClients = clientService.listClients();
        for (ClientListSubscriber subscriber : clientListEmitters) {
            try {
                subscriber.emitter().send(SseEmitter.event()
                        .name("clients")
                        .data(this.filterClientsByPermission(allClients, subscriber.userId(), subscriber.userRole())));
            } catch (Exception e) {
                removeClientListEmitter(subscriber.emitter());
            }
        }
    }

    /**
     * 向指定主机的运行时订阅者推送实时数据。
     *
     * @param clientId 主机ID
     * @param vo       运行时数据
     */
    public void pushRuntime(int clientId, RuntimeDetailVO vo) {
        List<SseEmitter> emitters = runtimeEmitters.get(clientId);
        if (emitters == null || emitters.isEmpty()) return;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("runtime").data(vo));
            } catch (Exception e) {
                emitters.remove(emitter);
            }
        }
    }

    /**
     * 移除主机列表订阅者。
     *
     * @param emitter SSE 发射器
     */
    private void removeClientListEmitter(SseEmitter emitter) {
        clientListEmitters.removeIf(subscriber -> subscriber.emitter() == emitter);
    }

    /**
     * 根据用户权限过滤主机列表。
     *
     * @param clients  原始主机列表
     * @param userId   用户ID
     * @param userRole 用户角色
     * @return 过滤后的主机列表
     */
    private List<ClientPreviewVO> filterClientsByPermission(List<ClientPreviewVO> clients, int userId, String userRole) {
        if (this.isAdminAccount(userRole)) return clients;
        Set<Integer> ids = new HashSet<>(this.accountAccessClients(userId));
        return clients.stream().filter(vo -> ids.contains(vo.getId())).toList();
    }

    /**
     * 校验用户是否有访问指定主机的权限。
     *
     * @param userId   用户ID
     * @param userRole 用户角色
     * @param clientId 主机ID
     * @return 是否允许访问
     */
    private boolean permissionCheck(int userId, String userRole, int clientId) {
        if (this.isAdminAccount(userRole)) return true;
        return this.accountAccessClients(userId).contains(clientId);
    }

    /**
     * 获取用户可访问的主机ID集合。
     *
     * @param userId 用户ID
     * @return 主机ID列表
     */
    private List<Integer> accountAccessClients(int userId) {
        Account account = accountService.getById(userId);
        if (account == null) return Collections.emptyList();
        return account.getClientList();
    }

    /**
     * 判断用户角色是否为管理员。
     *
     * @param role 角色字符串
     * @return 是否管理员
     */
    private boolean isAdminAccount(String role) {
        if (role == null || role.isBlank()) return false;
        String normalizedRole = role.startsWith("ROLE_") && role.length() > 5 ? role.substring(5) : role;
        return Const.ROLE_ADMIN.equals(normalizedRole);
    }

    /**
     * 移除指定主机的运行时订阅者。
     *
     * @param clientId 主机ID
     * @param emitter  SSE 发射器
     */
    private void removeRuntimeEmitter(int clientId, SseEmitter emitter) {
        List<SseEmitter> emitters = runtimeEmitters.get(clientId);
        if (emitters != null) {
            emitters.remove(emitter);
        }
    }
}
