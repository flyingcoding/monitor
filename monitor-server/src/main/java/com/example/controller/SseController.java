package com.example.controller;

import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.entity.vo.response.ClientPreviewVO;
import com.example.service.ClientService;
import com.example.service.PermissionService;
import com.example.utils.Const;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@RestController
@RequestMapping({"/api/sse", "/api/v1/sse"})
public class SseController {

    @Resource
    private ClientService clientService;

    @Resource
    private PermissionService permissionService;

    private final List<ClientListSubscriber> clientListEmitters = new CopyOnWriteArrayList<>();
    private final Map<Integer, List<SseEmitter>> runtimeEmitters = new ConcurrentHashMap<>();

    private record ClientListSubscriber(int userId, String userRole, SseEmitter emitter) {
    }

    /**
     * 订阅主机列表变更事件，按照当前用户权限返回可见主机列表。
     *
     * @param token 前端通过 query 透传的 JWT token
     * @param userId 当前用户ID
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
                    .data(permissionService.filterClientsByPermission(allClients, userId, userRole)));
        } catch (IOException e) {
            removeClientListEmitter(emitter);
        }
        return emitter;
    }

    /**
     * 订阅指定主机的实时运行数据，在建立连接前进行权限校验。
     *
     * @param clientId 目标主机ID
     * @param token 前端通过 query 透传的 JWT token
     * @param userId 当前用户ID
     * @param userRole 当前用户角色
     * @return SSE 发射器
     */
    @GetMapping("/runtime/{clientId}")
    public SseEmitter subscribeRuntime(@PathVariable int clientId,
                                       @RequestParam String token,
                                       @RequestAttribute(Const.ATTR_USER_ID) int userId,
                                       @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (!permissionService.canAccessClient(userId, userRole, clientId)) {
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
                        .data(permissionService.filterClientsByPermission(
                                allClients, subscriber.userId(), subscriber.userRole())));
            } catch (Exception e) {
                removeClientListEmitter(subscriber.emitter());
            }
        }
    }

    /**
     * 向指定主机的运行时订阅者推送实时数据。
     *
     * @param clientId 主机ID
     * @param vo 运行时数据
     */
    public void pushRuntime(int clientId, RuntimeDetailVO vo) {
        List<SseEmitter> emitters = runtimeEmitters.get(clientId);
        if (emitters == null || emitters.isEmpty()) return;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("runtime").data(vo));
            } catch (Exception e) {
                removeRuntimeEmitter(clientId, emitter);
            }
        }
    }

    /**
     * 应用关闭前主动完成所有 SSE 连接，避免强制断连。
     */
    @PreDestroy
    public void shutdown() {
        log.info("正在关闭所有 SSE 连接...");
        for (ClientListSubscriber subscriber : clientListEmitters) {
            try {
                subscriber.emitter().complete();
            } catch (Exception ignored) {
            }
        }
        clientListEmitters.clear();

        runtimeEmitters.values().forEach(list -> {
            list.forEach(emitter -> {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            });
            list.clear();
        });
        runtimeEmitters.clear();
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
     * 移除指定主机的运行时订阅者。
     *
     * @param clientId 主机ID
     * @param emitter SSE 发射器
     */
    private void removeRuntimeEmitter(int clientId, SseEmitter emitter) {
        List<SseEmitter> emitters = runtimeEmitters.get(clientId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            runtimeEmitters.remove(clientId);
        }
    }
}
