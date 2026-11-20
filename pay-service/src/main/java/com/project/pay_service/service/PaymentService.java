package com.project.pay_service.service;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.project.common.constants.PaymentProvider;
import com.project.common.constants.PaymentStatus;
import com.project.common.message.dto.request.PaymentRequest;
import com.project.common.message.dto.response.PaymentResponse;
import com.project.common.outbox.OutboxStatus;
import com.project.pay_service.model.Payment;
import com.project.pay_service.outbox.OrderOutboxHelper;
import com.project.pay_service.outbox.model.OrderOutboxEvent;
import com.project.pay_service.ports.output.message.publisher.PaymentResponseKafkaPublisher;
import com.project.pay_service.repository.jpa.PaymentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderOutboxHelper orderOutboxHelper;
    private final PaymentResponseKafkaPublisher paymentResponseKafkaPublisher;

    /**
     * 결제 행과 결제 응답 아웃박스 행을 <b>한 트랜잭션으로</b> 쓴다 — 아웃박스 패턴의 전부가 이것이다.
     *
     * <p>🐛 예전에는 {@code @Transactional} 이 없어 결제 행은 리포지토리의 트랜잭션으로, 아웃박스 행은
     * {@code OrderOutboxHelper} 의 트랜잭션으로 <b>따로</b> 커밋됐다. 둘 사이에서 실패하면(아웃박스 직렬화 · DB 순간 장애 · 파드 종료)
     * 결제는 기록됐는데 order-service 로 갈 응답은 없다. Kafka 재전달이 오면 "이미 있음" 분기가 아웃박스를 다시 써서
     * <b>우연히</b> 복구됐다 — 재전달이 오지 않으면(오프셋이 이미 커밋됐거나 DLT 로 갔다면) 주문은 결제 대기에 멈춘다.
     * 이제 둘 다 커밋되거나 둘 다 되돌려진다. 되돌려지면 리스너가 예외를 던져 Kafka 가 다시 준다(🐛 2026-10-15 전에는 팩토리의 레코드 어댑터가 그 예외를 삼켜 다시 주지 않았다 — B-88).
     */
    // [JA] 決済行と決済応答アウトボックス行を 1 つのトランザクションで書く。以前は別々にコミットされ、間で失敗すると
    //      決済は記録されたのに order-service への応答がなかった(再配信で偶然復旧していただけ)。
    @org.springframework.transaction.annotation.Transactional
    public void persistPayment(PaymentRequest request) {

        Optional<Payment> exist = paymentRepository.findByTrackingId(request.getTrackingId());

        if (!exist.isPresent()) {
            if (request.getPaymentStatus() == PaymentStatus.CANCELED) {
                // 결제 행이 없는데 환불이 왔다 — 결제 요청을 놓친 것이다(2026-10-15 전에는 처리 실패가 조용히 사라졌다, B-88). 응답은 그대로
                // 내되 운영자가 보게 남긴다.
                // [JA] 決済行がないのに返金が来た — 決済リクエストを取りこぼしたのである(2026-10-15 以前は処理失敗が黙って消えていた、B-88)。
                //      応答はそのまま出すが、運用者が見られるよう記録する。
                log.warn("결제 행이 없는 주문의 환불 요청 — 결제 요청을 놓쳤을 수 있다. orderId={}", request.getOrderId());
            }
            Payment pay = Payment.builder()
                    .customerId(request.getCustomerId())
                    .orderId(request.getOrderId())
                    .trackingId(request.getTrackingId())
                    .paymentProvider(providerOf(request))
                    .providerPaymentId(referenceOf(request))
                    .amounts(request.getAmounts())
                    .paymentType(request.getPaymentType())
                    .paymentStatus(request.getPaymentStatus())
                    .paymentCreatedAt(request.getCreatedAt())
                    .build();

            pay = paymentRepository.save(pay);

            PaymentResponse response = PaymentResponse.builder()
                    .paymentId(pay.getPaymentId())
                    .customerId(pay.getCustomerId())
                    .orderId(pay.getOrderId())
                    .amounts(pay.getAmounts())
                    .couponName(request.getCouponName())
                    .paymentStatus(pay.getPaymentStatus())
                    .build();

            orderOutboxHelper.saveOrderOutbox(response, OutboxStatus.STARTED);
        } else {
            Payment pay = exist.get();

            // 환불. order-service 가 같은 아웃박스로 CANCELED 를 보낸다(docs/49 §10).
            //
            // 🐛 예전에는 이 분기가 **기존 행을 절대 바꾸지 않았다** — 같은 trackingId 로
            //    무엇이 오든 저장된 상태를 그대로 되돌려 보냈다. 재전달에는 맞는 동작이지만
            //    환불에는 틀리다: 돈이 돌아갔는데 결제 행은 영원히 COMPLETED 다.
            //    상태 전이는 COMPLETED → CANCELED 한 방향뿐이다. CANCELED 에 COMPLETED 가
            //    다시 오면(재전달 순서 역전) 되살리지 않는다.
            // [JA] 返金。order-service が同じアウトボックスで CANCELED を送る(docs/49 §10)。
            //      🐛 以前はこの分岐が**既存の行を決して変えなかった** — 同じ trackingId で
            //      何が来ても保存済みの状態をそのまま返していた。再配信には正しい動作だが
            //      返金には誤り: お金は戻ったのに決済行は永遠に COMPLETED だ。
            //      状態遷移は COMPLETED → CANCELED の一方向だけ。CANCELED に COMPLETED が
            //      再び来ても(再配信の順序逆転)生き返らせない。
            if (request.getPaymentStatus() == PaymentStatus.CANCELED && pay.markCanceled()) {
                pay = paymentRepository.save(pay);
            }

            PaymentResponse response = PaymentResponse.builder()
                    .paymentId(pay.getPaymentId())
                    .customerId(pay.getCustomerId())
                    .orderId(pay.getOrderId())
                    .amounts(pay.getAmounts())
                    .couponName(request.getCouponName())
                    .paymentStatus(pay.getPaymentStatus())
                    .build();

            orderOutboxHelper.saveOrderOutbox(response, OutboxStatus.STARTED);
        }
    }

    /**
     * 결제를 확인해 준 곳. v3 요청은 그대로 싣는다. 앞 릴리스의 order 가 v2 로 쓴 레코드는 그 필드가 비어 있고 토픽
     * 보존 기간 동안 남는다 — 그때는 결제수단에서 정한다. 둘은 order 에서 1:1 로 대응한다({@code paymentTypeOf}).
     * v2 레코드가 사라진 뒤(docs/49 §3 의 C 단계) 이 대체 경로를 지운다.
     *
     * <p>[JA] 決済を確認した提供元。v3 リクエストはそのまま載せる。前リリースの order が v2 で書いたレコードはそのフィールドが空で、
     * トピックの保持期間のあいだ残る — その場合は決済手段から決める。二つは order で 1:1 に対応する({@code paymentTypeOf})。
     * v2 レコードが消えたあと(docs/49 §3 の C 段階)この代替経路を削除する。
     */
    static PaymentProvider providerOf(PaymentRequest request) {
        if (request.getPaymentProvider() != null) {
            return request.getPaymentProvider();
        }
        return request.getPaymentType() == null ? null : PaymentProvider.from(request.getPaymentType().name());
    }

    /**
     * 결제 참조. v3 부터 모든 제공자의 참조가 여기 실린다. v2 레코드(B 이전의 order 가 쓴 것)는 PayPal 참조를 옛 필드에만
     * 실었고 그 필드는 스키마 v4 에서 빠졌다 — 지금 읽으면 참조는 비어 있다. 그런 레코드는 DLT 재처리로만 올 수 있고,
     * 결제의 원본 참조는 order 의 {@code provider_payment_id} 에 있다(docs/49 §3 C2).
     *
     * <p>[JA] 決済参照。v3 からすべての提供元の参照がここに載る。v2 レコード(B 以前の order が書いたもの)は PayPal 参照を旧フィールドにだけ
     * 載せ、そのフィールドはスキーマ v4 で外れた — 今読むと参照は空である。そうしたレコードは DLT 再処理でしか来ず、
     * 決済の元の参照は order の {@code provider_payment_id} にある(docs/49 §3 C2)。
     */
    static String referenceOf(PaymentRequest request) {
        return request.getProviderPaymentId();
    }

    /**
     * 이미 처리한 요청인가 — 그렇다면 저장된 응답을 다시 발행하고 true.
     *
     * <h3>🐛 환불이 한 번도 반영되지 않았다</h3>
     *
     * <p>예전에는 <b>주문 id 만</b> 보고 "완료된 응답 이벤트가 있으면 처리한 것" 으로 판정했다.
     * 그런데 환불 요청(CANCELED)은 <b>같은 주문</b>으로 온다. 결제 완료 응답이 이미 있으니 환불은
     * 언제나 "중복" 이었다 — 결제 행은 영원히 COMPLETED, order-service 는 옛 COMPLETED 응답을
     * 다시 받아 무시, 쿠폰 복원·재고 복원·환불 알림 전부 일어나지 않았다. 주문 상태만 REFUNDED
     * 라(그건 order-service 가 동기로 바꾼다) 화면에서는 환불이 된 것처럼 보였다.
     *
     * <p>중복 판정은 <b>요청한 상태까지</b> 같아야 한다. 저장된 응답의 상태는 페이로드에서
     * 읽는다({@link OrderOutboxHelper#paymentStatusOf}).
     *
     * <p>[JA] 既に処理したリクエストか — そうなら保存済みの応答を再発行して true。
     * 🐛 返金が一度も反映されなかった: 以前は<b>注文 id だけ</b>を見て「完了した応答イベントがあれば処理済み」と判定していた。
     * ところが返金リクエスト(CANCELED)は<b>同じ注文</b>で来る。決済完了応答が既にあるので返金は常に「重複」だった —
     * 決済行は永遠に COMPLETED、order-service は古い COMPLETED 応答を再び受け取って無視し、クーポン復元・在庫復元・返金通知が
     * すべて起きなかった。注文状態だけが REFUNDED(order-service が同期で変える)なので画面では返金されたように見えた。
     * 重複判定は<b>リクエストした状態まで</b>同じでなければならない。保存済み応答の状態はペイロードから
     * 読む({@link OrderOutboxHelper#paymentStatusOf})。
     */
    /**
     * 같은 상태의 응답이 아직 발행 전(STARTED)인가 — 그러면 릴레이가 곧 보낸다. 새 응답 행을 쓰지 않는다.
     *
     * <p>🐛 예전에는 COMPLETED 응답만 보고 판정해, 첫 응답이 발행 대기 중일 때(발행 타임아웃 · 브로커 흔들림) 같은 요청이 다시 오면 응답
     * 행이 하나 더 생겼다(PY4 — 받는 쪽들이 흡수하지만 같은 결제 응답이 두 번 나갔다).
     * [JA] 同じ状態の応答がまだ発行前(STARTED)か — ならリレーがすぐ送る。新しい応答行を書かない。
     * 🐛 以前は COMPLETED の応答だけを見て判定し、最初の応答が発行待ちのとき(発行タイムアウト · ブローカーの揺れ)同じリクエストがまた来ると
     * 応答行がもう一つできた(PY4 — 受ける側が吸収するが、同じ決済応答が二回出た)。
     */
    public boolean hasPendingResponse(String aggregateId, PaymentStatus requested) {
        for (OrderOutboxEvent event : orderOutboxHelper.getCompletedOrderOutboxEvents(aggregateId, OutboxStatus.STARTED)) {
            if (orderOutboxHelper.paymentStatusOf(event) == requested) {
                log.info("같은 결제 응답이 발행 대기 중이다 — 새로 쓰지 않는다. orderId={} status={}", aggregateId, requested);
                return true;
            }
        }
        return false;
    }

    public boolean publishIfOutboxEventProcessedForPayment(String aggregateId, PaymentStatus requested,
            OutboxStatus outboxStatus) {

        for (OrderOutboxEvent event : orderOutboxHelper.getCompletedOrderOutboxEvents(aggregateId, outboxStatus)) {
            if (orderOutboxHelper.paymentStatusOf(event) == requested) {
                paymentResponseKafkaPublisher.publish(event, orderOutboxHelper::updateOutboxStatus);
                return true;
            }
        }
        return false;
    }

}
