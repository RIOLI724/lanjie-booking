package com.lanjie.booking.integration;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注册登录全链路（发码 → 注册 → 我的信息 → 密码登录 → 刷新轮换 → 登出）。
 * 依赖本机 MySQL/Redis（CI 中由服务容器提供），验证码为 dev 固定值 123456。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthFlowTest {

    private static final String DEV_CODE = "123456";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void fullAuthFlow() {
        String phone = "139" + String.format("%08d", Math.abs(System.nanoTime()) % 100_000_000);

        ResponseEntity<String> send = post("/api/v1/auth/sms-code",
                Map.of("phone", phone, "scene", "REGISTER"), null);
        assertEquals(0, code(send));

        ResponseEntity<String> register = post("/api/v1/auth/register",
                Map.of("phone", phone, "code", DEV_CODE, "password", "abc12345", "nickname", "测试球友"), null);
        assertEquals(0, code(register));
        String accessToken = JsonPath.read(register.getBody(), "$.data.accessToken");
        String refreshToken = JsonPath.read(register.getBody(), "$.data.refreshToken");

        ResponseEntity<String> me = get("/api/v1/me", accessToken);
        assertEquals(0, code(me));
        assertEquals("测试球友", JsonPath.read(me.getBody(), "$.data.nickname"));

        ResponseEntity<String> login = post("/api/v1/auth/login",
                Map.of("grantType", "PASSWORD", "phone", phone, "password", "abc12345"), null);
        assertEquals(0, code(login));

        ResponseEntity<String> refresh = post("/api/v1/auth/refresh", Map.of("refreshToken", refreshToken), null);
        assertEquals(0, code(refresh));
        String newRefresh = JsonPath.read(refresh.getBody(), "$.data.refreshToken");
        assertNotEquals(refreshToken, newRefresh);

        // 旧 refresh 已轮换作废
        ResponseEntity<String> replay = post("/api/v1/auth/refresh", Map.of("refreshToken", refreshToken), null);
        assertNotEquals(0, code(replay));

        ResponseEntity<String> logout = post("/api/v1/auth/logout", Map.of("refreshToken", newRefresh), null);
        assertEquals(0, code(logout));
        ResponseEntity<String> afterLogout = post("/api/v1/auth/refresh", Map.of("refreshToken", newRefresh), null);
        assertNotEquals(0, code(afterLogout));

        // 未带 token 访问受保护接口 → 401
        ResponseEntity<String> anonymous = get("/api/v1/me", null);
        assertEquals(401, anonymous.getStatusCode().value());
    }

    private int code(ResponseEntity<String> response) {
        assertTrue(response.getStatusCode().is2xxSuccessful() || response.getStatusCode().value() == 401,
                () -> "unexpected status " + response.getStatusCode());
        return (int) JsonPath.read(response.getBody(), "$.code");
    }

    private ResponseEntity<String> post(String path, Map<String, ?> body, String token) {
        return exchange(path, HttpMethod.POST, body, token);
    }

    private ResponseEntity<String> get(String path, String token) {
        return exchange(path, HttpMethod.GET, null, token);
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, Map<String, ?> body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }
}
