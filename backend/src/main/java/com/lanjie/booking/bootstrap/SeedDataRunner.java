package com.lanjie.booking.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 启动时补齐演示数据与未来 30 天格子（幂等） */
@Component
public class SeedDataRunner implements ApplicationRunner {

    private final SeedService seedService;

    public SeedDataRunner(SeedService seedService) {
        this.seedService = seedService;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedService.seedAll();
    }
}
