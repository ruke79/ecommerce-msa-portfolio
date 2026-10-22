package com.project.chat_service.message.producer;




import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.project.chat_service.dto.MessageDto;

/**
 * 채팅 메시지를 {@code chat} 토픽으로 보낸다 — 저장 · 방송은 소비자(MessageConsumer)가 한다.
 *
 * <p>🐛 예전: {@code send} 의 결과를 보지 않았다. 브로커 장애 · 너무 큰 레코드({@code max.request.size}) 같은 비동기 실패는 아무
 * 흔적 없이 사라졌다 — 보낸 사람 화면에는 아무 신호가 없고 메시지는 저장도 방송도 되지 않았다. 이제 실패를
 * {@value #FAILED_METRIC} 로 세고(경보 {@code ChatMessagePublishFailed}) 방 · 메시지 번호를 남긴다(본문은 남기지 않는다).
 *
 * <p>[JA] 以前は send の結果を見ておらず、非同期の失敗が跡形もなく消えた。今は失敗を数え(警報)、ルーム・メッセージ番号を残す。
 */
@Slf4j
@Component
public class MessageProducer {

    public static final String FAILED_METRIC = "chat.message.publish.failed";

    /**
     * 경보가 {@code increase()} 로 보는 계수기를 기동 때 0 으로 만든다(B-101) — 처음 늘 때 생기면 첫 값이 이미 1 이라 increase() 가
     * 첫 사고를 0 으로 본다(2026-10-22 실습: FAILED 3건에도 OrderOutboxRowFailed 가 울리지 않았다).
     * [JA] 警報が increase() で見るカウンターを起動時に 0 で作る — 初回の増加で生まれると最初の値が 1 で、increase() が初回を 0 と見る。
     */
    @jakarta.annotation.PostConstruct
    void registerAlertCounters() {
        io.micrometer.core.instrument.Metrics.counter(FAILED_METRIC);
    }

    @Autowired
    private KafkaTemplate<String, MessageDto> kafkaTemplate;

    // 메시지를 지정한 Kafka 토픽으로 전송
    public void send(String topic, MessageDto data) {

        // 키 = 방 id. 채팅은 **같은 방 안의 순서**가 곧 대화다 — 키가 없으면 파티션이 둘 이상일 때
        // 한 방의 메시지가 흩어져 소비자(→ 화면)에 뒤섞여 도착한다. 지금은 파티션 하나라 안 보이지만
        // 파티션을 늘리는 순간 깨진다(docs/38 §1.15).
        try {
            kafkaTemplate.send(topic, data.getRoomId(), data).whenComplete((result, ex) -> {
                if (ex != null) {
                    failed(data, ex);
                }
            });
        } catch (RuntimeException e) {
            // 동기 실패(직렬화 · 메타데이터 대기 초과)는 호출자에게도 올린다 — STOMP 오류 프레임으로 보낸 사람이 안다.
            failed(data, e);
            throw e;
        }
    }

    private static void failed(MessageDto data, Throwable e) {
        io.micrometer.core.instrument.Metrics.counter(FAILED_METRIC).increment();
        log.error("채팅 메시지 발행 실패: roomId={} messageId={} — 저장 · 방송되지 않았다: {}",
                data.getRoomId(), data.getId(), e.getClass().getSimpleName());
    }
}
