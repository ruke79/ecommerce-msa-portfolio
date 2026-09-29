package com.project.userservice.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 확인 메일 <b>요청 발행</b>용 스레드 풀.
 *
 * <p><b>왜 전용 풀인가.</b> {@code @Async} 를 이름 없이 쓰면 Spring 은
 * {@code SimpleAsyncTaskExecutor} 로 넘어가는데, 이건 <b>요청마다 새 스레드를 만들고 상한이
 * 없다.</b> 가입이 몰릴 때 스레드가 무제한으로 늘어 JVM 을 넘어뜨릴 수 있다. 경계를 둔다.
 *
 * <p><b>이제 여기서 하는 일은 Kafka 발행뿐이다</b>(docs/29). 예전에는 SMTP 왕복과 재시도 대기
 * (최대 8초)가 이 스레드 위에서 일어났기 때문에 풀이 훨씬 쉽게 찼다. 지금은 같은 클러스터 안의
 * 브로커에 한 줄 쓰는 것이 전부라 훨씬 빨리 비워진다 — 그래도 상한은 그대로 둔다. 브로커가
 * 흔들리면 발행이 최대 5초까지 걸릴 수 있고({@code MAX_BLOCK_MS}), 그때가 정확히 경계가
 * 필요한 순간이다.
 *
 * <p>큐가 가득 차면 {@code CallerRunsPolicy} 로 호출 스레드가 직접 처리한다. 메일 요청을 버리는
 * 것보다 잠깐 느려지는 편이 낫다 — 버리면 그 사용자는 계정을 활성화할 방법이 없어진다.
 */
@Configuration
@EnableAsync
public class AsyncMailConfig {

    @Bean(name = "mailExecutor")
    public Executor mailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("mail-");
        // 요청의 traceId·userId 를 메일 스레드로 넘긴다 — "가입 메일이 안 왔다" 를 요청과 이어 찾는다(docs/51 §3).
        executor.setTaskDecorator(new com.project.common.logging.MdcTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 종료 시 이미 큐에 들어간 메일은 보내고 내려간다. 그러지 않으면 배포할 때마다
        // 그 순간 가입한 사용자들의 확인 메일이 사라진다.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
