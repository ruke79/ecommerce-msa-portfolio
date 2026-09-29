package com.project.notification_service.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 채우지 않은 설정을 기동 시점에 잡는다.
 *
 * <p>🐛 {@code MAIL_HOST} 가 {@code "REPLACE_ME_smtp_host"} 인 채로 배포됐는데, 그 값은
 * <b>비어 있지 않아서</b> {@code isBlank()} 검사를 통과했다. 서비스는 "smtp (실제 발송)" 이라고
 * 찍고 정상 기동했고, 실패는 25초 뒤 {@code UnknownHostException} WARN 한 줄로만 나타났다.
 */
@DisplayName("설정 자리 표시자")
class PlaceholdersTest {

    @Test
    @DisplayName("🐛 자리 표시자가 남아 있으면 기동을 막는다 — 비어 있지 않다고 통과시키지 않는다")
    void 자리표시자_거부() {
        assertThatThrownBy(() -> Placeholders.rejectUnfilled("provider=smtp",
                Placeholders.pairs("spring.mail.host", "REPLACE_ME_smtp_host")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.mail.host");
    }

    /**
     * 🔒 값에는 {@code MAIL_PASSWORD} 같은 진짜 비밀이 섞일 수 있고 예외 메시지는 로그로 나간다.
     * 지금은 값이 자리 표시자라 무해하지만, "걸렸을 때만 안전한 규칙"은 다음 사람이 검사를
     * 넓히는 순간 깨진다.
     */
    @Test
    @DisplayName("🔒 메시지에 값을 넣지 않는다 — 키 이름만 넣는다")
    void 값을_노출하지_않는다() {
        assertThatThrownBy(() -> Placeholders.rejectUnfilled("provider=smtp",
                Placeholders.pairs("spring.mail.password", "REPLACE_ME_smtp_app_password")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.mail.password")
                .hasMessageNotContaining("REPLACE_ME_smtp_app_password");
    }

    @Test
    @DisplayName("채워져 있으면 통과한다")
    void 정상_설정() {
        assertThatCode(() -> Placeholders.rejectUnfilled("provider=smtp",
                Placeholders.pairs("spring.mail.host", "smtp.gmail.com",
                                   "notification.mail.from", "shop@example.com")))
                .doesNotThrowAnyException();
    }

    /**
     * 비어 있는 값은 여기서 다루지 않는다 — 호출하는 쪽의 {@code isBlank()} 검사가 이미 잡고,
     * 그쪽이 훨씬 구체적인 메시지를 준다. 여기서 또 막으면 어느 검사가 걸렸는지 흐려진다.
     */
    @Test
    @DisplayName("빈 값·null 은 이 검사의 몫이 아니다")
    void 빈_값은_통과() {
        assertThatCode(() -> Placeholders.rejectUnfilled("provider=smtp",
                Placeholders.pairs("a", "", "b", null)))
                .doesNotThrowAnyException();
    }

    /**
     * 🐛 {@code startsWith} 로 보면 연결 문자열 가운데 박힌 자리 표시자를 놓친다.
     * {@code 20-secret.example.yaml} 의 {@code SPRING_DATA_MONGODB_CLIENT} 가 그 모양이다.
     */
    @Test
    @DisplayName("🐛 값 가운데 박힌 자리 표시자도 잡는다")
    void 가운데_박힌_경우() {
        assertThat(Placeholders.isPlaceholder(
                "mongodb://admin:REPLACE_ME_mongo_password@mongodb:27017/chat")).isTrue();
    }

    @Test
    @DisplayName("걸린 키를 모두 알려준다 — 한 번에 다 고칠 수 있어야 한다")
    void 여러_키() {
        assertThatThrownBy(() -> Placeholders.rejectUnfilled("provider=smtp",
                Placeholders.pairs("k1", "REPLACE_ME_a", "k2", "ok", "k3", "REPLACE_ME_b")))
                .hasMessageContaining("k1")
                .hasMessageContaining("k3")
                .hasMessageNotContaining("k2");
    }
}
