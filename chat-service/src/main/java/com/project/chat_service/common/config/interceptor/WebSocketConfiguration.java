package com.project.chat_service.common.config.interceptor;


import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import com.project.chat_service.common.config.interceptor.StompHandler;

@Configuration
@RequiredArgsConstructor
@EnableWebSocketMessageBroker //websocker 활상화하고 메세지 브로커 사용가능
public class WebSocketConfiguration implements WebSocketMessageBrokerConfigurer {
    //private final StompHandler stompHandler;

    /** 연결 · 끊김을 알아야 토큰 만료 · 로그인 해제에 닫을 수 있다(docs/68 §9). */
    private final LiveSessionRegistry liveSessions;

    // 나머지 서비스의 globalcors 설정(https://localhost:3000)과 동일한 오리진만 허용한다.
    @Value("${frontend.url:https://localhost:3000}")
    private String frontendUrl;

    /**
     * STOMP 엔드포인트를 등록하는 메서드
     * Stomp 연결을 프론트에서 시도할때 요청을 보낼 엔드포인트를 지정하는 부분
     * param StompEndpointRegistry
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/chat") //stomp endpoint 지정
                .setAllowedOrigins(frontendUrl)
                .withSockJS();

    }
    /**
     * 메시지 브로커를 구성하는 메서드
     * 채팅방번호로 구독하는 채팅방을 구분
     * param MessageBrokerRegistry
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/sub"); // /subscribe/{chatNo}로 주제 구독 가능
        registry.setApplicationDestinationPrefixes("/pub");// /publish/message로 메시지 전송 컨트롤러 라우팅 가능

    }

    // prevent circular referenece
    @Bean 
    public StompHandler myChannelInterceptor() {
        return new StompHandler();
    }

    /**
     * 클라이언트 인바운드 채널을 구성하는 메서드
     * JWT 인증 절차가 채팅에서도 적용
     * param ChannelRegistration
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // stompHandler를 인터셉터로 등록하여 STOMP 메시지 핸들링을 수행
        registration.interceptors(myChannelInterceptor());
    }
    /** 들어오는 STOMP 메시지 하나의 상한 — 본문 1000자(MessageDto.MAX_CONTENT_LENGTH, UTF-8 로 3KB 남짓)와 헤더에 넉넉하다. */
    static final int MESSAGE_SIZE_LIMIT = 64 * 1024;

    /**
     * 🐛 예전: 메시지 상한이 160 × 64KB = 10MB 였다("64KB 이상을 못 보내는 문제 해결"). 채팅은 글자만 보내므로 그 크기가 필요한 곳이
     * 없고, 10MB 짜리 메시지가 몽고에 저장되고 방 전체에 방송될 수 있었다. 넘으면 그 연결을 닫는다(Spring 기본 동작).
     * [JA] 以前はメッセージ上限が 10MB だった。チャットは文字だけなので不要で、巨大なメッセージが保存・放送されえた。
     * param WebSocketTransportRegistration
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        registry.setMessageSizeLimit(MESSAGE_SIZE_LIMIT);
        registry.setSendTimeLimit(100 * 10000);
        registry.setSendBufferSizeLimit(3 * 512 * 1024);
        registry.addDecoratorFactory(liveSessions::decorate);

    }
}
