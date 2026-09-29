#!/usr/bin/env bash
# 운영 액션 ③ — 검색 ES 전환 (docs/13 §3 절차의 실행형)
#
# 절차: 재색인 트리거 → alias 확인 → 문서 수 확인 → 플래그 전환 안내(또는 k8s 자동 패치)
#
# 사용:
#   CATALOG_URL=https://localhost:8081 ES_URL=https://localhost:9200 \
#   ES_USER=elastic ES_PASSWORD=... ./ops/03-enable-es-search.sh
#   INSECURE=1        # 자가서명 TLS 환경(-k). 운영에서는 CA 신뢰 권장
#   RUN_K8S_PATCH=1   # 검증 통과 시 k8s ConfigMap에 SEARCH_ENGINE=es 반영 + 재기동까지 수행
#   DRY_RUN=1         # 명령만 출력
set -euo pipefail

CATALOG_URL="${CATALOG_URL:-https://localhost:8081}"
ES_URL="${ES_URL:-https://localhost:9200}"
ALIAS="${ALIAS:-product-alias}"
TIMEOUT="${TIMEOUT:-300}"
DRY_RUN="${DRY_RUN:-0}"

CURL=(curl -sS --fail-with-body)
[ "${INSECURE:-0}" = "1" ] && CURL+=(-k)
ES_AUTH=()
[ -n "${ES_USER:-}" ] && ES_AUTH=(-u "$ES_USER:${ES_PASSWORD:?ES_USER 지정 시 ES_PASSWORD 필요}")

run() { if [ "$DRY_RUN" = "1" ]; then echo "[DRY_RUN] $*"; else "$@"; fi }

echo ">>> 1) 재색인 트리거 (이번 수정으로 매핑 적용 + alias 롤오버가 실제 동작함)"
run "${CURL[@]}" -X POST "$CATALOG_URL/api/products/sync"
echo

if [ "$DRY_RUN" != "1" ]; then
    echo ">>> 2) alias '$ALIAS' 대기 (최대 ${TIMEOUT}s)"
    deadline=$(( $(date +%s) + TIMEOUT ))
    until "${CURL[@]}" "${ES_AUTH[@]}" "$ES_URL/_alias/$ALIAS" >/dev/null 2>&1; do
        [ "$(date +%s)" -ge "$deadline" ] && { echo "ERROR: alias 미생성 — 배치 잡 로그를 확인하세요"; exit 1; }
        printf '.'; sleep 5
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
        echo "WARN: korean_analyzer 실패 — ES 에 nori 플러그인이 설치되어 있는지 확인 (bin/elasticsearch-plugin install analysis-nori)"
fi

echo
if [ "${RUN_K8S_PATCH:-0}" = "1" ]; then
    echo ">>> 5) k8s 플래그 전환: SEARCH_ENGINE=es"
    run kubectl -n ecommerce patch configmap ecommerce-config --type merge -p '{"data":{"SEARCH_ENGINE":"es"}}'
    run kubectl -n ecommerce rollout restart deploy/catalog-service
    echo ">>> 롤백: SEARCH_ENGINE 을 'postgres' 로 되돌리고 재기동하면 즉시 원복"
else
    cat <<'GUIDE'
>>> 5) 플래그 전환 (배포 방식에 맞게 택1):
  - compose/직접 실행: 환경변수 SEARCH_ENGINE=es 로 catalog-service 재기동
  - k8s:  RUN_K8S_PATCH=1 로 이 스크립트 재실행, 또는
          kubectl -n ecommerce patch configmap ecommerce-config --type merge -p '{"data":{"SEARCH_ENGINE":"es"}}'
          kubectl -n ecommerce rollout restart deploy/catalog-service
  롤백은 값만 postgres 로 — 코드 변경 불필요 (ES 장애 시에도 자동 LIKE 폴백이 동작함)
GUIDE
fi
