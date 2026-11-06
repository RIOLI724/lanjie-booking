package com.lanjie.booking.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "lanjie.jwt")
public class JwtProperties {

    private String secret;
    private String issuer = "lanjie";
    private int accessTtlMinutes = 30;
    private int refreshTtlDays = 7;
}
