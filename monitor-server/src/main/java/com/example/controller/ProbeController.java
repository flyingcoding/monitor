package com.example.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.entity.RestBean;
import com.example.entity.vo.request.ProbeTaskCreateVO;
import com.example.entity.vo.request.ProbeTaskUpdateVO;
import com.example.entity.vo.response.ProbeHistoryVO;
import com.example.entity.vo.response.ProbeTaskVO;
import com.example.service.PermissionService;
import com.example.service.ProbeService;
import com.example.utils.Const;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 服务探测任务管理 API（仅管理员）。
 *
 * <p>与 {@code OidcProviderController} 同款 admin 鉴权模式：
 * <ul>
 *   <li>每个端点首行检查 {@link PermissionService#isAdmin(String)}；</li>
 *   <li>拒绝 API Token 鉴权的请求；</li>
 *   <li>不使用 {@code @PreAuthorize}。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/probes")
public class ProbeController {

    @Resource
    private ProbeService probeService;

    @Resource
    private PermissionService permissionService;

    /**
     * 列出全部探测任务。
     *
     * @param request  HTTP 请求（用于读取鉴权方式）
     * @param userRole 当前用户角色
     * @return 探测任务列表
     */
    @GetMapping
    public RestBean<List<ProbeTaskVO>> list(HttpServletRequest request,
                                            @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (isApiTokenAuth(request)) {
            return RestBean.forbidden("不允许通过 API Token 管理探测任务");
        }
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        return RestBean.success(probeService.listAll());
    }

    /**
     * 创建探测任务。
     */
    @PostMapping
    public RestBean<ProbeTaskVO> create(HttpServletRequest request,
                                        @RequestBody @Valid ProbeTaskCreateVO vo,
                                        @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (isApiTokenAuth(request)) {
            return RestBean.forbidden("不允许通过 API Token 管理探测任务");
        }
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        return RestBean.success(probeService.create(vo));
    }

    /**
     * 更新探测任务。
     */
    @PutMapping("/{id}")
    public RestBean<ProbeTaskVO> update(HttpServletRequest request,
                                        @PathVariable Long id,
                                        @RequestBody @Valid ProbeTaskUpdateVO vo,
                                        @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (isApiTokenAuth(request)) {
            return RestBean.forbidden("不允许通过 API Token 管理探测任务");
        }
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        return RestBean.success(probeService.update(id, vo));
    }

    /**
     * 删除探测任务。
     */
    @DeleteMapping("/{id}")
    public RestBean<Void> delete(HttpServletRequest request,
                                 @PathVariable Long id,
                                 @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (isApiTokenAuth(request)) {
            return RestBean.forbidden("不允许通过 API Token 管理探测任务");
        }
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        if (!probeService.delete(id)) {
            return RestBean.failure(404, "探测任务不存在");
        }
        return RestBean.success();
    }

    /**
     * 分页查询任务执行历史。
     *
     * @param id   任务 ID
     * @param page 页码（默认 1）
     * @param size 每页大小（默认 20，最大 200）
     */
    @GetMapping("/{id}/history")
    public RestBean<ProbeHistoryPageVO> history(HttpServletRequest request,
                                                @PathVariable Long id,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int size,
                                                @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (isApiTokenAuth(request)) {
            return RestBean.forbidden("不允许通过 API Token 管理探测任务");
        }
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        if (page < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page 必须 >= 1");
        }
        if (size < 1 || size > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size 必须在 1~200 之间");
        }
        IPage<ProbeHistoryVO> result = probeService.listHistory(id, page, size);
        ProbeHistoryPageVO body = new ProbeHistoryPageVO();
        body.setRecords(result.getRecords());
        body.setTotal(result.getTotal());
        body.setPage(result.getCurrent());
        body.setSize(result.getSize());
        return RestBean.success(body);
    }

    /**
     * 判断当前请求是否走 API Token 鉴权。
     */
    private boolean isApiTokenAuth(HttpServletRequest request) {
        Object method = request.getAttribute(Const.ATTR_AUTH_METHOD);
        return Const.AUTH_METHOD_API_TOKEN.equals(method);
    }

    /**
     * 历史分页结果 VO。
     */
    @Data
    public static class ProbeHistoryPageVO {
        private List<ProbeHistoryVO> records;
        private long total;
        private long page;
        private long size;
    }
}
