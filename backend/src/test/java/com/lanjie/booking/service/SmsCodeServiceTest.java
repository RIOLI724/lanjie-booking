package com.lanjie.booking.service;

import com.lanjie.booking.common.BusinessException;
import com.lanjie.booking.common.ErrorCode;
import com.lanjie.booking.entity.SmsCode;
import com.lanjie.booking.enums.SmsScene;
import com.lanjie.booking.mapper.SmsCodeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SmsCodeServiceTest {

    private static final String PHONE = "13800001234";

    @Mock
    private SmsCodeMapper smsCodeMapper;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private SmsCodeService service;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        service = new SmsCodeService(smsCodeMapper, redis, "123456");
    }

    @Test
    void sendStoresHashedCode() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        service.send(PHONE, SmsScene.REGISTER);
        ArgumentCaptor<SmsCode> captor = ArgumentCaptor.forClass(SmsCode.class);
        verify(smsCodeMapper).insert(captor.capture());
        SmsCode saved = captor.getValue();
        assertEquals(SmsCodeService.sha256("123456"), saved.getCodeHash());
        assertTrue(saved.getExpiresAt().isAfter(LocalDateTime.now().plusMinutes(4)));
    }

    @Test
    void sendRateLimitedWithin60s() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.send(PHONE, SmsScene.REGISTER));
        assertEquals(ErrorCode.AUTH_SEND_TOO_FREQUENT.getCode(), e.getCode());
        verify(smsCodeMapper, never()).insert(any(SmsCode.class));
    }

    @Test
    void verifyCorrectCodeMarksUsed() {
        when(redis.hasKey(anyString())).thenReturn(false);
        when(smsCodeMapper.selectOne(any())).thenReturn(activeCode(0));
        service.verify(PHONE, SmsScene.LOGIN, "123456");
        ArgumentCaptor<SmsCode> captor = ArgumentCaptor.forClass(SmsCode.class);
        verify(smsCodeMapper).updateById(captor.capture());
        assertTrue(captor.getValue().getUsedAt() != null);
    }

    @Test
    void wrongCodeIncrementsAttempts() {
        when(redis.hasKey(anyString())).thenReturn(false);
        when(smsCodeMapper.selectOne(any())).thenReturn(activeCode(0));
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.verify(PHONE, SmsScene.LOGIN, "000000"));
        assertEquals(ErrorCode.AUTH_CODE_INVALID.getCode(), e.getCode());
        verify(smsCodeMapper).update(any(), any());
    }

    @Test
    void fifthFailureLocksFor15Minutes() {
        when(redis.hasKey(anyString())).thenReturn(false);
        when(smsCodeMapper.selectOne(any())).thenReturn(activeCode(4));
        assertThrows(BusinessException.class, () -> service.verify(PHONE, SmsScene.LOGIN, "000000"));
        verify(valueOps).set(contains("lock"), eq("1"), eq(Duration.ofMinutes(15)));
    }

    @Test
    void lockedPhoneShortCircuits() {
        when(redis.hasKey(anyString())).thenReturn(true);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.verify(PHONE, SmsScene.LOGIN, "123456"));
        assertEquals(ErrorCode.AUTH_CODE_LOCKED.getCode(), e.getCode());
        verify(smsCodeMapper, never()).selectOne(any());
    }

    private SmsCode activeCode(int attempts) {
        SmsCode smsCode = new SmsCode();
        smsCode.setId(1L);
        smsCode.setPhone(PHONE);
        smsCode.setScene(SmsScene.LOGIN);
        smsCode.setCodeHash(SmsCodeService.sha256("123456"));
        smsCode.setAttempts(attempts);
        smsCode.setExpiresAt(LocalDateTime.now().plusMinutes(4));
        return smsCode;
    }
}
