package com.project.common.dto.order;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 관리자 주문 목록의 한 줄.
 *
 * <p>{@link OrderDto} 가 아니다 — 그쪽은 상품·색상·주소까지 끌고 와서(catalog 왕복 포함)
 * 목록 한 페이지에 쓰기에는 무겁다. 환불 화면이 필요한 것은 <b>어느 주문 · 누구 ·
 * 얼마 · 무엇으로 결제 · 지금 상태</b> 뿐이다.
 *
 * <p>고객 이메일은 관리자 콘솔이라 그대로 보여 준다. 다른 화면으로 옮길 때는 마스킹을
 * 먼저 생각할 것.
 *
 * <p>[JA] 管理者注文一覧の一行。
 * {@link OrderDto} ではない — そちらは商品 · 色 · 住所まで引っぱってきて(catalog の往復を含む)一覧一ページに使うには重い。返金画面が必要とするのは
 * <b>どの注文 · 誰 · いくら · 何で決済 · 今の状態</b>だけである。
 * 顧客のメールアドレスは管理コンソールなのでそのまま見せる。他の画面へ移すときはまずマスキングを考えること。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminOrderSummaryDto {

    /**
     * 🐛 <b>문자열이다.</b> TSID 는 18자리라 JSON 숫자로 내려보내면 브라우저의
     * {@code JSON.parse} 가 배정밀도 실수로 읽어 <b>값이 바뀐다</b>
     * ({@code 889196209423729897} → {@code 889196209423729920}). 안전 정수 한계는
     * {@code 2^53-1} 인데 TSID 는 그보다 두 자리 크다.
     *
     * <p>화면에 틀린 번호가 보이고, 그 번호를 다른 화면에 옮겨 적으면 "그런 주문이 없다"가
     * 된다 — 배송 관리의 송장 등록이 실제로 그렇게 막혔다(docs/25 §11.7). 이 저장소의 다른
     * DTO 들이 id 를 String 으로 내리는 이유가 이것이다({@code OrderDto.id}).
     *
     * <p>[JA] 🐛 <b>文字列である。</b>TSID は 18 桁なので JSON の数値で返すとブラウザの {@code JSON.parse} が倍精度浮動小数として読み<b>値が変わる</b>
     * ({@code 889196209423729897} → {@code 889196209423729920})。安全な整数の上限は {@code 2^53-1} だが TSID はそれより二桁大きい。
     * 画面に誤った番号が表示され、その番号を他の画面に写すと「そんな注文はない」になる — 配送管理の送り状登録が実際にそれで詰まった(docs/25 §11.7)。
     * このリポジトリの他の DTO が id を String で返す理由がこれである({@code OrderDto.id})。
     */
    private String orderId;
    private String trackingId;
    /** 주문 번호와 같은 이유로 문자열이다. ／ [JA] 注文番号と同じ理由で文字列である。 */
    private String customerId;
    /** 표시용. 목록은 트랜잭션 안에서 만들고, 이메일은 그 밖에서 user-service 에 한 번 물어 붙인다(order-service docs/48 §6-①). ／ [JA] 表示用。一覧はトランザクション内で作り、メールアドレスはその外で user-service に一度問い合わせて付ける(order-service docs/48 §6-①)。 */
    @lombok.Setter
    private String customerEmail;
    /** 상점 통화, 소수 둘째 자리까지(V7). ／ [JA] ストア通貨、小数第 2 位まで(V7)。 */
    private java.math.BigDecimal total;
    private String paymentMethod;
    private String paymentProvider;
    private String providerPaymentId;
    private String orderStatus;
    private LocalDateTime createdAt;
    private LocalDateTime paidCreatedAt;
    private String refundId;
    private LocalDateTime refundedAt;
    /** 청구 통화·금액. null 이면 상점 통화로 total. ／ [JA] 請求通貨 · 金額。null ならストア通貨で total。 */
    private String currency;
    private java.math.BigDecimal chargedAmount;

    /** 발송된 뒤 환불돼 물건이 돌아와야 하는 주문 — 반품 입고 버튼이 여기 붙는다. ／ [JA] 発送後に返金され物が戻る必要のある注文 — 返品入庫ボタンがここに付く。 */
    private boolean awaitingReturn;
    private LocalDateTime returnReceivedAt;
    /** 재고 복원 이벤트를 보낸 시각. null 이면 아직(또는 파손 등으로 복원하지 않기로 함). ／ [JA] 在庫復元イベントを送った時刻。null ならまだ(または破損などで復元しないことにした)。 */
    private LocalDateTime stockRestoredAt;

    /** 지금까지 돌려준 돈 — 상점 통화와 청구 통화. 부분 환불이 쌓인다. ／ [JA] これまでに返したお金 — ストア通貨と請求通貨。部分返金が積み上がる。 */
    private java.math.BigDecimal refundedAmount;
    private java.math.BigDecimal refundedChargedAmount;

    /** 무통장입금 — 입금 기한·입금자명. 입금 확인 버튼이 여기 붙는다. ／ [JA] 銀行振込 — 入金期限 · 振込名義人。入金確認ボタンがここに付く。 */
    private LocalDateTime depositDeadline;
    private String depositorName;

    /** 품목별 수량 상태. 부분 환불·반품 패널이 여기서 "남은 수량" 을 그린다. ／ [JA] 品目ごとの数量状態。部分返金 · 返品パネルがここから「残り数量」を描く。 */
    private java.util.List<AdminOrderItemDto> items;
}
