package com.project.chat_service.message.producer;




import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.project.chat_service.dto.MessageDto;

@Slf4j
@Component
public class MessageProducer {


    @Autowired
    private KafkaTemplate<String, MessageDto> kafkaTemplate;

    // 메시지를 지정한 Kafka 토픽으로 전송
    public void send(String topic, MessageDto data) {

        // 키 = 방 id. 채팅은 **같은 방 안의 순서**가 곧 대화다 — 키가 없으면 파티션이 둘 이상일 때
        // 한 방의 메시지가 흩어져 소비자(→ 화면)에 뒤섞여 도착한다. 지금은 파티션 하나라 안 보이지만
        // 파티션을 늘리는 순간 깨진다(docs/38 §1.15).
        kafkaTemplate.send(topic, data.getRoomId(), data);
    }
}
