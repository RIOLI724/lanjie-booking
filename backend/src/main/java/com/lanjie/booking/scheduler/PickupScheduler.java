package com.lanjie.booking.scheduler;

import com.lanjie.booking.service.PickupService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 拼场结算（每分钟）：开赛 1 小时后托管份子钱结给发起人，结束后转已完成 */
@Component
public class PickupScheduler {

    private final PickupService pickupService;

    public PickupScheduler(PickupService pickupService) {
        this.pickupService = pickupService;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 55_000)
    public void settle() {
        pickupService.settle();
    }
}
