package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lanjie.booking.common.BusinessException;
import com.lanjie.booking.common.ErrorCode;
import com.lanjie.booking.entity.SmsCode;
import com.lanjie.booking.enums.SmsScene;
import com.lanjie.booking.mapper.SmsCodeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * 短信验证码：发送 60 秒限频；验证码哈希落库、5 分钟有效；
 * 校验失败 5 次经 Redis 锁定 15 分钟；dev 环境用固定验证码（配置 lanjie.sms.dev-fixed-code）。
 */
@Slf4j
@Service
public class SmsCodeService {

    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration RESEND_INTERVAL = Duration.ofSeconds(60);
    private static final Duration LOCK_TTL = Duration.ofMinutes(15);
    private static final int MAX_ATTEMPTS = 5;

    private final SmsCodeMapper smsCodeMapper;
    private final StringRedisTemplate redis;
    private final String devFixedCode;
    private final SecureRandom random = new SecureRandom();

    public SmsCodeService(SmsCodeMapper smsCodeMapper, StringRedisTemplate redis,
                          @Value("${lanjie.sms.dev-fixed-code:}") String devFixedCode) {
        this.smsCodeMapper = smsCodeMapper;
        this.redis = redis;
        this.devFixedCode = devFixedCode;
    }

    public void send(String phone, SmsScene scene) {
        Boolean first = redis.opsForValue().setIfAbsent(redisKey("send", phone, scene), "1", RESEND_INTERVAL);
        if (first == null || !first) {
            throw new BusinessException(ErrorCode.AUTH_SEND_TOO_FREQUENT);
        }
        String code = devFixedCode != null && !devFixedCode.isBlank() ? devFixedCode : randomCode();
        SmsCode smsCode = new SmsCode();
        smsCode.setPhone(phone);
        smsCode.setScene(scene);
        smsCode.setCodeHash(sha256(code));
        smsCode.setExpiresAt(LocalDateTime.now().plus(CODE_TTL));
        smsCodeMapper.insert(smsCode);
        // dev 环境没有真实短信通道，验证码打在日志里；生产接入短信后删除此输出
        log.info("[SMS-DEV] phone={} scene={} code={}", phone, scene, code);
    }

    public void verify(String phone, SmsScene scene, String code) {
        Boolean locked = redis.hasKey(redisKey("lock", phone, scene));
        if (locked != null && locked) {
            throw new BusinessException(ErrorCode.AUTH_CODE_LOCKED);
        }
        SmsCode smsCode = smsCodeMapper.selectOne(new LambdaQueryWrapper<SmsCode>()
                .eq(SmsCode::getPhone, phone)
                .eq(SmsCode::getScene, scene)
                .isNull(SmsCode::getUsedAt)
                .orderByDesc(SmsCode::getId)
                .last("LIMIT 1"));
        if (smsCode == null || smsCode.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.AUTH_CODE_INVALID);
        }
        if (!sha256(code).equals(smsCode.getCodeHash())) {
            int attempts = smsCode.getAttempts() + 1;
            smsCodeMapper.update(null, new LambdaUpdateWrapper<SmsCode>()
                    .eq(SmsCode::getId, smsCode.getId())
                    .setSql("attempts = attempts + 1"));
            if (attempts >= MAX_ATTEMPTS) {
                redis.opsForValue().set(redisKey("lock", phone, scene), "1", LOCK_TTL);
            }
            throw new BusinessException(ErrorCode.AUTH_CODE_INVALID);
        }
        smsCode.setUsedAt(LocalDateTime.now());
        smsCodeMapper.updateById(smsCode);
    }

    private String randomCode() {
        return String.format("%06d", random.nextInt(1_000_000));
    }

    private String redisKey(String action, String phone, SmsScene scene) {
        return "sms:%s:%s:%s".formatted(action, scene.name().toLowerCase(), phone);
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
