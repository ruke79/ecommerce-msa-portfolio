package com.project.chat_service.message.consumer;




import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Component;

import com.project.chat_service.common.util.KafkaUtil;
import com.project.chat_service.dto.MessageDto;
import com.project.chat_service.service.ChatService;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageConsumer {

    private final SimpMessageSendingOperations template;
    private final ChatService chatService;
    private final com.project.chat_service.service.SupportDeskNotifier deskNotifier;
    private final com.project.chat_service.service.SupportReplyNotifier replyNotifier;


    @KafkaListener(topics = KafkaUtil.KAFKA_TOPIC, containerFactory = "kafkaListenerContainerFactory")
    public void receiveMessage(MessageDto message) {
        log.info("전송 위치 = /sub/chat/room/"+ message.getRoomId());
        log.info("채팅 방으로 메시지 전송 = {}", message);

        // 영속화는 **소비자 측**에서 한다(docs/16 B-24).
        // 예전에는 ChatService.saveMessage 를 호출하는 곳이 저장소 전체에 하나도 없어
        // 실시간 전달만 되고 **어떤 메시지도 저장되지 않았다** — 대화 이력 조회 API 와
        // 커서 페이지네이션이 영원히 빈 결과를 돌려주는 상태였다.
        //
        // 생산자 측(sendMessage)이 아니라 여기서 저장하는 이유:
        //   - 컨슈머 그룹 덕에 인스턴스를 늘려도 메시지당 정확히 한 번만 저장된다.
        //   - 저장 실패 시 Kafka 재시도/DLQ 경로를 그대로 탈 수 있다(브로드캐스트만 성공하고
        //     조용히 유실되는 상황을 만들지 않는다).
        //
        // 저장을 먼저 하고 브로드캐스트한다 — 구독자가 받은 메시지는 이미 이력에 있어야 한다.
        chatService.saveMessage(message);

        // 메시지객체 내부의 채팅방번호를 참조하여, 해당 채팅방 구독자에게 메시지를 발송한다.
        template.convertAndSend("/sub/chat/room/" + message.getRoomId(), message);

        // 상담 메시지면 데스크에도 알린다 — 저장된 뒤라 상담원이 열면 그 메시지가 기록에 있다(docs/58 §9).
        // 연결·종료 안내(TEXT_ENTER·LEAVE)는 SupportService 가 CLAIMED·CLOSED 로 따로 알린다.
        if (message.getType() == com.project.common.constants.chat.MessageType.TEXT_TALK) {
            Long threadId = com.project.chat_service.model.SupportThread.threadIdOf(message.getRoomId());
            if (threadId != null && threadId > 0) {
                deskNotifier.notify(threadId, com.project.chat_service.service.SupportDeskNotifier.Kind.MESSAGE,
                        message.getSenderId());
                // 상담원의 답이면 고객에게 알린다(보고 있지 않을 때만 — SupportReplyNotifier).
                replyNotifier.onMessage(message);
            }
        }
    }
}
