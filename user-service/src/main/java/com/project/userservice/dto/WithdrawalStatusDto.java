package com.project.userservice.dto;

import java.time.LocalDateTime;

/**
 * 탈퇴 진행 상태.
 *
 * <p>{@code scheduledAt} 과 {@code daysLeft} 를 <b>둘 다</b> 준다. 서버가 계산해야 유예 기간
 * 설정을 바꿔도 화면이 따라오고, 클라이언트 시계가 틀려도 "며칠 남았는지"가 흔들리지 않는다.
 *
 * @param pending     탈퇴 신청 후 아직 처리되지 않은 상태
 * @param requestedAt 신청 시각. 신청하지 않았으면 null
 * @param scheduledAt 최종 처리 예정 시각. 신청하지 않았으면 null
 * @param daysLeft    남은 일수(올림). 이미 지났으면 0
 */
public record WithdrawalStatusDto(boolean pending,
                                  LocalDateTime requestedAt,
                                  LocalDateTime scheduledAt,
                                  long daysLeft) {

    public static WithdrawalStatusDto none() {
        return new WithdrawalStatusDto(false, null, null, 0L);
    }
}
