package org.monitorclient.entity;

import com.alibaba.fastjson2.JSONObject;

/**
 * @program: monitor
 * @description: 服务端请求响应类
 * @author: 王贝强
 * @create: 2024-07-14 18:39
 */
public record Response(int id,int code,Object data,String message) {
    /** Accepts transport failures without assigning an agent identifier. */
    public Response(int code, Object data, String message) { this(0, code, data, message); }

    /** Requires an explicit status while preserving the original code=200 protocol. */
    public static Response parse(String body) {
        JSONObject json = JSONObject.parseObject(body);
        if (json == null || !json.containsKey("code") || json.get("code") == null) {
            throw new IllegalArgumentException("Server response is missing code");
        }
        return new Response(json.getIntValue("id"), json.getIntValue("code"), json.get("data"), json.getString("message"));
    }

    public boolean success(){
        return code==200;
    }
    public JSONObject asJSON(){
        return JSONObject.from(data);
    }
    public String asString(){
        return data.toString();
    }
    public static Response errorResponse(Exception e){
        return new Response(0,500,null,e.getMessage());
    }
}
