package com.lanjie.booking.security;

import com.lanjie.booking.common.ErrorCode;
import com.lanjie.booking.entity.User;
import com.lanjie.booking.enums.UserStatus;
import com.lanjie.booking.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Bearer 令牌认证。不是 Spring Bean，避免被 Servlet 容器重复注册，见 SecurityConfig。
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;
    private final JsonAuthHandler authHandler;

    public JwtAuthFilter(JwtUtil jwtUtil, UserMapper userMapper, JsonAuthHandler authHandler) {
        this.jwtUtil = jwtUtil;
        this.userMapper = userMapper;
        this.authHandler = authHandler;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }
        try {
            Claims claims = jwtUtil.parse(header.substring(7));
            User user = userMapper.selectById(Long.valueOf(claims.getSubject()));
            if (user == null || user.getStatus() != UserStatus.ACTIVE) {
                authHandler.writeUnauthorized(response);
                return;
            }
            LoginUser principal = new LoginUser(user.getId(), user.getPhone(), user.getRole().name());
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            chain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException e) {
            authHandler.writeUnauthorized(response);
        }
    }
}
