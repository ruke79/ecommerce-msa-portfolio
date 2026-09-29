#!/usr/bin/env bash
# 운영 액션 ㊷ — 부모 없이 저장된 사이즈(관리자 등록 버그) 찾기 · 짝 추정 · 다시 연결
#
# ── 무슨 버그였나 (docs/65 §5) ──────────────────────────────────────────
#
# 관리자 화면으로 상품(또는 색상)을 등록하면 사이즈가 부모(스타일 = product_sku) 없이 저장됐다 —
# product_size.skuproduct_id = NULL. 그 상품은 사이즈가 하나도 없는 상품이 되어 상세 화면에 가격이 없고
# 장바구니에 담을 수 없다. 코드는 고쳤지만(ProductService.sizesOf) 이미 저장된 행은 스스로 돌아오지 않는다.
#
# ── 어떻게 짝을 찾나 ────────────────────────────────────────────────────
#
# DB 에는 "이 사이즈가 어느 스타일의 것" 이라는 정보가 없다. 대신 **시각**이 남는다. 버그가 있던 등록 흐름은
#   스타일 엔티티 생성(createdAt) → 이미지 업로드(수 초) → 사이즈 엔티티 생성(createdAt)
# 순서라, 사이즈의 createdAt 은 자기 스타일의 createdAt **직후**다. 그리고 그 스타일은 사이즈가 하나도 없다
# (버그로 모두 떨어져 나갔으므로). 그래서 후보는:
#
#   "사이즈가 하나도 없는 스타일" 중, 사이즈보다 먼저 만들어졌고 WINDOW(기본 10분) 안에서 가장 가까운 것
#
# 이것은 **추정**이다. 두 관리자가 몇 초 간격으로 등록했으면 틀릴 수 있다. 그래서 이 스크립트는 기본으로
# 아무것도 고치지 않는다 — 미리보기로 후보와 확신도를 보여 주고, 사람이 확인한 짝만 apply 로 반영한다.
#
# ── 사용 ────────────────────────────────────────────────────────────────
#
#   ./ops/42-repair-orphan-sizes.sh status                     # 고아 사이즈 · 빈 스타일 수 (기본)
#   ./ops/42-repair-orphan-sizes.sh preview                    # 짝 추정표 — 고치지 않는다
#   ./ops/42-repair-orphan-sizes.sh apply 901:77 902:77 903:78 # <size_id>:<skuproduct_id> — 확인한 짝만
#
#   PG_NS=postgres PG_DEPLOY=postgres PG_USER=product PG_DB=productdb WINDOW='10 minutes'   # 기본값
#   PSQL='docker exec -i pg psql -U product -d productdb'   # 파드 대신 다른 psql 을 쓸 때(리허설 · 로컬)
#
# ── apply 뒤에 할 일 ────────────────────────────────────────────────────
#
#   1. 관리자 화면 › 상품 › 그 상품 › "저장" 한 번 — 상품 캐시(products · product · search_cache …)를 비우고
#      검색 색인을 다시 쓴다(ProductService.updateProduct 의 @CacheEvict + productIndexService.index).
#      이걸 안 하면 상세 화면은 캐시된 "사이즈 없음" 을, 검색은 가격 없는 상품을 계속 보여 준다.
#   2. 재고 수량은 등록 때 넣은 값 그대로다. 관리자 › 재고에서 확인한다.
#
# ⚠️ 먼저 백업 — 이 스크립트는 product_size 의 두 열(skuproduct_id · sku_code)만 UPDATE 한다. 그 표 하나면 충분하다.
#    (ops/08 은 클러스터 전체의 물리 백업 · PITR 용이라 여기에는 과하다.)
#
#      kubectl -n postgres exec deploy/postgres -- \
#          pg_dump -U product -d productdb -t product.product_size -Fc > product_size-$(date +%Y%m%d-%H%M).dump
#
#    되돌리기는 대개 덤프까지 갈 필요가 없다 — apply 한 size_id 들을 다시 NULL 로:
#      UPDATE product.product_size SET skuproduct_id = NULL, sku_code = NULL WHERE size_id IN (901, 902);
set -uo pipefail

ACTION="${1:-status}"
shift || true

PG_NS="${PG_NS:-postgres}"
PG_DEPLOY="${PG_DEPLOY:-postgres}"
PG_USER="${PG_USER:-product}"
PG_DB="${PG_DB:-productdb}"
WINDOW="${WINDOW:-10 minutes}"

die()  { echo "ERROR: $1" >&2; exit 1; }

# psql 을 파드 안에서 돌린다. SQL 은 stdin 으로, 값은 -v 로 — 문자열에 이어 붙이지 않는다(ops/30 과 같은 이유).
psql_in() {
    if [ -n "${PSQL:-}" ]; then
        # shellcheck disable=SC2086
        $PSQL -v ON_ERROR_STOP=1 -v "window=$WINDOW" "$@"
    else
        command -v kubectl >/dev/null || die "kubectl 이 필요합니다(또는 PSQL 을 지정)"
        kubectl -n "$PG_NS" exec -i "deploy/$PG_DEPLOY" -- \
            psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 -v "window=$WINDOW" "$@"
    fi
}

status() {
    echo "════ 부모 없는 사이즈 ($PG_DB) ════"
    psql_in <<'SQL'
SELECT (SELECT count(*) FROM product.product_size WHERE skuproduct_id IS NULL) AS "고아 사이즈",
       (SELECT count(*) FROM product.product_sku k
         WHERE NOT EXISTS (SELECT 1 FROM product.product_size s WHERE s.skuproduct_id = k.skuproduct_id))
                                                                             AS "사이즈 없는 스타일";
SQL
    echo "  고아 사이즈가 0 이면 할 일이 없습니다. 아니면:  $0 preview"
}

# 확신도
#   HIGH      — 창 안의 후보가 하나뿐이고, 그 후보 스타일에서 이 사이즈가 첫 사이즈와 1분 안
#   CHECK     — 창 안에 후보가 둘 이상(가까운 쪽을 골랐다) — 관리자 화면의 등록 내용과 대조할 것
#   NONE      — 창 안에 후보가 없다 — 자동으로 짝을 지을 수 없다(다시 등록 권장)
preview() {
    echo "════ 짝 추정 (창: $WINDOW) — 아무것도 고치지 않습니다 ════"
    psql_in <<'SQL'
WITH orphan AS (
    SELECT s.size_id, s.size, s.quantity, s.price, s.createdat
      FROM product.product_size s
     WHERE s.skuproduct_id IS NULL
), empty_style AS (
    SELECT k.skuproduct_id, k.sku, k.product_id, k.createdat
      FROM product.product_sku k
     WHERE NOT EXISTS (SELECT 1 FROM product.product_size s WHERE s.skuproduct_id = k.skuproduct_id)
), candidates AS (
    SELECT o.size_id, e.skuproduct_id, e.sku, e.product_id, o.createdat - e.createdat AS gap,
           row_number() OVER (PARTITION BY o.size_id ORDER BY o.createdat - e.createdat) AS rn,
           count(*)     OVER (PARTITION BY o.size_id) AS n
      FROM orphan o
      JOIN empty_style e
        ON e.createdat <= o.createdat
       AND o.createdat - e.createdat <= CAST(:'window' AS interval)
)
SELECT o.size_id,
       coalesce(nullif(o.size, ''), '(없음)')              AS size,
       o.quantity,
       o.price,
       c.skuproduct_id                                      AS "후보 스타일",
       c.sku                                                AS "스타일 코드",
       p.name                                               AS "상품",
       date_trunc('second', c.gap)                          AS "간격",
       CASE WHEN c.skuproduct_id IS NULL THEN 'NONE'
            WHEN c.n = 1 AND c.gap <= interval '1 minute' THEN 'HIGH'
            ELSE 'CHECK' END                                AS "확신도",
       coalesce(c.size_id || ':' || c.skuproduct_id, '-')   AS "apply 인자"
  FROM orphan o
  LEFT JOIN candidates c ON c.size_id = o.size_id AND c.rn = 1
  LEFT JOIN product.product p ON p.product_id = c.product_id
 ORDER BY o.size_id;
SQL
    cat <<'EOF'
  · HIGH 는 대개 맞습니다. CHECK 는 관리자 화면에서 그 상품을 열어 사이즈 · 가격이 등록 때 넣은 것과 맞는지 보고 고르세요.
  · NONE 은 짝을 지을 근거가 없습니다 — 그 상품을 다시 등록하고, 남은 고아 행은 그대로 두어도 화면에 나오지 않습니다.
  · 확인한 짝만:  ./ops/42-repair-orphan-sizes.sh apply <size_id>:<skuproduct_id> ...
EOF
}

apply() {
    [ "$#" -gt 0 ] || die "짝을 지정하세요:  $0 apply <size_id>:<skuproduct_id> ..."
    local values=""
    for pair in "$@"; do
        [[ "$pair" =~ ^([0-9]+):([0-9]+)$ ]] || die "형식이 틀렸습니다: '$pair' (예: 901:77)"
        values+="${values:+,}(${BASH_REMATCH[1]},${BASH_REMATCH[2]})"
    done
    echo "════ 다시 연결 — $# 줄 (한 트랜잭션) ════"
    # 숫자만 통과한 값이라 VALUES 에 그대로 넣는다(정규식이 주입을 막는다).
    # 한 줄이라도 조건에 맞지 않으면 전부 되돌린다 — 반쯤 고친 상태를 남기지 않는다.
    psql_in <<SQL
BEGIN;
CREATE TEMP TABLE repair_pairs(size_id bigint PRIMARY KEY, skuproduct_id bigint NOT NULL) ON COMMIT DROP;
INSERT INTO repair_pairs VALUES ${values};

DO \$\$
DECLARE bad text;
BEGIN
    SELECT string_agg(r.size_id::text, ', ') INTO bad
      FROM repair_pairs r
      LEFT JOIN product.product_size s ON s.size_id = r.size_id
     WHERE s.size_id IS NULL OR s.skuproduct_id IS NOT NULL;
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION '고아가 아니거나 없는 사이즈: % — 아무것도 바꾸지 않았습니다', bad;
    END IF;
    SELECT string_agg(r.skuproduct_id::text, ', ') INTO bad
      FROM repair_pairs r
      LEFT JOIN product.product_sku k ON k.skuproduct_id = r.skuproduct_id
     WHERE k.skuproduct_id IS NULL;
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION '없는 스타일: % — 아무것도 바꾸지 않았습니다', bad;
    END IF;
END \$\$;

UPDATE product.product_size s
   SET skuproduct_id = r.skuproduct_id
  FROM repair_pairs r
 WHERE s.size_id = r.size_id AND s.skuproduct_id IS NULL;

-- 사이즈 SKU 코드(V17). 함수가 없으면(V17 이전) 건너뛴다 — 배포 뒤 마이그레이션이 채운다.
DO \$\$
BEGIN
    IF to_regprocedure('product.assign_size_sku_codes()') IS NOT NULL THEN
        PERFORM product.assign_size_sku_codes();
    END IF;
END \$\$;

SELECT s.size_id, s.size, s.skuproduct_id, k.sku AS "스타일 코드", s.sku_code AS "SKU"
  FROM product.product_size s
  JOIN repair_pairs r ON r.size_id = s.size_id
  JOIN product.product_sku k ON k.skuproduct_id = s.skuproduct_id
 ORDER BY s.size_id;
COMMIT;
SQL
    local rc=$?
    [ "$rc" -eq 0 ] || die "되돌렸습니다(위 오류 참고)"
    cat <<'EOF'
  ✅ 연결했습니다. 이제 관리자 화면 › 상품 › 그 상품 › "저장" 을 한 번 눌러 상품 캐시 · 검색 색인을 새로 하세요.
     (안 하면 상세 화면이 캐시된 "사이즈 없음" 을 계속 보여 줍니다.)
EOF
}

case "$ACTION" in
    status)  status ;;
    preview) preview ;;
    apply)   apply "$@" ;;
    *) die "알 수 없는 동작: $ACTION (status | preview | apply)" ;;
esac
