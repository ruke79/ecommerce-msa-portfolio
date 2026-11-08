package com.project.order_service.service;

import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 처리한 이벤트 원장({@code orders.processed_event}, V30) — 부르는 쪽의 트랜잭션 안에서 쓴다: 처리와 함께 커밋 · 롤백된다.
 *
 * <p>[JA] 処理済みイベントの台帳 — 呼び出し側のトランザクションの中で書く。処理と一緒にコミット · ロールバックされる。
 */
@Component
public class ProcessedEventLedger {

    /** 원장을 쓰지 않는다 — 생성자로 만드는 단위 테스트의 기본값. */
    public static final ProcessedEventLedger NONE = new ProcessedEventLedger() {
        @Override
        public boolean markFirst(String eventId) {
            return true;
        }
    };

    @PersistenceContext
    private EntityManager em;

    /**
     * 이 이벤트를 처음 처리하면 원장에 남기고 true, 이미 있으면 false. 동시에 둘이 와도 하나만 true(기본 키 · ON CONFLICT).
     * [JA] 初めてなら台帳に残して true、既にあれば false。
     */
    public boolean markFirst(String eventId) {
        return em.createNativeQuery("INSERT INTO orders.processed_event (event_id, processed_at) VALUES (:id, now())"
                + " ON CONFLICT (event_id) DO NOTHING").setParameter("id", eventId).executeUpdate() == 1;
    }
}
