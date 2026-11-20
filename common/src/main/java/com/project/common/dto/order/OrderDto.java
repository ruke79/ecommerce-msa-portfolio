package com.project.common.dto.order;

import java.time.LocalDateTime;
import java.util.List;

import com.project.common.dto.SharedUserDto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Data
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED) 
@AllArgsConstructor
public class OrderDto {

    private String id;

    private String trackingId;
    
    private List<OrderedProductDto> products;

    private OrderAddressDto shippingAddress;

    private String paymentMethod;

    private String paymentStatus;
    
    /** 상점 통화, 소수 둘째 자리까지(V7). 예전에는 {@code int} 라 $59.98 이 59 로 왔다. ／ [JA] ストア通貨、小数第 2 位まで(V7)。以前は {@code int} だったので $59.98 が 59 で来た。 */
    private java.math.BigDecimal total;

    private java.math.BigDecimal shippingPrice;

    private java.math.BigDecimal totalBeforeDiscount;

    private String couponApplied;

    
    private String orderStatus;    

    private boolean isPaid;
    
    private LocalDateTime paidCreatedAt;

    private LocalDateTime deliveredCreatedAt;

    /**
     * 구매 확정(docs/25 §12). {@code COMPLETED} 가 된 시각과, 손님이 아니라 기한이 지나
     * 자동으로 됐는지. 화면은 후자를 "자동으로 확정되었습니다" 라고 따로 말한다.
     *
     * <p>[JA] 購入確定(docs/25 §12)。{@code COMPLETED} になった時刻と、客ではなく期限切れで自動的にそうなったか。画面は後者を「自動で確定されました」と別に表示する。
     */
    private LocalDateTime completedAt;

    private boolean autoCompleted;

    /**
     * 청구 통화와 금액 — 제공자에게 실제로 요청하는 결제. {@code total} 은 상점 통화다.
     * 옛 주문은 null 이고, 화면은 그때 상점 통화로 total 을 쓴다.
     *
     * <p>[JA] 請求通貨と金額 — プロバイダーに実際に要求する決済。{@code total} はストア通貨である。古い注文は null で、画面はそのときストア通貨で total を使う。
     */
    private String currency;

    private java.math.BigDecimal chargedAmount;

    /** 무통장입금(docs/49 §11) — 입금 기한과 입금자명. 입금 대기(UNPAID) 주문에만 있다. ／ [JA] 銀行振込(docs/49 §11) — 入金期限と振込名義人。入金待ち(UNPAID)の注文にだけある。 */
    private LocalDateTime depositDeadline;
    /** 결제 전 재고 예약의 만료(docs/59). null 이면 예약 없음. ／ [JA] 決済前の在庫予約の期限(docs/59)。null なら予約なし。 */
    private LocalDateTime stockHoldUntil;

    private String depositorName;

    /** 지금까지 돌려준 돈(상점 통화). 부분 환불이 쌓인다. 화면은 "환불 −$x" 줄을 그린다. ／ [JA] これまでに返したお金(ストア通貨)。部分返金が積み上がる。画面は「返金 −$x」の行を描く。 */
    private java.math.BigDecimal refundedAmount;

    /** 환불됐지만 물건이 아직 돌아오지 않은 주문. 화면은 "반품 접수 중" 으로 보여 준다. ／ [JA] 返金されたが物がまだ戻っていない注文。画面は「返品受付中」と表示する。 */
    private boolean awaitingReturn;

    private LocalDateTime returnReceivedAt;

}
