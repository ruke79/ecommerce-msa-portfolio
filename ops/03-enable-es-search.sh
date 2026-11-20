#!/usr/bin/env bash
# 운영 액션 ③ — 검색 ES 전환 (docs/13 §3 절차의 실행형)
#
# 절차: 재색인 트리거 → alias 확인 → 문서 수 확인 → 애널라이저 확인 → 전환 안내
#
# ⚠️ 재색인은 **게이트웨이를 거쳐 관리자 토큰으로** 부른다.
#
# 🐛 2026-11-20: 이 스크립트는 catalog 에 직접 `POST /api/products/sync` 했다. 그 사이 catalog 에
#    `AdminWriteInterceptor` 가 생겨 모든 쓰기는 `User-Id` · `User-Role: ROLE_ADMIN` 헤더를 요구하고,
#    그 헤더는 `internal-auth.identity-mode=enforce`(k8s/10 의 기본값, 2026-10-26 부터) 아래서
#    **게이트웨이가 mTLS 로 보낸 것만** 신뢰된다 — 손으로 헤더를 붙여도 거절된다(그게 enforce 의 목적이다).
#    그래서 직접 호출은 401 로 끝나고, 스크립트는 그 뒤 단계(alias 대기)에서 5분을 기다리다 실패했다.
#    [JA] 再索引はゲートウェイ経由で管理者トークンで呼ぶ — サービスへの直接呼び出しは 401。
#
# 사용:
#   GATEWAY=https://shop.lab ADMIN_TOKEN=<관리자 액세스 토큰> ES_URL=http://localhost:9200 \
#   ./ops/03-enable-es-search.sh
#   INSECURE=1        # 자가서명 TLS 환경(-k). 운영에서는 CA 신뢰 권장
#   SYNC_URL=...      # 재색인 주소를 직접 지정(기본 $GATEWAY/catalog-service/api/products/sync)
#   ES_USER · ES_PASSWORD  # ES 에 인증이 걸린 환경
#   DRY_RUN=1         # 명령만 출력
# [JA] 運用アクション ③ — 検索の ES 切り替え(docs/13 §3 手順の実行版)
#
#      手順: 再インデックスのトリガー → alias 確認 → ドキュメント数確認 → フラグ切り替えの案内(または k8s 自動パッチ)
#
#      使い方:
#        CATALOG_URL=https://localhost:8081 ES_URL=https://localhost:9200 ES_USER=elastic ES_PASSWORD=... ./ops/03-enable-es-search.sh
#        INSECURE=1        # 自己署名 TLS 環境(-k)。運用では CA の信頼を推奨
#        RUN_K8S_PATCH=1   # 検証が通れば k8s ConfigMap に SEARCH_ENGINE=es を反映 + 再起動まで行う
#        DRY_RUN=1         # コマンドだけ出力
set -euo pipefail

GATEWAY="${GATEWAY:-}"
SYNC_URL="${SYNC_URL:-${GATEWAY:+$GATEWAY/catalog-service/api/products/sync}}"
ES_URL="${ES_URL:-https://localhost:9200}"
ALIAS="${ALIAS:-product-alias}"
TIMEOUT="${TIMEOUT:-1800}"   # 1,000만 상품이 약 3분 — 여유를 둔다 ／ [JA] 1,000 万商品で約 3 分 — 余裕を持たせる
DRY_RUN="${DRY_RUN:-0}"

die() { echo "🔴 $*" >&2; exit 2; }

[ -n "$SYNC_URL" ] || die "GATEWAY 를 지정한다(예: GATEWAY=https://shop.lab) — 또는 SYNC_URL 로 직접"
# 토큰이 없으면 어차피 401 이다. 5분을 기다린 뒤 실패하는 대신 지금 멈춘다.
# [JA] トークンがなければどのみち 401 である。5 分待ってから失敗する代わりに、今止める。
[ -n "${ADMIN_TOKEN:-}" ] || die "ADMIN_TOKEN 이 필요하다(관리자 계정의 액세스 토큰) — 재색인은 관리자 쓰기다"

CURL=(curl -sS --fail-with-body)
[ "${INSECURE:-0}" = "1" ] && CURL+=(-k)
ES_AUTH=()
[ -n "${ES_USER:-}" ] && ES_AUTH=(-u "$ES_USER:${ES_PASSWORD:?ES_USER 지정 시 ES_PASSWORD 필요}")

run() { if [ "$DRY_RUN" = "1" ]; then echo "[DRY_RUN] $*"; else "$@"; fi }

echo ">>> 1) 재색인 트리거 (게이트웨이 경유 · 관리자 토큰 — 202 로 바로 돌아오고 색인은 뒤에서 돈다)"
# 토큰은 Authorization 헤더로만 — 명령줄에 찍지 않는다(셸 기록 · ps). [JA] トークンはヘッダーだけ。
run "${CURL[@]}" -X POST -H "Authorization: Bearer $ADMIN_TOKEN" "$SYNC_URL"
echo

if [ "$DRY_RUN" != "1" ]; then
    echo ">>> 2) alias '$ALIAS' 대기 (최대 ${TIMEOUT}s) — 30초마다 색인 중인 문서 수를 함께 적는다"
    # 🐛 2026-11-20: 예전에는 점(.)만 찍었다. 실습 1,000만 상품에서 기본 300초로는 끝나지 않아
    #    "alias 미생성 — 배치 잡 로그를 확인하세요" 로 끝났는데, 그 메시지로는 **느린 것과 죽은 것을
    #    구분할 수 없다**(그때 색인은 초당 5.7만 건으로 멀쩡히 돌고 있었다). 이제 진행 중인 인덱스의
    #    문서 수를 적고, 시간이 다 돼도 그 수가 늘고 있었으면 "느린 것" 이라고 말해 준다.
    #    기본 대기도 30분으로 — 1,000만 건이 약 3분, 여유를 둔다.
    # [JA] 以前は点だけだった — 遅いのか死んだのか区別できない。索引中の件数を出し、増えていれば遅いだけと言う。
    docs_now() {   # 색인 중인 products-*-products 의 문서 수(없으면 빈 값) ／ [JA] 索引中の products-*-products の文書数(なければ空)
        "${CURL[@]}" "${ES_AUTH[@]}" "$ES_URL/_cat/indices/products-*-products?h=docs.count" 2>/dev/null \
            | tr -d ' ' | tr '\n' '+' | sed 's/+$//'
    }
    deadline=$(( $(date +%s) + TIMEOUT ))
    first_docs="$(docs_now)"; last_docs="$first_docs"; ticks=0
    until "${CURL[@]}" "${ES_AUTH[@]}" "$ES_URL/_alias/$ALIAS" >/dev/null 2>&1; do
        if [ "$(date +%s)" -ge "$deadline" ]; then
            echo
            last_docs="$(docs_now)"
            if [ -n "$last_docs" ] && [ "$last_docs" != "$first_docs" ]; then
                echo "ERROR: alias 가 아직 없지만 색인은 **돌고 있다**(문서 ${first_docs} → ${last_docs})."
                echo "       느린 것이다 — TIMEOUT 을 늘려(예: TIMEOUT=3600) 다시 기다리거나,"
                echo "       alias 가 생길 때까지 두고 보세요. 지금 sync 를 다시 부르면 409(중복 실행 방지)입니다."
            else
                echo "ERROR: alias 미생성 · 문서 수도 늘지 않습니다(${last_docs:-없음}) — 배치 잡이 죽었습니다."
                echo "       kubectl -n ecommerce logs deploy/catalog-service --tail=-1 | grep -v otel-collector | tail -40"
            fi
            exit 1
        fi
        ticks=$(( ticks + 1 ))
        if [ $(( ticks % 6 )) = 0 ]; then
            last_docs="$(docs_now)"
            printf ' [문서 %s] ' "${last_docs:-0}"
        else
            printf '.'
        fi
        sleep 5
    done
    echo " OK"
    echo ">>> alias 가 가리키는 인덱스:"
    "${CURL[@]}" "${ES_AUTH[@]}" "$ES_URL/_alias/$ALIAS"

    echo ">>> 3) 문서 수 확인:"
    "${CURL[@]}" "${ES_AUTH[@]}" "$ES_URL/$ALIAS/_count"
    echo
    echo ">>> 4) 애널라이저 샘플 검증(nori):"
    "${CURL[@]}" "${ES_AUTH[@]}" -H 'Content-Type: application/json' \
        "$ES_URL/$ALIAS/_analyze" -d '{"analyzer":"korean_analyzer","text":"운동화"}' || \
        echo "WARN: korean_analyzer 실패 — ES 에 nori 플러그인이 설치되어 있는지 확인 — kubectl -n elasticsearch logs deploy/elasticsearch -c install-analysis-plugins (k8s/90, docs/19 §5.2)"
fi

echo
# 🐛 2026-11-20: 예전에는 여기서 `kubectl patch configmap` 을 돌렸다(RUN_K8S_PATCH=1). 그건 이 저장소의 규칙이
#    금지하는 것이다 — ConfigMap 은 저장소가 원본이고 `ops/28 --config-only` 로만 올린다. patch 로 고치면
#    다음 배포가 저장소 값으로 조용히 되돌리고, 그 사이 무엇이 달랐는지 아무 데도 남지 않는다(ops/27 이 사후에 찾는다).
#    그래서 자동 패치를 없애고, 저장소를 고치는 두 단계를 안내만 한다.
# [JA] 以前はここで kubectl patch をしていた — ConfigMap はリポジトリが原本で ops/28 --config-only だけで上げる。
cat <<'GUIDE'
>>> 5) 전환은 두 단계다(저장소를 고치고 ops/28 로 올린다 — kubectl patch 금지):

  ① ES 를 쓰는 배포로 만든다 — SEARCH_ENGINE=es
     k8s/10-configmap.yaml(또는 그 환경의 오버레이 patch-*-config.yaml)의 SEARCH_ENGINE 을 "es" 로
     ./ops/28-deploy.sh <환경> --config-only --restart catalog-service
     (이 값은 "ES 빈 · 색인이 있다" 만 정한다 — 이것만으로는 검색이 ES 로 가지 않는다)

  ② 검색을 ES 로 보내는 비율을 올린다 — 기능 플래그 catalog.search-engine(docs/74)
     k8s/15-feature-flags.yaml 의 fractional 을 [["es", 10], ["postgres", 90]] 처럼
     ./ops/28-deploy.sh <환경> --config-only        # 재기동 없이 1분 안팎에 반영된다

  되돌리기: ② 의 비율을 0 으로(가장 빠르다 · 재기동 없음) → 그다음 ① 을 postgres 로.
  ES 가 죽어도 EsProductSearchService 가 경고를 찍고 LIKE 경로로 폴백한다 — 비율을 0 으로 두면 더 확실하다.
GUIDE
