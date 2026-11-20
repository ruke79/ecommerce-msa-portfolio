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
 *
 * <p>[JA] 注文決済サガの<b>読み取りモデル</b>(プロジェクション)。{@code order} と {@code payment} トピックを注文 id で畳み込み、
 * 注文ごとに「リクエストされた / 完了 / 失敗 / 取消 / 応答だけある」を保ち、その状態を {@code order-saga-state}
 * トピックと {@code GET /api/saga/{orderId}} で出す(docs/38 §1.17)。
 * なぜ別モジュールか。このリポジトリで Kafka Streams の exactly-once が成り立つのは<b>Kafka から読んで
 * Kafka にだけ書く</b>処理だけである。既存の 15 個のリスナーはすべて DB を更新するのでトランザクション境界が二つに分かれ
 * EOS になれず、そちらの正解は既にある冪等台帳だ。だから Streams は新しいコンシューマに使う。
 */
@SpringBootApplication
@EnableDiscoveryClient
public class SagaProjectionApplication {

    public static void main(String[] args) {
        SpringApplication.run(SagaProjectionApplication.class, args);
    }
}
