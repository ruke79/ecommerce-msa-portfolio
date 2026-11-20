package com.project.userservice.message.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.project.common.outbox.OutboxRelayTrigger;
import com.project.userservice.message.UserOutboxScheduler;

/**
 * 사용자 이벤트(가입·프로필 변경·탈퇴) 아웃박스를 커밋 직후 릴레이한다 (docs/56 §9).
 *
 * <p>스케줄러를 {@link ObjectProvider} 로 받는 이유는 order·pay 와 같다 — 헬퍼 → 트리거 → 스케줄러의
 * 순환을 피하고, 꺼낸 것이 {@code @Transactional} 프록시라야 선점 잠금이 발행 끝까지 간다.
 *
 * <p>[JA] ユーザーイベント(登録・プロフィール変更・退会)のアウトボックスをコミット直後にリレーする (docs/56 §9)。
 * スケジューラを {@link ObjectProvider} で受け取る理由は order・pay と同じ — ヘルパー → トリガー → スケジューラの
 * 循環を避け、取り出したものが {@code @Transactional} プロキシであってこそ先取りロックが発行の終わりまで続く。
 */
@Configuration
public class UserOutboxRelayConfig {

    @Bean
    OutboxRelayTrigger userOutboxRelayTrigger(
            ObjectProvider<UserOutboxScheduler> scheduler,
            @Value("${app.outbox.relay-on-commit:true}") boolean enabled) {
        return new OutboxRelayTrigger("user-outbox",
                () -> scheduler.getObject().processOutboxMessage(), enabled);
    }
}
