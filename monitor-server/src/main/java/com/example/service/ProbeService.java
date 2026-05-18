package com.example.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.example.entity.dto.ProbeTask;
import com.example.entity.vo.request.ProbeTaskCreateVO;
import com.example.entity.vo.request.ProbeTaskUpdateVO;
import com.example.entity.vo.response.ProbeHistoryVO;
import com.example.entity.vo.response.ProbeTaskVO;

import java.util.List;

/**
 * 服务探测任务管理接口。负责：CRUD、敏感字段加密、查询历史。
 *
 * <p>关于 {@code headersEnc} / {@code basicAuthPasswordEnc} 的加密策略：复用 v1.2 OIDC client_secret
 * 的 AES-256-GCM 实现（{@link com.example.utils.CryptoUtils}），与
 * {@code security.ssh.encrypt-key} 共享密钥。
 */
public interface ProbeService extends IService<ProbeTask> {

    /**
     * 列出全部探测任务（admin 视角，含已禁用）；headers / channelIds 由 VO 层处理。
     *
     * @return 探测任务 VO 列表，按 id 倒序
     */
    List<ProbeTaskVO> listAll();

    /**
     * 创建新探测任务。
     *
     * @param vo 创建请求 VO
     * @return 新建任务 VO（含数据库分配的 id）
     */
    ProbeTaskVO create(ProbeTaskCreateVO vo);

    /**
     * 更新探测任务。
     *
     * <p>敏感字段沿用旧密文语义：
     * <ul>
     *   <li>{@code basicAuthPassword=null} 或 {@code "***"} → 保留旧密文；</li>
     *   <li>{@code basicAuthPassword=""} → 清空；</li>
     *   <li>{@code headers=null} → 保留旧密文；</li>
     *   <li>{@code headers=空Map} → 清空；</li>
     *   <li>{@code headers} 中 value 为 {@code "***"} → 用旧值回填（前端编辑时未改的占位）；</li>
     * </ul>
     *
     * @param id 任务 ID
     * @param vo 更新请求 VO
     * @return 更新后任务 VO
     */
    ProbeTaskVO update(Long id, ProbeTaskUpdateVO vo);

    /**
     * 删除探测任务（不删历史；30 天清理 job 会自然清除）。
     *
     * @param id 任务 ID
     * @return 已删除返回 true，未找到返回 false
     */
    boolean delete(Long id);

    /**
     * 分页查询单个任务的执行历史，按 executed_at 倒序。
     *
     * @param taskId 任务 ID
     * @param page   页码（>=1）
     * @param size   每页大小（1~200）
     * @return 分页结果
     */
    IPage<ProbeHistoryVO> listHistory(Long taskId, int page, int size);

    /**
     * 解密任务的 headers_enc 列；返回明文 Map。
     * 用于 ProbeScheduler / HttpProbeExecutor 实际发起请求时构建 Header。
     *
     * @param task 探测任务实体
     * @return 解密后的 Map，{@code null} / 解析失败时返回空 Map
     */
    java.util.Map<String, String> resolveHeaders(ProbeTask task);

    /**
     * 解密任务的 Basic Auth 密码。
     *
     * @param task 探测任务实体
     * @return 明文密码；未配置或解密失败时返回 null
     */
    String resolveBasicAuthPassword(ProbeTask task);

    /**
     * 解析 channel_ids 列（VARCHAR 中存的 JSON 数组字符串）。
     *
     * @param task 探测任务实体
     * @return 通道 ID 列表，空字符串返回空 List
     */
    List<Long> resolveChannelIds(ProbeTask task);
}
