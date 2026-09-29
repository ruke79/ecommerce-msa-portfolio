package com.project.pay_service.ports.input.message.listener;

import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.project.common.constants.PaymentStatus;
import com.project.common.message.dto.request.PaymentRequest;
import com.project.pay_service.exception.PaymentDomainException;
import com.project.pay_service.exception.PaymentNotFoundException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRequestKafaListener {

    private final PaymentRequestMessageListener paymentRequestMessageListener;
    private final com.project.common.outbox.OutboxTraceContext outboxTraceContext;

    @KafkaListener(id="payment-request", topics = "order")
    public void receive(List<PaymentRequest> messages,
            // 🐛 배치 리스너에는 컨슈머 추적이 걸리지 않는다. 레코드마다 헤더(traceparent)에서 추적을 이어받아야
            //    이 안에서 쓰는 아웃박스 행·로그가 보낸 쪽의 추적에 이어진다(docs/51 §4f). 없으면 그냥 처리한다.
            //    ⚠️ 배치 헤더 하나만 받으면 안 된다 — 이 팩토리는 레코드 어댑터라 레코드 헤더가 온다(recordHeaders).
            // [JA] 🐛 バッチリスナーにはコンシューマのトレースが掛からない。レコードごとにヘッダー(traceparent)から
            //      トレースを引き継いでこそ、この中で書くアウトボックス行・ログが送信側のトレースにつながる(docs/51 §4f)。
            //      ヘッダーが無ければそのまま処理する。
            //      ⚠️ バッチヘッダーを一つだけ受け取ってはならない — このファクトリはレコードアダプターなので
            //      レコードヘッダーが届く(recordHeaders)。
            @org.springframework.messaging.handler.annotation.Headers java.util.Map<String, Object> headers) {

        // 🔒 예전에는 "Authorization 헤더 또는 페이로드의 토큰이 있어야 한다" 를 검사했다. 그런데 그 토큰을
        //    <b>검증하지도, 쓰지도 않았다</b> — 존재만 봤다. 인증처럼 보이는 검사는 인증이 아니고, 그 대가로 사용자의
        //    액세스 토큰이 order 의 아웃박스 행과 이 토픽에 평문으로 남았다. 게다가 토큰이 없는 정상 경로(환불·
        //    무통장 입금 확인)가 CDC 릴레이로 오면 헤더도 페이로드 토큰도 없어 **결제가 DLT 로 갔다.** 지웠다
        //    (docs/54 §1.7). 이 토픽에 쓸 수 있는 주체를 제한하는 것은 브로커 ACL(SASL) 의 몫이다.
        // [JA] 🔒 以前は「Authorization ヘッダーかペイロードのトークンが必要」をチェックしていた。しかしそのトークンは
        //      <b>検証も利用もしていなかった</b> — 存在を見ていただけだ。認証に見えるチェックは認証ではなく、その代償として
        //      ユーザーのアクセストークンが order のアウトボックス行とこのトピックに平文で残った。しかもトークンの無い
        //      正常経路(返金・銀行振込の入金確認)が CDC リレー経由で来ると、ヘッダーにもペイロードにもトークンが無く
        //      **決済が DLT に送られていた。** 削除した(docs/54 §1.7)。このトピックに書き込める主体を制限するのは
        //      ブローカー ACL(SASL) の役割である。
        {
            // 실패 인덱스를 BatchListenerFailedException 으로 보고 → 성공분 커밋 + 실패 레코드만
            // order.DLT 격리 (KafkaDltConfig, docs/11 §3). 기존 NO-OP 스킵(유니크 제약=이미 처리됨,
            // 결제 없음)은 의도적으로 유지한다.
            // [JA] 失敗インデックスを BatchListenerFailedException で報告 → 成功分はコミットし、失敗レコードだけを
            //      order.DLT に隔離する(KafkaDltConfig, docs/11 §3)。既存の NO-OP スキップ(ユニーク制約=処理済み、
            //      決済なし)は意図的に残している。
            for (int i = 0; i < messages.size(); i++) {
                PaymentRequest paymentRequest = messages.get(i);
                try {
                    // 결제 요청을 보낸 주문 요청의 추적 안에서 처리한다 — 여기서 쓰는 결제 응답 아웃박스 행이
                    // 그 추적을 남기고, 결제 응답까지 한 추적으로 이어진다(docs/51 §4f).
                    // [JA] 決済リクエストを送った注文リクエストのトレースの中で処理する — ここで書く決済応答の
                    //      アウトボックス行がそのトレースを残し、決済応答まで一つのトレースでつながる(docs/51 §4f)。
                    outboxTraceContext.continueFrom(com.project.common.outbox.OutboxTraceContext.recordHeaders(headers, i),
                            "receive payment-request", () -> {
                        if (PaymentStatus.COMPLETED == paymentRequest.getPaymentStatus()) {
                            log.info("Processing payment for order id: {}", paymentRequest.getOrderId());
                            paymentRequestMessageListener.completePayment(paymentRequest);
                        } else if(PaymentStatus.CANCELED == paymentRequest.getPaymentStatus()) {
                            log.info("Cancelling payment for order id: {}", paymentRequest.getOrderId());
                            paymentRequestMessageListener.cancelPayment(paymentRequest);
                        }
                        return null;
                    });
                } catch (DuplicateKeyException e) {
                    // 이미 처리된 결제다. pay.payment 의 UNIQUE(order_id, tracking_id) 가 막아 준
                    // 것이므로 중복 소비를 여기서 흡수한다 — Kafka 는 at-least-once 이고, 아웃박스
                    // 재발행도 있으므로 같은 결제 요청은 실제로 두 번 온다.
                    //
                    // ⚠️ 예전 코드는 이 경로를 **한 번도 타지 못했다**. 두 가지가 겹쳐 있었다:
                    //   ① `"23000" == sqlException.getSQLState()` — String 을 == 로 비교했다.
                    //      드라이버가 만든 문자열은 인터닝되지 않으므로 항상 false 다.
                    //   ② SQLState 값도 틀렸다. PostgreSQL 의 유니크 위반은 **23505** 이고,
                    //      23000 은 다른 DB 계열이 쓰는 일반 무결성 위반 클래스다.
                    // 결과적으로 중복 결제 메시지는 스킵되지 않고 전부 DLT 로 갔다.
                    // 이제는 Spring 이 23505 를 번역해 주는 예외 타입으로 직접 잡는다 —
                    // SQLState 문자열을 손으로 비교하지 않으므로 같은 실수가 재발하지 않는다.
                    // [JA] 処理済みの決済である。pay.payment の UNIQUE(order_id, tracking_id) が防いでくれたものなので、
                    //      重複消費はここで吸収する — Kafka は at-least-once であり、アウトボックスの再発行もあるため、
                    //      同じ決済リクエストは実際に二度届く。
                    //
                    //      ⚠️ 以前のコードはこの経路を**一度も通れなかった**。二つの問題が重なっていた:
                    //        ① `"23000" == sqlException.getSQLState()` — String を == で比較していた。
                    //           ドライバが生成した文字列はインターンされないので常に false になる。
                    //        ② SQLState の値も誤っていた。PostgreSQL のユニーク違反は **23505** であり、
                    //           23000 は他の DB 系が使う一般的な整合性違反クラスである。
                    //      結果として重複決済メッセージはスキップされず、すべて DLT に送られていた。
                    //      今は Spring が 23505 を変換してくれる例外型で直接捕捉する —
                    //      SQLState 文字列を手で比較しないので、同じミスは再発しない。
                    log.info("Duplicate payment for order id: {} — already processed, skipping",
                            paymentRequest.getOrderId());
                } catch (DataAccessException e) {
                    throw new org.springframework.kafka.listener.BatchListenerFailedException(
                            "order(payment-request) 처리 실패",
                            new PaymentDomainException("Throwing DataAccessException in" +
                                    " PaymentRequestKafkaListener: " + e.getMessage(), e), i);
                } catch (PaymentNotFoundException e) {
                    //NO-OP for PaymentNotFoundException
                    log.error("No payment found for order id: {}", paymentRequest.getOrderId());
                } catch (Exception e) {
                    throw new org.springframework.kafka.listener.BatchListenerFailedException(
                            "order(payment-request) 처리 실패", e, i);
                }
            }
        }

    }
}
