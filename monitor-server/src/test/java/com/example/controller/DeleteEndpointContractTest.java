package com.example.controller;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;

/**
 * 破坏性接口 HTTP 方法合同测试。
 *
 * <p>删除操作必须使用 DELETE，避免浏览器预取、链接扫描或 readonly Token 把 GET 当成写操作调用。
 */
class DeleteEndpointContractTest {

    /**
     * 验证主机和子账户删除接口均为 DELETE，并保持约定的路径变量格式。
     */
    @Test
    void destructiveEndpointsShouldUseDeleteMapping() throws NoSuchMethodException {
        Method deleteClient = MonitorController.class.getDeclaredMethod("deleteClient", int.class, String.class);
        DeleteMapping clientMapping = deleteClient.getAnnotation(DeleteMapping.class);
        Assertions.assertNotNull(clientMapping);
        Assertions.assertArrayEquals(new String[]{"/{clientId}"}, clientMapping.value());
        Assertions.assertNull(deleteClient.getAnnotation(GetMapping.class));

        Method deleteSubAccount = UserController.class.getDeclaredMethod(
                "deleteSubAccount", HttpServletRequest.class, int.class, int.class, String.class);
        DeleteMapping subAccountMapping = deleteSubAccount.getAnnotation(DeleteMapping.class);
        Assertions.assertNotNull(subAccountMapping);
        Assertions.assertArrayEquals(new String[]{"/sub/{uid}"}, subAccountMapping.value());
        Assertions.assertNull(deleteSubAccount.getAnnotation(GetMapping.class));
    }
}
