package com.project.chat_service.message.fanout;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * {@link StompFanout#CHANNEL} 구독 — 받은 메시지를 <b>스레드 하나로 차례대로</b> 이 파드의 구독자에게 보낸다.
 *
 * <p>컨테이너의 기본 실행기({@code SimpleAsyncTaskExecutor})는 메시지마다 새 스레드를 띄워, Redis 가 순서대로 준 메시지도 화면에는
 * 뒤섞여 도착할 수 있다(대화 순서가 곧 채팅이다). 그래서 스레드 하나짜리 실행기를 준다(ChatScaleOutRedisTest 가 순서를 본다).
 * 로그인 해제 채널(SessionRevocationListenerConfig)과 컨테이너를 나눈 것은 그쪽의 느린 처리(연결 닫기)가 메시지 전달을 붙잡지 않게.
 *
 * <p>[JA] ファンアウトの購読 — 受けたメッセージを一本のスレッドで順に送る(既定の実行器はメッセージごとにスレッドを立て、順序が崩れる)。
 */
@Configuration
public class StompFanoutConfig {

    @Bean
    public ThreadPoolTaskExecutor stompFanoutExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setThreadNamePrefix("stomp-fanout-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    @Bean
    public RedisMessageListenerContainer stompFanoutListener(RedisConnectionFactory connectionFactory, StompFanout fanout,
            ThreadPoolTaskExecutor stompFanoutExecutor) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(stompFanoutExecutor);
        container.addMessageListener((message, pattern) -> fanout.onEnvelope(message.getBody()),
                new ChannelTopic(StompFanout.CHANNEL));
        return container;
    }
}
