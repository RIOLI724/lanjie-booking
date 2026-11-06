package com.lanjie.booking.bootstrap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.entity.Coach;
import com.lanjie.booking.entity.CoachScheduleRule;
import com.lanjie.booking.entity.CoachService;
import com.lanjie.booking.entity.Court;
import com.lanjie.booking.entity.PriceRule;
import com.lanjie.booking.entity.RechargePlan;
import com.lanjie.booking.entity.User;
import com.lanjie.booking.entity.Venue;
import com.lanjie.booking.entity.VenueHours;
import com.lanjie.booking.enums.CourtStatus;
import com.lanjie.booking.enums.CourtType;
import com.lanjie.booking.enums.DayType;
import com.lanjie.booking.enums.UserRole;
import com.lanjie.booking.enums.UserStatus;
import com.lanjie.booking.enums.VenueStatus;
import com.lanjie.booking.mapper.CoachMapper;
import com.lanjie.booking.mapper.CoachScheduleRuleMapper;
import com.lanjie.booking.mapper.CoachServiceMapper;
import com.lanjie.booking.mapper.CourtMapper;
import com.lanjie.booking.mapper.PriceRuleMapper;
import com.lanjie.booking.mapper.RechargePlanMapper;
import com.lanjie.booking.mapper.UserMapper;
import com.lanjie.booking.mapper.VenueHoursMapper;
import com.lanjie.booking.mapper.VenueMapper;
import com.lanjie.booking.service.CoachSlotGenerationService;
import com.lanjie.booking.service.SlotGenerationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalTime;

/**
 * 首次启动写入演示数据（城东体育馆 + 4 片场 + 营业时间 + 价格规则 + 管理员 + 充值档），
 * 然后补齐未来 30 天格子。幂等：已有数据则跳过 seed，只补格子。
 */
@Slf4j
@Component
public class SeedDataRunner implements ApplicationRunner {

    private final VenueMapper venueMapper;
    private final VenueHoursMapper venueHoursMapper;
    private final CourtMapper courtMapper;
    private final PriceRuleMapper priceRuleMapper;
    private final UserMapper userMapper;
    private final RechargePlanMapper rechargePlanMapper;
    private final CoachMapper coachMapper;
    private final CoachScheduleRuleMapper coachScheduleRuleMapper;
    private final CoachServiceMapper coachServiceMapper;
    private final PasswordEncoder passwordEncoder;
    private final SlotGenerationService slotGenerationService;
    private final CoachSlotGenerationService coachSlotGenerationService;

    public SeedDataRunner(VenueMapper venueMapper, VenueHoursMapper venueHoursMapper,
                          CourtMapper courtMapper, PriceRuleMapper priceRuleMapper,
                          UserMapper userMapper, RechargePlanMapper rechargePlanMapper,
                          CoachMapper coachMapper, CoachScheduleRuleMapper coachScheduleRuleMapper,
                          CoachServiceMapper coachServiceMapper, PasswordEncoder passwordEncoder,
                          SlotGenerationService slotGenerationService,
                          CoachSlotGenerationService coachSlotGenerationService) {
        this.venueMapper = venueMapper;
        this.venueHoursMapper = venueHoursMapper;
        this.courtMapper = courtMapper;
        this.priceRuleMapper = priceRuleMapper;
        this.userMapper = userMapper;
        this.rechargePlanMapper = rechargePlanMapper;
        this.coachMapper = coachMapper;
        this.coachScheduleRuleMapper = coachScheduleRuleMapper;
        this.coachServiceMapper = coachServiceMapper;
        this.passwordEncoder = passwordEncoder;
        this.slotGenerationService = slotGenerationService;
        this.coachSlotGenerationService = coachSlotGenerationService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (venueMapper.selectCount(null) == 0) {
            seed();
        }
        seedAdminAndPlans();
        seedCoaches();
        slotGenerationService.generateOnStartup();
        coachSlotGenerationService.generateOnStartup();
    }

    /** 演示教练：3 位教练账号 + 档期规则 + 私教服务 */
    private void seedCoaches() {
        if (coachMapper.selectCount(null) > 0) {
            return;
        }
        String[][] coaches = {
                {"13900000001", "张教练", "前省队 · 基础 / 步法", "30000"},
                {"13900000002", "李教练", "投篮专项 · 出手节奏", "28000"},
                {"13900000003", "王教练", "青少年 · 体能与协调", "26000"},
        };
        int sort = 1;
        for (String[] c : coaches) {
            User coachUser = new User();
            coachUser.setPhone(c[0]);
            coachUser.setPasswordHash(passwordEncoder.encode("coach123"));
            coachUser.setNickname(c[1]);
            coachUser.setRole(UserRole.COACH);
            coachUser.setStatus(UserStatus.ACTIVE);
            coachUser.setCumulativeRechargeCents(0L);
            userMapper.insert(coachUser);

            Coach coach = new Coach();
            coach.setUserId(coachUser.getId());
            coach.setDisplayName(c[1]);
            coach.setBio("十年教学经验，教得细、纠得准");
            coach.setSpecialties(c[2]);
            coach.setRating(new java.math.BigDecimal("4.9"));
            coach.setRatingCount(100);
            coach.setBasePriceCents(Integer.parseInt(c[3]));
            coach.setCommissionRate(new java.math.BigDecimal("0.200"));
            coach.setStatus("ACTIVE");
            coach.setSortOrder(sort++);
            coachMapper.insert(coach);

            for (int weekday = 1; weekday <= 7; weekday++) {
                CoachScheduleRule rule = new CoachScheduleRule();
                rule.setCoachId(coach.getId());
                rule.setWeekday(weekday);
                rule.setStartTime(java.time.LocalTime.of(9, 0));
                rule.setEndTime(java.time.LocalTime.of(22, 0));
                rule.setStatus("ACTIVE");
                coachScheduleRuleMapper.insert(rule);
            }

            CoachService service = new CoachService();
            service.setCoachId(coach.getId());
            service.setType(com.lanjie.booking.enums.CoachServiceType.PRIVATE);
            service.setTitle("私教单节（含场地）");
            service.setDurationMin(60);
            service.setPriceCents(Integer.parseInt(c[3]));
            service.setIncludesCourt(true);
            service.setMinStudents(1);
            service.setMaxStudents(1);
            service.setStatus("ACTIVE");
            coachServiceMapper.insert(service);
        }
        log.info("seeded 3 demo coaches");
    }

    /** 前台管理员账号 + 充值档位（幂等：按数量判断） */
    private void seedAdminAndPlans() {
        if (userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getRole, UserRole.ADMIN)) == 0) {
            User admin = new User();
            admin.setPhone("13800000000");
            admin.setPasswordHash(passwordEncoder.encode("admin123"));
            admin.setNickname("前台王姐");
            admin.setRole(UserRole.ADMIN);
            admin.setStatus(UserStatus.ACTIVE);
            admin.setCumulativeRechargeCents(0L);
            userMapper.insert(admin);
            log.info("seeded admin account 13800000000 / admin123");
        }
        if (rechargePlanMapper.selectCount(null) == 0) {
            seedPlan(50000, 5000, "充 500 送 50", 1);
            seedPlan(100000, 15000, "充 1000 送 150", 2);
            seedPlan(200000, 40000, "充 2000 送 400", 3);
            log.info("seeded recharge plans");
        }
    }

    private void seedPlan(int amountCents, int bonusCents, String title, int sort) {
        RechargePlan plan = new RechargePlan();
        plan.setAmountCents(amountCents);
        plan.setBonusCents(bonusCents);
        plan.setTitle(title);
        plan.setStatus("ACTIVE");
        plan.setSort(sort);
        rechargePlanMapper.insert(plan);
    }

    private void seed() {
        Venue venue = new Venue();
        venue.setName("城东体育馆");
        venue.setAddress("城东区体育大道 8 号");
        venue.setPhone("0871-88886666");
        venue.setOpenTime(LocalTime.of(9, 0));
        venue.setCloseTime(LocalTime.of(22, 0));
        venue.setTimezone("Asia/Shanghai");
        venue.setStatus(VenueStatus.ACTIVE);
        venueMapper.insert(venue);

        for (int weekday = 1; weekday <= 7; weekday++) {
            VenueHours hours = new VenueHours();
            hours.setVenueId(venue.getId());
            hours.setWeekday(weekday);
            hours.setOpenTime(LocalTime.of(9, 0));
            hours.setCloseTime(LocalTime.of(22, 0));
            venueHoursMapper.insert(hours);
        }

        String[] names = {"1 号场", "2 号场", "3 号场", "4 号场"};
        for (int i = 0; i < names.length; i++) {
            Court court = new Court();
            court.setVenueId(venue.getId());
            court.setName(names[i]);
            court.setType(CourtType.FULL);
            court.setBasePriceCents(4000);
            court.setStatus(CourtStatus.ACTIVE);
            court.setSortOrder(i + 1);
            courtMapper.insert(court);
        }

        // 全馆价格规则：平日 09-18 ¥40 / 18-22 ¥70；周末与节假日 09-10 ¥40 / 10-22 ¥80
        seedRule(null, DayType.WEEKDAY, "09:00", "18:00", 4000, 1);
        seedRule(null, DayType.WEEKDAY, "18:00", "22:00", 7000, 2);
        seedRule(null, DayType.WEEKEND, "09:00", "10:00", 4000, 1);
        seedRule(null, DayType.WEEKEND, "10:00", "22:00", 8000, 2);
        seedRule(null, DayType.HOLIDAY, "09:00", "10:00", 4000, 1);
        seedRule(null, DayType.HOLIDAY, "10:00", "22:00", 8000, 2);
        log.info("seeded demo venue/courts/hours/price rules");
    }

    private void seedRule(Long courtId, DayType dayType, String start, String end, int priceCents, int priority) {
        PriceRule rule = new PriceRule();
        rule.setCourtId(courtId);
        rule.setDayType(dayType);
        rule.setStartTime(LocalTime.parse(start));
        rule.setEndTime(LocalTime.parse(end));
        rule.setPriceCents(priceCents);
        rule.setPriority(priority);
        rule.setStatus("ACTIVE");
        priceRuleMapper.insert(rule);
    }
}
