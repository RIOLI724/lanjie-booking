package com.lanjie.booking.service;

import com.lanjie.booking.entity.BookingStatusHistory;
import com.lanjie.booking.mapper.BookingStatusHistoryMapper;
import org.springframework.stereotype.Service;

/** 订单状态流转留痕：谁（USER/ADMIN/SYSTEM/AI）把订单从什么状态改成了什么状态 */
@Service
public class BookingAuditService {

    private final BookingStatusHistoryMapper historyMapper;

    public BookingAuditService(BookingStatusHistoryMapper historyMapper) {
        this.historyMapper = historyMapper;
    }

    public void write(Long bookingId, String fromStatus, String toStatus, String actor, String reason) {
        BookingStatusHistory history = new BookingStatusHistory();
        history.setBookingId(bookingId);
        history.setFromStatus(fromStatus);
        history.setToStatus(toStatus);
        history.setActor(actor);
        history.setReason(reason);
        historyMapper.insert(history);
    }
}
