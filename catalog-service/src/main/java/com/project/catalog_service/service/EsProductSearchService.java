package com.project.catalog_service.service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.FetchSourceFilter;
import org.springframework.stereotype.Service;

import com.project.catalog_service.document.ProductDocument;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 상품 텍스트 검색의 Elasticsearch 경로 (docs/07 R-C1).
 *
 * search.engine=es 일 때만 빈이 생성된다(기본은 postgres — 기존 LIKE 경로 유지).
 * 패턴: "ES 로 관련도 검색 → productId 목록 → Postgres 에서 상세/파셋 하이드레이션".
 * SearchService 가 이 목록을 IN 조건으로 사용하므로 응답 형태(SearchResultDto)는 변하지 않고,
 * 선행 와일드카드 LIKE 풀스캔(docs/06 CAT-1)만 관련도 기반 전문 검색으로 대체된다.
 *
 * 대상 인덱스는 배치가 롤오버하는 alias(product-alias)이며, 필드는 ProductDocument 의
 * 한/영/일 멀티필드(nori·standard·kuromoji — 배치의 명시적 매핑 생성으로 실제 적용됨)를 사용한다.
 *
 * <p>[JA] 商品テキスト検索の Elasticsearch 経路(docs/07 R-C1)。
 * search.engine=es のときだけ Bean が作られる(デフォルトは postgres — 既存の LIKE 経路を維持)。
 * パターン: 「ES で関連度検索 → productId 一覧 → Postgres で詳細/ファセットのハイドレーション」。
 * SearchService がこの一覧を IN 条件に使うのでレスポンスの形(SearchResultDto)は変わらず、前方ワイルドカード LIKE のフルスキャン(docs/06 CAT-1)だけが
 * 関連度ベースの全文検索に置き換わる。
 * 対象インデックスはバッチがロールオーバーするエイリアス(product-alias)で、フィールドは ProductDocument の
 * 韓/英/日マルチフィールド(nori・standard・kuromoji — バッチの明示的なマッピング生成で実際に適用される)を使う。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "search.engine", havingValue = "es")
@RequiredArgsConstructor
public class EsProductSearchService {

    private final ElasticsearchOperations elasticsearchOperations;

    /**
     * ES 이름 검색 한 번의 시간 — {@code catalog_search_es_seconds}(outcome = success · error). 부하 중 ES 가 느려지는지를 이것으로 본다.
     * 🐛 2026-11-24: 부하 중 name_search p99 3.2초의 몫을 ES · SQL · 격벽 대기로 나눌 숫자가 없었다.
     * [JA] ES 名前検索一回の時間 — 負荷中に ES が遅くなるかをこれで見る。
     */
    private final MeterRegistry meterRegistry;

    @Value("${elasticsearch.alias.name.product:product-alias}")
    private String productAliasName;

    /**
     * 검색어에 매칭되는 productId 목록을 관련도 순으로 반환한다.
     * 실패 시 예외를 그대로 던진다 — 폴백(기존 LIKE 경로) 판단은 호출자(SearchService)가 한다.
     *
     * <p>[JA] 検索語にマッチする productId 一覧を関連度順に返す。
     * 失敗時は例外をそのまま投げる — フォールバック(既存の LIKE 経路)の判断は呼び出し元(SearchService)が行う。
     */
    public List<Long> searchProductIds(String term, int limit) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            List<Long> ids = query(term, limit);
            outcome = "success";
            return ids;
        } finally {
            sample.stop(Timer.builder("catalog.search.es")
                    .description("ES 이름 검색 한 번(후보 productId) — 격벽 · 트랜잭션 밖")
                    .tag("outcome", outcome)
                    .register(meterRegistry));
        }
    }

    private List<Long> query(String term, int limit) {
        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.multiMatch(m -> m
                        .query(term)
                        .fields("name", "name.english", "name.japanese",
                                "brand", "brand.english", "brand.japanese",
                                "description", "description.english", "description.japanese")))
                .withPageable(PageRequest.of(0, limit))
                // 🐛 2026-11-23 실습(상품 1,000만): 쓰는 것은 productId 하나인데 문서 전체(SKU · 사이즈 · 하위 분류 중첩까지)를
                //    받아 1,000개를 ProductDocument 로 바꿨다 — ES 는 33ms(750KB)인데 캐시에 없는 이름 검색이 부하 없이 0.5초,
                //    browse 천장이 첫 계단(p99 422ms)에서 멈췄다. productId 만 받고(113KB), 전체 건수도 세지 않는다(쓰지 않는다).
                // [JA] 使うのは productId だけなのに文書全体(ネスト含む)を 1,000 件受け取り変換していた — ES は 33ms でも
                //      キャッシュにない名前検索が無負荷で 0.5 秒。productId だけを受け取り、総件数も数えない。
                .withSourceFilter(new FetchSourceFilter(true, new String[] { "productId" }, null))
                .withTrackTotalHits(false)
                .build();

        SearchHits<ProductDocument> hits = elasticsearchOperations.search(
                query, ProductDocument.class, IndexCoordinates.of(productAliasName));

        return hits.getSearchHits().stream()
                .map(hit -> hit.getContent().getProductId())
                .filter(Objects::nonNull)
                .map(Long::valueOf)
                .collect(Collectors.toList());
    }
}
