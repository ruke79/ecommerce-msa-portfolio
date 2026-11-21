package com.project.order_service.repository.jpa;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import com.project.common.constants.OrderStatus;
import com.project.common.dto.order.PurchaseStatDto;
import com.project.order_service.model.Order;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    
    // 비관적 잠금: 주문을 조회할 때 다른 트랜잭션이 해당 주문을 변경하지 못하도록 락을 겁니다.
    // [JA] 悲観的ロック: 注文を照会する時、他のトランザクションがその注文を変更できないようロックをかける。
    //@Lock(LockModeType.PESSIMISTIC_WRITE)
    //@Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Order> findByOrderIdAndOrderStatus(Long orderId, OrderStatus status);

    // 낙관적 잠금: 주문 엔티티의 @Version 필드를 활용하여 동시성 문제를 해결합니다.
    // [JA] 楽観的ロック: 注文エンティティの @Version フィールドを活用して同時実行の問題を解決する。
    //@Lock(LockModeType.OPTIMISTIC)
    /**
    //@Lock(LockModeType.OPTIMISTIC)
    /**
     * 추적 번호로 주문 — 품목 · 배송지를 함께 읽는다. 결제 시작 · 입금 안내 · Stripe 처럼 트랜잭션 밖에서 이 주문의 품목을 쓰는 곳이 많다
     * (open-in-view 를 끈 뒤로는 지연 로딩이 거기서 실패한다, B-87).
     *
     * <p>[JA] 追跡番号で注文 — 品目 · 配送先を一緒に読む。決済開始 · 入金案内 · Stripe のように、トランザクションの外でこの注文の品目を使う所が多い
     * (open-in-view を切ってからは遅延ロードがそこで失敗する、B-87)。
     */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"orderedProducts", "shippingAddress"})
    Optional<Order> findByTrackingId(String trackingId);

    /** 제공자 웹훅(콘솔 환불 · 분쟁)이 가리키는 주문 — 제공자 + 그 제공자의 결제 id(5단계 14). [JA] 提供者のウェブフックが指す注文。 */
    Optional<Order> findByPaymentProviderAndProviderPaymentId(com.project.common.constants.PaymentProvider paymentProvider,
            String providerPaymentId);

    /**
     * 주문 행을 잠그고(SELECT … FOR UPDATE) 지금 버전을 읽는다 — 돈이 오가는 경로(환불 · 결제 기록)를 줄 세운다.
     *
     * <p>🐛 예전에는 잠그지 않고 읽어 @Version 이 커밋 때만 봤다. 같은 주문의 환불 둘이 겹치면 둘 다 제공자에게 돈을 돌려주게 한 뒤
     * 늦은 쪽의 커밋만 낙관적 잠금으로 실패했다 — 돈은 두 번 나가고 기록은 한 번이었다. 잠근 뒤 버전이 읽은 것과 다르면
     * (그 사이 다른 요청이 바꿨다) 제공자를 부르기 전에 멈춘다.
     * [JA] 注文行をロックして(SELECT … FOR UPDATE)現在のバージョンを読む — お金が動く経路(返金 · 決済記録)を直列化する。
     * 以前はロックせずに読み、@Version はコミット時にしか見なかった。同じ注文の返金二つが重なると両方とも提供者にお金を返させた後、
     * 遅い方のコミットだけが楽観ロックで失敗した — お金は二回出て記録は一回だった。ロック後にバージョンが読んだものと違えば
     * (その間に別のリクエストが変えた)提供者を呼ぶ前に止まる。
     * [JA] 注文行をロックして現在のバージョンを読む — 返金を直列化する。以前は二つの返金が両方とも提供者を呼び、記録は一回だった。
     */
    @org.springframework.data.jpa.repository.Query(value = "select version from orders.orders where order_id = :id for update",
            nativeQuery = true)
    Long lockVersion(@org.springframework.data.repository.query.Param("id") Long orderId);

    /**
     * 결제는 기록됐는데(제공자 결제 참조가 붙었다) 결제 응답이 오지 않아 아직 결제 전인 주문 — 대사(UnconfirmedPaymentWatch).
     * 정상이면 몇 초 안에 PAID 가 된다. [JA] 決済は記録されたのに決済応答が来ず、まだ決済前の注文 — 照合用。
     */
    @org.springframework.data.jpa.repository.Query("select o.orderId from Order o where o.orderStatus = :status"
            + " and o.providerPaymentId is not null and o.updatedAt < :before order by o.orderId")
    List<Long> findUnconfirmedPayments(@org.springframework.data.repository.query.Param("status") OrderStatus status,
            @org.springframework.data.repository.query.Param("before") LocalDateTime before, Pageable pageable);

    @org.springframework.data.jpa.repository.Query("select count(o) from Order o where o.orderStatus = :status"
            + " and o.providerPaymentId is not null and o.updatedAt < :before")
    long countUnconfirmedPayments(@org.springframework.data.repository.query.Param("status") OrderStatus status,
            @org.springframework.data.repository.query.Param("before") LocalDateTime before);

    long countByCustomerIdAndOrderStatus(Long customerId, OrderStatus status);

    /** 주문 생성의 멱등 키(V26) — 부분 유니크 (customer_id, create_key). */
    // [JA] 注文作成の冪等キー(V26) — 部分ユニーク (customer_id, create_key)。
    Optional<Order> findByCustomerIdAndCreateKey(Long customerId, String createKey);

    // --- 관리자 목록 (고객 무관) — 환불 화면. 최신 주문이 위로 온다. ---
    // [JA] --- 管理者一覧 (顧客に無関係) — 返金画面。最新の注文が上に来る。 ---
    Page<Order> findAllByOrderByOrderIdDesc(Pageable pageable);

    Page<Order> findByOrderStatusOrderByOrderIdDesc(OrderStatus status, Pageable pageable);

    /** 무통장입금 만료 잡 — 기한이 지난 입금 대기 주문. */
    // [JA] 銀行振込の期限切れジョブ — 期限が過ぎた入金待ちの注文。
    List<Order> findByOrderStatusAndDepositDeadlineBefore(OrderStatus status, LocalDateTime deadline);

    /** 자동 구매 확정 잡 — 배달 완료 뒤 기한이 지난 주문(docs/25 §12). 한 번에 페이지 하나만. */
    // [JA] 自動購入確定ジョブ — 配達完了後に期限が過ぎた注文(docs/25 §12)。一度に 1 ページだけ。
    List<Order> findByOrderStatusAndDeliveredCreatedAtBefore(OrderStatus status, LocalDateTime cutoff,
            Pageable pageable);

    /** 결제 응답을 기다리는 주문 — 카드는 NOT_PROCESSED, 무통장입금은 UNPAID. */
    // [JA] 決済応答を待つ注文 — カードは NOT_PROCESSED、銀行振込は UNPAID。
    Optional<Order> findByOrderIdAndOrderStatusIn(Long orderId, Collection<OrderStatus> statuses);

    /** 반품 대기 — 발송된 뒤 환불돼 물건이 돌아와야 하는 주문. */
    // [JA] 返品待ち — 発送後に返金され、品物が戻ってくるべき注文。
    Page<Order> findByAwaitingReturnTrueOrderByOrderIdDesc(Pageable pageable);

    /**
     * 마지막 재고 복원 시각을 찍는다.
     *
     * <p>엔티티를 다시 읽어 저장하지 않고 한 문장으로 하는 이유: 호출자({@code OrderSaga})는
     * 트랜잭션 밖에서 분리된 엔티티를 들고 있고, 그것을 {@code save} 하면 버전 충돌이나
     * 다른 필드의 덮어쓰기가 생긴다. "한 번만" 은 이 시각이 아니라 품목의
     * {@code restockedQty}({@code OrderProductRepository#addRestocked}) 가 지킨다 — 부분
     * 반품(V9)부터 한 주문에 복원이 여럿이다.
     *
     * <p>[JA] 最後の在庫復元時刻を記録する。
     * エンティティを読み直して保存せず一文で行う理由: 呼び出し側({@code OrderSaga})は
     * トランザクションの外で分離されたエンティティを持っており、それを {@code save} するとバージョン衝突や
     * 他のフィールドの上書きが起きる。「一度だけ」はこの時刻ではなく品目の
     * {@code restockedQty}({@code OrderProductRepository#addRestocked}) が守る — 部分
     * 返品(V9)から一つの注文に復元が複数ある。
     */
    @Modifying
    @Transactional
    @Query("update Order o set o.stockRestoredAt = :at where o.orderId = :orderId")
    int markStockRestored(@Param("orderId") Long orderId, @Param("at") LocalDateTime at);

    Optional<List<Order>> findByCustomerId(Long customerId);

     // --- 페이지 기반 조회 메서드 (Pageable) ---
     // [JA] --- ページベースの照会メソッド (Pageable) ---
    /**
    /**
     * 고객 ID로 주문 목록을 페이지 단위로 조회합니다.
     *
     * <p>[JA] 顧客 ID で注文一覧をページ単位で照会する。
     *
     * @param customerId 조회할 고객의 ID
     *                   [JA] 照会する顧客の ID
     * @param pageable 페이지 정보 (PageRequest 객체)
     *                 [JA] ページ情報 (PageRequest オブジェクト)
     * @return Order 엔티티의 페이지 객체
     *         [JA] Order エンティティのページオブジェクト
     */
    Page<Order> findByCustomerId(Long customerId, Pageable pageable);

    // --- 커서 기반 조회 메서드 (Cursor-based Pagination) ---
    // [JA] --- カーソルベースの照会メソッド (Cursor-based Pagination) ---
    /**
    /**
     * 특정 고객의 주문 목록을 커서를 기준으로 페이지 단위로 조회합니다.
     * 커서는 마지막으로 조회한 주문 ID를 의미하며, 이 ID보다 작은 주문을 가져옵니다.
     *
     * <p>[JA] 特定顧客の注文一覧をカーソルを基準にページ単位で照会する。
     * カーソルは最後に照会した注文 ID を意味し、この ID より小さい注文を取得する。
     *
     * @param customerId 조회할 고객의 ID
     *                   [JA] 照会する顧客の ID
     * @param cursorId 마지막으로 조회한 주문 ID
     *                 [JA] 最後に照会した注文 ID
     * @param pageable 페이지 크기 정보
     *                 [JA] ページサイズ情報
     * @return Order 엔티티의 페이지 객체
     *         [JA] Order エンティティのページオブジェクト
     */
    Page<Order> findByCustomerIdAndOrderIdLessThanOrderByOrderIdDesc(Long customerId, Long cursorId, Pageable pageable);

    /**
     * 커서를 사용한 첫 페이지 조회용 메서드 (cursorId가 없을 때 사용).
     *
     * <p>[JA] カーソルを使った最初のページ照会用メソッド (cursorId がない時に使う)。
     *
     * @param customerId 조회할 고객의 ID
     *                   [JA] 照会する顧客の ID
     * @param pageable 페이지 크기 정보
     *                 [JA] ページサイズ情報
     * @return Order 엔티티의 페이지 객체
     *         [JA] Order エンティティのページオブジェクト
     */
    Page<Order> findByCustomerIdOrderByOrderIdDesc(Long customerId, Pageable pageable);

    /**
     * 상태로 거른 커서 조회.
     *
     * <p>🐛 위의 커서 메서드들에는 <b>상태 조건이 없다.</b> 그런데 주문 목록 화면은 상태로
     * 거른다(All / Paid / Unpaid …). 그래서 기존 목록 조회는 주문을 전부 가져온 뒤
     * <b>자바에서</b> 한 건씩 다시 조회하며 걸렀고, 조건에 맞지 않는 주문은 {@code null} 로
     * 남아 <b>응답 목록에 null 이 그대로 섞여 나갔다</b>(OrderDataService.getOrders).
     * 화면이 `order?.id` 처럼 물음표를 붙여 가며 그리던 이유가 이것이다 — "Paid" 를 골라도
     * 행 수는 그대로이고 대부분이 빈 줄이었다.
     *
     * <p>거르기는 SQL 에서 한다. 페이지 크기만큼 <b>실제로 그 상태인</b> 주문이 온다.
     *
     * <p>[JA] 状態で絞ったカーソル照会。
     * 🐛 上のカーソルメソッドには<b>状態条件がない。</b>ところが注文一覧画面は状態で
     * 絞る(All / Paid / Unpaid …)。そのため既存の一覧照会は注文を全部取得した後
     * <b>Java で</b>一件ずつ再照会しながら絞っており、条件に合わない注文は {@code null} として
     * 残り、<b>応答リストに null がそのまま混じって出ていった</b>(OrderDataService.getOrders)。
     * 画面が `order?.id` のように疑問符を付けながら描いていた理由がこれである — 「Paid」を選んでも
     * 行数はそのままで、ほとんどが空行だった。
     * 絞り込みは SQL で行う。ページサイズ分だけ<b>実際にその状態の</b>注文が来る。
     */
    Page<Order> findByCustomerIdAndOrderStatusOrderByOrderIdDesc(Long customerId, OrderStatus status,
            Pageable pageable);

    Page<Order> findByCustomerIdAndOrderStatusAndOrderIdLessThanOrderByOrderIdDesc(Long customerId,
            OrderStatus status, Long cursorId, Pageable pageable);

    /**
     * 고객별 구매 집계 — 등급 산정의 원재료.
     *
     * <p><b>왜 한 방에 GROUP BY 인가.</b> 등급 배치는 "모든 고객" 을 다뤄야 한다. 고객마다
     * 주문을 조회해 자바에서 더하면 고객 수만큼 쿼리가 나간다(N+1 의 야간 배치 버전).
     * 합계는 DB 가 가장 잘 하는 일이다.
     *
     * <p><b>기간 조건이 WHERE 가 아니라 CASE 안에 있는 이유.</b> 결과에는 두 가지가 같이
     * 필요하다 — 최근 3개월 결제액(등급)과 <b>평생 구매 여부</b>(PURCHASED 세그먼트).
     * WHERE 로 기간을 자르면 3개월 안에 주문이 없는 고객은 행 자체가 사라져서, 5년째
     * 단골이지만 최근에 잠잠한 사람이 "한 번도 안 산 사람" 과 구분되지 않는다.
     *
     * <p><b>커서 방식</b>이다. {@code afterCustomerId} 보다 큰 고객만, {@code pageable} 크기만큼.
     * OFFSET 페이징은 GROUP BY 결과 위에서 뒤로 갈수록 느려지고, 배치 도중 새 주문이
     * 들어오면 행이 밀려 건너뛰는 고객이 생긴다. 커서는 둘 다 겪지 않는다.
     *
     * <p>{@code SUM(CASE ...)} 은 {@code null} 이 될 수 없다 — 그룹에는 항상 1건 이상이
     * 있고 CASE 는 언제나 숫자를 내므로 {@code coalesce} 가 필요 없다.
     *
     * <p>{@code o.total} 은 V7 부터 {@code numeric(19,2)} 다. {@link PurchaseStatDto} 의
     * 금액은 {@code long}(상점 통화의 정수 단위)이므로 합계를 {@code Long} 으로 캐스팅한다 —
     * 등급 구간이 달러 단위라 센트는 의미가 없고, DTO 생성자 매칭도 그 형을 요구한다.
     *
     * <p>부분 환불(V9)된 주문은 상태가 그대로(결제됨)라 이 집합에 남는다 — 돌려준 돈만큼
     * 빼서 더한다({@code total − refundedAmount}). 전액 환불은 REFUNDED 라 애초에 빠진다.
     *
     * <p>[JA] 顧客別の購入集計 — 等級算定の原材料。
     * <b>なぜ一発の GROUP BY なのか。</b>等級バッチは「すべての顧客」を扱わなければならない。顧客ごとに
     * 注文を照会して Java で足すと、顧客数だけクエリが出る(N+1 の夜間バッチ版)。
     * 合計は DB が最も得意とすることである。
     * <b>期間条件が WHERE ではなく CASE の中にある理由。</b>結果には二つが同時に
     * 必要である — 直近 3 か月の決済額(等級)と<b>生涯の購入有無</b>(PURCHASED セグメント)。
     * WHERE で期間を切ると 3 か月以内に注文のない顧客は行自体が消え、5 年来の
     * 常連だが最近おとなしい人が「一度も買っていない人」と区別できない。
     * <b>カーソル方式</b>である。{@code afterCustomerId} より大きい顧客だけ、{@code pageable} のサイズ分。
     * OFFSET ページングは GROUP BY の結果の上で後ろへ行くほど遅くなり、バッチ途中に新しい注文が
     * 入ると行がずれて飛ばされる顧客が出る。カーソルはどちらも起こらない。
     * {@code SUM(CASE ...)} は {@code null} になりえない — グループには常に 1 件以上が
     * あり、CASE は常に数値を出すので {@code coalesce} は不要である。
     * {@code o.total} は V7 から {@code numeric(19,2)} である。{@link PurchaseStatDto} の
     * 金額は {@code long}(店舗通貨の整数単位)なので合計を {@code Long} にキャストする —
     * 等級区間がドル単位なのでセントは意味がなく、DTO コンストラクタのマッチングもその型を要求する。
     * 部分返金(V9)された注文は状態がそのまま(決済済み)なのでこの集合に残る — 返したお金の分を
     * 引いて足す({@code total − refundedAmount})。全額返金は REFUNDED なので最初から外れる。
     */
    @Query("""
            select new com.project.common.dto.order.PurchaseStatDto(
                o.customerId,
                count(o),
                sum(case when o.createdAt >= :since then 1 else 0 end),
                cast(sum(case when o.createdAt >= :since then o.total - o.refundedAmount else 0 end) as Long))
            from Order o
            where o.orderStatus in :paidStatuses
              and o.customerId is not null
              and o.customerId > :afterCustomerId
            group by o.customerId
            order by o.customerId asc
            """)
    List<PurchaseStatDto> aggregatePurchases(@Param("paidStatuses") Collection<OrderStatus> paidStatuses,
            @Param("since") LocalDateTime since,
            @Param("afterCustomerId") Long afterCustomerId,
            Pageable pageable);

    // ── 재고가 모자라 성립하지 않은 주문 버리기(docs/59) ────────────────────────
    //
    // 주문은 저장한 뒤에 재고를 잡는다(주문 id 가 예약의 키다). 모자라면 방금 만든 주문을 지운다 — 결제 전이고 밖으로
    // 나간 적도 없다(아웃박스·배송·환불 행이 없다). 주문이 주소를 가리키므로(orders.order_address_id, V25) 주소 id 를 읽고
    // 끊은 뒤 주소 → 품목 → 주문 순서로 지운다(V25 전에 만든 주소는 order_id 로 주문도 가리킨다 — 주문보다 먼저 지우면 된다). 모든 단계가 "결제 전·결제 참조 없음" 을 조건으로 건다 — 잘못 불러도 결제된
    // 주문은 지워지지 않는다.
    // [JA] ── 在庫が足りず成立しなかった注文の破棄(docs/59) ──
    //      注文は保存した後に在庫を確保する(注文 id が予約のキーである)。足りなければ今作った注文を消す — 決済前で外に
    //      出たこともない(アウトボックス・配送・返金の行がない)。注文が住所を指すので(orders.order_address_id, V25)住所 id を読んで
    //      切った後、住所 → 品目 → 注文の順に消す(V25 より前に作った住所は order_id で注文も指す — 注文より先に消せばよい)。すべての段階が「決済前・決済参照なし」を条件にする — 誤って呼んでも決済済みの
    //      注文は消えない。

    @Modifying
    @Query(value = """
            update orders.orders set order_address_id = null
             where order_id = :id and order_status = 'NOT_PROCESSED' and provider_payment_id is null
            """, nativeQuery = true)
    int detachAddressOfUnplaced(@Param("id") Long id);

    /** 결제 전 주문이 가리키는 주소 id — 끊기 전에 읽는다(V25 부터 주소는 주문을 가리키지 않는다). */
    // [JA] 決済前の注文が指す住所 id — 切る前に読む(V25 から住所は注文を指さない)。
    @Query(value = """
            select order_address_id from orders.orders
             where order_id = :id and order_status = 'NOT_PROCESSED' and provider_payment_id is null
            """, nativeQuery = true)
    Long findAddressIdOfUnplaced(@Param("id") Long id);

    /** 끊어 낸 주소를 지운다 — 아직 어느 주문이 가리키면 지우지 않는다. */
    // [JA] 切り離した住所を消す — まだどこかの注文が指していれば消さない。
    @Modifying
    @Query(value = """
            delete from orders.order_address a
             where a.order_address_id = :addressId
               and not exists (select 1 from orders.orders o where o.order_address_id = :addressId)
            """, nativeQuery = true)
    int deleteDetachedAddress(@Param("addressId") Long addressId);

    @Modifying
    @Query(value = """
            delete from orders.order_product p
             where p.order_id = :id
               and exists (select 1 from orders.orders o
                            where o.order_id = :id and o.order_status = 'NOT_PROCESSED' and o.provider_payment_id is null)
            """, nativeQuery = true)
    int deleteProductsOfUnplaced(@Param("id") Long id);

    @Modifying
    @Query(value = """
            delete from orders.orders
             where order_id = :id and order_status = 'NOT_PROCESSED' and provider_payment_id is null
            """, nativeQuery = true)
    int deleteUnplaced(@Param("id") Long id);

    /**
     * 같은 고객의 <b>다른</b> 결제 전 주문 중 재고를 아직 잡고 있는 것(docs/59 §4). 장바구니는 결제 뒤에야 비워지므로, 주문하고
     * 돌아가 다시 주문하면 앞 주문의 예약이 새 주문을 "남의 예약" 으로 막는다 — 새 주문을 만들 때 앞의 것을 풀어 준다.
     * 입금 대기(UNPAID)는 입금 기한까지 잡아 둔 약속이라 건드리지 않는다.
     *
     * <p>[JA] 同じ顧客の<b>別の</b>決済前注文のうち、まだ在庫を確保しているもの(docs/59 §4)。カートは決済後にようやく空になるので、注文して
     * 戻って再び注文すると、前の注文の予約が新しい注文を「他人の予約」として塞ぐ — 新しい注文を作る時に前のものを解放する。
     * 入金待ち(UNPAID)は入金期限まで確保しておく約束なので触らない。
     */
    @Query("""
           select o.orderId from Order o
            where o.customerId = :customerId and o.orderId <> :keepOrderId
              and o.orderStatus = :status and o.providerPaymentId is null
              and o.stockHoldUntil > :now
           """)
    List<Long> findHeldUnplacedOrderIds(@Param("customerId") Long customerId, @Param("keepOrderId") Long keepOrderId,
            @Param("now") LocalDateTime now, @Param("status") OrderStatus status);
}
