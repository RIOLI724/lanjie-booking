package com.lanjie.booking.scheduler;

import com.lanjie.booking.service.GroupClassService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 团课调度（每分钟）：递补单超时让位、开班前 24h 不成班自动取消全退、结束转完成 */
@Slf4j
@Component
public class GroupClassScheduler {

    private final GroupClassService groupClassService;

    public GroupClassScheduler(GroupClassService groupClassService) {
        this.groupClassService = groupClassService;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 50_000)
    public void sweep() {
        groupClassService.sweep();
    }
}
