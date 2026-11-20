package com.project.catalog_service.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.project.catalog_service.dto.HomeDtos.HomeView;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 첫 화면 구성(섹션 · 섹션의 상품 목록)을 파드 메모리에 잠깐 둔다 — 재고 표시는 여기 들지 않는다(ContentController 가 응답마다).
 *
 * <h3>왜</h3>
 *
 * <p>2026-10-18 부하 측정(docs/83 §5): browse 에서 catalog 가 쓴 시간의 <b>65%</b> 가 {@code /api/content/home} 이었다 —
 * 요청당 27ms(요청 ~125/초), 상세 3ms · 검색 5ms 의 9배, 요청 ~500/초에서 105ms. 홈 한 번이 섹션 조회 1번 + 섹션마다 카테고리
 * id 조회 1 ~ 2번(DB) + 섹션마다 상품 목록(Redis 에서 받아 역직렬화)이고, 그 동안 읽기 트랜잭션이 DB 커넥션을 쥔다. 그런데 이
 * 구성은 손님마다 같고 관리자가 바꿀 때만 바뀐다.
 *
 * <h3>어떻게</h3>
 *
 * <ul>
 * <li>값 하나(홈은 인자가 없다)를 {@code content.home.cache-ttl}(기본 30초) 동안 쓴다. {@code 0} 이면 끈다(매번 만든다).
 * <li>트랜잭션 밖이다 — 맞으면 DB 커넥션을 잡지 않는다({@link HomeSectionService} 는 클래스 전체가 읽기 트랜잭션).
 * <li>만료되면 한 스레드만 다시 만들고 나머지는 그 결과를 기다린다 — 만료 순간 동시 요청이 모두 DB 로 가지 않게.
 * <li>만들다 실패하면 담지 않는다 — 다음 요청이 다시 만든다.
 * <li>관리자가 섹션을 바꾸면 그 요청을 받은 파드는 바로 비운다. 다른 파드 · 상품 정보(이름 · 가격 · 이미지)의 변경은 길어야 TTL
 *     뒤에 보인다 — 주문 금액은 주문할 때 서버가 catalog 의 지금 값으로 정하므로 카드의 가격이 30초 늦어도 청구 금액은 틀리지 않는다.
 * </ul>
 *
 * <p>담긴 값은 여러 요청이 함께 읽는다 — 고치지 않는다. 재고 반영은 {@code StockAvailability.Snapshot} 이 복사본을 만든다.
 * 지표 {@code catalog_home_cache_total{result="hit|miss|off"}}.
 *
 * <p>[JA] トップ画面の構成(セクション · セクションの商品一覧)を Pod のメモリに短く置く — 在庫表示はここに入らない(ContentController が応答ごとに)。
 *
 * <p>なぜ: 2026-10-18 負荷計測(docs/83 §5)で、browse において catalog が使った時間の <b>65%</b> が {@code /api/content/home} だった —
 * リクエストあたり 27ms(リクエスト ~125/秒)、詳細 3ms · 検索 5ms の 9 倍、リクエスト ~500/秒で 105ms。ホーム一回がセクション照会 1 回 +
 * セクションごとのカテゴリ id 照会 1 〜 2 回(DB) + セクションごとの商品一覧(Redis から受けて逆シリアル化)で、その間読み取りトランザクションが
 * DB コネクションを握る。しかしこの構成は客ごとに同じで、管理者が変えるときだけ変わる。
 *
 * <p>どうやって:
 * <ul>
 * <li>値一つ(ホームは引数がない)を {@code content.home.cache-ttl}(既定 30 秒)の間使う。{@code 0} なら無効(毎回作る)。
 * <li>トランザクションの外 — ヒットなら DB コネクションを取らない({@link HomeSectionService} はクラス全体が読み取りトランザクション)。
 * <li>期限切れになると一つのスレッドだけが作り直し、残りはその結果を待つ — 期限切れの瞬間の同時リクエストがすべて DB へ行かないように。
 * <li>作る途中で失敗したら入れない — 次のリクエストが作り直す。
 * <li>管理者がセクションを変えると、そのリクエストを受けた Pod はすぐ空にする。他の Pod · 商品情報(名前 · 価格 · 画像)の変更は長くても TTL
 *     後に見える — 注文金額は注文時にサーバーが catalog の現在値で決めるので、カードの価格が 30 秒遅れても請求金額は間違わない。
 * </ul>
 *
 * <p>入った値は複数のリクエストが一緒に読む — 変更しない。在庫反映は {@code StockAvailability.Snapshot} がコピーを作る。
 * 指標 {@code catalog_home_cache_total{result="hit|miss|off"}}。
 */
@Component
public class HomeViewCache {

    private record Entry(HomeView view, Instant expiresAt) {
    }

    private final HomeSectionService sections;
    private final Duration ttl;
    private final Clock clock;
    private final Counter hit;
    private final Counter miss;
    private final Counter off;
    private final Object rebuild = new Object();
    private volatile Entry entry;

    @Autowired
    public HomeViewCache(HomeSectionService sections,
            @Value("${content.home.cache-ttl:PT30S}") Duration ttl, MeterRegistry registry) {
        this(sections, ttl, registry, Clock.systemUTC());
    }

    HomeViewCache(HomeSectionService sections, Duration ttl, MeterRegistry registry, Clock clock) {
        if (ttl == null || ttl.isNegative()) {
            throw new IllegalArgumentException("content.home.cache-ttl 은 0 이상이어야 한다: " + ttl);
        }
        this.sections = sections;
        this.ttl = ttl;
        this.clock = clock;
        this.hit = counter(registry, "hit");
        this.miss = counter(registry, "miss");
        this.off = counter(registry, "off");
    }

    private static Counter counter(MeterRegistry registry, String result) {
        return Counter.builder("catalog.home.cache").description("첫 화면 구성 캐시 — hit · miss(다시 만듦) · off(TTL 0)")
                .tag("result", result).register(registry);
    }

    /** 첫 화면 구성 — 재고는 반영되지 않은 값(호출한 쪽이 반영한다). */
    // [JA] 最初の画面の構成 — 在庫は反映されていない値(呼んだ側が反映する)。
    public HomeView get() {
        if (ttl.isZero()) {
            off.increment();
            return sections.home();
        }
        Entry e = entry;
        if (fresh(e)) {
            hit.increment();
            return e.view();
        }
        synchronized (rebuild) {
            e = entry;
            if (fresh(e)) {   // 기다리는 동안 다른 스레드가 만들었다 ／ [JA] 待っている間に他のスレッドが作った
                hit.increment();
                return e.view();
            }
            miss.increment();
            HomeView view = sections.home();   // 실패하면 예외가 그대로 나간다 — 담지 않는다 ／ [JA] 失敗したら例外がそのまま出る — キャッシュに入れない
            entry = new Entry(view, clock.instant().plus(ttl));
            return view;
        }
    }

    /** 이 파드의 값을 비운다 — 관리자가 섹션을 바꾼 뒤(커밋 뒤). 다른 파드는 TTL 로 맞춰진다. */
    // [JA] この Pod の値を空にする — 管理者がセクションを変えた後(コミット後)。他の Pod は TTL で揃う。
    public void invalidate() {
        entry = null;
    }

    private boolean fresh(Entry e) {
        return e != null && clock.instant().isBefore(e.expiresAt());
    }
}
