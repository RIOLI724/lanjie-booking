package com.lanjie.booking.vo;

import java.util.List;

public record PickupGameVO(Long id, Long slotId, String courtName, String startAt, String endAt,
                           String format, int totalPlayers, int joinedPlayers, int pricePerPersonCents,
                           String note, String status, boolean settled, boolean isInitator,
                           List<ParticipantVO> participants) {

    public record ParticipantVO(Long userId, String nickname, String status) {
    }
}
