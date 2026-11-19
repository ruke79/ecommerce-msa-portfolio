package com.project.userservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 로그인 2단계 인증의 SMS 수단 — notification-service 가 <b>등록된 번호로</b> 코드를 보내고 맞는지 본다(docs/24 §10).
 *
 * <p>🔒 {@link OrderServiceClient} 와 같은 이유로 인터셉터를 붙이지 않는다 — 신원 헤더가 없어야 저쪽이 내부 호출로 읽는다(경로 규칙
 * {@code /api/notifications/internal/two-factor=user-service}, mTLS). 저쪽은 게이트웨이를 거친 요청을 관리자라도 거절한다.
 *
 * <p>응답은 늘 200 의 {@code result} 다 — 4xx 로 갈리면 "코드가 틀렸다" 와 "notification 이 죽었다" 가 같은 예외로 온다. 그래서
 * 이 클라이언트의 예외는 언제나 "닿지 못했다" 이다({@link com.project.userservice.security.SmsTwoFactor}).
 *
 * <p>[JA] ログイン二段階認証の SMS 手段 — notification-service が登録済みの番号へコードを送り、合っているかを見る。インターセプターは
 *      付けない(身元ヘッダーがないことで内部呼び出しと読まれる)。応答は常に 200 の result — 例外は常に「届かなかった」。
 */
@FeignClient(name = "notification-service", url = "${feign.notification-url}")
public interface NotificationServiceClient {

    @PostMapping("api/notifications/internal/two-factor/sms/send")
    TwoFactorSmsResponse sendTwoFactorSms(@RequestBody TwoFactorSmsRequest request);

    @PostMapping("api/notifications/internal/two-factor/sms/verify")
    TwoFactorSmsResponse verifyTwoFactorSms(@RequestBody TwoFactorSmsRequest request);

    /**
     * @param binding 켤 때 저장한 번호 결속값 — 켜는 중이면 null(지금 등록된 번호로 보내고 그 결속값을 돌려받는다)
     * @param code    확인할 6자리(발송에는 null). 쿼리가 아니라 본문 — URL 은 접근 로그 · 추적에 남는다.
     */
    record TwoFactorSmsRequest(Long userId, String binding, String code) {

        /** 코드 · 결속값을 문자열 표현에 남기지 않는다(Feign 로그 · 예외 메시지). */
        @Override
        public String toString() {
            return "TwoFactorSmsRequest[userId=" + userId + "]";
        }
    }

    /**
     * @param result SENT · VERIFIED · INVALID · EXPIRED · NO_PHONE · PHONE_CHANGED · TOO_MANY · FAILED
     */
    record TwoFactorSmsResponse(String result, String maskedPhone, String binding, int ttlMinutes,
            long retryAfterSeconds, int attemptsLeft) {

        @Override
        public String toString() {
            return "TwoFactorSmsResponse[result=" + result + "]";
        }
    }
}
