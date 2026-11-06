package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.common.BusinessException;
import com.lanjie.booking.common.ErrorCode;
import com.lanjie.booking.dto.GrantType;
import com.lanjie.booking.dto.LoginRequest;
import com.lanjie.booking.dto.LoginVO;
import com.lanjie.booking.dto.MeVO;
import com.lanjie.booking.dto.RegisterRequest;
import com.lanjie.booking.dto.UpdateMeRequest;
import com.lanjie.booking.entity.User;
import com.lanjie.booking.entity.UserSession;
import com.lanjie.booking.enums.SmsScene;
import com.lanjie.booking.enums.UserRole;
import com.lanjie.booking.enums.UserStatus;
import com.lanjie.booking.mapper.UserMapper;
import com.lanjie.booking.mapper.UserSessionMapper;
import com.lanjie.booking.security.JwtProperties;
import com.lanjie.booking.security.JwtUtil;
import com.lanjie.booking.security.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * 注册 / 登录（密码、验证码双方式）/ 刷新轮换 / 登出 / 我的信息。
 */
@Slf4j
@Service
public class AuthService {

    private static final int REFRESH_TOKEN_BYTES = 32;

    private final UserMapper userMapper;
    private final UserSessionMapper userSessionMapper;
    private final SmsCodeService smsCodeService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final JwtProperties jwtProperties;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserMapper userMapper, UserSessionMapper userSessionMapper,
                       SmsCodeService smsCodeService, PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil, JwtProperties jwtProperties) {
        this.userMapper = userMapper;
        this.userSessionMapper = userSessionMapper;
        this.smsCodeService = smsCodeService;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.jwtProperties = jwtProperties;
    }

    @Transactional
    public LoginVO register(RegisterRequest request) {
        smsCodeService.verify(request.getPhone(), SmsScene.REGISTER, request.getCode());
        if (findByPhone(request.getPhone()) != null) {
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS);
        }
        User user = new User();
        user.setPhone(request.getPhone());
        user.setRole(UserRole.CUSTOMER);
        user.setStatus(UserStatus.ACTIVE);
        user.setNickname(defaultNickname(request.getPhone(), request.getNickname()));
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        }
        user.setCumulativeRechargeCents(0L);
        userMapper.insert(user);
        return issueTokens(user, null);
    }

    @Transactional
    public LoginVO login(LoginRequest request) {
        User user = findByPhone(request.getPhone());
        if (request.getGrantType() == GrantType.PASSWORD) {
            if (user == null || user.getPasswordHash() == null
                    || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
                throw new BusinessException(ErrorCode.USER_NOT_FOUND);
            }
        } else {
            smsCodeService.verify(request.getPhone(), SmsScene.LOGIN, request.getCode());
            // 验证码登录：账号不存在则自动注册
            if (user == null) {
                user = new User();
                user.setPhone(request.getPhone());
                user.setRole(UserRole.CUSTOMER);
                user.setStatus(UserStatus.ACTIVE);
                user.setNickname(defaultNickname(request.getPhone()));
                user.setCumulativeRechargeCents(0L);
                userMapper.insert(user);
            }
        }
        if (user.getStatus() == UserStatus.FROZEN) {
            throw new BusinessException(ErrorCode.USER_FROZEN);
        }
        return issueTokens(user, null);
    }

    @Transactional
    public LoginVO refresh(String refreshToken) {
        UserSession session = findActiveSession(refreshToken);
        User user = userMapper.selectById(session.getUserId());
        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        // 轮换：旧 refresh token 立即作废
        session.setRevokedAt(LocalDateTime.now());
        userSessionMapper.updateById(session);
        return issueTokens(user, session.getDevice());
    }

    @Transactional
    public void logout(String refreshToken) {
        UserSession session = findActiveSession(refreshToken);
        session.setRevokedAt(LocalDateTime.now());
        userSessionMapper.updateById(session);
    }

    public MeVO me(LoginUser loginUser) {
        User user = userMapper.selectById(loginUser.userId());
        if (user == null) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        return toMeVO(user);
    }

    @Transactional
    public MeVO updateMe(LoginUser loginUser, UpdateMeRequest request) {
        User user = userMapper.selectById(loginUser.userId());
        if (user == null) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        User changes = new User();
        changes.setId(user.getId());
        if (request.getNickname() != null && !request.getNickname().isBlank()) {
            changes.setNickname(request.getNickname().trim());
        }
        if (request.getAvatarUrl() != null && !request.getAvatarUrl().isBlank()) {
            changes.setAvatarUrl(request.getAvatarUrl());
        }
        userMapper.updateById(changes);
        return toMeVO(userMapper.selectById(user.getId()));
    }

    private LoginVO issueTokens(User user, String device) {
        String accessToken = jwtUtil.createAccessToken(user.getId(), user.getRole().name(), user.getPhone());
        String refreshToken = newRefreshToken();
        UserSession session = new UserSession();
        session.setUserId(user.getId());
        session.setRefreshTokenHash(SmsCodeService.sha256(refreshToken));
        session.setDevice(device);
        session.setExpiresAt(LocalDateTime.now().plusDays(jwtProperties.getRefreshTtlDays()));
        userSessionMapper.insert(session);
        return new LoginVO(accessToken, refreshToken, "Bearer", jwtUtil.getAccessTtl().toSeconds());
    }

    private UserSession findActiveSession(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        UserSession session = userSessionMapper.selectOne(new LambdaQueryWrapper<UserSession>()
                .eq(UserSession::getRefreshTokenHash, SmsCodeService.sha256(refreshToken))
                .last("LIMIT 1"));
        if (session == null || session.getRevokedAt() != null
                || session.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
        return session;
    }

    private User findByPhone(String phone) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone).last("LIMIT 1"));
    }

    private String defaultNickname(String phone) {
        return "球友" + phone.substring(phone.length() - 4);
    }

    private String defaultNickname(String phone, String provided) {
        return provided != null && !provided.isBlank() ? provided.trim() : defaultNickname(phone);
    }

    private String newRefreshToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private MeVO toMeVO(User user) {
        String masked = user.getPhone().substring(0, 3) + "****" + user.getPhone().substring(7);
        return new MeVO(user.getId(), user.getNickname(), masked, user.getAvatarUrl(), user.getRole().name());
    }
}
