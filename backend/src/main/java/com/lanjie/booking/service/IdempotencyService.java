package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanjie.booking.common.BusinessException;
import com.lanjie.booking.common.ErrorCode;
import com.lanjie.booking.entity.IdempotencyKey;
import com.lanjie.booking.mapper.IdempotencyKeyMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * 幂等执行器：同一个「幂等键 + 用户」只允许同一份请求内容执行一次，
 * 重放直接返回首次响应；内容不同视为误用；执行失败清掉占位，允许重试。
 */
@Slf4j
@Service
public class IdempotencyService {

    private final IdempotencyKeyMapper idempotencyKeyMapper;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyKeyMapper idempotencyKeyMapper, ObjectMapper objectMapper) {
        this.idempotencyKeyMapper = idempotencyKeyMapper;
        this.objectMapper = objectMapper;
    }

    public <T> T execute(String key, Long userId, String endpoint, Object request,
                         Class<T> responseType, Supplier<T> action) {
        if (key == null || key.isBlank()) {
            return action.get();
        }
        String requestHash = SmsCodeService.sha256(toJson(request));
        IdempotencyKey existing = find(key, userId);
        if (existing != null) {
            return replayOrThrow(existing, requestHash, responseType);
        }
        IdempotencyKey row = new IdempotencyKey();
        row.setIdemKey(key);
        row.setUserId(userId);
        row.setEndpoint(endpoint);
        row.setRequestHash(requestHash);
        try {
            idempotencyKeyMapper.insert(row);
        } catch (DuplicateKeyException e) {
            IdempotencyKey concurrent = find(key, userId);
            if (concurrent == null) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
            }
            return replayOrThrow(concurrent, requestHash, responseType);
        }
        try {
            T result = action.get();
            row.setResponseJson(toJson(result));
            idempotencyKeyMapper.updateById(row);
            return result;
        } catch (RuntimeException e) {
            idempotencyKeyMapper.deleteById(row.getId());
            throw e;
        }
    }

    private <T> T replayOrThrow(IdempotencyKey existing, String requestHash, Class<T> responseType) {
        if (!requestHash.equals(existing.getRequestHash())) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "请求内容与上次不同");
        }
        if (existing.getResponseJson() == null) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "请求正在处理，请稍候");
        }
        try {
            return objectMapper.readValue(existing.getResponseJson(), responseType);
        } catch (Exception e) {
            log.error("idempotency response deserialize failed, key={}", existing.getIdemKey(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR);
        }
    }

    private IdempotencyKey find(String key, Long userId) {
        return idempotencyKeyMapper.selectOne(new LambdaQueryWrapper<IdempotencyKey>()
                .eq(IdempotencyKey::getIdemKey, key)
                .eq(IdempotencyKey::getUserId, userId)
                .last("LIMIT 1"));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("json serialize failed", e);
        }
    }
}
