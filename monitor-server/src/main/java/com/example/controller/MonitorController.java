package com.example.controller;

import com.example.entity.RestBean;
import com.example.entity.vo.request.RenameClientVO;
import com.example.entity.vo.request.RenameNodeVO;
import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.entity.vo.request.SshConnectVO;
import com.example.entity.vo.response.ClientDetailsVO;
import com.example.entity.vo.response.ClientPreviewVO;
import com.example.entity.vo.response.ClientSimpleVO;
import com.example.entity.vo.response.RuntimeHistoryVO;
import com.example.entity.vo.response.SshSettingsVO;
import com.example.service.ClientService;
import com.example.service.PermissionService;
import com.example.utils.Const;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 监控信息管理接口。
 */
@RestController
@RequestMapping({"/api/monitor", "/api/v1/monitor"})
public class MonitorController {

    @Resource
    private ClientService clientService;

    @Resource
    private PermissionService permissionService;

    /**
     * 查询当前用户可见的客户端列表。
     *
     * @param userId 当前用户ID
     * @param userRole 当前用户角色
     * @return 客户端预览列表
     */
    @GetMapping("/list")
    public RestBean<List<ClientPreviewVO>> listAllClient(@RequestAttribute(Const.ATTR_USER_ID) int userId,
                                                          @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        List<ClientPreviewVO> clients = clientService.listClients();
        return RestBean.success(permissionService.filterClientsByPermission(clients, userId, userRole));
    }

    /**
     * 查询用于权限设置的客户端简要列表，仅管理员可访问。
     *
     * @param userRole 当前用户角色
     * @return 客户端简要列表
     */
    @GetMapping("/simple-list")
    public RestBean<List<ClientSimpleVO>> simpleClientList(@RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (permissionService.isAdmin(userRole))
            return RestBean.success(clientService.listSimpleClients());
        else
            return RestBean.noPermission();
    }

    /**
     * 重命名客户端。
     *
     * @param client 客户端重命名请求
     * @param userId 当前用户ID
     * @param userRole 当前用户角色
     * @return 操作结果
     */
    @PostMapping("/rename")
    public RestBean<Void> renameClient(@RequestBody @Valid RenameClientVO client,
                                       @RequestAttribute(Const.ATTR_USER_ID) int userId,
                                       @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (permissionService.canAccessClient(userId, userRole, client.getId())) {
            clientService.renameClient(client);
            return RestBean.success();
        } else
            return RestBean.noPermission();
    }

    /**
     * 修改客户端节点信息。
     *
     * @param vo 节点信息
     * @param userId 当前用户ID
     * @param userRole 当前用户角色
     * @return 操作结果
     */
    @PostMapping("/node")
    public RestBean<Void> renameNode(@RequestBody @Valid RenameNodeVO vo,
                                     @RequestAttribute(Const.ATTR_USER_ID) int userId,
                                     @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (permissionService.canAccessClient(userId, userRole, vo.getId())) {
            clientService.renameNode(vo);
            return RestBean.success();
        } else
            return RestBean.noPermission();
    }

    /**
     * 查询客户端详情。
     *
     * @param clientId 客户端ID
     * @param userId 当前用户ID
     * @param userRole 当前用户角色
     * @return 客户端详情
     */
    @GetMapping("/details")
    public RestBean<ClientDetailsVO> details(@RequestParam int clientId,
                                             @RequestAttribute(Const.ATTR_USER_ID) int userId,
                                             @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (permissionService.canAccessClient(userId, userRole, clientId)) {
            return RestBean.success(clientService.clientDetails(clientId));
        } else
            return RestBean.noPermission();
    }

    /**
     * 查询客户端历史运行时数据。
     *
     * @param clientId 客户端ID
     * @param userId 当前用户ID
     * @param userRole 当前用户角色
     * @return 历史运行时数据
     */
    @GetMapping("/runtime_history")
    public RestBean<RuntimeHistoryVO> runtimeDetailsHistory(@RequestParam int clientId,
                                                            @RequestAttribute(Const.ATTR_USER_ID) int userId,
                                                            @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (permissionService.canAccessClient(userId, userRole, clientId)) {
            return RestBean.success(clientService.clientRuntimeDetailsHistory(clientId));
        } else
            return RestBean.noPermission();
    }

    /**
     * 查询客户端当前运行时数据。
     *
     * @param clientId 客户端ID
     * @param userId 当前用户ID
     * @param userRole 当前用户角色
     * @return 当前运行时数据
     */
    @GetMapping("/runtime_now")
    public RestBean<RuntimeDetailVO> runtimeDetailsNow(@RequestParam int clientId,
                                                       @RequestAttribute(Const.ATTR_USER_ID) int userId,
                                                       @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (permissionService.canAccessClient(userId, userRole, clientId)) {
            return RestBean.success(clientService.clientRuntimeDetailsNow(clientId));
        } else
            return RestBean.noPermission();
    }

    /**
     * 获取客户端注册 token，仅管理员可访问。
     *
     * @param userRole 当前用户角色
     * @return 注册 token
     */
    @GetMapping("/register")
    public RestBean<String> registerToken(@RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (permissionService.isAdmin(userRole))
            return RestBean.success(clientService.getToken());
        else
            return RestBean.noPermission();
    }

    /**
     * 删除客户端，仅管理员可访问。
     *
     * @param clientId 客户端ID
     * @param userRole 当前用户角色
     * @return 操作结果
     */
    @GetMapping("/delete")
    public RestBean<Void> deleteClient(@RequestParam int clientId,
                                       @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (permissionService.isAdmin(userRole)) {
            clientService.deleteClient(clientId);
            return RestBean.success();
        } else
            return RestBean.noPermission();
    }

    /**
     * 保存客户端 SSH 连接信息。
     *
     * @param userId 当前用户ID
     * @param userRole 当前用户角色
     * @param vo SSH连接信息
     * @return 操作结果
     */
    @PostMapping("/ssh-save")
    public RestBean<Void> saveSSHConnection(@RequestAttribute(Const.ATTR_USER_ID) int userId,
                                            @RequestAttribute(Const.ATTR_USER_ROLE) String userRole,
                                            @RequestBody @Valid SshConnectVO vo) {
        if (permissionService.canAccessClient(userId, userRole, vo.getId())) {
            clientService.saveSshConnection(vo);
            return RestBean.success();
        } else
            return RestBean.noPermission();
    }

    /**
     * 查询客户端 SSH 配置。
     *
     * @param userId 当前用户ID
     * @param userRole 当前用户角色
     * @param clientId 客户端ID
     * @return SSH配置
     */
    @GetMapping("/ssh")
    public RestBean<SshSettingsVO> getSshConnect(@RequestAttribute(Const.ATTR_USER_ID) int userId,
                                                 @RequestAttribute(Const.ATTR_USER_ROLE) String userRole,
                                                 @RequestParam int clientId) {
        if (permissionService.canAccessClient(userId, userRole, clientId)) {
            return RestBean.success(clientService.getSshSetting(clientId));
        } else
            return RestBean.noPermission();
    }
}
