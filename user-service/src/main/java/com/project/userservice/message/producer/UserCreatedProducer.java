package com.project.userservice.message.producer;

import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.project.common.message.dto.request.UserCreatedRequest;


/**
 * 가입이 확정된 사용자를 다른 서비스에 알린다.
 *
 * <p>이 이벤트를 받아 chat-service 는 {@code chat_member} 행을 만들고, coupon-service 는 <b>가입 축하 쿠폰을
 * 발급한다.</b> 이 한 줄이 실패하면 그 사용자는 채팅에 못 들어가고 쿠폰을 못 받는다. (order-service 는 더 듣지
 * 않는다 — 주문자 id 는 토큰의 uid 다, docs/48 §6-①.)
 *
 * <h3>🐛 예전에는 실패해도 아무 데도 남지 않았다</h3>
 *
 * <pre>{@code
 * @Transactional
 * public void publish(UserCreatedRequest req) {
 *     kafkaTemplate.send("user-created", String.valueOf(req.getUserId()), req);
 * }
 * }</pre>
 *
 * <p>둘이 잘못돼 있었다.
 *
 * <ul>
 *   <li><b>dual-write.</b> {@code @Transactional} 이 붙어 있어도 <b>카프카 발행은 그
 *       트랜잭션에 참여하지 않는다.</b> 계정은 커밋되고 이벤트는 안 갈 수 있다.</li>
 *   <li><b>결과를 보지 않는다.</b> {@code send} 는 비동기라 future 를 돌려주고 즉시
 *       끝난다 — 직렬화 실패, 브로커 거절, 타임아웃 중 무엇이 나도 로그 한 줄 없다.</li>
 * </ul>
 *
 * <p>실제로 그 결과를 봤다: {@code user-created} 토픽이 통째로 비어 있는데(offset 0,
 * lag 0) 계정은 멀쩡했고, 그 사용자는 주문 버튼을 누르면 400 을 받았다(docs/47).
 *
 * <h3>이제 이 클래스는 "보내기"만 한다</h3>
 *
 * <p>무엇을 보낼지는 <b>아웃박스가 정한다</b>({@code UserOutboxScheduler}). 여기는 그
 * 한 건을 보내고 <b>성공했는지 확실히 말해 주는</b> 일만 맡는다 — 그래서 동기다.
 * 비동기로 두면 폴러가 상태를 확정할 수 없고, 같은 문제가 한 겹 아래로 옮겨 갈 뿐이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserCreatedProducer {

    /** 토픽 이름이자 아웃박스의 {@code event_type}. 나중에 CDC EventRouter 로 옮겨도 그대로 쓰인다. */
    public static final String TOPIC = "user-created";

    private final KafkaTemplate<String, UserCreatedRequest> kafkaTemplate;

    /**
     * 브로커의 ack 를 기다리는 시간.
     *
     * <p>무한정 기다리면 안 된다 — 폴러가 트랜잭션을 연 채 잠금을 쥐고 있다. 여기서
     * 막히면 그 배치 전체가 멈추고 다른 레플리카도 그 행을 볼 수 없다.
     */
    @Value("${app.outbox.publish-timeout-ms:10000}")
    private long publishTimeoutMs;

    /**
     * 한 건을 보내고 <b>끝날 때까지 기다린다.</b>
     *
     * @throws RuntimeException 발행에 실패하면. 호출자가 그 행을 FAILED 로 남긴다.
     */
    public void publish(UserCreatedRequest userCreatedRequest) {
        Long userId = userCreatedRequest.getUserId();
        try {
            // 키 = 사용자 id — 소비자 셋(order·coupon·chat)이 같은 사용자의 이벤트를 같은
            // 파티션에서 본다(docs/38 §1.15).
            kafkaTemplate.send(TOPIC, String.valueOf(userId), userCreatedRequest)
                    .get(publishTimeoutMs, TimeUnit.MILLISECONDS);
            log.info("user-created 발행: userId={}", userId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("user-created 발행이 중단됐다: userId=" + userId, e);
        } catch (Exception e) {
            // 이메일·이름은 남기지 않는다. userId 로 충분히 추적된다.
            throw new IllegalStateException("user-created 발행 실패: userId=" + userId, e);
        }
    }
}
