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
}
