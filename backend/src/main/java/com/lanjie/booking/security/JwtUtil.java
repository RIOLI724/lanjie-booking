package com.lanjie.booking.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * 访问令牌（JWT，30 分钟，无状态）。刷新令牌是随机数、哈希落库，见 {@link com.lanjie.booking.service.AuthService}。
 */
@Component
public class JwtUtil {

    private final SecretKey key;
    private final String issuer;
    private final Duration accessTtl;

    public JwtUtil(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
        this.issuer = properties.getIssuer();
        this.accessTtl = Duration.ofMinutes(properties.getAccessTtlMinutes());
    }

    public String createAccessToken(Long userId, String role, String phone) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(String.valueOf(userId))
                .claim("role", role)
                .claim("phone", phone)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(key)
                .compact();
    }

    /** 解析并校验签名、有效期与签发者；失败抛 io.jsonwebtoken.JwtException */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Duration getAccessTtl() {
        return accessTtl;
    }
}
