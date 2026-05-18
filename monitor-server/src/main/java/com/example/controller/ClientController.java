package com.example.controller;

import com.example.entity.RestBean;
import com.example.entity.dto.Client;
import com.example.entity.vo.request.ClientDetailVO;
import com.example.entity.vo.request.GpuSnapshotVO;
import com.example.entity.vo.request.ProcessSnapshotVO;
import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.entity.vo.request.SmartSnapshotVO;
import com.example.entity.vo.request.SystemdSnapshotVO;
import com.example.service.ClientService;
import com.example.service.GpuSnapshotService;
import com.example.service.ProcessSnapshotService;
import com.example.service.SmartSnapshotService;
import com.example.service.SystemdSnapshotService;
import com.example.utils.Const;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;

/**
 * @program: monitor
 * @description: 客户端接口
 * @author: 王贝强
 * @create: 2024-07-12 21:23
 */
@RestController
@RequestMapping("/monitor")
public class ClientController {
    @Resource
    ClientService clientService;

    @Resource
    SystemdSnapshotService systemdSnapshotService;

    @Resource
    SmartSnapshotService smartSnapshotService;

    @Resource
    ProcessSnapshotService processSnapshotService;

    @Resource
    GpuSnapshotService gpuSnapshotService;

    @GetMapping("/register")
    public RestBean<Void> registerClient(@RequestHeader("Authorization") String token){
        return clientService.registerClient(token) ? RestBean.success() :RestBean.failure(401,"客户端注册失败，请检查Token是否正确！");
    }
    @GetMapping("/heartbeat")
    public RestBean<Void> heartbeat(@RequestAttribute(Const.ATTR_CLIENT)Client client){
        clientService.updateHeartbeat(client);
        return RestBean.success();
    }
    @GetMapping("/offline")
    public RestBean<Void> offline(@RequestAttribute(Const.ATTR_CLIENT)Client client){
        clientService.clientOffline(client);
        return RestBean.success();
    }
    @PostMapping("/detail")
    public RestBean<Void> updateClientDetails(@RequestAttribute(Const.ATTR_CLIENT)Client client,
                                             @RequestBody @Valid ClientDetailVO vo){
        clientService.updateClientDetail(vo,client);
        return RestBean.success();
    }
    @PostMapping("/runtime")
    public RestBean<Void> updateRuntimeDetails(@RequestAttribute(Const.ATTR_CLIENT)Client client,
                                               @RequestBody @Valid RuntimeDetailVO vo){
        clientService.updateRuntimeDetail(vo,client);
        return RestBean.success();
    }

    /**
     * 批量上报运行时数据，逐条复用现有服务逻辑处理缓存补报场景。
     *
     * @param client 当前客户端
     * @param batch  运行时数据批次
     * @return 处理结果
     */
    @PostMapping("/runtime/batch")
    public RestBean<Void> updateRuntimeBatch(@RequestAttribute(Const.ATTR_CLIENT) Client client,
                                             @RequestBody List<@Valid RuntimeDetailVO> batch) {
        if (batch == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        this.validateRuntimeBatch(batch);
        for (RuntimeDetailVO vo : batch) {
            clientService.updateRuntimeDetail(vo, client);
        }
        return RestBean.success();
    }

    /**
     * 客户端上报 systemd unit 状态快照。
     *
     * @param client 当前客户端
     * @param vo 快照载荷
     * @return 处理结果
     */
    @PostMapping("/systemd")
    public RestBean<Void> updateSystemdSnapshot(@RequestAttribute(Const.ATTR_CLIENT) Client client,
                                                @RequestBody @Valid SystemdSnapshotVO vo) {
        systemdSnapshotService.ingest(client.getId(), vo);
        return RestBean.success();
    }

    /**
     * 客户端上报 SMART 磁盘健康快照。
     *
     * @param client 当前客户端
     * @param vo 快照载荷
     * @return 处理结果
     */
    @PostMapping("/smart")
    public RestBean<Void> updateSmartSnapshot(@RequestAttribute(Const.ATTR_CLIENT) Client client,
                                              @RequestBody @Valid SmartSnapshotVO vo) {
        smartSnapshotService.ingest(client.getId(), vo);
        return RestBean.success();
    }

    /**
     * 客户端上报进程快照（Top N + watched pattern 状态）。
     *
     * @param client 当前客户端
     * @param vo 快照载荷
     * @return 处理结果
     */
    @PostMapping("/process")
    public RestBean<Void> updateProcessSnapshot(@RequestAttribute(Const.ATTR_CLIENT) Client client,
                                                @RequestBody @Valid ProcessSnapshotVO vo) {
        processSnapshotService.ingest(client.getId(), vo);
        return RestBean.success();
    }

    /**
     * 客户端上报 NVIDIA GPU 快照。
     *
     * @param client 当前客户端
     * @param vo 快照载荷
     * @return 处理结果
     */
    @PostMapping("/gpu")
    public RestBean<Void> updateGpuSnapshot(@RequestAttribute(Const.ATTR_CLIENT) Client client,
                                            @RequestBody @Valid GpuSnapshotVO vo) {
        gpuSnapshotService.ingest(client.getId(), vo);
        return RestBean.success();
    }

    /**
     * 对批量运行时数据执行全量预校验，避免中途失败导致部分数据已写入。
     *
     * @param batch 运行时数据批次
     */
    private void validateRuntimeBatch(List<RuntimeDetailVO> batch) {
        for (RuntimeDetailVO vo : batch) {
            if (Objects.isNull(vo)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体包含空的运行时数据");
            }
        }
    }
}
