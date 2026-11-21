package com.project.userservice.message;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.project.common.outbox.OutboxRelayTrigger;

/**
 * 가입 확인 메일 행이 커밋되면 메일 릴레이를 곧바로 깨운다 — 10초 주기를 기다리지 않는다(common {@link OutboxRelayTrigger}).
 *
 * <p>고유한 타입으로 둔다 — 가입 이벤트 릴레이의 트리거({@code userOutboxRelayTrigger})와 같은 타입이면 주입이 모호하다. 릴레이는
 * {@link ObjectProvider} 로 늦게 찾는다 — UserService → 아웃박스 헬퍼 → 이 트리거 → 릴레이 → UserService 순환을 끊는다.
 *
 * <p>[JA] 登録確認メールの行がコミットされたらメールリレーをすぐ起こす。固有の型にする(同じ型だと注入が曖昧)。リレーは遅延で探し、
 *      循環依存を断つ。
 */
@Component
public class RegistrationMailTrigger implements AutoCloseable {

    /** 아무것도 하지 않는다 — 단위 시험이 헬퍼를 직접 만들 때. [JA] 何もしない — 単体試験がヘルパーを直接作るとき。 */
    public static final RegistrationMailTrigger DISABLED = new RegistrationMailTrigger(OutboxRelayTrigger.DISABLED);

    private final OutboxRelayTrigger trigger;

    private RegistrationMailTrigger(OutboxRelayTrigger trigger) {
        this.trigger = trigger;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RegistrationMailTrigger(ObjectProvider<RegistrationMailRelay> relay,
            @Value("${app.outbox.relay-on-commit:true}") boolean enabled) {
        this.trigger = new OutboxRelayTrigger("registration-mail", () -> relay.getObject().relayOnce(), enabled);
    }

    /** 트랜잭션 안이면 커밋 뒤에, 밖이면 곧바로. [JA] トランザクション内ならコミット後、外なら直ちに。 */
    public void afterCommit() {
        trigger.afterCommit();
    }

    @Override
    public void close() {
        trigger.close();
    }
}
