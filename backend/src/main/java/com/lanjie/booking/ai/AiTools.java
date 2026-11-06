package com.lanjie.booking.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanjie.booking.entity.Booking;
import com.lanjie.booking.entity.BookingItem;
import com.lanjie.booking.entity.Coach;
import com.lanjie.booking.entity.CoachPackage;
import com.lanjie.booking.entity.GroupClass;
import com.lanjie.booking.entity.KnowledgeChunk;
import com.lanjie.booking.entity.KnowledgeDoc;
import com.lanjie.booking.entity.User;
import com.lanjie.booking.enums.BookingStatus;
import com.lanjie.booking.mapper.BookingItemMapper;
import com.lanjie.booking.mapper.BookingMapper;
import com.lanjie.booking.mapper.CoachMapper;
import com.lanjie.booking.mapper.CoachPackageMapper;
import com.lanjie.booking.mapper.GroupClassMapper;
import com.lanjie.booking.mapper.KnowledgeChunkMapper;
import com.lanjie.booking.mapper.KnowledgeDocMapper;
import com.lanjie.booking.mapper.UserMapper;
import com.lanjie.booking.security.LoginUser;
import com.lanjie.booking.service.CoachQueryService;
import com.lanjie.booking.service.PickupService;
import com.lanjie.booking.service.VenueQueryService;
import com.lanjie.booking.vo.CourtVO;
import com.lanjie.booking.vo.SlotVO;
import com.lanjie.booking.vo.VenueVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 工具白名单（12 个）。只读工具即时执行；有副作用的锁位工具返回「待确认提案」，
 * 必须由用户在手机上点确认（/ai/confirmations/{token}）才真正执行——模型自己发不起锁位。
 */
@Slf4j
@Component
public class AiTools {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VenueQueryService venueQueryService;
    private final CoachQueryService coachQueryService;
    private final PickupService pickupService;
    private final BookingMapper bookingMapper;
    private final BookingItemMapper bookingItemMapper;
    private final CoachMapper coachMapper;
    private final CoachPackageMapper coachPackageMapper;
    private final GroupClassMapper groupClassMapper;
    private final UserMapper userMapper;
    private final KnowledgeDocMapper knowledgeDocMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final AiConfirmationService confirmationService;

    public AiTools(VenueQueryService venueQueryService, CoachQueryService coachQueryService,
                   PickupService pickupService, BookingMapper bookingMapper,
                   BookingItemMapper bookingItemMapper, CoachMapper coachMapper,
                   CoachPackageMapper coachPackageMapper, GroupClassMapper groupClassMapper,
                   UserMapper userMapper, KnowledgeDocMapper knowledgeDocMapper,
                   KnowledgeChunkMapper knowledgeChunkMapper, AiConfirmationService confirmationService) {
        this.venueQueryService = venueQueryService;
        this.coachQueryService = coachQueryService;
        this.pickupService = pickupService;
        this.bookingMapper = bookingMapper;
        this.bookingItemMapper = bookingItemMapper;
        this.coachMapper = coachMapper;
        this.coachPackageMapper = coachPackageMapper;
        this.groupClassMapper = groupClassMapper;
        this.userMapper = userMapper;
        this.knowledgeDocMapper = knowledgeDocMapper;
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.confirmationService = confirmationService;
    }

    public static final List<ChatModel.ToolSpec> SPECS = List.of(
            spec("get_venue_info", "查场馆营业时间、地址、电话", "{\"type\":\"object\",\"properties\":{}}"),
            spec("search_slots", "查某天可订的场地格子（最多 8 条）",
                    "{\"type\":\"object\",\"properties\":{\"dateHint\":{\"type\":\"string\",\"description\":\"today/tomorrow/weekend/2026-11-08\"}}}"),
            spec("hold_slots", "【有副作用】锁位 15 分钟：生成待确认提案，用户确认后才锁",
                    "{\"type\":\"object\",\"properties\":{\"slotIds\":{\"type\":\"array\",\"items\":{\"type\":\"integer\"}}},\"required\":[\"slotIds\"]}"),
            spec("list_coaches", "教练列表（含价格与专项）", "{\"type\":\"object\",\"properties\":{}}"),
            spec("get_coach_slots", "查教练某天档期",
                    "{\"type\":\"object\",\"properties\":{\"coachId\":{\"type\":\"integer\"},\"dateHint\":{\"type\":\"string\"}},\"required\":[\"coachId\"]}"),
            spec("hold_coach_slot", "【有副作用】锁私教课：教练档期+场地一起锁，需用户确认",
                    "{\"type\":\"object\",\"properties\":{\"coachSlotId\":{\"type\":\"integer\"},\"serviceId\":{\"type\":\"integer\"},\"courtSlotId\":{\"type\":\"integer\"}},\"required\":[\"coachSlotId\",\"serviceId\"]}"),
            spec("list_my_bookings", "查我的订单", "{\"type\":\"object\",\"properties\":{}}"),
            spec("get_booking", "查订单详情（仅本人）",
                    "{\"type\":\"object\",\"properties\":{\"bookingId\":{\"type\":\"integer\"}},\"required\":[\"bookingId\"]}"),
            spec("list_my_packages", "查我的课包", "{\"type\":\"object\",\"properties\":{}}"),
            spec("list_group_classes", "查某天团课",
                    "{\"type\":\"object\",\"properties\":{\"dateHint\":{\"type\":\"string\"}}}"),
            spec("get_policy", "查场馆规则（退款/改期/爽约/课包/团课/拼场），回答必须带出处",
                    "{\"type\":\"object\",\"properties\":{\"topic\":{\"type\":\"string\"}},\"required\":[\"topic\"]}"),
            spec("handoff_to_human", "转人工：退款/支付/投诉/超范围/连续听不懂",
                    "{\"type\":\"object\",\"properties\":{\"reason\":{\"type\":\"string\"},\"summary\":{\"type\":\"string\"}}}")
    );

    private static ChatModel.ToolSpec spec(String name, String desc, String params) {
        return new ChatModel.ToolSpec(name, desc, params);
    }

    public List<ChatModel.ToolSpec> specs() {
        return SPECS;
    }

    public String execute(String name, String argsJson, LoginUser user) {
        try {
            JsonNode args = objectMapper.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            return switch (name) {
                case "get_venue_info" -> venueInfo();
                case "search_slots" -> searchSlots(args.path("dateHint").asText("today"));
                case "hold_slots" -> proposeHoldSlots(args, user);
                case "list_coaches" -> JSON.write(objectMapper, Map.of("coaches", coachQueryService.coaches()));
                case "get_coach_slots" -> coachSlots(args);
                case "hold_coach_slot" -> proposeHoldCoach(args, user);
                case "list_my_bookings" -> myBookings(user);
                case "get_booking" -> bookingDetail(args, user);
                case "list_my_packages" -> JSON.write(objectMapper,
                        Map.of("packages", coachQueryService.myPackages(user.userId())));
                case "list_group_classes" -> groupClasses(args.path("dateHint").asText("today"));
                case "get_policy" -> policy(args.path("topic").asText("refund"));
                case "handoff_to_human" -> JSON.write(objectMapper, Map.of("handoff", true,
                        "reason", args.path("reason").asText("USER_REQUEST")));
                default -> JSON.write(objectMapper, Map.of("error", "unknown tool"));
            };
        } catch (Exception e) {
            log.warn("ai tool failed: {} {}", name, e.getMessage());
            return JSON.write(objectMapper, Map.of("error", "工具执行失败"));
        }
    }

    private String venueInfo() {
        VenueVO venue = venueQueryService.venue();
        return JSON.write(objectMapper, Map.of("venue", venue != null ? venue : Map.of()));
    }

    private String searchSlots(String hint) {
        LocalDate date = resolveDate(hint);
        List<SlotVO> slots = venueQueryService.slotsOfDate(date);
        Map<Long, String> courtNames = new LinkedHashMap<>();
        for (CourtVO court : venueQueryService.courts()) {
            courtNames.put(court.id(), court.name());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (SlotVO slot : slots) {
            if (!"AVAILABLE".equals(slot.status())) {
                continue;
            }
            result.add(Map.of("slotId", slot.id(),
                    "title", courtNames.getOrDefault(slot.courtId(), "场地") + " " + slot.startAt() + "-" + slot.endAt(),
                    "priceCents", slot.priceCents()));
            if (result.size() >= 8) {
                break;
            }
        }
        return JSON.write(objectMapper, Map.of("slots", result, "date", date.toString()));
    }

    private String proposeHoldSlots(JsonNode args, LoginUser user) {
        List<Long> slotIds = new ArrayList<>();
        for (JsonNode id : args.path("slotIds")) {
            slotIds.add(id.asLong());
        }
        if (slotIds.isEmpty()) {
            return JSON.write(objectMapper, Map.of("error", "缺少 slotIds"));
        }
        String token = confirmationService.propose(user.userId(), "HOLD_SLOTS",
                Map.of("slotIds", slotIds));
        return JSON.write(objectMapper, Map.of("proposal", true, "token", token,
                "summary", "锁位 15 分钟，需你确认"));
    }

    private String coachSlots(JsonNode args) {
        long coachId = args.path("coachId").asLong();
        LocalDate date = resolveDate(args.path("dateHint").asText("tomorrow"));
        return JSON.write(objectMapper, Map.of("slots", coachQueryService.slots(coachId, date)));
    }

    private String proposeHoldCoach(JsonNode args, LoginUser user) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("coachSlotId", args.path("coachSlotId").asLong());
        payload.put("serviceId", args.path("serviceId").asLong());
        if (args.has("courtSlotId")) {
            payload.put("courtSlotId", args.path("courtSlotId").asLong());
        }
        String token = confirmationService.propose(user.userId(), "HOLD_COACH", payload);
        return JSON.write(objectMapper, Map.of("proposal", true, "token", token,
                "summary", "锁私教课，需你确认"));
    }

    private String myBookings(LoginUser user) {
        List<Booking> bookings = bookingMapper.selectList(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getUserId, user.userId())
                .orderByDesc(Booking::getId)
                .last("LIMIT 5"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Booking booking : bookings) {
            BookingItem item = bookingItemMapper.selectOne(new LambdaQueryWrapper<BookingItem>()
                    .eq(BookingItem::getBookingId, booking.getId())
                    .orderByAsc(BookingItem::getStartAt)
                    .last("LIMIT 1"));
            result.add(Map.of("bookingId", booking.getId(), "status", booking.getStatus().name(),
                    "title", item != null ? item.getTitle() : "",
                    "payableCents", booking.getPayableCents()));
        }
        return JSON.write(objectMapper, Map.of("bookings", result));
    }

    private String bookingDetail(JsonNode args, LoginUser user) {
        Booking booking = bookingMapper.selectById(args.path("bookingId").asLong());
        if (booking == null || !booking.getUserId().equals(user.userId())) {
            return JSON.write(objectMapper, Map.of("error", "订单不存在"));
        }
        return JSON.write(objectMapper, Map.of("booking", Map.of(
                "bookingId", booking.getId(), "status", booking.getStatus().name(),
                "payableCents", booking.getPayableCents(), "paidCents", booking.getPaidCents())));
    }

    private String groupClasses(String hint) {
        LocalDate date = resolveDate(hint);
        List<GroupClass> classes = groupClassMapper.selectList(new LambdaQueryWrapper<GroupClass>()
                .apply("DATE(start_at) = {0}", date)
                .in(GroupClass::getStatus, "OPEN", "FULL")
                .orderByAsc(GroupClass::getStartAt));
        List<Map<String, Object>> result = new ArrayList<>();
        for (GroupClass clazz : classes) {
            result.add(Map.of("classId", clazz.getId(), "title", clazz.getTitle(),
                    "startAt", clazz.getStartAt().toString(), "priceCents", clazz.getPriceCents(),
                    "enrolled", clazz.getEnrolledCount(), "max", clazz.getMaxStudents()));
        }
        return JSON.write(objectMapper, Map.of("classes", result, "date", date.toString()));
    }

    /** 规则检索：按主题命中知识块，返回内容 + 出处（标题），查不到明确说不知道 */
    private String policy(String topic) {
        String normalized = topic == null ? "" : topic.trim().toLowerCase();
        KnowledgeDoc doc = knowledgeDocMapper.selectList(new LambdaQueryWrapper<KnowledgeDoc>()
                        .eq(KnowledgeDoc::getStatus, "ACTIVE"))
                .stream()
                .filter(d -> d.getCategory().toLowerCase().contains(normalized)
                        || normalized.contains(d.getCategory().toLowerCase()))
                .findFirst()
                .orElse(null);
        if (doc == null) {
            return JSON.write(objectMapper, Map.of("policies", List.of(),
                    "note", "未找到相关规则，建议转人工"));
        }
        List<KnowledgeChunk> chunks = knowledgeChunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getDocId, doc.getId())
                        .orderByAsc(KnowledgeChunk::getSeq));
        List<Map<String, Object>> result = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks) {
            result.add(Map.of("title", doc.getTitle(), "content", chunk.getContent(),
                    "source", "knowledge_doc:" + doc.getId()));
        }
        return JSON.write(objectMapper, Map.of("policies", result));
    }

    private LocalDate resolveDate(String hint) {
        LocalDate today = LocalDate.now();
        return switch (hint == null ? "today" : hint) {
            case "tomorrow" -> today.plusDays(1);
            case "weekend" -> today.with(TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SATURDAY));
            default -> {
                try {
                    yield LocalDate.parse(hint);
                } catch (Exception e) {
                    yield today;
                }
            }
        };
    }

    /** 简单 JSON 输出工具 */
    static final class JSON {
        static String write(ObjectMapper mapper, Object value) {
            try {
                return mapper.writeValueAsString(value);
            } catch (Exception e) {
                return "{}";
            }
        }
    }
}
