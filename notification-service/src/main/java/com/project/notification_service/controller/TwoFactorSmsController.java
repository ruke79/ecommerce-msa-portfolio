package com.project.notification_service.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.project.common.security.InternalCallGuard;
import com.project.notification_service.service.VerificationService;
import com.project.notification_service.service.VerificationService.TwoFactorOutcome;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 로그인 2단계 인증의 SMS 수단 — <b>user-service 만</b> 부른다(경로 규칙 {@code /api/notifications/internal/two-factor=user-service},
 * docs/24 §10 · docs/57 §16).
 *
 * <p>로그인 흐름(비밀번호 → 대기 토큰 → 코드)과 2단계 인증을 켜 두었는지는 user-service 가 갖는다. 여기는 <b>등록된 번호로 보내고
 * 맞는지 본다</b>만 한다 — 번호를 요청에서 받지 않는다(받으면 코드를 아무 번호로 보낼 수 있다).
 *
 * <p>🔒 다른 내부 경로와 달리 <b>관리자도 게이트웨이로는 부르지 못한다</b>. {@code InternalCallGuard.allowed} 는 관리자를 통과시키는데,
 * 관리자 토큰 하나로 아무 사용자에게 로그인 코드를 보내고 맞춰 볼 이유가 없다 — 게이트웨이를 거친 요청은 모두 거절한다.
 *
 * <p>응답은 늘 200 의 {@code {"result": …}} 다(쿠폰 결제 시작과 같은 모양). 4xx 를 쓰면 user-service 의 Feign 이 그것을 연결 실패와
 * 같은 예외로 받아, "코드가 틀렸다" 와 "notification 이 죽었다" 를 가르기 어렵다. 본문에 코드가 있으므로 쿼리로 받지 않는다(URL 은
 * 접근 로그 · 추적에 남는다).
 *
 * <p>[JA] ログイン二段階認証の SMS 手段 — user-service だけが呼ぶ。登録済みの番号へ送り、合っているかを見るだけ。管理者もゲートウェイ
 *      経由では呼べない。応答は常に 200 の {"result": …}。コードは本文で受ける(URL はログ · トレースに残る)。
 */
@Slf4j
@RestController
@RequestMapping("/api/notifications/internal/two-factor/sms")
@RequiredArgsConstructor
public class TwoFactorSmsController {

    private final VerificationService verificationService;

    /**
     * @param userId  대상 사용자
     * @param binding 켤 때 저장한 번호 결속값(켜는 중이면 null)
     * @param code    확인할 6자리(발송에는 없다)
     */
    public record TwoFactorSmsRequest(Long userId, String binding, String code) {

        /** 코드는 어떤 문자열 표현에도 남기지 않는다(로그 · 예외 메시지). */
        @Override
        public String toString() {
            return "TwoFactorSmsRequest[userId=" + userId + "]";
        }
    }

    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> send(@RequestBody TwoFactorSmsRequest request,
            @RequestHeader(value = "User-Role", required = false) String callerRole,
            @RequestHeader(value = "User-Email", required = false) String callerEmail,
            @RequestHeader(value = "User-Id", required = false) String callerId) {
        if (!InternalCallGuard.allowed(callerRole, callerEmail, callerId)
                || InternalCallGuard.fromGateway(callerRole, callerEmail, callerId)) {
            log.warn("외부에서 2단계 인증 SMS 발송 시도를 차단");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (request == null || request.userId() == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(body(verificationService.sendTwoFactor(request.userId(), request.binding())));
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestBody TwoFactorSmsRequest request,
            @RequestHeader(value = "User-Role", required = false) String callerRole,
            @RequestHeader(value = "User-Email", required = false) String callerEmail,
            @RequestHeader(value = "User-Id", required = false) String callerId) {
        if (!InternalCallGuard.allowed(callerRole, callerEmail, callerId)
                || InternalCallGuard.fromGateway(callerRole, callerEmail, callerId)) {
            log.warn("외부에서 2단계 인증 SMS 확인 시도를 차단");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (request == null || request.userId() == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(body(
                verificationService.verifyTwoFactor(request.userId(), request.binding(), request.code())));
    }

    private static Map<String, Object> body(TwoFactorOutcome outcome) {
        Map<String, Object> body = new HashMap<>();
        body.put("result", outcome.result().name());
        if (outcome.maskedPhone() != null) {
            body.put("maskedPhone", outcome.maskedPhone());
        }
        if (outcome.binding() != null) {
            body.put("binding", outcome.binding());
        }
        body.put("ttlMinutes", outcome.ttlMinutes());
        body.put("retryAfterSeconds", outcome.retryAfterSeconds());
        body.put("attemptsLeft", outcome.attemptsLeft());
        return body;
    }
}
