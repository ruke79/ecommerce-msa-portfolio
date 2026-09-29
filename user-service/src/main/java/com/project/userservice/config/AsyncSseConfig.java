package com.project.userservice.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import lombok.extern.slf4j.Slf4j;

/**
 * SSE(실시간 알림) <b>전송</b>용 스레드 풀.
 *
 * <p>예전에는 {@code NotificationService} 가 필드에서 {@code Executors.newSingleThreadExecutor()}
 * 를 직접 만들었다. 문제가 셋이었다.
 *
 * <ol>
 *   <li><b>스레드 하나가 접속한 모든 브라우저를 담당했다.</b> {@code SseEmitter.send()} 는 그
 *       클라이언트의 소켓에 쓰는 블로킹 I/O 라, 느린 클라이언트 하나(모바일 네트워크, 탭이
 *       백그라운드로 간 브라우저)가 나머지 전부의 알림을 그 뒤에 줄 세웠다.</li>
 *   <li><b>큐에 상한이 없었다.</b> {@code newSingleThreadExecutor} 의 큐는 무제한이라 앞이
 *       막히면 Redis 구독 스레드가 밀어 넣는 알림이 힙에 쌓이기만 한다.</li>
 *   <li><b>종료도 이름도 없었다.</b> 스프링이 모르는 스레드라 내려갈 때 정리되지 않고, 스레드
 *       덤프에서 {@code pool-3-thread-1} 로만 보였다.</li>
 * </ol>
 *
 * <p>풀이 작은 이유: 여기서 하는 일은 이미 열려 있는 소켓에 짧은 문자열을 쓰는 것뿐이다.
 * 병렬성은 "느린 클라이언트가 다른 클라이언트를 막지 않을 만큼"이면 충분하다.
 *
 * <p>큐가 차면 <b>버리고 로그를 남긴다.</b> {@code CallerRunsPolicy} 는 여기서 틀린 선택이다 —
 * 호출자가 Redis 구독 스레드라서, 그 스레드가 SSE 소켓에 쓰느라 멈추면 이 파드의 알림 수신
 * 자체가 멈춘다. 실시간 알림은 놓쳐도 DB 에 남아 있어 목록 조회로 볼 수 있다(§NotificationService).
 */
@Slf4j
@Configuration
public class AsyncSseConfig {

    @Bean(name = "sseExecutor")
    public Executor sseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("sse-");
        executor.setTaskDecorator(new com.project.common.logging.MdcTaskDecorator());   // docs/51 §3
        executor.setRejectedExecutionHandler((task, pool) ->
                log.warn("SSE 전송 큐가 가득 차 알림 하나를 버린다 (active={}, queue={})",
                        pool.getActiveCount(), pool.getQueue().size()));
        // 내려갈 때 큐에 남은 전송을 기다릴 이유가 없다 — 소켓은 어차피 곧 닫힌다.
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
