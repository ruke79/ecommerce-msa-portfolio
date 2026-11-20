package com.project.order_service.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.common.constants.OrderStatus;
import com.project.order_service.model.Order;
import com.project.order_service.repository.jpa.OrderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 배송 상태({@code delivery-status} 토픽)를 주문 상태에 반영한다 — 사가의 마지막 마디.
 *
 * <p>{@code OrderStatus} 의 PROCESSING·DISPATCHED·DELIVERED 는 선언만 있고 아무도 채우지
 * 않던 값이었다(docs/25 §0). 주문은 PAID 에서 멈췄고, 프론트의 "발송됨" 필터는 영원히
 * 비어 있었으며, {@code deliveredCreatedAt} 은 언제나 null 이었다. 여기서 채운다.
 *
 * <h3>규칙 — 앞으로만 간다</h3>
 *
 * <p>Kafka 는 순서를 파티션 안에서만 지키고 재전달도 한다. 배달 완료 뒤에 늦게 온 "이동 중"
 * 이 주문을 되돌리면 손님은 받은 물건이 다시 오는 중이라고 보게 된다. 그래서 전이는
 * <b>PAID → PROCESSING → DISPATCHED → DELIVERED</b> 한 방향이고, 같은 자리나 뒤로 가는
 * 이벤트는 무시한다.
 *
 * <p><b>결제된 주문만</b> 움직인다. 환불·취소된 주문({@code REFUNDED}/{@code CANCELED})에
 * 배송 이벤트가 늦게 도착해도 — 발송 뒤 환불된 물건이 그래도 배달되는 경우가 실제로
 * 있다 — 주문 상태는 돈의 상태를 말하므로 덮어쓰지 않는다. {@code COMPLETED}(구매 확정)도
 * 그대로 둔다.
 *
 * <h3>배송 상태 → 주문 상태</h3>
 *
 * <ul>
 *   <li>{@code PENDING}(배송 건 생성) → {@code PROCESSING}</li>
 *   <li>송장 등록 이후 이동 중인 모든 상태(INFO_RECEIVED·PICKED_UP·IN_TRANSIT·통관·배달
 *       출발·배달 실패·예외) → {@code DISPATCHED}</li>
 *   <li>{@code DELIVERED} → {@code DELIVERED} + {@code deliveredCreatedAt}</li>
 *   <li>{@code CANCELLED} → <b>{@code PAID} 로 돌아간다</b>(PROCESSING·DISPATCHED 였다면).
 *       배송 취소는 발송 <b>전</b>에만 가능하므로 물건은 나가지 않았다 — 그런데 주문이
 *       DISPATCHED 로 남으면 "배송이 시작된 주문은 환불할 수 없습니다" 에 갇힌다. 손님이 배송을
 *       취소한 이유가 환불인데 그 길이 막히는 것이다. 실제로 그랬다.</li>
 *   <li>{@code RETURNED} → 주문은 손대지 않는다. 반송은 반품 입고가 처리한다.</li>
 * </ul>
 *
 * <p>"앞으로만 간다" 의 유일한 예외가 {@code CANCELLED} 다. 결제 취소·환불로 시스템이 접은
 * 배송이면 주문은 이미 {@code REFUNDED}/{@code CANCELED} 라 {@link #MOVABLE} 밖이고, 그때는
 * 여기서도 손대지 않는다 — 돈의 상태가 우선이다.
 *
 * <p>모르는 상태는 발행자가 함께 보내는 {@code stage} 로 판정한다 — 배송 서비스가 상태를
 * 하나 더 만들어도 여기가 깨지지 않게.
 *
 * <p>[JA] 配送状態({@code delivery-status} トピック)を注文状態に反映する — サーガの最後の節。
 * {@code OrderStatus} の PROCESSING・DISPATCHED・DELIVERED は宣言だけあって誰も埋めて
 * いなかった値である(docs/25 §0)。注文は PAID で止まり、フロントの「発送済み」フィルタは永遠に
 * 空で、{@code deliveredCreatedAt} は常に null だった。ここで埋める。
 * <b>ルール — 前にだけ進む</b>: Kafka は順序をパーティションの中でだけ守り、再配信もする。配達完了の後に遅れて来た「移動中」
 * が注文を戻すと、顧客は受け取った品物がまた向かっていると見ることになる。そのため遷移は
 * <b>PAID → PROCESSING → DISPATCHED → DELIVERED</b> の一方向で、同じ位置や後ろに戻る
 * イベントは無視する。
 * <b>決済済みの注文だけ</b>動かす。返金・取消された注文({@code REFUNDED}/{@code CANCELED})に
 * 配送イベントが遅れて届いても — 発送後に返金された品物がそれでも配達されることは実際に
 * ある — 注文状態はお金の状態を表すので上書きしない。{@code COMPLETED}(購入確定)も
 * そのままにする。
 * <b>配送状態 → 注文状態</b>: {@code PENDING}(配送作成) → {@code PROCESSING}。
 * 送り状登録以降の移動中のすべての状態(INFO_RECEIVED・PICKED_UP・IN_TRANSIT・通関・配達
 * 出発・配達失敗・例外) → {@code DISPATCHED}。
 * {@code DELIVERED} → {@code DELIVERED} + {@code deliveredCreatedAt}。
 * {@code CANCELLED} → <b>{@code PAID} に戻る</b>(PROCESSING・DISPATCHED だったなら)。
 * 配送取消は発送<b>前</b>にだけ可能なので品物は出ていない — ところが注文が
 * DISPATCHED のまま残ると「配送が始まった注文は返金できません」に閉じ込められる。顧客が配送を
 * 取消した理由が返金なのに、その道が塞がれるのである。実際にそうなった。
 * {@code RETURNED} → 注文には触れない。返送は返品入庫が処理する。
 * 「前にだけ進む」の唯一の例外が {@code CANCELLED} である。決済取消・返金でシステムが畳んだ
 * 配送なら注文は既に {@code REFUNDED}/{@code CANCELED} なので {@link #MOVABLE} の外で、その時は
 * ここでも触れない — お金の状態が優先である。
 * 未知の状態は発行者が一緒に送る {@code stage} で判定する — 配送サービスが状態を
 * 一つ増やしてもここが壊れないように。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderDeliveryStatusService {

    /** 상태 변경 + 이벤트(docs/76). 세터 — 생성자를 쓰는 테스트가 많다. 기본값은 이벤트 없이 상태만 바꾼다(단위 테스트). */
    // [JA] 状態変更 + イベント(docs/76)。セッター — コンストラクタを使うテストが多い。デフォルトはイベントなしで状態だけ変える。
    private OrderStatusChanger statusChanger = OrderStatusChanger.withoutEvents();

    @org.springframework.beans.factory.annotation.Autowired
    public void setStatusChanger(OrderStatusChanger statusChanger) {
        this.statusChanger = statusChanger;
    }

    /** 처리 원장(V30) — 세터, statusChanger 와 같은 이유. 기본값은 원장 없음(단위 테스트). */
    // [JA] 処理台帳(V30) — セッター、statusChanger と同じ理由。デフォルトは台帳なし(単体テスト)。
    private ProcessedEventLedger ledger = ProcessedEventLedger.NONE;

    @org.springframework.beans.factory.annotation.Autowired
    public void setLedger(ProcessedEventLedger ledger) {
        this.ledger = ledger;
    }

    /** 배송 이벤트가 움직일 수 있는 주문 상태. 이 밖의 주문은 돈의 상태가 우선이다. */
    // [JA] 配送イベントが動かせる注文状態。この外の注文はお金の状態が優先である。
    static final Set<OrderStatus> MOVABLE = EnumSet.of(OrderStatus.PAID, OrderStatus.PROCESSING, OrderStatus.DISPATCHED);

    private final OrderRepository orderRepository;
    private final OrderCacheEvictor cacheEvictor;

    /**
     * @return 주문 상태가 실제로 바뀌었으면 true
     *         [JA] 注文状態が実際に変わったら true
     */
    @Transactional
    public boolean apply(Long orderId, String deliveryStatus, String stage) {
        return apply(orderId, deliveryStatus, stage, null);
    }

    /**
     * 원장 키와 함께 — 같은 키를 이미 반영했으면 아무것도 하지 않는다(재전달 · 새 그룹의 다시 읽기).
     *
     * <p>키는 {@link #ledgerKey 배송 건 · 상태}다. 🐛 원장이 없을 때 "앞으로만 간다" 밖의 배송 취소가 다시 오면, 관리자가 배송을 다시 만들어
     * PROCESSING 이 된 주문이 PAID 로 돌아갔다. 배송을 다시 만들면 배송 건 번호가 새로 생기므로 새 배송의 상태는 걸러지지 않는다.
     * [JA] 台帳キー付き — 同じキーをすでに反映していれば何もしない(再配信 · 新しいグループの読み直し)。
     * キーは {@link #ledgerKey 配送件 · 状態}だ。🐛 台帳がないとき「前にだけ進む」の外の配送取消が再び来ると、管理者が配送を作り直して
     * PROCESSING になった注文が PAID に戻った。配送を作り直すと配送番号が新しくできるので、新しい配送の状態は除かれない。
     * ledgerKey が null なら台帳を使わない(以前の動作 — 配送番号のないイベント)。
     *
     * @param ledgerKey null 이면 원장을 쓰지 않는다(예전 동작 — 배송 건 번호가 없는 이벤트)
     */
    @Transactional
    public boolean apply(Long orderId, String deliveryStatus, String stage, String ledgerKey) {
        if (ledgerKey != null && !ledger.markFirst(ledgerKey)) {
            log.info("이미 반영한 배송 상태, 건너뜀: orderId={} key={}", orderId, ledgerKey);
            return false;
        }
        if ("CANCELLED".equalsIgnoreCase(deliveryStatus == null ? "" : deliveryStatus.trim())) {
            return revertToPaidOnCancel(orderId);
        }
        Optional<OrderStatus> target = targetOf(deliveryStatus, stage);
        if (target.isEmpty()) {
            log.debug("주문 상태와 무관한 배송 상태, 무시: orderId={} status={} stage={}", orderId, deliveryStatus, stage);
            return false;
        }

        Optional<Order> found = orderRepository.findById(orderId);
        if (found.isEmpty()) {
            // 배송 서비스가 아는 주문을 우리가 모른다 — 삭제됐거나 다른 환경의 이벤트다. 재시도해도 같다.
            // [JA] 配送サービスが知っている注文を当方が知らない — 削除されたか別環境のイベントである。再試行しても同じ。
            log.warn("배송 상태를 받았지만 주문이 없음: orderId={} status={}", orderId, deliveryStatus);
            return false;
        }
        Order order = found.get();
        OrderStatus current = order.getOrderStatus();

        if (current == null || !MOVABLE.contains(current)) {
            log.info("결제 상태가 우선이라 배송 상태를 반영하지 않음: orderId={} order={} delivery={}",
                    orderId, current, deliveryStatus);
            return false;
        }
        if (!isForward(current, target.get())) {
            log.debug("뒤로 가는 배송 이벤트, 무시: orderId={} {} → {}", orderId, current, target.get());
            return false;
        }

        statusChanger.change(order, target.get(), "delivery");
        if (target.get() == OrderStatus.DELIVERED && order.getDeliveredCreatedAt() == null) {
            order.setDeliveredCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        }
        orderRepository.save(order);
        cacheEvictor.evictOrderCaches(orderId, "delivery→" + target.get());
        log.info("배송 상태 반영: orderId={} {} → {} (delivery={})", orderId, current, target.get(), deliveryStatus);
        return true;
    }

    /**
     * 원장 키 — 배송 건 · 상태. 발행자의 eventId({@code delivery:<주문>:<상태>})는 알림의 중복 거르기용이라 배송을 다시 만든 주문의 두 번째
     * PENDING · CANCELLED 를 같은 값으로 본다 — 여기서는 쓰지 않는다. 배송 건 번호나 상태가 없으면 null(원장 없이 예전 동작).
     * [JA] 台帳キー — 配送番号 · 状態。発行者の eventId は通知の重複除去用で、配送を作り直した注文の二度目を同じ値と見るので使わない。
     */
    public static String ledgerKey(String deliveryId, String deliveryStatus) {
        if (deliveryId == null || deliveryId.isBlank() || deliveryStatus == null || deliveryStatus.isBlank()) {
            return null;
        }
        return "delivery-status:" + deliveryId.trim() + ":" + deliveryStatus.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * 발송 전 배송 취소 → 주문을 {@code PAID} 로 되돌린다.
     *
     * <p>{@code PROCESSING}(배송 준비) 이나 {@code DISPATCHED}(송장 등록) 였을 때만이다.
     * 배송 도메인이 발송 뒤의 취소를 막으므로 {@code DELIVERED} 에서 이 이벤트가 오는 일은
     * 없지만, 온다 해도 되돌리지 않는다 — 받은 물건을 "결제만 된 상태" 로 만들 수는 없다.
     *
     * <p>[JA] 発送前の配送取消 → 注文を {@code PAID} に戻す。
     * {@code PROCESSING}(配送準備) か {@code DISPATCHED}(送り状登録) だった時だけである。
     * 配送ドメインが発送後の取消を防ぐので {@code DELIVERED} でこのイベントが来ることは
     * ないが、来たとしても戻さない — 受け取った品物を「決済だけされた状態」にはできない。
     */
    private boolean revertToPaidOnCancel(Long orderId) {
        Optional<Order> found = orderRepository.findById(orderId);
        if (found.isEmpty()) {
            log.warn("배송 취소를 받았지만 주문이 없음: orderId={}", orderId);
            return false;
        }
        Order order = found.get();
        OrderStatus current = order.getOrderStatus();
        if (current != OrderStatus.PROCESSING && current != OrderStatus.DISPATCHED) {
            log.info("배송 취소 — 주문은 그대로 둔다: orderId={} order={}", orderId, current);
            return false;
        }
        statusChanger.change(order, OrderStatus.PAID, "delivery-cancelled");
        orderRepository.save(order);
        cacheEvictor.evictOrderCaches(orderId, "delivery→CANCELLED");
        log.info("배송 취소로 주문을 되돌림: orderId={} {} → PAID (환불 가능)", orderId, current);
        return true;
    }

    /** 배송 상태 → 주문 상태. 주문을 움직이지 않는 상태면 비어 있다. */
    // [JA] 配送状態 → 注文状態。注文を動かさない状態なら空である。
    static Optional<OrderStatus> targetOf(String deliveryStatus, String stage) {
        String status = deliveryStatus == null ? "" : deliveryStatus.trim().toUpperCase();
        switch (status) {
            case "PENDING":
                return Optional.of(OrderStatus.PROCESSING);
            case "INFO_RECEIVED":
            case "PICKED_UP":
            case "IN_TRANSIT":
            case "CUSTOMS_HOLD":
            case "CUSTOMS_CLEARED":
            case "OUT_FOR_DELIVERY":
            case "ATTEMPT_FAILED":
            case "EXCEPTION":
                return Optional.of(OrderStatus.DISPATCHED);
            case "DELIVERED":
                return Optional.of(OrderStatus.DELIVERED);
            case "CANCELLED":
            case "RETURNED":
                return Optional.empty();
            default:
                break;
        }
        // 모르는 상태 — 단계로 판정한다.
        // [JA] 未知の状態 — 段階で判定する。
        String s = stage == null ? "" : stage.trim().toUpperCase();
        return switch (s) {
            case "ORDERED" -> Optional.of(OrderStatus.PROCESSING);
            case "SHIPPED", "IN_TRANSIT", "CUSTOMS", "EXCEPTION" -> Optional.of(OrderStatus.DISPATCHED);
            case "DELIVERED" -> Optional.of(OrderStatus.DELIVERED);
            default -> Optional.empty();
        };
    }

    /** PAID → PROCESSING → DISPATCHED → DELIVERED 순서에서 앞으로 가는가. */
    // [JA] PAID → PROCESSING → DISPATCHED → DELIVERED の順序で前に進むか。
    private static boolean isForward(OrderStatus from, OrderStatus to) {
        return rank(to) > rank(from);
    }

    private static int rank(OrderStatus s) {
        return switch (s) {
            case PAID -> 0;
            case PROCESSING -> 1;
            case DISPATCHED -> 2;
            case DELIVERED -> 3;
            default -> Integer.MAX_VALUE;
        };
    }
}
