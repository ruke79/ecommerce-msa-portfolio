package com.project.userservice.message;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.message.dto.request.UserCreatedRequest;
import com.project.userservice.message.model.OutboxEvent;
import com.project.userservice.message.producer.UserCreatedProducer;
import com.project.userservice.message.repository.jpa.OutboxEventRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 아웃박스에 적힌 사용자 이벤트를 카프카로 내보내는 폴러.
 *
 * <p>{@link OutboxScheduler} 인터페이스는 이 저장소에 <b>구현체 없이</b> 놓여 있었다.
 * 이것이 그 구현이다. 설계는 order-service 의 {@code PaymentOutboxScheduler} 와 같다 —
 * 같은 문제를 두 번 다르게 풀 이유가 없다.
 *
 * <h3>레플리카가 둘이어도 안전해야 한다</h3>
 *
 * <p>user-service 는 {@code replicas: 2} 다. 두 가지를 지켜야 중복 발행이 없다.
 *
 * <ol>
 *   <li><b>선점해서 읽는다</b> — {@code FOR UPDATE SKIP LOCKED}. 잠금 없이 읽으면 두
 *       레플리카가 같은 행을 본다.</li>
 *   <li><b>발행을 이 트랜잭션 안에서 동기로 기다린다.</b> 비동기로 두면 상태를 바꾸는
 *       콜백이 <b>커밋 뒤에</b> 돌고, 그 사이 행은 여전히 STARTED 인데 잠금은 풀려 있다 —
 *       그 틈에 다른 레플리카가 집어 간다.</li>
 * </ol>
 *
 * <p>order-service 의 아웃박스가 정확히 이 두 가지를 어겨서 결제 요청이 두 번 나갈 수
 * 있었다(docs/21).
 *
 * <h3>그래도 중복은 남는다 — 그건 소비 측의 몫이다</h3>
 *
 * <p>카프카는 at-least-once 다. 발행에는 성공했는데 커밋 직전에 죽으면 그 행은 STARTED 로
 * 남고 다음 주기에 다시 나간다. 여기서 없애는 것은 <b>레플리카 때문에 생기는</b>
 * 중복뿐이다. 소비자 셋은 이미 이를 전제로 짜여 있다 — order·chat 은
 * {@code DataIntegrityViolationException} 을 흘려보내고, coupon 은 중복 발급을 막는다.
 *
 * <h3>실패를 지우지 않는다</h3>
 *
 * <p>발행이 실패하면 {@code FAILED} 로 남긴다. 지우면 <b>무엇이 안 나갔는지 알 수 없다</b> —
 * 조용히 사라지는 것이 이 전체 사태의 원인이었다(docs/47). 남아 있으면 세어 볼 수 있고,
 * 손으로든 배치로든 다시 보낼 수 있다.
 *
 * <p>⚠️ FAILED 는 <b>자동으로 재시도하지 않는다.</b> 브로커가 죽어 있으면 한 주기에 배치
 * 전체가 FAILED 가 되는데, 그걸 계속 되돌리면 무한 루프에 가깝고 잠금만 오래 잡는다.
 * 복구는 대사 배치의 몫이다(docs/48 ③). 지금은 <b>보이게 하는 것</b>까지가 목표다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserOutboxScheduler implements OutboxScheduler {

    private final OutboxEventRepository outboxEventRepository;
    private final UserCreatedProducer userCreatedProducer;
    /** 프로필 변경·탈퇴(docs/48 §6-⑤). */
    private final com.project.userservice.message.producer.UserLifecycleProducer lifecycleProducer;
    private final ObjectMapper objectMapper;
    private final com.project.common.outbox.OutboxTraceContext outboxTraceContext;

    /**
     * 한 번에 선점할 행 수.
     *
     * <p>제한이 없으면 한 레플리카가 밀린 이벤트를 전부 잠가 나머지가 놀고, 트랜잭션도
     * 그만큼 길어진다.
     */
    @Value("${app.outbox.batch-size:100}")
    private int batchSize;

    @Override
    @Transactional
    @Scheduled(fixedDelayString = "${app.outbox.fixed-delay-ms:10000}",
            initialDelayString = "${app.outbox.initial-delay-ms:3000}")
    public void processOutboxMessage() {
        List<OutboxEvent> claimed = outboxEventRepository.claimPending(batchSize);
        if (claimed.isEmpty()) {
            return;
        }

        log.debug("아웃박스 선점: {}건", claimed.size());

        int sent = 0;
        int failed = 0;
        for (OutboxEvent event : claimed) {
            if (publish(event)) {
                event.markCompleted();
                sent++;
            } else {
                event.markFailed();
                failed++;
            }
        }

        // 선점한 행은 관리 상태다 — 더티 체킹으로 상태가 커밋된다. save 를 부르면
        // 그 엔티티는 isNew()=false 라 merge 로 가고, 같은 일을 한 번 더 한다.
        if (failed > 0) {
            log.error("아웃박스 발행 실패 {}건 — FAILED 로 남긴다(자동 재시도하지 않는다)", failed);
        }
        log.info("아웃박스 처리: 발행={} 실패={}", sent, failed);
    }

    /** @return 발행에 성공했으면 true */
    private boolean publish(OutboxEvent event) {
        String type = event.getEventType();
        if (com.project.userservice.message.producer.UserLifecycleProducer.UPDATED.equals(type)
                || com.project.userservice.message.producer.UserLifecycleProducer.DELETED.equals(type)) {
            try {
                // 페이로드는 이미 JSON 문자열이다 — 그대로 보낸다. 행을 쓴 요청의 추적 아래에서(docs/51 §4e).
                outboxTraceContext.resume(event.getTraceContext(), "outbox relay " + type, () -> {
                    lifecycleProducer.publish(type, event.getAggregateId(), event.getPayload());
                    return null;
                });
                return true;
            } catch (Exception e) {
                log.error("아웃박스 발행 실패: id={} type={}", event.getId(), type, e);
                return false;
            }
        }
        if (!UserCreatedProducer.TOPIC.equals(type)) {
            // 모르는 종류를 조용히 흘려보내면 그 행은 영원히 STARTED 로 남아 매 주기마다
            // 다시 선점된다. FAILED 로 못 박아 눈에 띄게 한다.
            log.error("아웃박스에 모르는 event_type 이 있다: {} (id={})",
                    event.getEventType(), event.getId());
            return false;
        }

        try {
            UserCreatedRequest dto =
                    objectMapper.readValue(event.getPayload(), UserCreatedRequest.class);
            // 가입 확인 요청의 추적 문맥 아래에서 보낸다(docs/51 §4e).
            outboxTraceContext.resume(event.getTraceContext(), "outbox relay user-created", () -> {
                userCreatedProducer.publish(dto);
                return null;
            });
            return true;
        } catch (Exception e) {
            // 페이로드가 깨졌든 브로커가 죽었든 결론은 같다 — 이 행은 지금 못 보낸다.
            log.error("아웃박스 발행 실패: id={}", event.getId(), e);
            return false;
        }
    }
}
