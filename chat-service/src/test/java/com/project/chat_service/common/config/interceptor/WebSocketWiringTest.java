package com.project.chat_service.common.config.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import com.project.chat_service.common.util.TokenHandler;
import com.project.chat_service.message.producer.MessageProducer;
import com.project.chat_service.repository.jpa.ChatMemberRepository;
import com.project.chat_service.repository.jpa.SupportThreadRepository;
import com.project.chat_service.service.ChatService;
import com.project.chat_service.service.SequenceGeneratorService;
import com.project.chat_service.service.SupportDeskNotifier;
import com.project.chat_service.service.SupportPresence;
import com.project.chat_service.service.SupportService;

/**
 * 🔴 STOMP 브로커 조립이 순환 없이 뜬다(docs/57 §17).
 *
 * <p>🐛 2026-09-26 배포에서 chat-service 가 "APPLICATION FAILED TO START" 로 기동하지 못했다. 상담 채팅(docs/58)이 들어오며
 * {@code StompHandler} 가 {@code SupportService} 를 받게 됐는데, 그 아래의 {@code SupportDeskNotifier} 가 <b>브로커가 만드는</b>
 * {@code SimpMessageSendingOperations} 를 필요로 한다 — 그 브로커는 다시 {@code StompHandler} 를 인터셉터로 등록해야 만들어진다.
 * 단위 테스트는 이 조립을 하지 않아 초록이었다. 여기서는 실제 WebSocket 설정·상담 서비스·알림기를 띄우고 나머지만 목으로 둔다.
 */
@DisplayName("chat WebSocket 조립 — 순환 참조 없이 뜬다")
class WebSocketWiringTest {

    @Test
    @DisplayName("🔴 브로커·StompHandler·상담 서비스·알림기가 함께 뜬다")
    void contextStarts() {
        new WebApplicationContextRunner()
                .withUserConfiguration(WebSocketConfiguration.class)
                // 목은 완성된 싱글턴으로 넣는다 — withBean 으로 넣으면 목 클래스가 물려받은 @Autowired·@PersistenceContext 필드를
                // 스프링이 채우려 든다.
                .withInitializer(ctx -> {
                    var bf = ctx.getBeanFactory();
                    bf.registerSingleton("tokenHandler", mock(TokenHandler.class));
                    bf.registerSingleton("chatService", mock(ChatService.class));
                    bf.registerSingleton("supportThreadRepository", mock(SupportThreadRepository.class));
                    bf.registerSingleton("chatMemberRepository", mock(ChatMemberRepository.class));
                    bf.registerSingleton("messageProducer", mock(MessageProducer.class));
                    bf.registerSingleton("sequenceGeneratorService", mock(SequenceGeneratorService.class));
                    bf.registerSingleton("liveSessionRegistry", new LiveSessionRegistry(
                            mock(org.springframework.data.redis.core.StringRedisTemplate.class)));
                })
                .withBean(SupportPresence.class)
                .withBean(SupportDeskNotifier.class)
                .withBean(SupportService.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(StompHandler.class);
                });
    }

    @Test
    @DisplayName("🔴 StompHandler 는 컴포넌트 스캔 대상이 아니다 — 빈은 WebSocketConfiguration 의 @Bean 하나(예전에는 둘)")
    void stompHandlerIsNotAComponent() {
        assertThat(StompHandler.class.isAnnotationPresent(org.springframework.stereotype.Component.class)).isFalse();
    }
}
