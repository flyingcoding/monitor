package com.example.utils;

/**
 * 一些常量字符串整合
 */
public final class Const {
    //JWT令牌
    public final static String JWT_BLACK_LIST = "jwt:blacklist:";
    public final static String JWT_FREQUENCY = "jwt:frequency:";
    //用户
    public final static String USER_BLACK_LIST="user:blacklist:";
    //请求频率限制
    public final static String FLOW_LIMIT_COUNTER = "flow:counter:";
    public final static String FLOW_LIMIT_BLOCK = "flow:block:";
    //邮件验证码
    public final static String VERIFY_EMAIL_LIMIT = "verify:email:limit:";
    public final static String VERIFY_EMAIL_DATA = "verify:email:data:";
    //过滤器优先级
    public final static int ORDER_FLOW_LIMIT = -101;
    public final static int ORDER_CORS = -102;
    //请求自定义属性
    public final static String ATTR_USER_ID = "userId";
    public final static String ATTR_CLIENT = "client";
    public final static String ATTR_USER_ROLE = "userRole";
    public final static String ATTR_API_TOKEN = "attr.api_token";
    public final static String ATTR_AUTH_METHOD = "attr.auth_method";
    //鉴权方式标识（写入 ATTR_AUTH_METHOD），便于日志与下游分流
    public final static String AUTH_METHOD_JWT = "jwt";
    public final static String AUTH_METHOD_API_TOKEN = "api_token";
    public final static String AUTH_METHOD_OIDC = "oidc";
    //API Token 前缀（HMAC-SHA256，独立 HMAC 密钥，详见 prd.md D5）
    public final static String API_TOKEN_PREFIX = "mtk_";
    //消息队列
    public final static String MQ_MAIL = "mail";
    public final static String MQ_NOTIFICATION = "notification";
    //用户角色
    public final static String ROLE_DEFAULT = "user";
    public final static String ROLE_ADMIN = "admin";

}
