package com.project.catalog_service.service;

import java.util.Locale;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.project.common.dto.catalog.request.SearchParamsRequest;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ImmutableContext;

/**
 * 이 검색을 어느 엔진으로 할지 — 기능 플래그 {@value #FLAG}(docs/74).
 *
 * <p>예전에는 {@code search.engine} 하나가 기동할 때 전부를 정했다. 바꾸려면 ConfigMap 을 고치고 파드를 다시 띄워야
 * 했고, 바꾸는 순간 <b>모든</b> 검색이 한꺼번에 넘어갔다. 이제 둘을 나눈다:
 * <ul>
 *   <li>{@code search.engine=es} — <b>ES 가 이 배포에 있는가</b>(빈 · 색인). 기동 설정 그대로</li>
 *   <li>플래그 {@value #FLAG} — <b>있다면 이 검색에 쓰는가</b>. 실행 중에 비율로 바꾼다</li>
 * </ul>
 *
 * <p><b>무엇을 기준으로 나누는가 — 검색어.</b> 검색은 로그인 없이 쓰는 경로라 게이트웨이가 {@code User-Id} 를 넣지
 * 않는다(공개 라우트). 요청마다 무작위로 나누면 <b>같은 검색의 1쪽은 ES, 2쪽은 LIKE</b> 로 갈려 목록이 어긋난다.
 * 정규화한 검색어로 나누면 같은 검색어는 언제나 같은 엔진이다 — 무한 스크롤이 어긋나지 않고, 캐시도 그대로 맞다.
 * 대가: "사용자의 10%" 가 아니라 "검색어의 10%" 다. 자주 쓰는 검색어 하나가 어느 쪽에 들어가느냐에 따라 실제
 * 트래픽 비율은 10% 와 다를 수 있다 — 실제 비율은 지표 {@code feature_flag.evaluations} 로 본다.
 *
 * <p>코드의 기본값(플래그 파일이 없거나 틀렸을 때)은 <b>플래그 이전의 동작</b>이다: ES 가 있으면 ES.
 *
 * <p>[JA] この検索をどのエンジンで行うか — 機能フラグ {@value #FLAG}(docs/74)。{@code search.engine=es} は
 * <b>ES がこのデプロイにあるか</b>(起動設定)、フラグは<b>あるならこの検索に使うか</b>(実行中に比率で変える)。
 * <b>分ける基準は検索語である。</b> 検索はログインなしで使う経路なので、ゲートウェイが {@code User-Id} を入れない。
 * リクエストごとに無作為に分けると<b>同じ検索の 1 ページ目は ES、2 ページ目は LIKE</b> に分かれて一覧がずれる。
 * 正規化した検索語で分ければ、同じ検索語は常に同じエンジンになる。代償: 「ユーザーの 10%」ではなく「検索語の 10%」
 * である。コードのデフォルト値は<b>フラグ導入前の動作</b>である: ES があれば ES。
 */
@Component
public class SearchEngineSelector {

    public static final String FLAG = "catalog.search-engine";

    private final Client flags;
    private final boolean esDeployed;

    @Autowired
    public SearchEngineSelector(Client flags, ObjectProvider<EsProductSearchService> es) {
        this(flags, es.getIfAvailable() != null);
    }

    SearchEngineSelector(Client flags, boolean esDeployed) {
        this.flags = flags;
        this.esDeployed = esDeployed;
    }

    public SearchEngine select(SearchParamsRequest params) {
        String term = normalize(params == null ? null : params.getSearch());
        if (term == null) {
            // 검색어가 없으면 엔진이 결과에 영향을 주지 않는다 — 평가하지 않고, 캐시 키도 하나로 모은다.
            return SearchEngine.POSTGRES;
        }
        if (!esDeployed) {
            // ES 가 없는 배포(OCI). 플래그가 뭐라 하든 갈 곳이 없다 — 평가해서 "es" 로 기록되면 추적이 거짓말을 한다.
            return SearchEngine.POSTGRES;
        }
        String variant = flags.getStringValue(FLAG, "es", new ImmutableContext(term));
        return "es".equals(variant) ? SearchEngine.ES : SearchEngine.POSTGRES;
    }

    /** 대소문자 · 앞뒤 공백 · 연속 공백만 맞춘다. "Nike" 와 " nike " 는 같은 검색이다. */
    static String normalize(String search) {
        if (search == null) {
            return null;
        }
        String t = search.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }
}
