package com.example.filter;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * RequestLogFilter 字段脱敏单测（v1.2 PRD R31 / AC14）。
 */
class RequestLogFilterRedactTest {

    @Test
    void shouldRedactSensitiveTopLevelKeys() {
        JSONObject input = new JSONObject();
        input.put("username", "alice");
        input.put("password", "p@ss");
        input.put("Authorization", "Bearer xyz");
        input.put("apiToken", "mtk_aaaa1234");
        input.put("recovery_code", "987654");
        input.put("clientSecret", "shhh");
        input.put("hmac_key", "raw-bytes");

        JSONObject out = RequestLogFilter.redact(input);

        Assertions.assertEquals("alice", out.get("username"));
        Assertions.assertEquals("***", out.get("password"));
        Assertions.assertEquals("***", out.get("Authorization"));
        Assertions.assertEquals("***", out.get("apiToken"));
        Assertions.assertEquals("***", out.get("recovery_code"));
        Assertions.assertEquals("***", out.get("clientSecret"));
        Assertions.assertEquals("***", out.get("hmac_key"));
    }

    @Test
    void shouldRedactNestedJsonObject() {
        JSONObject nested = new JSONObject();
        nested.put("name", "ci");
        nested.put("secret", "leak-me");

        JSONObject input = new JSONObject();
        input.put("payload", nested);

        JSONObject out = RequestLogFilter.redact(input);
        JSONObject masked = (JSONObject) out.get("payload");
        Assertions.assertEquals("ci", masked.get("name"));
        Assertions.assertEquals("***", masked.get("secret"));
    }

    @Test
    void shouldRedactWithinJsonArray() {
        JSONArray arr = new JSONArray();
        JSONObject row = new JSONObject();
        row.put("user", "bob");
        row.put("password", "hidden");
        arr.add(row);

        JSONObject input = new JSONObject();
        input.put("list", arr);

        JSONObject out = RequestLogFilter.redact(input);
        JSONArray outArr = (JSONArray) out.get("list");
        JSONObject outRow = outArr.getJSONObject(0);
        Assertions.assertEquals("bob", outRow.get("user"));
        Assertions.assertEquals("***", outRow.get("password"));
    }

    @Test
    void redactingNullInputReturnsNull() {
        Assertions.assertNull(RequestLogFilter.redact(null));
    }

    @Test
    void sensitiveKeyDetectionIsCaseInsensitiveAndSubstring() {
        Assertions.assertTrue(RequestLogFilter.isSensitiveKey("Password"));
        Assertions.assertTrue(RequestLogFilter.isSensitiveKey("OLD_PASSWORD"));
        Assertions.assertTrue(RequestLogFilter.isSensitiveKey("api_token"));
        Assertions.assertTrue(RequestLogFilter.isSensitiveKey("clientSecret"));
        Assertions.assertTrue(RequestLogFilter.isSensitiveKey("verification-code"));
        Assertions.assertTrue(RequestLogFilter.isSensitiveKey("HMAC_KEY"));
        Assertions.assertTrue(RequestLogFilter.isSensitiveKey("authorization"));
        Assertions.assertFalse(RequestLogFilter.isSensitiveKey("username"));
        Assertions.assertFalse(RequestLogFilter.isSensitiveKey("email"));
        Assertions.assertFalse(RequestLogFilter.isSensitiveKey("name"));
        Assertions.assertFalse(RequestLogFilter.isSensitiveKey(""));
        Assertions.assertFalse(RequestLogFilter.isSensitiveKey(null));
    }

    /**
     * P1-1：JSON 响应体的 {@code token} 字段必须被脱敏；普通字段照常透传。
     */
    @Test
    void shouldRedactSensitiveResponseBodyForJsonContent() {
        String raw = "{\"token\":\"mtk_abcd1234567890\",\"other\":\"keep\"}";
        String redacted = RequestLogFilter.redactJsonString(raw, "application/json");
        Assertions.assertTrue(redacted.contains("\"token\":\"***\""),
                "token 字段必须被脱敏，实际：" + redacted);
        Assertions.assertTrue(redacted.contains("\"other\":\"keep\""),
                "普通字段必须保留，实际：" + redacted);
        Assertions.assertFalse(redacted.contains("mtk_abcd"),
                "原文中的 token 明文不得出现在日志中，实际：" + redacted);
    }

    /**
     * P1-1：非 JSON 响应（HTML 错误页 / 纯文本）原样输出，避免误伤可读性。
     */
    @Test
    void shouldPassThroughNonJsonResponseBodyUnchanged() {
        String htmlBody = "<html><body>Oops</body></html>";
        Assertions.assertEquals(htmlBody,
                RequestLogFilter.redactJsonString(htmlBody, "text/html;charset=UTF-8"),
                "非 JSON 响应必须原样返回");
        Assertions.assertEquals("hello world",
                RequestLogFilter.redactJsonString("hello world", null),
                "Content-Type 为 null 时按非 JSON 处理，原样返回");
    }

    /**
     * P1-1：解析失败的 JSON 内容（charset 损坏 / 残缺）必须回退原文，不抛异常打断日志记录。
     */
    @Test
    void shouldFallbackToRawForMalformedJsonContent() {
        String broken = "{this is not valid json";
        String result = RequestLogFilter.redactJsonString(broken, "application/json");
        Assertions.assertEquals(broken, result, "残缺 JSON 必须回退原文");
    }

    /**
     * P1-1：JSON 数组顶层也覆盖（响应体可以是数组）。
     */
    @Test
    void shouldRedactJsonArrayResponseBody() {
        String raw = "[{\"name\":\"a\",\"token\":\"mtk_xxx\"},{\"name\":\"b\",\"token\":\"mtk_yyy\"}]";
        String redacted = RequestLogFilter.redactJsonString(raw, "application/json");
        Assertions.assertFalse(redacted.contains("mtk_xxx"));
        Assertions.assertFalse(redacted.contains("mtk_yyy"));
        Assertions.assertTrue(redacted.contains("\"name\":\"a\""));
    }
}
