package com.example.controller;

import com.example.entity.RestBean;
import com.example.entity.vo.request.RenameClientVO;
import com.example.entity.vo.request.RenameNodeVO;
import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.entity.vo.request.SshConnectVO;
import com.example.entity.vo.response.ClientDetailsVO;
import com.example.entity.vo.response.ClientPreviewVO;
import com.example.entity.vo.response.ClientSimpleVO;
import com.example.entity.vo.response.GpuSnapshotResponseVO;
import com.example.entity.vo.response.ProcessSnapshotResponseVO;
import com.example.entity.vo.response.RuntimeHistoryVO;
import com.example.entity.vo.response.SshSettingsVO;
import com.example.entity.vo.response.SmartSnapshotResponseVO;
import com.example.entity.vo.response.SystemdSnapshotResponseVO;
import com.example.service.ClientService;
import com.example.service.GpuSnapshotService;
import com.example.service.PermissionService;
import com.example.service.ProcessSnapshotService;
import com.example.service.SmartSnapshotService;
import com.example.service.SystemdSnapshotService;
import com.example.utils.Const;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
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

    @Resource
    private SystemdSnapshotService systemdSnapshotService;

    @Resource
    private SmartSnapshotService smartSnapshotService;

    @Resource
    private ProcessSnapshotService processSnapshotService;

    @Resource
    private GpuSnapshotService gpuSnapshotService;

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

    /** 历史查询时间窗口硬上限：7 天（PRD §D2）。 */
    private static final Duration MAX_HISTORY_WINDOW = Duration.ofDays(7);

    /** 缺省查询窗口：1 小时（v2.0-beta 之前的默认行为，无 from/to 入参时兼容）。 */
    private static final Duration DEFAULT_HISTORY_WINDOW = Duration.ofHours(1);

    /**
     * 查询客户端历史运行时数据，支持按时间范围查询。
     *
     * <p>无 from/to 入参时默认查询最近 1 小时（v2.0-beta 之前行为）；带 from/to 时按指定范围查询。
     * 时间跨度硬上限 7 天（详见 PRD §D2），超过则返回 400；from 必须早于 to。
     * server 端按 {@code TsdbQueryUtils.chooseStep} 选择 step 下采样，返回 1k-2k 点。
     *
     * @param clientId 客户端ID
     * @param from     可选起始时间（ISO 8601），缺省 {@code to - 1h}
     * @param to       可选截止时间（ISO 8601），缺省 {@code now}
     * @param userId   当前用户ID
     * @param userRole 当前用户角色
     * @return 历史运行时数据
     */
    @GetMapping("/runtime_history")
    public RestBean<RuntimeHistoryVO> runtimeDetailsHistory(@RequestParam int clientId,
                                                            @RequestParam(required = false)
                                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                                            @RequestParam(required = false)
                                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                                            @RequestAttribute(Const.ATTR_USER_ID) int userId,
                                                            @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (!permissionService.canAccessClient(userId, userRole, clientId)) {
            return RestBean.noPermission();
        }
        Instant effectiveTo = to == null ? Instant.now() : to;
        Instant effectiveFrom = from == null ? effectiveTo.minus(DEFAULT_HISTORY_WINDOW) : from;
        if (!effectiveFrom.isBefore(effectiveTo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from 必须早于 to");
        }
        if (Duration.between(effectiveFrom, effectiveTo).compareTo(MAX_HISTORY_WINDOW) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "时间跨度不能超过 7 天");
        }
        return RestBean.success(clientService.clientRuntimeDetailsHistory(clientId, effectiveFrom, effectiveTo));
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
    @DeleteMapping("/{clientId}")
    public RestBean<Void> deleteClient(@PathVariable int clientId,
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

    /**
     * 查询客户端最近一次 systemd 快照。无 systemd 采集或缓存已过期时返回 null。
     *
     * @param clientId 客户端ID
     * @param userId 当前用户ID
     * @param userRole 当前用户角色
     * @return 快照响应
     */
    @GetMapping("/systemd")
    public RestBean<SystemdSnapshotResponseVO> getSystemdSnapshot(@RequestParam int clientId,
                                                                  @RequestAttribute(Const.ATTR_USER_ID) int userId,
                                                                  @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (permissionService.canAccessClient(userId, userRole, clientId)) {
            return RestBean.success(systemdSnapshotService.getLatest(clientId));
        } else
            return RestBean.noPermission();
    }

    /**
     * 查询客户端最近一次 SMART 磁盘健康快照。无 SMART 采集或缓存已过期时返回 null。
     *
     * @param clientId 客户端ID
     * @param userId 当前用户ID
     * @param userRole 当前用户角色
     * @return 快照响应
     */
    @GetMapping("/smart")
    public RestBean<SmartSnapshotResponseVO> getSmartSnapshot(@RequestParam int clientId,
                                                              @RequestAttribute(Const.ATTR_USER_ID) int userId,
                                                              @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (permissionService.canAccessClient(userId, userRole, clientId)) {
            return RestBean.success(smartSnapshotService.getLatest(clientId));
        } else
            return RestBean.noPermission();
    }

    /**
     * 查询客户端最近一次进程快照（Top N + watched pattern 状态）。
     * 无进程采集或缓存已过期时返回 null。
     *
     * @param clientId 客户端ID
     * @param userId 当前用户ID
     * @param userRole 当前用户角色
     * @return 快照响应
     */
    @GetMapping("/process")
    public RestBean<ProcessSnapshotResponseVO> getProcessSnapshot(@RequestParam int clientId,
                                                                  @RequestAttribute(Const.ATTR_USER_ID) int userId,
                                                                  @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (permissionService.canAccessClient(userId, userRole, clientId)) {
            return RestBean.success(processSnapshotService.getLatest(clientId));
        } else
            return RestBean.noPermission();
    }

    /**
     * 查询客户端最近一次 NVIDIA GPU 快照（每张卡的利用率/显存/温度/功耗）。
     * 无 GPU 采集或缓存已过期时返回 null。
     *
     * @param clientId 客户端ID
     * @param userId 当前用户ID
     * @param userRole 当前用户角色
     * @return 快照响应
     */
    @GetMapping("/gpu")
    public RestBean<GpuSnapshotResponseVO> getGpuSnapshot(@RequestParam int clientId,
                                                          @RequestAttribute(Const.ATTR_USER_ID) int userId,
                                                          @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (permissionService.canAccessClient(userId, userRole, clientId)) {
            return RestBean.success(gpuSnapshotService.getLatest(clientId));
        } else
            return RestBean.noPermission();
    }
}
