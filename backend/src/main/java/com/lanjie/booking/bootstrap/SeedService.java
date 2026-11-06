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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

/**
 * 种子与测试重置：seedAll() 幂等补齐演示数据；resetAll() 清空全部业务表后重新种子，
 * 供集成测试在类级别获得干净的数据库（并发正确性依赖确定的初始状态）。
 */
@Slf4j
@Service
public class SeedService {

    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter ITEM_DATE = DateTimeFormatter.ofPattern("MM-dd");

    private final VenueMapper venueMapper;
    private final VenueHoursMapper venueHoursMapper;
    private final CourtMapper courtMapper;
    private final PriceRuleMapper priceRuleMapper;
    private final UserMapper userMapper;
    private final RechargePlanMapper rechargePlanMapper;
    private final CoachMapper coachMapper;
    private final CoachScheduleRuleMapper coachScheduleRuleMapper;
    private final CoachServiceMapper coachServiceMapper;
    private final com.lanjie.booking.mapper.GroupClassMapper groupClassMapper;
    private final com.lanjie.booking.mapper.MemberLevelMapper memberLevelMapper;
    private final com.lanjie.booking.mapper.CouponTemplateMapper couponTemplateMapper;
    private final com.lanjie.booking.mapper.KnowledgeDocMapper knowledgeDocMapper;
    private final com.lanjie.booking.mapper.KnowledgeChunkMapper knowledgeChunkMapper;
    private final com.lanjie.booking.service.GroupClassService groupClassService;
    private final PasswordEncoder passwordEncoder;
    private final SlotGenerationService slotGenerationService;
    private final CoachSlotGenerationService coachSlotGenerationService;
    private final JdbcTemplate jdbcTemplate;

    public SeedService(VenueMapper venueMapper, VenueHoursMapper venueHoursMapper,
                       CourtMapper courtMapper, PriceRuleMapper priceRuleMapper,
                       UserMapper userMapper, RechargePlanMapper rechargePlanMapper,
                       CoachMapper coachMapper, CoachScheduleRuleMapper coachScheduleRuleMapper,
                       CoachServiceMapper coachServiceMapper,
                       com.lanjie.booking.mapper.GroupClassMapper groupClassMapper,
                       com.lanjie.booking.mapper.MemberLevelMapper memberLevelMapper,
                       com.lanjie.booking.mapper.CouponTemplateMapper couponTemplateMapper,
                       com.lanjie.booking.mapper.KnowledgeDocMapper knowledgeDocMapper,
                       com.lanjie.booking.mapper.KnowledgeChunkMapper knowledgeChunkMapper,
                       com.lanjie.booking.service.GroupClassService groupClassService,
                       PasswordEncoder passwordEncoder, SlotGenerationService slotGenerationService,
                       CoachSlotGenerationService coachSlotGenerationService, JdbcTemplate jdbcTemplate) {
        this.venueMapper = venueMapper;
        this.venueHoursMapper = venueHoursMapper;
        this.courtMapper = courtMapper;
        this.priceRuleMapper = priceRuleMapper;
        this.userMapper = userMapper;
        this.rechargePlanMapper = rechargePlanMapper;
        this.coachMapper = coachMapper;
        this.coachScheduleRuleMapper = coachScheduleRuleMapper;
        this.coachServiceMapper = coachServiceMapper;
        this.groupClassMapper = groupClassMapper;
        this.memberLevelMapper = memberLevelMapper;
        this.couponTemplateMapper = couponTemplateMapper;
        this.knowledgeDocMapper = knowledgeDocMapper;
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.groupClassService = groupClassService;
        this.passwordEncoder = passwordEncoder;
        this.slotGenerationService = slotGenerationService;
        this.coachSlotGenerationService = coachSlotGenerationService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 启动时调用：已有数据则只补格子 */
    public void seedAll() {
        if (venueMapper.selectCount(null) == 0) {
            seedVenueAndCourts();
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getRole, UserRole.ADMIN)) == 0) {
            seedAdmin();
        }
        if (rechargePlanMapper.selectCount(null) == 0) {
            seedPlans();
        }
        if (coachMapper.selectCount(null) == 0) {
            seedCoaches();
        }
        seedGroupServices();
        seedMembership();
        seedKnowledge();
        slotGenerationService.generateOnStartup();
        coachSlotGenerationService.generateOnStartup();
        seedGroupClasses();
    }

    /** 测试重置：清空业务数据（保留表结构）后完整重种。不加事务：内部种子各自成事务，
     * 单个种子失败不应把整个重置打成回滚（失败会被 catch 记录并跳过） */
    public void resetAll() {
        String[] tables = {
                "booking_status_history", "booking_item", "booking", "pay_order", "refund_order",
                "wallet_txn", "wallet_account", "points_transaction", "points_account",
                "user_coupon", "coach_package", "group_enrollment", "group_class",
                "pickup_participant", "pickup_game", "no_show_record", "user_ban",
                "idempotency_key", "sms_code", "user_session",
                "slot", "coach_slot", "coach_time_off", "coach_schedule_rule",
                "coach_service", "coach", "user",
                "venue_hours", "venue", "court", "price_rule", "holiday", "recharge_plan",
        };
        for (String table : tables) {
            // TRUNCATE 重置自增：测试里引用种子固定 id（场地 1-4、教练 1-3 等）才不会漂移
            jdbcTemplate.execute("TRUNCATE TABLE `" + table + "`");
        }
        log.warn("test database reset: all business tables cleared");
        seedAll();
    }

    private void seedVenueAndCourts() {
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

        seedRule(null, DayType.WEEKDAY, "09:00", "18:00", 4000, 1);
        seedRule(null, DayType.WEEKDAY, "18:00", "22:00", 7000, 2);
        seedRule(null, DayType.WEEKEND, "09:00", "10:00", 4000, 1);
        seedRule(null, DayType.WEEKEND, "10:00", "22:00", 8000, 2);
        seedRule(null, DayType.HOLIDAY, "09:00", "10:00", 4000, 1);
        seedRule(null, DayType.HOLIDAY, "10:00", "22:00", 8000, 2);
        log.info("seeded venue/courts/price rules");
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

    private void seedAdmin() {
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

    private void seedPlans() {
        seedPlan(50000, 5000, "充 500 送 50", 1);
        seedPlan(100000, 15000, "充 1000 送 150", 2);
        seedPlan(200000, 40000, "充 2000 送 400", 3);
        log.info("seeded recharge plans");
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

    private void seedCoaches() {
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
                rule.setStartTime(LocalTime.of(9, 0));
                rule.setEndTime(LocalTime.of(22, 0));
                rule.setStatus("ACTIVE");
                coachScheduleRuleMapper.insert(rule);
            }

            seedPrivateService(coach.getId(), Integer.parseInt(c[3]));
        }
        log.info("seeded 3 demo coaches");
    }

    private void seedPrivateService(Long coachId, int priceCents) {
        CoachService service = new CoachService();
        service.setCoachId(coachId);
        service.setType(com.lanjie.booking.enums.CoachServiceType.PRIVATE);
        service.setTitle("私教单节（含场地）");
        service.setDurationMin(60);
        service.setPriceCents(priceCents);
        service.setIncludesCourt(true);
        service.setMinStudents(1);
        service.setMaxStudents(1);
        service.setStatus("ACTIVE");
        coachServiceMapper.insert(service);
    }

    /** 会员四档 + 券模板（幂等） */
    private void seedMembership() {
        if (memberLevelMapper.selectCount(null) == 0) {
            seedLevel("NORMAL", "普通", 0L, 100, "1.0", 1);
            seedLevel("SILVER", "银卡", 100000L, 98, "1.1", 2);
            seedLevel("GOLD", "金卡", 300000L, 95, "1.2", 3);
            seedLevel("BLACK", "黑卡", 800000L, 92, "1.5", 4);
            log.info("seeded member levels");
        }
        if (couponTemplateMapper.selectCount(null) == 0) {
            seedCoupon("新人场地券", "FIXED", 2000, 100, 0, 10000, "COURT", 30);
            seedCoupon("教练课券", "FIXED", 5000, 100, 0, 30000, "COACH", 30);
            log.info("seeded coupon templates");
        }
    }

    private void seedLevel(String code, String name, long threshold, int discountPct, String pointsRate, int sort) {
        com.lanjie.booking.entity.MemberLevel level = new com.lanjie.booking.entity.MemberLevel();
        level.setCode(code);
        level.setName(name);
        level.setThresholdCents(threshold);
        level.setDiscountPct(discountPct);
        level.setPointsRate(new java.math.BigDecimal(pointsRate));
        level.setSort(sort);
        memberLevelMapper.insert(level);
    }

    private void seedCoupon(String name, String type, int valueCents, int percent, int maxDiscount,
                            int minAmount, String scope, int validDays) {
        com.lanjie.booking.entity.CouponTemplate template = new com.lanjie.booking.entity.CouponTemplate();
        template.setName(name);
        template.setType(type);
        template.setValueCents(valueCents);
        template.setPercent(percent);
        template.setMaxDiscountCents(maxDiscount);
        template.setMinAmountCents(minAmount);
        template.setScope(scope);
        template.setValidFrom(java.time.LocalDateTime.now());
        template.setValidTo(java.time.LocalDateTime.now().plusDays(validDays));
        template.setTotalQuantity(0);
        template.setIssuedQuantity(0);
        template.setPerUserLimit(1);
        template.setStatus("ACTIVE");
        couponTemplateMapper.insert(template);
    }

    /** 规则库（RAG 源）：8 篇政策，get_policy 按主题检索并引用 */
    private void seedKnowledge() {
        if (knowledgeDocMapper.selectCount(null) > 0) {
            return;
        }
        seedDoc("退款规则", "refund",
                "开场前 24 小时以上取消全额退款；开场前 2 至 24 小时取消退 70%；开场前不足 2 小时取消不退款。",
                "退款,取消,阶梯,70%,24小时");
        seedDoc("改期规则", "reschedule",
                "开场前 2 小时可改期一次，多退少补；改期后不可再次改期。",
                "改期,换时间,差价");
        seedDoc("爽约规则", "noshow",
                "开场 30 分钟未核销记为爽约；7 天内爽约 3 次暂停预订 7 天。",
                "爽约,违约,黑名单");
        seedDoc("课包规则", "package",
                "私教课包分 5/10/20 节，分别 95/90/85 折，购买日起 12 个月有效；开场前 24 小时取消退回课时，24 小时内取消扣 1 节。",
                "课包,课时,私教");
        seedDoc("团课规则", "group",
                "团课 4 人开班、上限 8 人，24 小时前不足 4 人自动取消并全额退款；满员可候补，有人退按顺序递补。",
                "团课,开班,候补");
        seedDoc("拼场规则", "pickup",
                "拼场为 3v3/4v4/5v5，发起人先付全场，参与者份子钱由平台托管，开赛 1 小时后结算给发起人。",
                "拼场,份子钱,AA");
        seedDoc("场馆信息", "venue",
                "城东体育馆营业时间 09:00-22:00，4 片全场；地址城东区体育大道 8 号，电话 0871-88886666。",
                "营业,地址,电话,停车");
        seedDoc("会员与优惠", "member",
                "会员四档按累计充值升级（1000/3000/8000），享 98/95/92 折；会员折扣与优惠券二选一，积分可再抵最多 20%。",
                "会员,折扣,积分,优惠券");
        log.info("seeded knowledge base");
    }

    private void seedDoc(String title, String category, String content, String keywords) {
        com.lanjie.booking.entity.KnowledgeDoc doc = new com.lanjie.booking.entity.KnowledgeDoc();
        doc.setTitle(title);
        doc.setCategory(category);
        doc.setContent(content);
        doc.setVersion(1);
        doc.setStatus("ACTIVE");
        knowledgeDocMapper.insert(doc);
        com.lanjie.booking.entity.KnowledgeChunk chunk = new com.lanjie.booking.entity.KnowledgeChunk();
        chunk.setDocId(doc.getId());
        chunk.setSeq(0);
        chunk.setContent(content);
        chunk.setKeywords(keywords);
        knowledgeChunkMapper.insert(chunk);
    }

    private void seedGroupServices() {
        for (Coach coach : coachMapper.selectList(null)) {
            Long existing = coachServiceMapper.selectCount(new LambdaQueryWrapper<CoachService>()
                    .eq(CoachService::getCoachId, coach.getId())
                    .eq(CoachService::getType, com.lanjie.booking.enums.CoachServiceType.GROUP));
            if (existing > 0) {
                continue;
            }
            CoachService group = new CoachService();
            group.setCoachId(coach.getId());
            group.setType(com.lanjie.booking.enums.CoachServiceType.GROUP);
            group.setTitle("基础团课（4 人开班）");
            group.setDurationMin(60);
            group.setPriceCents(8000);
            group.setIncludesCourt(true);
            group.setMinStudents(4);
            group.setMaxStudents(8);
            group.setStatus("ACTIVE");
            coachServiceMapper.insert(group);
        }
    }

    private void seedGroupClasses() {
        if (groupClassMapper.selectCount(null) > 0) {
            return;
        }
        try {
            Long coach1 = coachMapper.selectOne(new LambdaQueryWrapper<Coach>()
                    .eq(Coach::getSortOrder, 1).last("LIMIT 1")).getId();
            Long coach2 = coachMapper.selectOne(new LambdaQueryWrapper<Coach>()
                    .eq(Coach::getSortOrder, 2).last("LIMIT 1")).getId();
            groupClassService.createClass(coach1, groupServiceId(coach1), nextSaturday10am(), 1L);
            groupClassService.createClass(coach2, groupServiceId(coach2), nextSunday3pm(), 2L);
            log.info("seeded 2 demo group classes");
        } catch (Exception e) {
            log.warn("seed group classes skipped: {}", e.getMessage());
        }
    }

    private Long groupServiceId(Long coachId) {
        var service = coachServiceMapper.selectOne(new LambdaQueryWrapper<CoachService>()
                .eq(CoachService::getCoachId, coachId)
                .eq(CoachService::getType, com.lanjie.booking.enums.CoachServiceType.GROUP)
                .last("LIMIT 1"));
        return service != null ? service.getId() : null;
    }

    private LocalDateTime nextSaturday10am() {
        var d = LocalDate.now().with(TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SATURDAY));
        var t = LocalDateTime.of(d, LocalTime.of(10, 0));
        return t.isBefore(LocalDateTime.now()) ? t.plusDays(7) : t;
    }

    private LocalDateTime nextSunday3pm() {
        var d = LocalDate.now().with(TemporalAdjusters.next(java.time.DayOfWeek.SUNDAY));
        var t = LocalDateTime.of(d, LocalTime.of(15, 0));
        return t.isBefore(LocalDateTime.now()) ? t.plusDays(7) : t;
    }
}
