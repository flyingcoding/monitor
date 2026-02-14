package org.monitorclient.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.monitorclient.entity.BaseDetail;
import org.monitorclient.entity.ConnectionConfig;
import org.monitorclient.entity.Response;
import org.monitorclient.entity.RuntimeDetail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

@Slf4j
public class NetUtils {

    private final HttpClient client = HttpClient.newHttpClient();
    private volatile ConnectionConfig config;

    /**
     * 设置运行期连接配置。
     *
     * @param config 连接配置
     */
    public void setConfig(ConnectionConfig config) {
        this.config = config;
    }

    /**
     * 使用指定地址和 token 进行客户端注册。
     *
     * @param address 服务端地址
     * @param token 注册 token
     * @return 是否注册成功
     */
    public boolean registerToServer(String address, String token) {
        log.info("正在向服务端注册，请稍等...");
        Response response = this.doGet("/register", address, token);
        if (response.success()) {
            log.info("客户端注册已完成");
        } else {
            log.error("客户端注册失败：{}", response.message());
        }
        return response.success();
    }

    /**
     * 上报基础静态信息。
     *
     * @param detail 基础信息
     */
    public void updateBaseDetails(BaseDetail detail) {
        Response response = this.doPost("/detail", detail);
        if (response.success()) {
            log.info("系统基本信息更新完成");
        } else {
            log.error("系统基本信息更新失败：{}", response.message());
        }
    }

    /**
     * 发送心跳包并执行快速重试，降低瞬时网络抖动带来的误判。
     */
    public void sendHeartbeat() {
        for (int i = 0; i < 3; i++) {
            Response response = this.doGet("/heartbeat");
            if (response.success()) {
                log.debug("心跳发送成功");
                return;
            }
            log.warn("心跳发送失败（第{}次）：{}", i + 1, response.message());
            if (i < 2) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * 通知服务端当前客户端即将下线。
     */
    public void notifyShutdown() {
        log.info("正在通知服务端客户端即将下线...");
        Response response = this.doGet("/offline");
        if (response.success()) {
            log.info("已通知服务端客户端下线");
        } else {
            log.warn("通知服务端下线失败：{}", response.message());
        }
    }

    /**
     * 上报运行时数据；上报失败时执行指数退避重试，最终失败则写入本地缓存。
     *
     * @param detail 运行时监控数据
     */
    public void updateRuntimeDetails(RuntimeDetail detail) {
        try {
            RetryUtils.retryWithBackoff(() -> {
                Response response = this.doPost("/runtime", detail);
                if (!response.success()) {
                    String message = response.message() == null ? "未知错误" : response.message();
                    throw new RuntimeException(message);
                }
                return response;
            }, "上报运行时数据");
            flushCachedData();
        } catch (Exception e) {
            log.warn("上报运行时数据异常，缓存到本地: {}", e.getMessage());
            LocalCacheUtils.offer(detail);
        }
    }

    /**
     * 按批次补报本地缓存数据，失败时回滚未发送的同批次数据，避免缓存数据丢失。
     */
    public void flushCachedData() {
        if (LocalCacheUtils.isEmpty()) {
            return;
        }
        log.info("开始补报缓存数据，当前缓存数量：{}", LocalCacheUtils.size());
        while (!LocalCacheUtils.isEmpty()) {
            List<RuntimeDetail> batch = LocalCacheUtils.drainBatch();
            if (batch.isEmpty()) {
                break;
            }
            if (!this.sendRuntimeBatch(batch)) {
                log.warn("批量补报失败，回退单条补报。");
                int failedIndex = this.sendRuntimeOneByOne(batch);
                if (failedIndex >= 0) {
                    LocalCacheUtils.requeueUnsentBatch(batch, failedIndex);
                    return;
                }
            }
            if (!LocalCacheUtils.isEmpty()) {
                try {
                    Thread.sleep(LocalCacheUtils.getFlushBatchIntervalMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.info("缓存数据补报完成");
    }

    /**
     * 尝试通过批量接口一次性补报一个批次的数据。
     *
     * @param batch 待补报批次
     * @return 批量补报是否成功
     */
    private boolean sendRuntimeBatch(List<RuntimeDetail> batch) {
        try {
            Response response = this.doPost("/runtime/batch", batch);
            if (!response.success()) {
                log.warn("批量补报返回失败：{}", response.message());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("批量补报异常：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 逐条补报当前批次数据，用于批量接口不可用时的兼容回退。
     *
     * @param batch 待补报批次
     * @return 失败条目的下标；全部成功则返回 -1
     */
    private int sendRuntimeOneByOne(List<RuntimeDetail> batch) {
        for (int i = 0; i < batch.size(); i++) {
            RuntimeDetail cached = batch.get(i);
            try {
                Response response = this.doPost("/runtime", cached);
                if (!response.success()) {
                    log.warn("单条补报失败：{}", response.message());
                    return i;
                }
            } catch (Exception e) {
                log.warn("单条补报异常：{}", e.getMessage());
                return i;
            }
        }
        return -1;
    }

    /**
     * 使用当前配置发起 GET 请求。
     *
     * @param url 接口路径
     * @return 标准响应对象
     */
    private Response doGet(String url) {
        ConnectionConfig current = this.config;
        if (current == null) {
            return Response.errorResponse(new IllegalStateException("未设置连接配置"));
        }
        return this.doGet(url, current.getAddress(), current.getToken());
    }

    /**
     * 发起指定地址和 token 的 GET 请求。
     *
     * @param url 接口路径
     * @param address 服务端地址
     * @param token 鉴权 token
     * @return 标准响应对象
     */
    private Response doGet(String url, String address, String token) {
        try {
            HttpRequest request = HttpRequest.newBuilder().GET()
                    .uri(new URI(address + "/monitor" + url))
                    .header("Authorization", token)
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return JSONObject.parseObject(response.body()).to(Response.class);
        } catch (Exception e) {
            log.error("向服务端发起GET请求出现问题", e);
            return Response.errorResponse(e);
        }
    }

    /**
     * 使用当前配置发起 POST 请求。
     *
     * @param url 接口路径
     * @param data 请求体
     * @return 标准响应对象
     */
    private Response doPost(String url, Object data) {
        ConnectionConfig current = this.config;
        if (current == null) {
            return Response.errorResponse(new IllegalStateException("未设置连接配置"));
        }
        try {
            String rawData = this.serializeRequestBody(data);
            HttpRequest request = HttpRequest.newBuilder().POST(HttpRequest.BodyPublishers.ofString(rawData))
                    .uri(new URI(current.getAddress() + "/monitor" + url))
                    .header("Authorization", current.getToken())
                    .header("Content-Type", "application/json")
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return JSONObject.parseObject(response.body()).to(Response.class);
        } catch (Exception e) {
            log.error("向服务端发起POST请求出现问题", e);
            return Response.errorResponse(e);
        }
    }

    /**
     * 将请求体对象序列化为 JSON 字符串，兼容普通对象与集合类型。
     *
     * @param data 请求体对象
     * @return JSON 字符串
     */
    private String serializeRequestBody(Object data) {
        return JSON.toJSONString(data);
    }
}
