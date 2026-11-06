package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.common.BusinessException;
import com.lanjie.booking.common.ErrorCode;
import com.lanjie.booking.dto.CreatePickupRequest;
import com.lanjie.booking.entity.Booking;
import com.lanjie.booking.entity.BookingItem;
import com.lanjie.booking.entity.Court;
import com.lanjie.booking.entity.PickupGame;
import com.lanjie.booking.entity.PickupParticipant;
import com.lanjie.booking.entity.Slot;
import com.lanjie.booking.entity.User;
import com.lanjie.booking.entity.UserBan;
import com.lanjie.booking.enums.BookingItemType;
import com.lanjie.booking.enums.BookingStatus;
import com.lanjie.booking.enums.BookingType;
import com.lanjie.booking.enums.SlotStatus;
import com.lanjie.booking.enums.UserStatus;
import com.lanjie.booking.enums.WalletTxnType;
import com.lanjie.booking.mapper.BookingItemMapper;
import com.lanjie.booking.mapper.BookingMapper;
import com.lanjie.booking.mapper.CourtMapper;
import com.lanjie.booking.mapper.PickupGameMapper;
import com.lanjie.booking.mapper.PickupParticipantMapper;
import com.lanjie.booking.mapper.SlotMapper;
import com.lanjie.booking.mapper.UserBanMapper;
import com.lanjie.booking.mapper.UserMapper;
import com.lanjie.booking.security.LoginUser;
import com.lanjie.booking.vo.HoldVO;
import com.lanjie.booking.vo.ItemVO;
import com.lanjie.booking.vo.PickupGameVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 拼场：发起人付全场并锁定场地 → 参与者付份子钱（平台托管）→ 开赛 1 小时后结算给发起人。
 * 取消：参与者 ≥24h 全退；发起人取消时场地按场地阶梯、份子钱全退；场馆原因全退。
 */
@Slf4j
@Service
public class PickupService {

    private static final Map<String, Integer> FORMAT_PLAYERS = Map.of("3v3", 6, "4v4", 8, "5v5", 10);
    private static final Duration HOLD_TTL = Duration.ofMinutes(15);
    private static final DateTimeFormatter ITEM_DATE = DateTimeFormatter.ofPattern("MM-dd");

    private final PickupGameMapper gameMapper;
    private final PickupParticipantMapper participantMapper;
    private final BookingMapper bookingMapper;
    private final BookingItemMapper bookingItemMapper;
    private final SlotMapper slotMapper;
    private final CourtMapper courtMapper;
    private final UserMapper userMapper;
    private final UserBanMapper userBanMapper;
    private final WalletService walletService;
    private final RefundService refundService;
    private final BookingAuditService audit;
    private final StringRedisTemplate redis;

    public PickupService(PickupGameMapper gameMapper, PickupParticipantMapper participantMapper,
                         BookingMapper bookingMapper, BookingItemMapper bookingItemMapper,
                         SlotMapper slotMapper, CourtMapper courtMapper, UserMapper userMapper,
                         UserBanMapper userBanMapper, WalletService walletService,
                         RefundService refundService, BookingAuditService audit,
                         StringRedisTemplate redis) {
        this.gameMapper = gameMapper;
        this.participantMapper = participantMapper;
        this.bookingMapper = bookingMapper;
        this.bookingItemMapper = bookingItemMapper;
        this.slotMapper = slotMapper;
        this.courtMapper = courtMapper;
        this.userMapper = userMapper;
        this.userBanMapper = userBanMapper;
        this.walletService = walletService;
        this.refundService = refundService;
        this.audit = audit;
        this.redis = redis;
    }

    /** 发起拼场：锁定场地并建待支付订单（付全场），支付成功后场次才 OPEN */
    @Transactional
    public HoldVO create(LoginUser loginUser, CreatePickupRequest request) {
        User user = checkUser(loginUser.userId());
        Integer totalPlayers = FORMAT_PLAYERS.get(request.getFormat());
        if (totalPlayers == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "赛制为 3v3 / 4v4 / 5v5");
        }
        Slot slot = slotMapper.selectById(request.getSlotId());
        if (slot == null || slot.getStatus() != SlotStatus.AVAILABLE
                || slot.getStartAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.SLOT_TAKEN, "该时段不可预订");
        }
        Long duplicate = gameMapper.selectCount(new LambdaQueryWrapper<PickupGame>()
                .eq(PickupGame::getSlotId, slot.getId())
                .in(PickupGame::getStatus, "OPEN", "FULL", "PENDING_PAYMENT"));
        if (duplicate > 0) {
            throw new BusinessException(ErrorCode.SLOT_TAKEN, "该时段已有拼场");
        }

        int pricePerPerson = Math.max(1, slot.getPriceCents() / totalPlayers);
        String token = UUID.randomUUID().toString().replace("-", "");
        String key = "lanjie:hold:" + slot.getId();
        boolean acquired = acquireFastFail(key, token);
        try {
            slotMapper.reclaimExpired(List.of(slot.getId()), LocalDateTime.now());
            Court court = courtMapper.selectById(slot.getCourtId());
            String courtName = court != null ? court.getName() : "场地";
            String title = "%s 拼场 %s %s-%s".formatted(courtName, slot.getSlotDate().format(ITEM_DATE),
                    slot.getStartAt().toLocalTime(), slot.getEndAt().toLocalTime());

            Booking booking = new Booking();
            booking.setBookingNo(newNo("BJ"));
            booking.setUserId(user.getId());
            booking.setVenueId(slot.getVenueId());
            booking.setType(BookingType.PICKUP);
            booking.setStatus(BookingStatus.PENDING_PAYMENT);
            booking.setHoldExpiresAt(LocalDateTime.now().plus(HOLD_TTL));
            booking.setListAmountCents(slot.getPriceCents());
            booking.setDiscountCents(0);
            booking.setPayableCents(slot.getPriceCents());
            booking.setPaidCents(0);
            booking.setRescheduleCount(0);
            bookingMapper.insert(booking);

            BookingItem item = new BookingItem();
            item.setBookingId(booking.getId());
            item.setItemType(BookingItemType.COURT_SLOT);
            item.setRefId(slot.getId());
            item.setTitle(title);
            item.setStartAt(slot.getStartAt());
            item.setEndAt(slot.getEndAt());
            item.setUnitPriceCents(slot.getPriceCents());
            item.setAmountCents(slot.getPriceCents());
            item.setRefundAmountCents(0);
            bookingItemMapper.insert(item);

            int held = slotMapper.holdSlots(List.of(slot.getId()), token, booking.getHoldExpiresAt(), booking.getId());
            if (held != 1) {
                throw new BusinessException(ErrorCode.SLOT_TAKEN);
            }

            PickupGame game = new PickupGame();
            game.setInitiatorId(user.getId());
            game.setBookingId(booking.getId());
            game.setSlotId(slot.getId());
            game.setFormat(request.getFormat());
            game.setTotalPlayers(totalPlayers);
            game.setJoinedPlayers(1);
            game.setPricePerPersonCents(pricePerPerson);
            game.setNote(request.getNote() == null ? "" : request.getNote());
            game.setStatus("PENDING_PAYMENT");
            game.setSettled(false);
            gameMapper.insert(game);

            audit.write(booking.getId(), null, BookingStatus.PENDING_PAYMENT.name(), "USER", "发起拼场");
            return new HoldVO(booking.getId(), booking.getBookingNo(), booking.getPayableCents(),
                    booking.getHoldExpiresAt().toString(),
                    List.of(new ItemVO(title, slot.getStartAt().toString(),
                            slot.getEndAt().toString(), slot.getPriceCents())));
        } finally {
            if (acquired) {
                redis.delete(List.of(key));
            }
        }
    }

    /** 加入拼场：付一份份子钱（平台托管） */
    @Transactional
    public HoldVO join(LoginUser loginUser, Long gameId) {
        User user = checkUser(loginUser.userId());
        PickupGame game = load(gameId);
        if (!"OPEN".equals(game.getStatus())) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID,
                    "FULL".equals(game.getStatus()) ? "已满员" : "该拼场已结束或取消");
        }
        if (game.getInitiatorId().equals(user.getId())) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID, "你是发起人，无需加入");
        }
        Slot slot = slotMapper.selectById(game.getSlotId());
        if (slot == null || slot.getStartAt().isBefore(LocalDateTime.now().plusHours(0))) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID, "该场次已开始");
        }
        PickupParticipant existing = participantMapper.selectOne(new LambdaQueryWrapper<PickupParticipant>()
                .eq(PickupParticipant::getGameId, gameId)
                .eq(PickupParticipant::getUserId, user.getId())
                .last("LIMIT 1"));
        if (existing != null && !"CANCELLED".equals(existing.getStatus())) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID, "已在拼场中");
        }

        int share = game.getPricePerPersonCents();
        Booking booking = new Booking();
        booking.setBookingNo(newNo("BJ"));
        booking.setUserId(user.getId());
        booking.setVenueId(slot.getVenueId());
        booking.setType(BookingType.PICKUP);
        booking.setStatus(BookingStatus.PENDING_PAYMENT);
        booking.setHoldExpiresAt(LocalDateTime.now().plus(HOLD_TTL));
        booking.setListAmountCents(share);
        booking.setDiscountCents(0);
        booking.setPayableCents(share);
        booking.setPaidCents(0);
        booking.setRescheduleCount(0);
        bookingMapper.insert(booking);

        BookingItem item = new BookingItem();
        item.setBookingId(booking.getId());
        item.setItemType(BookingItemType.PICKUP_SHARE);
        item.setRefId(gameId);
        item.setTitle("拼场份子钱 " + game.getFormat());
        item.setStartAt(slot.getStartAt());
        item.setEndAt(slot.getEndAt());
        item.setUnitPriceCents(share);
        item.setAmountCents(share);
        item.setRefundAmountCents(0);
        bookingItemMapper.insert(item);

        PickupParticipant participant = existing != null ? existing : new PickupParticipant();
        participant.setGameId(gameId);
        participant.setUserId(user.getId());
        participant.setBookingId(booking.getId());
        participant.setStatus("PENDING_PAYMENT");
        if (participant.getId() != null) {
            participantMapper.updateById(participant);
        } else {
            participantMapper.insert(participant);
        }
        // 加入即条件占坑，防并发超员；未支付超时由 settle 释放
        if (gameMapper.joinOne(gameId) == 0) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID, "拼场已满员");
        }
        PickupGame freshGame = gameMapper.selectById(gameId);
        if (freshGame.getJoinedPlayers() >= freshGame.getTotalPlayers()) {
            gameMapper.updateStatus(gameId, "FULL");
        }
        return new HoldVO(booking.getId(), booking.getBookingNo(), share,
                booking.getHoldExpiresAt().toString(),
                List.of(new ItemVO(item.getTitle(), slot.getStartAt().toString(),
                        slot.getEndAt().toString(), share)));
    }

    /** 支付回调：发起人 → 场次 OPEN；参与者 → 占坑确认 */
    @Transactional
    public void onBookingPaid(Booking booking) {
        PickupGame asInitiator = gameMapper.selectOne(new LambdaQueryWrapper<PickupGame>()
                .eq(PickupGame::getBookingId, booking.getId())
                .last("LIMIT 1"));
        if (asInitiator != null) {
            asInitiator.setStatus("OPEN");
            gameMapper.updateById(asInitiator);
            PickupParticipant initiator = new PickupParticipant();
            initiator.setGameId(asInitiator.getId());
            initiator.setUserId(booking.getUserId());
            initiator.setBookingId(booking.getId());
            initiator.setStatus("CONFIRMED");
            participantMapper.insert(initiator);
            return;
        }
        PickupParticipant participant = participantMapper.selectOne(new LambdaQueryWrapper<PickupParticipant>()
                .eq(PickupParticipant::getBookingId, booking.getId())
                .last("LIMIT 1"));
        if (participant == null) {
            return;
        }
        participant.setStatus("CONFIRMED");
        participantMapper.updateById(participant);
    }

    /** 订单取消回调：发起人取消 → 全场次取消 + 份子钱全退；参与者取消 → 按 24h 规则 */
    @Transactional
    public void handleBookingCancelled(Booking booking) {
        PickupGame asInitiator = gameMapper.selectOne(new LambdaQueryWrapper<PickupGame>()
                .eq(PickupGame::getBookingId, booking.getId())
                .last("LIMIT 1"));
        if (asInitiator != null) {
            cancelGameInternal(asInitiator, "发起人取消");
            return;
        }
        PickupParticipant participant = participantMapper.selectOne(new LambdaQueryWrapper<PickupParticipant>()
                .eq(PickupParticipant::getBookingId, booking.getId())
                .last("LIMIT 1"));
        if (participant == null) {
            return;
        }
        participant.setStatus("CANCELLED");
        participantMapper.updateById(participant);
        // 加入时已占坑，任何状态的取消都释放
        gameMapper.leaveOne(participant.getGameId());
        PickupGame game = gameMapper.selectById(participant.getGameId());
        if (game != null && "FULL".equals(game.getStatus())) {
            gameMapper.updateStatus(game.getId(), "OPEN");
        }
    }

    public boolean isInitiatorBooking(Long bookingId) {
        return gameMapper.selectCount(new LambdaQueryWrapper<PickupGame>()
                .eq(PickupGame::getBookingId, bookingId)) > 0;
    }

    /** 场馆原因取消：所有人全退 */
    @Transactional
    public void cancelGameInternal(PickupGame game, String reason) {
        List<PickupParticipant> participants = participantMapper.selectList(
                new LambdaQueryWrapper<PickupParticipant>()
                        .eq(PickupParticipant::getGameId, game.getId())
                        .in(PickupParticipant::getStatus, "CONFIRMED", "PENDING_PAYMENT"));
        for (PickupParticipant participant : participants) {
            if (game.getBookingId().equals(participant.getBookingId())) {
                participant.setStatus("CANCELLED");
                participantMapper.updateById(participant);
                continue; // 发起人自己的订单由取消流程按场地规则处理，避免重复退款
            }
            Booking booking = bookingMapper.selectById(participant.getBookingId());
            if (booking == null) {
                continue;
            }
            if (booking.getStatus() == BookingStatus.CONFIRMED) {
                if (bookingMapper.markCancelledConfirmed(booking.getId(), "PICKUP_CANCELLED") > 0
                        && booking.getPaidCents() > 0) {
                    refundService.refundForReschedule(booking, booking.getPaidCents(), reason);
                }
            } else if (booking.getStatus() == BookingStatus.PENDING_PAYMENT) {
                bookingMapper.markCancelled(booking.getId(), "PICKUP_CANCELLED");
            }
            participant.setStatus("CANCELLED");
            participantMapper.updateById(participant);
        }
        gameMapper.updateStatus(game.getId(), "CANCELLED");
        audit.write(game.getBookingId(), "OPEN", "CANCELLED", "SYSTEM", reason);
        log.warn("pickup game cancelled: id={} reason={}", game.getId(), reason);
    }

    public List<PickupGameVO> list(LoginUser loginUser, LocalDate date, String format) {
        LambdaQueryWrapper<PickupGame> query = new LambdaQueryWrapper<PickupGame>()
                .in(PickupGame::getStatus, "OPEN", "FULL")
                .orderByAsc(PickupGame::getCreatedAt)
                .last("LIMIT 50");
        if (format != null && !format.isBlank()) {
            query.eq(PickupGame::getFormat, format);
        }
        List<PickupGame> games = gameMapper.selectList(query);
        return games.stream()
                .filter(game -> {
                    Slot slot = slotMapper.selectById(game.getSlotId());
                    return slot != null && (date == null || slot.getSlotDate().equals(date));
                })
                .map(game -> toVO(game, loginUser.userId()))
                .toList();
    }

    public PickupGameVO detail(LoginUser loginUser, Long gameId) {
        return toVO(load(gameId), loginUser.userId());
    }

    /** 参与者的份子钱订单 id（取消用），非参与者返回 null */
    public Long participantBookingId(Long userId, Long gameId) {
        PickupParticipant participant = participantMapper.selectOne(new LambdaQueryWrapper<PickupParticipant>()
                .eq(PickupParticipant::getGameId, gameId)
                .eq(PickupParticipant::getUserId, userId)
                .last("LIMIT 1"));
        return participant != null && participant.getBookingId() != null ? participant.getBookingId() : null;
    }

    /** 结算：开赛 1 小时后把托管份子钱打给发起人（唯一键幂等） */
    @Transactional
    public void settle() {
        // 未支付超时：释放加入时占的坑
        for (PickupParticipant stale : participantMapper.selectList(new LambdaQueryWrapper<PickupParticipant>()
                .eq(PickupParticipant::getStatus, "PENDING_PAYMENT"))) {
            Booking staleBooking = bookingMapper.selectById(stale.getBookingId());
            if (staleBooking == null || staleBooking.getStatus() == BookingStatus.CANCELLED) {
                stale.setStatus("CANCELLED");
                participantMapper.updateById(stale);
                gameMapper.leaveOne(stale.getGameId());
            }
        }
        List<PickupGame> games = gameMapper.selectList(new LambdaQueryWrapper<PickupGame>()
                .in(PickupGame::getStatus, "OPEN", "FULL")
                .eq(PickupGame::getSettled, false));
        LocalDateTime now = LocalDateTime.now();
        for (PickupGame game : games) {
            Slot slot = slotMapper.selectById(game.getSlotId());
            if (slot == null) {
                continue;
            }
            // 开赛前 2 小时内仍未满员：自动流局，所有人全额退款并释放场地；
            // 已开赛的不再追溯，按原结算规则处理
            if (game.getJoinedPlayers() < game.getTotalPlayers()
                    && slot.getStartAt().isAfter(now)
                    && !slot.getStartAt().isAfter(now.plusHours(2))) {
                abortGame(game, "人数不足，自动流局", "SYSTEM");
                continue;
            }
            if (slot.getStartAt().plusHours(1).isAfter(now)) {
                continue;
            }
            long shares = participantMapper.selectList(new LambdaQueryWrapper<PickupParticipant>()
                            .eq(PickupParticipant::getGameId, game.getId())
                            .eq(PickupParticipant::getStatus, "CONFIRMED")
                            .ne(PickupParticipant::getUserId, game.getInitiatorId()))
                    .stream()
                    .mapToLong(p -> {
                        Booking booking = bookingMapper.selectById(p.getBookingId());
                        return booking != null && booking.getStatus() == BookingStatus.CONFIRMED
                                ? booking.getPaidCents() : 0;
                    })
                    .sum();
            if (shares > 0) {
                walletService.credit(game.getInitiatorId(), shares, WalletTxnType.PICKUP_SETTLEMENT,
                        "PICKUP_GAME", game.getId());
            }
            gameMapper.markSettled(game.getId());
            log.info("pickup settled: game={} initiator={} shares={}", game.getId(), game.getInitiatorId(), shares);
            if (slot.getEndAt().isBefore(now)) {
                gameMapper.updateStatus(game.getId(), "COMPLETED");
            }
        }
    }

    // ---- 管理端处置：流局 / 发起人缺席 / 单个份子钱争议 ----

    public List<Map<String, Object>> adminGames(LocalDate date, String status) {
        List<PickupGame> games = gameMapper.selectList(new LambdaQueryWrapper<PickupGame>()
                .orderByDesc(PickupGame::getId).last("LIMIT 200"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (PickupGame game : games) {
            Slot slot = slotMapper.selectById(game.getSlotId());
            if (slot == null || (date != null && !date.equals(slot.getSlotDate()))) {
                continue;
            }
            if (status != null && !status.isBlank() && !status.equals(game.getStatus())) {
                continue;
            }
            Court court = courtMapper.selectById(slot.getCourtId());
            User initiator = userMapper.selectById(game.getInitiatorId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", game.getId());
            row.put("status", game.getStatus());
            row.put("format", game.getFormat());
            row.put("courtName", court != null ? court.getName() : "");
            row.put("startAt", slot.getStartAt());
            row.put("endAt", slot.getEndAt());
            row.put("pricePerPersonCents", game.getPricePerPersonCents());
            row.put("joinedPlayers", game.getJoinedPlayers());
            row.put("totalPlayers", game.getTotalPlayers());
            row.put("settled", game.getSettled());
            row.put("initiatorId", game.getInitiatorId());
            row.put("initiatorName", initiator != null ? initiator.getNickname() : "");
            row.put("initiatorPhone", initiator != null ? initiator.getPhone() : "");
            row.put("createdAt", game.getCreatedAt());
            result.add(row);
        }
        return result;
    }

    public Map<String, Object> adminDetail(Long gameId) {
        PickupGame game = load(gameId);
        Slot slot = slotMapper.selectById(game.getSlotId());
        Court court = slot != null ? courtMapper.selectById(slot.getCourtId()) : null;
        List<PickupParticipant> participants = participantMapper.selectList(
                new LambdaQueryWrapper<PickupParticipant>()
                        .eq(PickupParticipant::getGameId, gameId)
                        .orderByAsc(PickupParticipant::getCreatedAt));
        List<Map<String, Object>> roster = new ArrayList<>();
        for (PickupParticipant participant : participants) {
            User user = userMapper.selectById(participant.getUserId());
            Booking booking = participant.getBookingId() != null
                    ? bookingMapper.selectById(participant.getBookingId()) : null;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("participantId", participant.getId());
            row.put("userId", participant.getUserId());
            row.put("userName", user != null ? user.getNickname() : "");
            row.put("userPhone", user != null ? user.getPhone() : "");
            row.put("status", participant.getStatus());
            row.put("bookingId", participant.getBookingId());
            row.put("bookingStatus", booking != null ? booking.getStatus().name() : "");
            row.put("paidCents", booking != null ? booking.getPaidCents() : 0);
            row.put("initiator", game.getInitiatorId().equals(participant.getUserId()));
            roster.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("game", game);
        data.put("courtName", court != null ? court.getName() : "");
        data.put("startAt", slot != null ? slot.getStartAt() : null);
        data.put("endAt", slot != null ? slot.getEndAt() : null);
        data.put("roster", roster);
        return data;
    }

    /** 店长手动流局：所有人（含发起人）全额退款，释放场地。 */
    @Transactional
    public int adminAbort(Long gameId, String reason) {
        PickupGame game = load(gameId);
        if ("CANCELLED".equals(game.getStatus()) || "COMPLETED".equals(game.getStatus())) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID, "该拼场已结束或已取消");
        }
        String safeReason = reason == null || reason.isBlank() ? "场馆原因流局" : reason;
        return abortGame(game, safeReason, "ADMIN");
    }

    /** 发起人缺席：退所有参与者份子钱，发起人场地费不退，场次标记完成。 */
    @Transactional
    public int adminInitiatorNoShow(Long gameId, String reason) {
        PickupGame game = load(gameId);
        if (!"OPEN".equals(game.getStatus()) && !"FULL".equals(game.getStatus())) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID, "当前拼场状态不能做缺席处理");
        }
        if (Boolean.TRUE.equals(game.getSettled())) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID, "该场次已结算");
        }
        String safeReason = reason == null || reason.isBlank() ? "发起人缺席" : reason;
        int refunded = 0;
        for (PickupParticipant participant : participantMapper.selectList(
                new LambdaQueryWrapper<PickupParticipant>()
                        .eq(PickupParticipant::getGameId, gameId)
                        .ne(PickupParticipant::getUserId, game.getInitiatorId())
                        .in(PickupParticipant::getStatus, "CONFIRMED", "PENDING_PAYMENT"))) {
            Booking booking = bookingMapper.selectById(participant.getBookingId());
            if (booking != null && booking.getStatus() == BookingStatus.CONFIRMED) {
                if (bookingMapper.markCancelledConfirmed(booking.getId(), "INITIATOR_NO_SHOW") > 0
                        && booking.getPaidCents() > 0) {
                    refundService.refundForReschedule(booking, booking.getPaidCents(), safeReason);
                    refunded++;
                }
            } else if (booking != null && booking.getStatus() == BookingStatus.PENDING_PAYMENT) {
                bookingMapper.markCancelled(booking.getId(), "INITIATOR_NO_SHOW");
            }
            participant.setStatus("CANCELLED");
            participantMapper.updateById(participant);
        }
        PickupParticipant initiator = participantMapper.selectOne(new LambdaQueryWrapper<PickupParticipant>()
                .eq(PickupParticipant::getGameId, gameId)
                .eq(PickupParticipant::getUserId, game.getInitiatorId())
                .last("LIMIT 1"));
        if (initiator != null) {
            initiator.setStatus("NO_SHOW");
            participantMapper.updateById(initiator);
        }
        Booking initiatorBooking = bookingMapper.selectById(game.getBookingId());
        if (initiatorBooking != null && initiatorBooking.getStatus() == BookingStatus.CONFIRMED) {
            initiatorBooking.setStatus(BookingStatus.NO_SHOW);
            initiatorBooking.setCancelReason("PICKUP_INITIATOR_NO_SHOW");
            bookingMapper.updateById(initiatorBooking);
            audit.write(initiatorBooking.getId(), BookingStatus.CONFIRMED.name(), BookingStatus.NO_SHOW.name(),
                    "ADMIN", safeReason);
        }
        gameMapper.markSettled(gameId);
        gameMapper.updateStatus(gameId, "COMPLETED");
        log.warn("pickup initiator no-show: game={} refunded={}", gameId, refunded);
        return refunded;
    }

    /** 单个参与者份子钱争议：全额退款并释放坑位。 */
    @Transactional
    public void adminRefundParticipant(Long gameId, Long participantId, String reason) {
        PickupGame game = load(gameId);
        PickupParticipant participant = participantMapper.selectById(participantId);
        if (participant == null || !participant.getGameId().equals(gameId)) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_FOUND, "报名记录不存在");
        }
        if (game.getInitiatorId().equals(participant.getUserId())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "发起人退款请走整场流局");
        }
        if ("CANCELLED".equals(participant.getStatus())) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID, "该报名已取消");
        }
        Booking booking = bookingMapper.selectById(participant.getBookingId());
        if (booking != null && booking.getStatus() == BookingStatus.CONFIRMED) {
            if (bookingMapper.markCancelledConfirmed(booking.getId(), "PICKUP_SHARE_REFUND") > 0
                    && booking.getPaidCents() > 0) {
                refundService.refundForReschedule(booking, booking.getPaidCents(),
                        reason == null || reason.isBlank() ? "份子钱争议退款" : reason);
            }
        } else if (booking != null && booking.getStatus() == BookingStatus.PENDING_PAYMENT) {
            bookingMapper.markCancelled(booking.getId(), "PICKUP_SHARE_REFUND");
        }
        participant.setStatus("CANCELLED");
        participantMapper.updateById(participant);
        gameMapper.leaveOne(gameId);
        PickupGame fresh = gameMapper.selectById(gameId);
        if (fresh != null && "FULL".equals(fresh.getStatus())) {
            gameMapper.updateStatus(gameId, "OPEN");
        }
        if (booking != null) {
            audit.write(booking.getId(), BookingStatus.CONFIRMED.name(), BookingStatus.CANCELLED.name(),
                    "ADMIN", "份子钱退款：" + reason);
        }
    }

    private int abortGame(PickupGame game, String reason, String actor) {
        if ("CANCELLED".equals(game.getStatus()) || "COMPLETED".equals(game.getStatus())) {
            return 0;
        }
        String fromStatus = game.getStatus();
        int refunded = 0;
        for (PickupParticipant participant : participantMapper.selectList(
                new LambdaQueryWrapper<PickupParticipant>()
                        .eq(PickupParticipant::getGameId, game.getId())
                        .in(PickupParticipant::getStatus, "CONFIRMED", "PENDING_PAYMENT", "NO_SHOW"))) {
            Booking booking = bookingMapper.selectById(participant.getBookingId());
            if (booking != null && booking.getStatus() == BookingStatus.CONFIRMED) {
                if (bookingMapper.markCancelledConfirmed(booking.getId(), "PICKUP_ABORTED") > 0
                        && booking.getPaidCents() > 0) {
                    refundService.refundForReschedule(booking, booking.getPaidCents(), reason);
                    refunded++;
                }
            } else if (booking != null && booking.getStatus() == BookingStatus.PENDING_PAYMENT) {
                bookingMapper.markCancelled(booking.getId(), "PICKUP_ABORTED");
            }
            participant.setStatus("CANCELLED");
            participantMapper.updateById(participant);
        }
        // 发起人付的是整场，释放场地（含锁位中的情况）
        slotMapper.releaseBookedByBooking(game.getBookingId());
        slotMapper.releaseByBooking(game.getBookingId());
        gameMapper.updateStatus(game.getId(), "CANCELLED");
        audit.write(game.getBookingId(), fromStatus, "CANCELLED", actor, reason);
        log.warn("pickup game aborted: id={} reason={} refunded={}", game.getId(), reason, refunded);
        return refunded;
    }

    private PickupGameVO toVO(PickupGame game, Long viewerId) {
        Slot slot = slotMapper.selectById(game.getSlotId());
        Court court = slot != null ? courtMapper.selectById(slot.getCourtId()) : null;
        List<PickupGameVO.ParticipantVO> participants = participantMapper.selectList(
                        new LambdaQueryWrapper<PickupParticipant>()
                                .eq(PickupParticipant::getGameId, game.getId())
                                .in(PickupParticipant::getStatus, "CONFIRMED", "PENDING_PAYMENT")
                                .orderByAsc(PickupParticipant::getCreatedAt))
                .stream()
                .map(p -> {
                    User user = userMapper.selectById(p.getUserId());
                    return new PickupGameVO.ParticipantVO(p.getUserId(),
                            user != null ? user.getNickname() : "", p.getStatus());
                })
                .toList();
        return new PickupGameVO(game.getId(), game.getSlotId(),
                court != null ? court.getName() : "",
                slot != null ? slot.getStartAt().toString() : "",
                slot != null ? slot.getEndAt().toString() : "",
                game.getFormat(), game.getTotalPlayers(), game.getJoinedPlayers(),
                game.getPricePerPersonCents(), game.getNote(), game.getStatus(),
                Boolean.TRUE.equals(game.getSettled()), game.getInitiatorId().equals(viewerId), participants);
    }

    private PickupGame load(Long gameId) {
        PickupGame game = gameMapper.selectById(gameId);
        if (game == null) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_FOUND, "拼场不存在");
        }
        return game;
    }

    private User checkUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.USER_FROZEN);
        }
        UserBan ban = userBanMapper.selectOne(new LambdaQueryWrapper<UserBan>()
                .eq(UserBan::getUserId, userId)
                .gt(UserBan::getEndAt, LocalDateTime.now())
                .last("LIMIT 1"));
        if (ban != null) {
            throw new BusinessException(ErrorCode.USER_BANNED,
                    "因爽约被暂停预订，" + ban.getEndAt().toLocalDate() + " 恢复");
        }
        return user;
    }

    private boolean acquireFastFail(String key, String token) {
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(key, token, Duration.ofSeconds(15));
            return ok != null && ok;
        } catch (Exception e) {
            return false;
        }
    }

    private String newNo(String prefix) {
        return prefix + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }
}
