package com.example.service;

import com.example.entity.dto.Account;
import com.example.entity.vo.response.ClientPreviewVO;
import com.example.utils.Const;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class PermissionService {

    @Resource
    private AccountService accountService;

    /**
     * 判断角色字符串是否为管理员。
     *
     * @param role 角色字符串（可能包含 ROLE_ 前缀）
     * @return 是否管理员
     */
    public boolean isAdmin(String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        String normalized = role.startsWith("ROLE_") && role.length() > 5 ? role.substring(5) : role;
        return Const.ROLE_ADMIN.equals(normalized);
    }

    /**
     * 查询用户可访问的客户端 ID 列表。
     *
     * @param userId 用户 ID
     * @return 可访问客户端 ID 列表
     */
    public List<Integer> accessClientIds(int userId) {
        Account account = accountService.getById(userId);
        if (account == null || account.getClientList() == null) {
            return Collections.emptyList();
        }
        return account.getClientList();
    }

    /**
     * 判断用户是否可访问指定客户端。
     *
     * @param userId 用户 ID
     * @param role 用户角色
     * @param clientId 客户端 ID
     * @return 是否可访问
     */
    public boolean canAccessClient(int userId, String role, int clientId) {
        if (this.isAdmin(role)) {
            return true;
        }
        return this.accessClientIds(userId).contains(clientId);
    }

    /**
     * 根据权限过滤客户端预览列表。
     *
     * @param clients 原始客户端列表
     * @param userId 用户 ID
     * @param role 用户角色
     * @return 过滤后的客户端列表
     */
    public List<ClientPreviewVO> filterClientsByPermission(List<ClientPreviewVO> clients, int userId, String role) {
        if (this.isAdmin(role)) {
            return clients;
        }
        Set<Integer> allowSet = new HashSet<>(this.accessClientIds(userId));
        return clients.stream().filter(vo -> allowSet.contains(vo.getId())).toList();
    }
}
