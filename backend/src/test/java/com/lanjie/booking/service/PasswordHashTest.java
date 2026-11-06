package com.lanjie.booking.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PasswordHashTest {
    @Test
    void printHash() {
        System.out.println("DEMO_HASH=" + new BCryptPasswordEncoder().encode("123456"));
    }
}
