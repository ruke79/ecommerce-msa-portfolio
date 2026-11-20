package com.project.coupon_service.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.springframework.data.domain.Persistable;

/**
 * 컨슈머 멱등성 원장 (docs/12 §2).
 *
 * <p>처리한 이벤트 id 를 <b>업무 변경과 같은 트랜잭션</b>으로 기록한다. 둘 다 반영되거나 둘 다
 * 롤백되므로, "쿠폰은 USED 가 됐는데 원장에는 없다" 같은 어긋난 상태가 생기지 않는다.
 *
 * <p>[JA] コンシューマー冪等性台帳 (docs/12 §2)。
 * <p>処理したイベント id を<b>業務変更と同じトランザクション</b>で記録する。両方反映されるか両方ロールバックされるので、「クーポンは USED になったのに台帳にはない」のようなずれた状態が生じない。
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "processed_event", schema = "coupon")
public class ProcessedEvent implements Persistable<String> {

    @Id
    @Column(name = "event_id", length = 64, nullable = false)
    private String eventId;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    public ProcessedEvent(String eventId) {
        this.eventId = eventId;
        this.processedAt = LocalDateTime.now();
    }

    // ── 왜 Persistable 을 구현하는가 ────────────────────────────────────────
    //
    // eventId 는 "payment-<paymentId>" 를 **그대로 배정**하는 자연 키다. save() 시점에
    // id 가 이미 null 이 아니므로 SimpleJpaRepository 는 persist() 가 아니라 merge() 를
    // 고르고, 그 결과가 둘이다.
    //
    //  1. merge 는 붙일 대상을 찾으려 SELECT 를 먼저 던진다 — 결제 응답을 처리할 때마다
    //     빈손으로 끝나는 SELECT 가 한 번씩 더 나갔다. 바로 앞 줄의 existsById 와 같은
    //     조회가 두 번 나간 셈이다.
    //
    //  2. 더 중요한 쪽. merge 는 다른 트랜잭션이 이미 커밋한 같은 eventId 를 찾아내면
    //     PK 충돌 대신 **UPDATE** 를 낸다. 예외가 없으니 롤백도 없고, 쿠폰 사용 처리가
    //     두 번 반영된다. existsById 를 동시에 통과한 두 소비자를 막아 줄 마지막 방어선이
    //     그 경로에서만 조용히 빠져 있었다. persist() 로 가야 실제로 충돌한다.
    // [JA] ── なぜ Persistable を実装するのか ──
    //      eventId は "payment-<paymentId>" を**そのまま割り当てる**自然キーだ。save() 時点で id が既に null でないので、
    //      SimpleJpaRepository は persist() ではなく merge() を選び、その結果が二つある。
    //      1. merge は付ける対象を探すため先に SELECT を投げる — 決済応答を処理するたびに空振りの SELECT が一回ずつ余分に出た。
    //      すぐ前の行の existsById と同じ照会が二回出ていたことになる。
    //      2. より重要な方。merge は別トランザクションが既にコミットした同じ eventId を見つけると、PK 衝突の代わりに **UPDATE** を出す。
    //      例外がないのでロールバックもなく、クーポン使用処理が二回反映される。existsById を同時に通過した二つのコンシューマーを止める
    //      最後の防衛線がその経路でだけ静かに抜けていた。persist() に行ってこそ実際に衝突する。
    @Transient
    private boolean persisted;

    @Override
    public String getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostPersist
    @PostLoad
    private void markPersisted() {
        this.persisted = true;
    }
}
