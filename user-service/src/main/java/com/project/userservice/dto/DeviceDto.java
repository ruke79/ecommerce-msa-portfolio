package com.project.userservice.dto;

import java.time.LocalDateTime;

import com.project.userservice.model.KnownDevice;

/**
 * 화면에 내려줄 로그인 기기.
 *
 * <p>🔒 <b>토큰 해시를 내보내지 않는다.</b> 그 값을 알면 그 기기인 척해서 보안 알림을 끌
 * 수 있다 — 비밀번호를 이미 아는 공격자에게는 딱 필요한 조각이다(KnownDevice 주석).
 * 엔티티를 그대로 직렬화하면 그게 새어 나가므로 DTO 로 한 겹 둔다.
 *
 * <p>IP 는 이미 마스킹된 값이다(저장부터 그렇다). 전체 주소는 어디에도 없다.
 */
public record DeviceDto(
        Long id,
        String label,
        String lastIp,
        LocalDateTime firstSeenAt,
        LocalDateTime lastSeenAt) {

    public static DeviceDto of(KnownDevice device) {
        return new DeviceDto(
                device.getDeviceId(),
                // 라벨을 못 읽은 기기도 목록에는 나와야 한다 — 빈 줄보다 낫다.
                device.getLabel() == null || device.getLabel().isBlank()
                        ? "알 수 없는 기기"
                        : device.getLabel(),
                device.getLastIp(),
                device.getFirstSeenAt(),
                device.getLastSeenAt());
    }
}
