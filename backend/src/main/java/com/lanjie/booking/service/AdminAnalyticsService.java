package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.entity.Court;
import com.lanjie.booking.mapper.CourtMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 时段分析：按「场地 × 小时」聚合最近 N 天（并含未来 7 天已售）的占用率。
 * 只统计 BOOKED 格子；取消/释放的格子算回可售，不会虚高。
 */
@Service
public class AdminAnalyticsService {

    private static final int OPEN_HOUR = 9;
    private static final int CLOSE_HOUR = 21;

    private final CourtMapper courtMapper;
    private final JdbcTemplate jdbcTemplate;

    public AdminAnalyticsService(CourtMapper courtMapper, JdbcTemplate jdbcTemplate) {
        this.courtMapper = courtMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> heatmap(int days) {
        int span = Math.max(7, Math.min(days, 60));
        LocalDate from = LocalDate.now().minusDays(span - 1L);
        LocalDate to = LocalDate.now().plusDays(7);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT court_id AS courtId, HOUR(start_at) AS hour, COUNT(*) AS total,
                       SUM(CASE WHEN status = 'BOOKED' THEN 1 ELSE 0 END) AS booked,
                       SUM(CASE WHEN status = 'BOOKED' THEN price_cents ELSE 0 END) AS revenue
                FROM slot
                WHERE slot_date BETWEEN ? AND ?
                GROUP BY court_id, HOUR(start_at)
                """, from, to);
        Map<Long, Map<Integer, Map<String, Object>>> byCourt = rows.stream()
                .collect(Collectors.groupingBy(row -> ((Number) row.get("courtId")).longValue(),
                        Collectors.toMap(row -> ((Number) row.get("hour")).intValue(), row -> row)));

        List<Court> courts = courtMapper.selectList(new LambdaQueryWrapper<Court>()
                .eq(Court::getStatus, "ACTIVE").orderByAsc(Court::getSortOrder));
        List<Map<String, Object>> courtRows = new ArrayList<>();
        List<Map<String, Object>> allCells = new ArrayList<>();
        for (Court court : courts) {
            Map<Integer, Map<String, Object>> data = byCourt.getOrDefault(court.getId(), Map.of());
            List<Map<String, Object>> cells = new ArrayList<>();
            for (int hour = OPEN_HOUR; hour <= CLOSE_HOUR; hour++) {
                Map<String, Object> source = data.get(hour);
                long total = source == null ? 0 : ((Number) source.get("total")).longValue();
                long booked = source == null ? 0 : ((Number) source.get("booked")).longValue();
                long revenue = source == null ? 0 : ((Number) source.get("revenue")).longValue();
                int pct = total == 0 ? 0 : (int) Math.round(booked * 100.0 / total);
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("hour", hour);
                cell.put("total", total);
                cell.put("booked", booked);
                cell.put("occupancyPct", pct);
                cell.put("revenueCents", revenue);
                cell.put("courtId", court.getId());
                cell.put("courtName", court.getName());
                cells.add(cell);
                if (total > 0) {
                    allCells.add(cell);
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("courtId", court.getId());
            row.put("courtName", court.getName());
            row.put("cells", cells);
            courtRows.add(row);
        }

        List<Map<String, Object>> hottest = allCells.stream()
                .filter(cell -> ((Number) cell.get("booked")).longValue() > 0)
                .sorted(Comparator.comparing(cell -> ((Number) cell.get("occupancyPct")).intValue(),
                        Comparator.reverseOrder()))
                .limit(3).toList();
        List<Map<String, Object>> coldest = allCells.stream()
                .sorted(Comparator.comparing(cell -> ((Number) cell.get("occupancyPct")).intValue()))
                .limit(3).toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("days", span);
        data.put("from", from.toString());
        data.put("to", to.toString());
        data.put("courts", courtRows);
        data.put("hottest", hottest);
        data.put("coldest", coldest);
        return data;
    }
}
