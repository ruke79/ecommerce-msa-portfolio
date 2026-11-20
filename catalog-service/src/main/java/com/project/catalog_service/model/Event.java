package com.project.catalog_service.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.project.common.constants.event.AudienceSegment;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 이벤트 / 기획전 — 기간 한정 세일.
 *
 * <p><b>할인율이 여기(정확히는 {@link EventProduct})에 있는 것이 이 설계의 핵심이다.</b>
 * 이벤트가 시작될 때 {@code ProductSku.discount} 를 덮어쓰고 끝날 때 되돌리는 방식은 쓰지
 * 않는다 — 진행 중에 관리자가 그 상품을 수정하면 복원이 그 수정을 덮고, 이벤트 둘이 같은
 * 상품을 겹쳐 잡으면 먼저 끝나는 쪽이 다른 이벤트의 할인까지 지운다. 상품을 조회할 때
 * 합성한다({@code EventPricing}).
 *
 * <p><b>상태를 시각과 함께 두는 이유.</b> "시작 시각이 지났는가"만으로 판정하면 초안이
 * 저장되자마자 나갈 수 있다. 관리자가 {@link EventStatus#SCHEDULED} 로 확정한 것만
 * 스케줄러가 시작시킨다.
 *
 * <p>[JA] イベント / 企画展 — 期間限定セール。
 * 割引率がここ(正確には EventProduct)にあることがこの設計の核心だ。イベント開始時に ProductSku.discount を上書きし、終了時に戻す方式は使わない —
 * 進行中に管理者がその商品を修正すると復元がその修正を上書きし、二つのイベントが同じ商品を重ねて持つと先に終わる側が他のイベントの割引まで消す。
 * 商品の照会時に合成する(EventPricing)。
 * 状態を時刻と一緒に持つ理由。「開始時刻を過ぎたか」だけで判定すると、下書きが保存されるやいなや公開されうる。
 * 管理者が EventStatus#SCHEDULED で確定したものだけをスケジューラが開始させる。
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Table(name = "event", schema = "content",
       indexes = { @Index(columnList = "status, starts_at", name = "idx_event_status_starts") })
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long eventId;

    @Column(nullable = false, length = 200)
    private String title;

    /**
     * URL 에 쓰는 이름.
     *
     * <p>숫자 id 를 노출하면 링크가 무엇에 대한 것인지 말해 주지 않고, 진행 중인 기획전
     * 수가 그대로 드러난다. 상품 slug 와 같은 이유다.
     *
     * <p>[JA] URL に使う名前。
     * 数値の id を露出すると、リンクが何についてのものか分からず、進行中の企画展の数もそのまま見えてしまう。商品の slug と同じ理由だ。
     */
    @Column(nullable = false, length = 200, unique = true)
    private String slug;

    @Column(length = 2000)
    private String description;

    @Column(name = "banner_image_url", length = 1000)
    private String bannerImageUrl;

    /** 비워 두면 {@code /event/<slug>} 로 간다. */
    // [JA] 空にしておくと /event/<slug> へ行く。
    @Column(name = "link_url", length = 1000)
    private String linkUrl;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EventStatus status = EventStatus.DRAFT;

    @Column(name = "starts_at")
    private LocalDateTime startsAt;

    /** null 이면 관리자가 직접 끝낼 때까지 계속된다. */
    // [JA] null なら管理者が自分で終えるまで続く。
    @Column(name = "ends_at")
    private LocalDateTime endsAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 대상 상품과 각각의 할인율.
     *
     * <p>값 타입 컬렉션이다({@link EventProduct} 주석). 기획전을 지우면 이 행들도 함께
     * 사라지고, 대상에서 뺀 상품의 행은 컬렉션에서 빠지는 것만으로 지워진다 — 엔티티였다면
     * cascade 와 orphanRemoval 을 정확히 걸어야 했고, 빠뜨리면 지운 상품에 할인이 계속
     * 걸린 채 남는다.
     *
     * <p>EAGER 인 이유: 할인 합성이 이 목록을 <b>항상</b> 쓴다. LAZY 로 두면 진행 중인
     * 기획전 수만큼 추가 조회가 붙는다 — 상품 목록 한 번 그릴 때마다.
     *
     * <p>[JA] 対象商品とそれぞれの割引率。
     * 値型コレクションである(EventProduct のコメント)。企画展を消すとこれらの行も一緒に消え、対象から外した商品の行はコレクションから抜けるだけで削除される —
     * エンティティだったら cascade と orphanRemoval を正確にかける必要があり、漏れると消した商品に割引がかかったまま残る。
     * EAGER の理由: 割引の合成がこの一覧を常に使う。LAZY にすると進行中の企画展の数だけ追加照会が付く — 商品一覧を一回描くたびに。
     */
    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "event_product", schema = "content",
                     joinColumns = @JoinColumn(name = "event_id"))
    private List<EventProduct> products = new ArrayList<>();

    /**
     * 대상 카테고리와 각각의 할인율.
     *
     * <p>상품 단위 지정과 <b>함께</b> 쓸 수 있다. 같은 상품이 양쪽에 걸리면 높은 할인율이
     * 이긴다({@code EventPricing}) — 카테고리 전체 20% 에 특정 상품만 40% 같은 조합이
     * 자연스럽게 표현된다.
     *
     * <p>카테고리 → 상품 펼치기는 조회 시점에 한다({@link EventCategory} 주석).
     *
     * <p>[JA] 対象カテゴリとそれぞれの割引率。
     * 商品単位の指定と併用できる。同じ商品が両方にかかると高い割引率が勝つ(EventPricing) — カテゴリ全体 20% に特定商品だけ 40% のような組み合わせが
     * 自然に表現できる。
     * カテゴリ → 商品の展開は照会時点で行う(EventCategory のコメント)。
     */
    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "event_category", schema = "content",
                     joinColumns = @JoinColumn(name = "event_id"))
    private List<EventCategory> categories = new ArrayList<>();

    /**
     * 딸린 쿠폰을 받을 수 있는 고객군.
     *
     * <p>🔴 <b>진열가는 가르지 않는다.</b> 이유는 {@link AudienceSegment} 주석에 있다 —
     * 요약하면 상품 조회에 신원이 없고, 목록이 캐시되며, 화면마다 가격이 달라지기 때문이다.
     * 세그먼트 할인은 쿠폰으로 준다.
     *
     * <p>[JA] 付属クーポンを受け取れる顧客層。
     * 🔴 表示価格は分けない。理由は AudienceSegment のコメントにある — 要約すると、商品照会には身元がなく、一覧はキャッシュされ、
     * 画面ごとに価格が変わってしまうからだ。セグメント割引はクーポンで与える。
     */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AudienceSegment audience = AudienceSegment.ALL;

    /**
     * 딸린 쿠폰의 이름. <b>비어 있으면 이 기획전에는 쿠폰이 없다.</b>
     *
     * <p>이름이 곧 키다 — coupon 테이블의 {@code uk_coupon_name_user_id} 가 한 사람이 같은
     * 이름의 쿠폰을 두 번 갖지 못하게 막는다. 중복 수령 방어를 따로 만들지 않는 이유다.
     *
     * <p>[JA] 付属クーポンの名前。空ならこの企画展にはクーポンがない。
     * 名前がそのままキーである — coupon テーブルの uk_coupon_name_user_id が、一人が同じ名前のクーポンを二度持てないようにしている。
     * 重複受け取りの防御を別に作らない理由だ。
     */
    @Column(name = "coupon_name", length = 120)
    private String couponName;

    /** 쿠폰 할인율(%). */
    // [JA] クーポンの割引率(%)。
    @Column(name = "coupon_discount")
    private Integer couponDiscount;

    /**
     * 받은 날로부터 며칠간 쓸 수 있는가.
     *
     * <p>기획전 종료와 <b>별개</b>다. 기획전 마지막 날 받은 쿠폰이 그 자리에서 만료되면
     * 받을 이유가 없다.
     *
     * <p>[JA] 受け取った日から何日間使えるか。
     * 企画展の終了とは別である。企画展の最終日に受け取ったクーポンがその場で期限切れになるなら受け取る理由がない。
     */
    @Column(name = "coupon_valid_days")
    private Integer couponValidDays;

    /**
     * 이 시각에 시작해야 하는가.
     *
     * <p>{@link EventStatus#SCHEDULED} 인 것만 대상이다 — 초안은 시작 시각이 지났어도
     * 시작되지 않는다. 시작 시각이 없으면 관리자가 직접 시작시켜야 한다.
     *
     * <p>[JA] この時刻に開始すべきか。
     * EventStatus#SCHEDULED のものだけが対象 — 下書きは開始時刻を過ぎても開始されない。開始時刻がなければ管理者が自分で開始させる必要がある。
     */
    public boolean shouldStartAt(LocalDateTime now) {
        return status == EventStatus.SCHEDULED && startsAt != null && !now.isBefore(startsAt);
    }

    /**
     * 이 시각에 끝나야 하는가.
     *
     * <p>경계는 <b>종료 시각 포함</b>이다(배너와 같다). "23:59:59 까지"를 운영자가 직접
     * 계산하지 않아도 되도록, 자정을 넣으면 그 자정까지 진행된다.
     *
     * <p>[JA] この時刻に終了すべきか。
     * 境界は終了時刻を含む(バナーと同じ)。「23:59:59 まで」を運用者が自分で計算しなくて済むよう、深夜 0 時を入れればその 0 時まで進行する。
     */
    public boolean shouldEndAt(LocalDateTime now) {
        return status == EventStatus.ACTIVE && endsAt != null && now.isAfter(endsAt);
    }

    /** 대상 상품을 통째로 갈아 끼운다. 컬렉션을 새 리스트로 바꾸면 orphanRemoval 이 깨진다. */
    // [JA] 対象商品を丸ごと差し替える。コレクションを新しいリストに置き換えると orphanRemoval が壊れる。
    public void replaceProducts(List<EventProduct> next) {
        products.clear();
        if (next != null) {
            products.addAll(next);
        }
    }

    /** 대상 카테고리를 통째로 갈아 끼운다. */
    // [JA] 対象カテゴリを丸ごと差し替える。
    public void replaceCategories(List<EventCategory> next) {
        categories.clear();
        if (next != null) {
            categories.addAll(next);
        }
    }

    /**
     * 받아 갈 쿠폰이 있는가.
     *
     * <p>이름과 할인율이 <b>둘 다</b> 있어야 한다. 이름만 있고 할인율이 없으면 0% 쿠폰이
     * 발급되고, 사용자는 받았는데 아무 일도 일어나지 않는다.
     *
     * <p>[JA] 受け取れるクーポンがあるか。
     * 名前と割引率の両方が必要だ。名前だけで割引率がなければ 0% クーポンが発行され、ユーザーは受け取ったのに何も起きない。
     */
    public boolean offersCoupon() {
        return couponName != null && !couponName.isBlank()
                && couponDiscount != null && couponDiscount > 0;
    }
}
