package com.lanjie.booking.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    private static final String PHONE = "13800001234";

    @Mock
    private UserMapper userMapper;
    @Mock
    private UserSessionMapper userSessionMapper;
    @Mock
    private SmsCodeService smsCodeService;

    private AuthService service;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        properties.setIssuer("lanjie");
        properties.setAccessTtlMinutes(30);
        properties.setRefreshTtlDays(7);
        service = new AuthService(userMapper, userSessionMapper, smsCodeService,
                new BCryptPasswordEncoder(), new JwtUtil(properties), properties);
        doAnswer(invocation -> {
            Object arg = invocation.getArgument(0);
            if (arg instanceof User user) {
                user.setId(1L);
            } else if (arg instanceof UserSession session) {
                session.setId(100L);
            }
            return 1;
        }).when(userMapper).insert(any(User.class));
        doAnswer(invocation -> 1).when(userSessionMapper).insert(any(UserSession.class));
    }

    @Test
    void registerCreatesCustomerWithDefaultNickname() {
        when(userMapper.selectOne(any())).thenReturn(null);
        RegisterRequest request = new RegisterRequest();
        request.setPhone(PHONE);
        request.setCode("123456");
        LoginVO loginVO = service.register(request);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        User saved = captor.getValue();
        assertEquals(UserRole.CUSTOMER, saved.getRole());
        assertEquals("球友1234", saved.getNickname());
        assertNull(saved.getPasswordHash());
        assertNotNull(loginVO.accessToken());
        assertFalse(loginVO.refreshToken().isBlank());
        verify(smsCodeService).verify(PHONE, SmsScene.REGISTER, "123456");
    }

    @Test
    void registerWithPasswordStoresHash() {
        when(userMapper.selectOne(any())).thenReturn(null);
        RegisterRequest request = new RegisterRequest();
        request.setPhone(PHONE);
        request.setCode("123456");
        request.setPassword("abc12345");
        service.register(request);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertTrue(captor.getValue().getPasswordHash() != null);
    }

    @Test
    void registerRejectsDuplicatePhone() {
        when(userMapper.selectOne(any())).thenReturn(new User());
        BusinessException e = assertThrows(BusinessException.class, () -> {
            RegisterRequest request = new RegisterRequest();
            request.setPhone(PHONE);
            request.setCode("123456");
            service.register(request);
        });
        assertEquals(ErrorCode.USER_ALREADY_EXISTS.getCode(), e.getCode());
    }

    @Test
    void smsLoginAutoRegisters() {
        when(userMapper.selectOne(any())).thenReturn(null);
        LoginRequest request = smsLogin();
        LoginVO loginVO = service.login(request);
        verify(smsCodeService).verify(PHONE, SmsScene.LOGIN, "123456");
        verify(userMapper).insert(any(User.class));
        assertNotNull(loginVO.accessToken());
    }

    @Test
    void wrongPasswordRejected() {
        when(userMapper.selectOne(any())).thenReturn(userWithPassword());
        LoginRequest request = new LoginRequest();
        request.setGrantType(GrantType.PASSWORD);
        request.setPhone(PHONE);
        request.setPassword("wrong-pass");
        BusinessException e = assertThrows(BusinessException.class, () -> service.login(request));
        assertEquals(ErrorCode.USER_NOT_FOUND.getCode(), e.getCode());
    }

    @Test
    void frozenUserCannotLogin() {
        User frozen = userWithPassword();
        frozen.setStatus(UserStatus.FROZEN);
        when(userMapper.selectOne(any())).thenReturn(frozen);
        LoginRequest request = new LoginRequest();
        request.setGrantType(GrantType.PASSWORD);
        request.setPhone(PHONE);
        request.setPassword("abc12345");
        BusinessException e = assertThrows(BusinessException.class, () -> service.login(request));
        assertEquals(ErrorCode.USER_FROZEN.getCode(), e.getCode());
    }

    @Test
    void refreshRotatesSession() {
        String oldToken = "a".repeat(64);
        UserSession session = session(oldToken);
        when(userSessionMapper.selectOne(any())).thenReturn(session);
        when(userMapper.selectById(1L)).thenReturn(userWithPassword());
        LoginVO loginVO = service.refresh(oldToken);
        ArgumentCaptor<UserSession> revoked = ArgumentCaptor.forClass(UserSession.class);
        verify(userSessionMapper).updateById(revoked.capture());
        assertTrue(revoked.getValue().getRevokedAt() != null);
        verify(userSessionMapper).insert(any(UserSession.class));
        assertFalse(oldToken.equals(loginVO.refreshToken()));
    }

    @Test
    void refreshRejectsRevokedSession() {
        UserSession session = session("a".repeat(64));
        session.setRevokedAt(LocalDateTime.now().minusMinutes(1));
        when(userSessionMapper.selectOne(any())).thenReturn(session);
        BusinessException e = assertThrows(BusinessException.class, () -> service.refresh("a".repeat(64)));
        assertEquals(ErrorCode.TOKEN_INVALID.getCode(), e.getCode());
    }

    @Test
    void logoutRevokesSession() {
        when(userSessionMapper.selectOne(any())).thenReturn(session("a".repeat(64)));
        service.logout("a".repeat(64));
        ArgumentCaptor<UserSession> captor = ArgumentCaptor.forClass(UserSession.class);
        verify(userSessionMapper).updateById(captor.capture());
        assertTrue(captor.getValue().getRevokedAt() != null);
    }

    @Test
    void meMasksPhone() {
        when(userMapper.selectById(1L)).thenReturn(userWithPassword());
        MeVO meVO = service.me(new LoginUser(1L, PHONE, "CUSTOMER"));
        assertEquals("138****1234", meVO.phoneMasked());
        assertEquals("CUSTOMER", meVO.role());
    }

    @Test
    void updateMeChangesNickname() {
        User user = userWithPassword();
        User updated = userWithPassword();
        updated.setNickname("老王");
        when(userMapper.selectById(1L)).thenReturn(user, updated);
        UpdateMeRequest request = new UpdateMeRequest();
        request.setNickname("老王");
        MeVO meVO = service.updateMe(new LoginUser(1L, PHONE, "CUSTOMER"), request);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertEquals("老王", captor.getValue().getNickname());
        assertEquals("老王", meVO.nickname());
    }

    private LoginRequest smsLogin() {
        LoginRequest request = new LoginRequest();
        request.setGrantType(GrantType.SMS);
        request.setPhone(PHONE);
        request.setCode("123456");
        return request;
    }

    private User userWithPassword() {
        User user = new User();
        user.setId(1L);
        user.setPhone(PHONE);
        user.setNickname("球友1234");
        user.setRole(UserRole.CUSTOMER);
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash(new BCryptPasswordEncoder().encode("abc12345"));
        return user;
    }

    private UserSession session(String refreshToken) {
        UserSession session = new UserSession();
        session.setId(100L);
        session.setUserId(1L);
        session.setRefreshTokenHash(SmsCodeService.sha256(refreshToken));
        session.setDevice("iOS");
        session.setExpiresAt(LocalDateTime.now().plusDays(7));
        return session;
    }
}
