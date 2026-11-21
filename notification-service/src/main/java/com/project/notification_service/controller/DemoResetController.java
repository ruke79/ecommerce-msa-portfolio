package com.project.notification_service.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.project.common.security.InternalCallGuard;
import com.project.notification_service.repository.BroadcastReadRepository;
import com.project.notification_service.repository.NotificationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 공개 데모 계정의 알림 초기화 — <b>user-service 만</b> 매일 부른다(경로 규칙 {@code /api/notifications/internal/demo-reset=user-service},
 * docs/100). 알림 · 공지 읽음 표시를 지운다. 관리자도 게이트웨이로는 부르지 못한다 — 남의 알림을 지울 이유가 없다.
 *
 * <p>[JA] 公開デモアカウントの通知初期化 — user-service だけが毎日呼ぶ。通知 · お知らせの既読表示を消す。管理者もゲートウェイ経由では呼べない。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class DemoResetController {

    /** 한 번에 받는 계정 수 상한 — 데모 계정은 많아야 50. [JA] 一度に受けるアカウント数の上限。 */
    static final int MAX_IDS = 50;

    private final NotificationRepository notifications;
    private final BroadcastReadRepository broadcastReads;

    public record DemoResetRequest(List<Long> userIds) {
    }

    @PostMapping("/api/notifications/internal/demo-reset")
    @Transactional
    public ResponseEntity<Map<String, Integer>> reset(@RequestBody DemoResetRequest request,
            @RequestHeader(value = "User-Role", required = false) String callerRole,
            @RequestHeader(value = "User-Email", required = false) String callerEmail,
            @RequestHeader(value = "User-Id", required = false) String callerId) {
        if (!InternalCallGuard.allowed(callerRole, callerEmail, callerId)
                || InternalCallGuard.fromGateway(callerRole, callerEmail, callerId)) {
            log.warn("외부에서 데모 알림 초기화 시도를 차단");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (request == null || request.userIds() == null || request.userIds().size() > MAX_IDS) {
            return ResponseEntity.badRequest().build();
        }
        if (request.userIds().isEmpty()) {
            return ResponseEntity.ok(Map.of("notifications", 0, "broadcastReads", 0));
        }
        int n = notifications.deleteAllOfRecipients(request.userIds());
        int b = broadcastReads.deleteAllOfRecipients(request.userIds());
        log.info("데모 알림 초기화: 계정={} 알림={} 공지 표시={}", request.userIds().size(), n, b);
        return ResponseEntity.ok(Map.of("notifications", n, "broadcastReads", b));
    }
}
