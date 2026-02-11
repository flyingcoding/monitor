package com.example.controller;

import com.example.entity.RestBean;
import com.example.entity.dto.Client;
import com.example.entity.vo.request.ClientDetailVO;
import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.service.ClientService;
import com.example.utils.Const;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

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
}
