package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.common.BusinessException;
import com.lanjie.booking.common.ErrorCode;
import com.lanjie.booking.entity.Booking;
import com.lanjie.booking.entity.PayOrder;
import com.lanjie.booking.entity.RefundOrder;
import com.lanjie.booking.entity.User;
import com.lanjie.booking.entity.WalletTxn;
import com.lanjie.booking.enums.PayOrderStatus;
import com.lanjie.booking.enums.PayPurpose;
import com.lanjie.booking.enums.RefundStatus;
import com.lanjie.booking.enums.WalletTxnType;
import com.lanjie.booking.mapper.BookingMapper;
import com.lanjie.booking.mapper.PayOrderMapper;
import com.lanjie.booking.mapper.RefundOrderMapper;
import com.lanjie.booking.mapper.UserMapper;
import com.lanjie.booking.mapper.WalletTxnMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 前台财务视图：营收/退款趋势、收支流水、CSV 导出。
 * 营收不含充值（充值属于预收款），课包销售计入营收，退款单列后算净额。
 */
@Service
public class AdminFinanceService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final PayOrderMapper payOrderMapper;
    private final RefundOrderMapper refundOrderMapper;
    private final BookingMapper bookingMapper;
    private final UserMapper userMapper;
    private final WalletTxnMapper walletTxnMapper;

    public AdminFinanceService(PayOrderMapper payOrderMapper, RefundOrderMapper refundOrderMapper,
                               BookingMapper bookingMapper, UserMapper userMapper,
                               WalletTxnMapper walletTxnMapper) {
        this.payOrderMapper = payOrderMapper;
        this.refundOrderMapper = refundOrderMapper;
        this.bookingMapper = bookingMapper;
        this.userMapper = userMapper;
        this.walletTxnMapper = walletTxnMapper;
    }

    public Map<String, Object> summary(int days) {
        int span = Math.max(1, Math.min(days, 90));
        LocalDate from = LocalDate.now().minusDays(span - 1L);
        LocalDate to = LocalDate.now();
        List<PayOrder> pays = pays(from, to);
        List<RefundOrder> refunds = refunds(from, to);
        Map<Long, Booking> bookings = bookingsOf(pays);

        long revenue = 0;
        long recharge = 0;
        long orderCount = 0;
        long bookingRevenue = 0;
        Map<String, Long> byType = new LinkedHashMap<>();
        Map<String, Long> byChannel = new LinkedHashMap<>();
        Map<LocalDate, long[]> trend = new LinkedHashMap<>();
        for (int i = 0; i < span; i++) {
            trend.put(from.plusDays(i), new long[3]);
        }
        for (PayOrder pay : pays) {
            LocalDate day = pay.getPaidAt().toLocalDate();
            long[] row = trend.get(day);
            if (pay.getPurpose() == PayPurpose.RECHARGE) {
                recharge += pay.getAmountCents();
                continue;
            }
            revenue += pay.getAmountCents();
            if (row != null) {
                row[0] += pay.getAmountCents();
            }
            String type = pay.getPurpose() == PayPurpose.COACH_PACKAGE ? "PACKAGE"
                    : pay.getPurpose() == PayPurpose.RESCHEDULE_DIFF || pay.getPurpose() == PayPurpose.BOOKING
                    ? bookingType(bookings.get(pay.getBookingId()))
                    : pay.getPurpose().name();
            byType.merge(type, (long) pay.getAmountCents(), Long::sum);
            byChannel.merge(pay.getChannel().name(), (long) pay.getAmountCents(), Long::sum);
            if (pay.getPurpose() == PayPurpose.BOOKING) {
                orderCount++;
                bookingRevenue += pay.getAmountCents();
                if (row != null) {
                    row[2]++;
                }
            }
        }
        long refundTotal = 0;
        for (RefundOrder refund : refunds) {
            refundTotal += refund.getAmountCents();
            long[] row = trend.get(refund.getCreatedAt().toLocalDate());
            if (row != null) {
                row[1] += refund.getAmountCents();
            }
        }

        List<Map<String, Object>> trendRows = new ArrayList<>();
        trend.forEach((day, values) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", day.toString());
            row.put("revenueCents", values[0]);
            row.put("refundCents", values[1]);
            row.put("orderCount", values[2]);
            trendRows.add(row);
        });
        List<Map<String, Object>> typeRows = new ArrayList<>();
        byType.forEach((type, cents) -> typeRows.add(Map.of("type", type, "amountCents", cents)));
        List<Map<String, Object>> channelRows = new ArrayList<>();
        byChannel.forEach((channel, cents) -> channelRows.add(Map.of("channel", channel, "amountCents", cents)));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("from", from.toString());
        data.put("to", to.toString());
        data.put("revenueCents", revenue);
        data.put("rechargeCents", recharge);
        data.put("refundCents", refundTotal);
        data.put("netCents", revenue - refundTotal);
        data.put("orderCount", orderCount);
        data.put("avgOrderCents", orderCount == 0 ? 0 : bookingRevenue / orderCount);
        data.put("trend", trendRows);
        data.put("byType", typeRows);
        data.put("byChannel", channelRows);
        return data;
    }

    public List<Map<String, Object>> transactions(LocalDate from, LocalDate to, String direction) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<PayOrder> pays = pays(from, to);
        List<RefundOrder> refunds = refunds(from, to);
        List<WalletTxn> adjusts = walletTxnMapper.selectList(new LambdaQueryWrapper<WalletTxn>()
                .eq(WalletTxn::getType, WalletTxnType.ADJUST)
                .ge(WalletTxn::getCreatedAt, from.atStartOfDay())
                .lt(WalletTxn::getCreatedAt, to.plusDays(1).atStartOfDay()));

        Map<Long, Booking> bookings = bookingsOf(pays);
        Map<Long, String> names = userNames(pays, refunds, adjusts);
        for (PayOrder pay : pays) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", pay.getPaidAt());
            row.put("direction", "IN");
            row.put("category", pay.getPurpose().name());
            row.put("bookingType", bookingType(bookings.get(pay.getBookingId())));
            row.put("amountCents", pay.getAmountCents());
            row.put("channel", pay.getChannel().name());
            row.put("userName", names.getOrDefault(pay.getUserId(), ""));
            row.put("status", pay.getStatus().name());
            row.put("ref", pay.getPayNo());
            rows.add(row);
        }
        for (RefundOrder refund : refunds) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", refund.getCompletedAt() != null ? refund.getCompletedAt() : refund.getCreatedAt());
            row.put("direction", "OUT");
            row.put("category", "REFUND");
            row.put("bookingType", "");
            row.put("amountCents", refund.getAmountCents());
            row.put("channel", refund.getChannel().name());
            row.put("userName", names.getOrDefault(refund.getUserId(), ""));
            row.put("status", refund.getStatus().name());
            row.put("ref", refund.getRefundNo());
            rows.add(row);
        }
        for (WalletTxn txn : adjusts) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", txn.getCreatedAt());
            row.put("direction", txn.getAmountCents() > 0 ? "IN" : "OUT");
            row.put("category", "ADJUST");
            row.put("bookingType", "");
            row.put("amountCents", Math.abs(txn.getAmountCents()));
            row.put("channel", "WALLET");
            row.put("userName", names.getOrDefault(txn.getUserId(), ""));
            row.put("status", "SUCCESS");
            row.put("ref", "ADJ-" + txn.getId());
            rows.add(row);
        }
        if (direction != null && ("IN".equals(direction) || "OUT".equals(direction))) {
            rows.removeIf(row -> !direction.equals(row.get("direction")));
        }
        rows.sort(Comparator.comparing(row -> (LocalDateTime) row.get("time"), Comparator.reverseOrder()));
        return rows.size() > 500 ? rows.subList(0, 500) : rows;
    }

    public String exportCsv(LocalDate from, LocalDate to) {
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("时间,收支,类别,金额(元),渠道,客户,状态,单号\n");
        for (Map<String, Object> row : transactions(from, to, null)) {
            csv.append(csvCell(String.valueOf(row.get("time")).replace('T', ' ').substring(0, 16))).append(',')
                    .append("IN".equals(row.get("direction")) ? "收入" : "支出").append(',')
                    .append(csvCell(categoryText(String.valueOf(row.get("category")),
                            String.valueOf(row.get("bookingType"))))).append(',')
                    .append(String.format("%.2f", ((Number) row.get("amountCents")).longValue() / 100.0)).append(',')
                    .append(csvCell(channelText(String.valueOf(row.get("channel"))))).append(',')
                    .append(csvCell(String.valueOf(row.get("userName")))).append(',')
                    .append(csvCell(String.valueOf(row.get("status")))).append(',')
                    .append(csvCell(String.valueOf(row.get("ref")))).append('\n');
        }
        return csv.toString();
    }

    private List<PayOrder> pays(LocalDate from, LocalDate to) {
        return payOrderMapper.selectList(new LambdaQueryWrapper<PayOrder>()
                .in(PayOrder::getStatus, PayOrderStatus.PAID, PayOrderStatus.REFUNDED)
                .ge(PayOrder::getPaidAt, from.atStartOfDay())
                .lt(PayOrder::getPaidAt, to.plusDays(1).atStartOfDay())
                .orderByAsc(PayOrder::getPaidAt));
    }

    private List<RefundOrder> refunds(LocalDate from, LocalDate to) {
        return refundOrderMapper.selectList(new LambdaQueryWrapper<RefundOrder>()
                .eq(RefundOrder::getStatus, RefundStatus.SUCCESS)
                .ge(RefundOrder::getCreatedAt, from.atStartOfDay())
                .lt(RefundOrder::getCreatedAt, to.plusDays(1).atStartOfDay())
                .orderByAsc(RefundOrder::getCreatedAt));
    }

    private Map<Long, Booking> bookingsOf(List<PayOrder> pays) {
        List<Long> ids = pays.stream().map(PayOrder::getBookingId)
                .filter(id -> id != null).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return bookingMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Booking::getId, Function.identity()));
    }

    private Map<Long, String> userNames(List<PayOrder> pays, List<RefundOrder> refunds, List<WalletTxn> adjusts) {
        List<Long> ids = new ArrayList<>();
        pays.forEach(p -> ids.add(p.getUserId()));
        refunds.forEach(r -> ids.add(r.getUserId()));
        adjusts.forEach(a -> ids.add(a.getUserId()));
        List<Long> distinct = ids.stream().filter(id -> id != null).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(distinct).stream()
                .collect(Collectors.toMap(User::getId, u -> u.getNickname() == null ? u.getPhone() : u.getNickname()));
    }

    private String bookingType(Booking booking) {
        return booking == null ? "OTHER" : booking.getType().name();
    }

    private String categoryText(String category, String bookingType) {
        return switch (category) {
            case "BOOKING" -> switch (bookingType) {
                case "COURT" -> "场地";
                case "COACH" -> "私教";
                case "GROUP" -> "团课";
                case "PICKUP" -> "拼场";
                default -> "订场";
            };
            case "RESCHEDULE_DIFF" -> "改期差价";
            case "COACH_PACKAGE" -> "课包";
            case "RECHARGE" -> "充值";
            case "REFUND" -> "退款";
            case "ADJUST" -> "调余额";
            default -> category;
        };
    }

    private String channelText(String channel) {
        return switch (channel) {
            case "WALLET" -> "余额";
            case "WECHAT_MOCK" -> "微信";
            case "PACKAGE" -> "课包";
            default -> channel;
        };
    }

    private String csvCell(String value) {
        String text = value == null ? "" : value;
        // 防 CSV 公式注入：Excel/WPS 会执行以 = + - @ 开头的单元格
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
