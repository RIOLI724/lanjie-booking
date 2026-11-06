package com.lanjie.booking.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtUtilTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private JwtProperties properties(int accessTtlMinutes) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setIssuer("lanjie");
        properties.setAccessTtlMinutes(accessTtlMinutes);
        properties.setRefreshTtlDays(7);
        return properties;
    }

    @Test
    void createAndParseRoundTrip() {
        JwtUtil jwtUtil = new JwtUtil(properties(30));
        String token = jwtUtil.createAccessToken(42L, "CUSTOMER", "13800001234");
        Claims claims = jwtUtil.parse(token);
        assertEquals("42", claims.getSubject());
        assertEquals("CUSTOMER", claims.get("role", String.class));
        assertEquals("13800001234", claims.get("phone", String.class));
        assertEquals(30 * 60, jwtUtil.getAccessTtl().toSeconds());
    }

    @Test
    void expiredTokenRejected() {
        JwtUtil jwtUtil = new JwtUtil(properties(-1));
        String token = jwtUtil.createAccessToken(1L, "CUSTOMER", "13800001234");
        assertThrows(ExpiredJwtException.class, () -> jwtUtil.parse(token));
    }

    @Test
    void tamperedTokenRejected() {
        JwtUtil jwtUtil = new JwtUtil(properties(30));
        String token = jwtUtil.createAccessToken(1L, "CUSTOMER", "13800001234");
        assertThrows(JwtException.class, () -> jwtUtil.parse(token + "x"));
    }

    @Test
    void foreignIssuerRejected() {
        // 解析器要求 issuer 匹配，签发方 issuer 不同即拒绝
        JwtUtil local = new JwtUtil(properties(30));
        JwtProperties otherProps = properties(30);
        otherProps.setIssuer("someone-else");
        JwtUtil otherIssuer = new JwtUtil(otherProps);
        String token = otherIssuer.createAccessToken(1L, "CUSTOMER", "13800001234");
        assertThrows(JwtException.class, () -> local.parse(token));
    }
}
