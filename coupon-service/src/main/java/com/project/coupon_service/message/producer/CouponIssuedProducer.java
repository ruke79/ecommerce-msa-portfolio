package com.project.coupon_service.message.producer;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.coupon_service.model.Coupon;

import lombok.extern.slf4j.Slf4j;

/**
 * 쿠폰이 발급됐다는 사실을 알린다.
 *
 * <p>🐛 <b>지금까지 이 이벤트가 없었다.</b> {@code NotificationType.COUPON_ISSUED} 는
 * 메일 제목({@code MailTemplates})·SMS 문구({@code SmsTemplates})·메일 종류 매핑
 * ({@code MailKind})·수신 설정 정책이 전부 갖춰져 있었는데, <b>그 알림을 만들어 내는 쪽이
 * 없었다.</b> 가입하면 쿠폰이 발급되지만 사용자는 결제 화면에 가서야 그 사실을 알았다.
 *
 * <p>배송 알림({@code delivery-status})과 같은 형태로 맞춘다 — 알림 서비스가 이미
 * 그 모양을 읽을 줄 안다:
 *
 * <ul>
 *   <li>{@code customerId} — 받는 사람</li>
 *   <li>{@code eventId} — 재전달돼도 알림이 하나만 생기게 하는 안정적인 키</li>
 * </ul>
 *
 * <p><b>발행 실패가 발급을 되돌리지 않는다.</b> 쿠폰은 이미 DB 에 저장됐고 그것이 진실이다.
 * 알림을 못 보냈다고 쿠폰을 취소하면 "받았어야 할 쿠폰이 없는" 쪽이 되는데, 그게 훨씬
 * 나쁘다. 실패는 로그로 남긴다.
 *
 * <p>(정석은 아웃박스다 — 쿠폰 저장과 이벤트 발행을 한 트랜잭션에 묶는 것. 이 서비스에
 * outbox 테이블을 두는 별도 작업이고, delivery-service 도 같은 이유로 직접 발행한다.)
 */
@Slf4j
@Component
public class CouponIssuedProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${coupon.topics.issued:coupon-issued}")
    private String topic;

    /**
     * ⚠️ 생성자를 직접 쓴다. {@code @RequiredArgsConstructor} 로는 {@link Qualifier} 를 생성자
     * 파라미터로 옮길 수 없어서다(Lombok 의 {@code copyableAnnotations} 설정이 필요한데 이
     * 저장소에는 {@code lombok.config} 가 없다). catalog-service 의
     * {@code EventStartedProducer} 가 같은 이유로 같은 모양이다.
     *
     * <p>이름을 명시하는 이유: 이 설정에는 Avro 용
     * {@code KafkaTemplate<String, CouponUseRequest>} 도 있다. 지금은 제네릭이 달라 타입만으로도
     * 갈리지만, 문자열 템플릿이 하나 더 생기면 그때는 <b>이름 없이는 갈리지 않는다.</b>
     */
    public CouponIssuedProducer(
            @Qualifier("couponEventKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publish(Coupon coupon) {
        if (coupon == null || coupon.getUserId() == null) {
            return;
        }

        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("couponId", coupon.getCouponId());
            event.put("customerId", coupon.getUserId());
            event.put("name", coupon.getName());
            event.put("discount", coupon.getDiscount());
            event.put("endDate", coupon.getEndDate() == null ? null : coupon.getEndDate().toString());
            event.put("occurredAt", LocalDateTime.now().toString());
            // 쿠폰 하나당 알림 하나. 재전달돼도 sourceEventId 가 UNIQUE 라 중복되지 않는다.
            event.put("eventId", "coupon:" + coupon.getCouponId());

            Long couponId = coupon.getCouponId();
            // 🐛 예전: send 의 결과를 보지 않았다 — send 는 바로 Future 를 돌려주므로 브로커 거절 · 시간 초과는 아래 catch 에 걸리지
            //    않고 아무 흔적도 남지 않았다(알림만 조용히 사라졌다). 비동기 결과로 남긴다.
            // [JA] 以前は send の結果を見ていなかった — ブローカーの拒否・タイムアウトは catch に掛からず痕跡が残らなかった。
            kafkaTemplate.send(topic, String.valueOf(coupon.getUserId()), objectMapper.writeValueAsString(event))
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("쿠폰 발급 발행 실패(브로커): couponId={} — 알림이 나가지 않는다: {}", couponId,
                                    ex.getClass().getSimpleName());
                        } else {
                            log.debug("쿠폰 발급 발행: couponId={}", couponId);
                        }
                    });

        } catch (Exception e) {
            // 쿠폰은 이미 저장됐다. 알림만 못 나간 것이라 되돌리지 않는다.
            log.error("쿠폰 발급 발행 실패: couponId={} — 알림이 나가지 않는다: {}",
                    coupon.getCouponId(), e.getMessage());
        }
    }
}
