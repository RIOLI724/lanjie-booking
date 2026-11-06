package com.lanjie.booking.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanjie.booking.common.ErrorCode;
import com.lanjie.booking.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 未认证时返回 401 + 统一 JSON，而不是跳登录页。
 */
@Component
public class JsonAuthHandler implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JsonAuthHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         org.springframework.security.core.AuthenticationException authException) throws IOException {
        writeUnauthorized(response);
    }

    public void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.fail(ErrorCode.TOKEN_INVALID.getCode(), ErrorCode.TOKEN_INVALID.getMessage())));
    }
}
