package com.project.catalog_service.repository.jpa.data;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.project.catalog_service.model.StockReservation;

/** 결제 전 재고 예약 (docs/59). */
// [JA] 決済前の在庫予約(docs/59)。
public interface StockReservationRepository extends JpaRepository<StockReservation, Long> {

    /** "뺄 주문 없음" — 주문 id 는 양수 TSID 라 겹치지 않는다. */
    // [JA] 「除外する注文なし」— 注文 id は正の TSID なので重ならない。
    long NO_ORDER = -1L;

    List<StockReservation> findByOrderId(Long orderId);

    /**
     * 이 옵션에 다른 주문이 잡아 둔 수 — 살아 있는(HELD·만료 전) 것만. {@code excludeOrderId} 의 예약은 빼고 센다 —
     * 자기 주문의 예약은 자기 몫이다(결제 확정 차감에서 자기 예약을 "남의 것" 으로 세면 자기가 잡은 수를 못 쓴다).
     * 뺄 주문이 없으면 {@link #NO_ORDER} 를 넘긴다 — {@code :p is null} 조건은 PostgreSQL 이 null 파라미터의 타입을
     * 추론하지 못해 깨질 수 있어 쓰지 않는다.
     *
     * <p>🐛 처음에는 JPQL 에 상태를 <b>파라미터</b>로 넘겼다({@code r.status = :held}). PostgreSQL 의 일반 플랜(같은 문장을
     * 여러 번 실행하면 쓰는 것)은 바인딩 값으로 부분 인덱스({@code WHERE status = 'HELD'})의 조건을 증명하지 못해 <b>표 전체를
     * 훑는다</b> — PG16 {@code force_generic_plan} 에서 확인. 이 조회는 SKU 잠금을 쥔 채 돌고(예약·결제 차감), 표는 확정·해제된
     * 행이 쌓이기만 한다. 네이티브로 {@code 'HELD'} 를 리터럴로 쓴다(중첩 enum 의 JPQL 리터럴은 해석이 불안해 피했던 것).
     *
     * <p>[JA] このオプションで他の注文が確保した数 — 有効な(HELD・期限前)ものだけ。excludeOrderId の予約は除いて数える —
     * 自分の注文の予約は自分の取り分だ(決済確定の減算で自分の予約を「他人のもの」と数えると、自分が確保した数を使えない)。
     * 除外する注文がなければ NO_ORDER を渡す — :p is null の条件は、PostgreSQL が null パラメータの型を推論できず壊れることがあるので使わない。
     * 🐛 最初は JPQL で状態をパラメータとして渡していた(r.status = :held)。PostgreSQL の汎用プラン(同じ文を何度も実行すると使われるもの)は、
     * バインド値では部分インデックス(WHERE status = 'HELD')の条件を証明できず表全体を走査する — PG16 の force_generic_plan で確認。
     * この照会は SKU ロックを保持したまま動き(予約・決済減算)、表は確定・解放された行がたまる一方だ。
     * ネイティブで 'HELD' をリテラルとして書く(ネストした enum の JPQL リテラルは解釈が不安定なので避けていた)。
     */
    @Query(value = """
           select coalesce(sum(r.qty), 0) from product.stock_reservation r
            where r.product_id = :productId and r.color_id = :colorId and r.size = :size
              and r.status = 'HELD'
              and r.expires_at > :now
              and r.order_id <> :excludeOrderId
           """, nativeQuery = true)
    long sumHeldByOthers(@Param("productId") Long productId, @Param("colorId") Long colorId, @Param("size") String size,
            @Param("excludeOrderId") Long excludeOrderId, @Param("now") Instant now);

    default long heldByOthers(Long productId, Long colorId, String size, Long excludeOrderId, Instant now) {
        return sumHeldByOthers(productId, colorId, size, excludeOrderId, now);
    }

    /**
     * 만료된 예약을 푼다 — 조건부 갱신이라 여러 파드가 동시에 돌려도 한 번씩만 바뀐다. 상태는 리터럴(위와 같은 이유 — 만료
     * 인덱스도 {@code WHERE status = 'HELD'} 부분 인덱스다).
     *
     * <p>[JA] 期限切れの予約を解放する — 条件付き更新なので複数の Pod が同時に回しても一度ずつしか変わらない。状態はリテラル(上と同じ理由 —
     * 期限切れ用インデックスも WHERE status = 'HELD' の部分インデックスだ)。
     */
    @Modifying
    @Query(value = """
           update product.stock_reservation
              set status = 'RELEASED', updated_at = :now
            where status = 'HELD' and expires_at <= :now
           """, nativeQuery = true)
    int releaseExpired(@Param("now") Instant now);

    /**
     * 상품들의 옵션별 살아 있는 예약 합 — 상세·장바구니의 남은 수량 표시용(docs/59 §5). 행은
     * {@code [product_id, color_id, size, sum]}.
     *
     * <p>네이티브에 {@code status = 'HELD'} 를 <b>리터럴</b>로 쓴다 — 부분 인덱스({@code WHERE status = 'HELD'})는 조건이
     * 리터럴이어야 쓸 수 있다. 바인딩 값이면 PostgreSQL 의 일반 플랜(같은 문장을 여러 번 실행하면 쓰는 것)이 인덱스 조건을
     * 증명하지 못한다. 공개 상세 화면마다 부르는 조회라, 쌓이기만 하는 표를 훑게 두지 않는다.
     *
     * <p>[JA] 商品群のオプション別の有効な予約の合計 — 詳細・カートの残り数量表示用(docs/59 §5)。行は [product_id, color_id, size, sum]。
     * ネイティブで status = 'HELD' をリテラルとして書く — 部分インデックス(WHERE status = 'HELD')は条件がリテラルでないと使えない。
     * バインド値だと PostgreSQL の汎用プラン(同じ文を何度も実行すると使われるもの)がインデックスの条件を証明できない。
     * 公開詳細画面ごとに呼ぶ照会なので、たまる一方の表を走査させない。
     */
    @Query(value = """
           select r.product_id, r.color_id, r.size, sum(r.qty)
             from product.stock_reservation r
            where r.product_id in (:productIds) and r.status = 'HELD' and r.expires_at > :now
            group by r.product_id, r.color_id, r.size
           """, nativeQuery = true)
    List<Object[]> sumHeldByProducts(@Param("productIds") Collection<Long> productIds, @Param("now") Instant now);
}
