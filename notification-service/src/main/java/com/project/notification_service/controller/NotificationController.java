package com.project.notification_service.controller;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.project.notification_service.domain.Notification;
import com.project.notification_service.domain.NotificationPreference;
import com.project.notification_service.dto.NotificationResponse;
import com.project.notification_service.dto.PreferenceResponse;
import com.project.notification_service.dto.VerifyCodeRequest;
import com.project.notification_service.dto.VerifyPhoneRequest;
import com.project.notification_service.service.BroadcastService;
import com.project.notification_service.service.NotificationService;
import com.project.notification_service.service.VerificationService;
import com.project.notification_service.service.VerificationService.IssueResult;
import com.project.notification_service.service.VerificationService.VerifyResult;
import com.project.notification_service.sms.PhoneNumbers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 알림 조회·설정 및 SMS 인증 API.
 *
 * <p><b>사용자 식별.</b> 게이트웨이가 JWT 를 검증한 뒤 {@code User-Id} 헤더로 넘겨준다.
 * 이 헤더를 <b>클라이언트가 직접 보낼 수 있으면 안 된다</b> — 남의 알림을 그대로 읽게 된다.
 * 게이트웨이가 외부 요청의 이 헤더를 제거하고 자기가 채워 넣어야 하며, 서비스는 클러스터 밖에
 * 노출되지 않아야 한다. 여기서는 헤더가 없으면 401 로 끊는다.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final VerificationService verificationService;
    private final BroadcastService broadcastService;

    @GetMapping
    public ResponseEntity<List<NotificationResponse>> list(
            @RequestHeader(value = "User-Id", required = false) Long userId,
            @RequestHeader(value = "User-Email", required = false) String userEmail,
            @RequestParam(defaultValue = "20") int size) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // 알림 메일을 보내려면 주소가 필요한데 이 서비스는 사용자 테이블이 없다. 게이트웨이가
        // JWT 에서 꺼내 붙인 헤더를 여기서 기록해 둔다(NotificationService#rememberEmail 주석).
        // ⚠️ 배지(unread-count)에는 넣지 않는다 — 화면마다 불리는 경로라 조회가 하나 더 붙는다.
        notificationService.rememberEmail(userId, userEmail);

        /*
         * 개인 알림과 **전체 알림(공지·관리자 안내)** 을 합쳐서 돌려준다.
         *
         * 전체 알림은 사용자별 행을 만들지 않는다(BroadcastNotification 주석) — 공지 하나에
         * 사용자 수만큼 insert 하지 않기 위해서다. 그래서 저장은 따로, 화면에 보일 때는
         * 하나로 합친다. 사용자에게는 "알림" 하나이고 어디에 저장됐는지는 관심 밖이다.
         *
         * 합친 뒤 시간 역순으로 정렬하고 size 개만 남긴다. 각 응답의 scope 로 어느 쪽인지
         * 구분되므로, 화면은 읽음·삭제에 맞는 엔드포인트를 고를 수 있다.
         */
        Stream<NotificationResponse> personal = notificationService.recent(userId, size)
                .stream()
                .map(NotificationResponse::from)
                .map(NotificationResponse::localized);   // 서버가 만든 한국어 문구 → 요청 언어(docs/60 §9)

        Stream<NotificationResponse> broadcast = broadcastService.visibleFor(userId, size)
                .stream()
                .map(v -> NotificationResponse.from(v.broadcast(), v.read()));

        List<NotificationResponse> merged = Stream.concat(personal, broadcast)
                .sorted(Comparator.comparing(NotificationResponse::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.max(1, size))
                .toList();

        return ResponseEntity.ok(merged);
    }

    /** 배지 숫자. 화면 진입마다 불리므로 가볍게 유지한다. */
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount(
            @RequestHeader(value = "User-Id", required = false) Long userId) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // 배지는 사용자가 보는 "안 읽은 알림" 전체다 — 저장 위치로 나뉘면 안 된다.
        long count = notificationService.unreadCount(userId) + broadcastService.unreadCount(userId);
        return ResponseEntity.ok(Map.of("count", count));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markRead(
            @RequestHeader(value = "User-Id", required = false) Long userId,
            @PathVariable Long id) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // 남의 알림이면 404 다 — 403 을 주면 "그 id 는 존재한다"를 알려주는 셈이다.
        return notificationService.markRead(userId, id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PatchMapping("/read-all")
    public ResponseEntity<Map<String, Integer>> markAllRead(
            @RequestHeader(value = "User-Id", required = false) Long userId) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // 배지를 0 으로 만드는 버튼이다. 개인 알림만 읽음 처리하면 전체 알림이 남아
        // 배지가 0 이 되지 않는다 — 사용자는 "모두 읽음"을 계속 누르게 된다.
        int updated = notificationService.markAllRead(userId) + broadcastService.markAllRead(userId);
        return ResponseEntity.ok(Map.of("updated", updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader(value = "User-Id", required = false) Long userId,
            @PathVariable Long id) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return notificationService.delete(userId, id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // ── 전체 알림(공지·관리자 안내) ─────────────────────────────────────────
    //
    // 개인 알림과 **엔드포인트가 다르다.** 전체 알림은 모두가 공유하는 행이라 개인 알림처럼
    // 지울 수 없고, 개인별로는 읽음·치움 표시만 남긴다. 목록 응답의 scope 가 화면에게
    // 어느 쪽을 부를지 알려 준다(NotificationResponse 주석).
    //
    // 경로를 나눈 이유: id 만으로는 구분할 수 없다. 두 테이블이 각자 TSID 를 쓰므로,
    // 한 경로로 받으면 우연히 같은 id 의 남의 개인 알림을 건드릴 수 있다.

    @PatchMapping("/broadcasts/{id}/read")
    public ResponseEntity<Void> markBroadcastRead(
            @RequestHeader(value = "User-Id", required = false) Long userId,
            @PathVariable Long id) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return broadcastService.markRead(userId, id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** 내 목록에서만 치운다 — 공지 자체는 남는다(한 사람의 삭제가 남의 화면을 바꾸면 안 된다). */
    @DeleteMapping("/broadcasts/{id}")
    public ResponseEntity<Void> dismissBroadcast(
            @RequestHeader(value = "User-Id", required = false) Long userId,
            @PathVariable Long id) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return broadcastService.dismiss(userId, id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // ── 알림 설정 ────────────────────────────────────────────────────────────

    @GetMapping("/preferences")
    public ResponseEntity<PreferenceResponse> preferences(
            @RequestHeader(value = "User-Id", required = false) Long userId,
            @RequestHeader(value = "User-Email", required = false) String userEmail) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(
                PreferenceResponse.from(notificationService.preferenceOf(userId, userEmail)));
    }

    @PatchMapping("/preferences")
    public ResponseEntity<PreferenceResponse> updatePreferences(
            @RequestHeader(value = "User-Id", required = false) Long userId,
            @RequestHeader(value = "User-Email", required = false) String userEmail,
            @RequestBody Map<String, Boolean> body) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        NotificationPreference preference = notificationService.preferenceOf(userId, userEmail);

        if (body.containsKey("smsEnabled")) {
            preference.setSmsEnabled(Boolean.TRUE.equals(body.get("smsEnabled")));
        }
        if (body.containsKey("emailEnabled")) {
            // 메일 수신 거부. ⚠️ 가입 확인·비밀번호 재설정은 이 설정과 무관하게 나간다 —
            // 본인이 방금 요청한 메일을 수신 거부로 막으면 계정에 못 들어간다
            // (MailKind#isTransactional).
            preference.setEmailEnabled(Boolean.TRUE.equals(body.get("emailEnabled")));
        }
        if (body.containsKey("twoFactorEnabled")) {
            // 인증된 번호가 없으면 켤 수 없다 — 켜지면 본인도 로그인하지 못한다.
            boolean applied = preference.enableTwoFactor(Boolean.TRUE.equals(body.get("twoFactorEnabled")));
            if (!applied) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
        }
        return ResponseEntity.ok(PreferenceResponse.from(preference));
    }

    // ── SMS 인증 (번호 등록 · 2단계 인증) ──────────────────────────────────────

    /**
     * 인증번호를 요청한다.
     *
     * <p>속도 제한에 걸리면 429 와 함께 재시도 가능 시각을 준다. 이 제한은 사용자 편의가 아니라
     * <b>남의 번호로 문자 폭탄을 보내는 것을 막기 위한 것</b>이다 — 요금은 우리가 낸다.
     */
    @PostMapping("/phone/verification")
    public ResponseEntity<Map<String, Object>> requestCode(
            @RequestHeader(value = "User-Id", required = false) Long userId,
            @Valid @RequestBody VerifyPhoneRequest request) {

        IssueResult result = verificationService.issue(
                request.phone(), PhoneNumbers.regionFrom(request.region()), userId);

        if (result instanceof IssueResult.Sent sent) {
            return ResponseEntity.ok(Map.of(
                    "phone", sent.maskedPhone(),
                    "expiresInMinutes", sent.ttlMinutes()));
        }
        if (result instanceof IssueResult.TooMany tooMany) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(tooMany.retryAfterSeconds()))
                    .body(Map.of("message", tooMany.reason(),
                                 "retryAfterSeconds", tooMany.retryAfterSeconds()));
        }
        IssueResult.Rejected rejected = (IssueResult.Rejected) result;
        return ResponseEntity.badRequest().body(Map.of("message", rejected.reason()));
    }

    /** 인증번호를 확인하고, 로그인 사용자면 번호를 등록한다. */
    @PostMapping("/phone/verification/confirm")
    public ResponseEntity<Map<String, Object>> confirmCode(
            @RequestHeader(value = "User-Id", required = false) Long userId,
            @Valid @RequestBody VerifyCodeRequest request) {

        VerifyResult result = verificationService.verify(
                request.phone(), PhoneNumbers.regionFrom(request.region()), request.code());

        if (result instanceof VerifyResult.Verified verified) {
            if (userId != null) {
                NotificationPreference preference =
                        verificationService.confirmPhoneForUser(userId, verified.e164());
                return ResponseEntity.ok(Map.of(
                        "verified", true,
                        "preference", PreferenceResponse.from(preference)));
            }
            // 로그인 전 2FA 흐름 — 번호 등록 없이 통과 사실만 알린다.
            return ResponseEntity.ok(Map.of("verified", true));
        }

        VerifyResult.Rejected rejected = (VerifyResult.Rejected) result;
        return ResponseEntity.badRequest().body(Map.of(
                "verified", false,
                "message", rejected.reason(),
                "attemptsLeft", rejected.attemptsLeft()));
    }
}
