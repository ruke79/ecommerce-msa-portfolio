package com.project.saga_projection;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 주문 결제 사가의 <b>읽기 모델</b>(프로젝션). {@code order} 와 {@code payment} 토픽을 주문 id 로 접어
 * 주문마다 "요청됐다 / 완료 / 실패 / 취소 / 응답만 있다" 를 유지하고, 그 상태를 {@code order-saga-state}
 * 토픽과 {@code GET /api/saga/{orderId}} 로 낸다(docs/38 §1.17).
 *
 * <p>왜 별도 모듈인가. 이 저장소에서 Kafka Streams 의 exactly-once 가 성립하는 곳은 <b>Kafka 에서 읽어
 * Kafka 로만 쓰는</b> 처리뿐이다. 기존 15개 리스너는 전부 DB 를 갱신하므로 트랜잭션 경계가 둘로 갈라져
 * EOS 가 될 수 없고, 그쪽의 정답은 이미 있는 멱등 원장이다. 그래서 Streams 는 새 소비자에게 준다.
 */
@SpringBootApplication
@EnableDiscoveryClient
public class SagaProjectionApplication {

    public static void main(String[] args) {
        SpringApplication.run(SagaProjectionApplication.class, args);
    }
}
