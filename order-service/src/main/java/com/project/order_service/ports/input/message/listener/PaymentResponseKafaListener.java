package com.project.order_service.ports.input.message.listener;

import java.util.List;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.project.common.constants.PaymentStatus;
import com.project.common.message.dto.response.PaymentResponse;
import com.project.order_service.domain.exception.OrderNotFoundException;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentResponseKafaListener {

    private final PaymentResponseMessageListener paymentResponseMessageListener;
    private final com.project.common.outbox.OutboxTraceContext outboxTraceContext;

    @KafkaListener(id = "payment-response", topics = "payment", containerFactory = "paymentResponseListenerContainerFactory")
    public void received(List<PaymentResponse> messages,
            // 🐛 배치 리스너에는 컨슈머 추적이 걸리지 않는다. 레코드마다 헤더(traceparent)에서 추적을 이어받아야
            //    이 안에서 쓰는 아웃박스 행·로그가 보낸 쪽의 추적에 이어진다(docs/51 §4f). 없으면 그냥 처리한다.
            //    ⚠️ 배치 헤더 하나만 받으면 안 된다 — 이 팩토리는 레코드 어댑터라 레코드 헤더가 온다(recordHeaders).
            // [JA] 🐛 バッチリスナーにはコンシューマのトレースが掛からない。レコードごとにヘッダ(traceparent)からトレースを
            //      引き継いで初めて、この中で書くアウトボックス行・ログが送信側のトレースにつながる(docs/51 §4f)。無ければそのまま処理する。
            //      ⚠️ バッチヘッダを一つだけ受け取ってはならない — このファクトリはレコードアダプタなのでレコードヘッダが来る(recordHeaders)。
            @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> headers) {

        // 🔒 예전에는 Authorization 헤더가 없으면 배치를 거부했다. 그런데 pay 가 싣는 값은 스케줄러 스레드의
        //    TokenContext — 대개 "Bearer null" 이었고, 여기서는 존재만 봤을 뿐 검증하지 않았다. 그 "토큰" 은 사가를 따라
        //    product-update 에까지 실려 갔지만 아무도 쓰지 않았다. 인증처럼 보이는 검사는 인증이 아니다 — 지웠다
        //    (docs/54 §1.7). 이 토픽에 쓸 수 있는 주체를 제한하는 것은 브로커 ACL(SASL) 의 몫이다.
        // [JA] 🔒 以前は Authorization ヘッダがなければバッチを拒否していた。ところが pay が載せる値はスケジューラスレッドの
        //      TokenContext — たいてい "Bearer null" であり、ここでは存在を見るだけで検証していなかった。その「トークン」は Saga に
        //      乗って product-update にまで運ばれたが、誰も使っていなかった。認証のように見える検査は認証ではない — 削除した
        //      (docs/54 §1.7)。このトピックに書き込める主体を制限するのはブローカー ACL(SASL) の役目である。
        {
            // 실패 인덱스를 BatchListenerFailedException 으로 알려 성공분 오프셋 커밋 + 실패 레코드만
            // 재시도/DLT(payment.DLT) 격리한다. 기존 NO-OP 예외(낙관적 락/주문 없음)는 의도적 스킵 유지.
            // [JA] 失敗したインデックスを BatchListenerFailedException で知らせ、成功分のオフセットはコミットし、失敗レコードだけを
            //      リトライ/DLT(payment.DLT) に隔離する。既存の NO-OP 例外(楽観ロック/注文なし)は意図的なスキップとして維持する。
            for (int i = 0; i < messages.size(); i++) {
                PaymentResponse paymentResponse = messages.get(i);
                try {
                    // 결제 응답의 추적(= 원래 결제 확인 요청의 추적) 안에서 주문을 확정한다(docs/51 §4f).
                    // [JA] 決済応答のトレース(= 元の決済確認リクエストのトレース)の中で注文を確定する(docs/51 §4f)。
                    outboxTraceContext.continueFrom(com.project.common.outbox.OutboxTraceContext.recordHeaders(headers, i),
                            "receive payment-response", () -> {
                        if (PaymentStatus.COMPLETED == paymentResponse.getPaymentStatus()) {
                            log.info("Processing successful payment for order id: {}", paymentResponse.getOrderId());
                            paymentResponseMessageListener.paymentCompleted(paymentResponse);
                        } else if (PaymentStatus.CANCELED == paymentResponse.getPaymentStatus() ||
                                PaymentStatus.FAILED == paymentResponse.getPaymentStatus()) {
                            log.info("Processing unsuccessful payment for order id: {}", paymentResponse.getOrderId());
                            paymentResponseMessageListener.paymentCancelled(paymentResponse);
                        }
                        return null;
                    });
                } catch (OptimisticLockingFailureException e) {
                    // NO-OP for optimistic lock. This means another thread finished the work, do
                    // not throw error to prevent reading the data from kafka again!
                    log.error("Caught optimistic locking exception in PaymentResponseKafkaListener for order id: {}",
                            paymentResponse.getOrderId());
                } catch (OrderNotFoundException e) {
                    // NO-OP for OrderNotFoundException
                    log.error("No order found for order id: {}", paymentResponse.getOrderId());
                } catch (Exception e) {
                    throw new org.springframework.kafka.listener.BatchListenerFailedException(
                            "payment 응답 처리 실패", e, i);
                }
            }

        }

    }
}
