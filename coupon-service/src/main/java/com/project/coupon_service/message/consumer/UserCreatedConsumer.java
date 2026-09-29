package com.project.coupon_service.message.consumer;


import java.util.List;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.project.common.message.dto.request.UserCreatedRequest;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserCreatedConsumer {

        
    private final UserMessageListener userMessageListener;
    
      // required=false — user-created 의 발행자(UserCreatedProducer)는 Authorization 헤더를 싣지 않는다.
      // 필수로 두면 헤더가 없는 배치가 MessageHandlingException 으로 DLT 에 간다. order·chat 의 같은
      // 토픽 리스너는 애초에 헤더를 받지 않는다. 토큰이 없을 때 couponService.create 가 어떻게 되는지는
      // 그쪽(userServiceClient.findUserByEmail 의 인증)이 결정한다 — 여기서 미리 죽이지 않는다.
      @KafkaListener(id = "user-2", topics = "user-created")
    public void received(List<UserCreatedRequest> messages) {
      
      //  messages.forEach(userCreated ->
      //    log.info("User Id : {}", userCreated));

        // 실패 인덱스 보고 → 성공분 커밋 + 실패 레코드만 DLT 격리 (KafkaDltConfig, docs/11 §3)
        for (int i = 0; i < messages.size(); i++) {
            try {
                userMessageListener.userCreated(messages.get(i));
            } catch (Exception e) {
                throw new org.springframework.kafka.listener.BatchListenerFailedException(
                        "user-created(coupon) 처리 실패", e, i);
            }
        }
    }
}
