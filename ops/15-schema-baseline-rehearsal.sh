#!/usr/bin/env bash
# 스키마 베이스라인 리허설 — `ddl-auto: update` 를 끄기 전에 반드시 한 번.
#
# 무엇을 확인하나
# ---------------
# 이 저장소는 이제 Flyway 가 스키마를 소유하고 Hibernate 는 `validate` 로 검증만 한다(docs/20).
# 전환의 위험은 딱 하나다: **여러분의 기존 DB 가 엔티티와 어긋나 있으면 서비스가 기동조차
# 못 한다** (`Schema-validation: missing table/column`). 그 사실을 배포 중에 알게 되면 늦다.
#
# 이 스크립트는 배포 전에 같은 판정을 내린다:
#   ① 적용 전 스키마를 스냅샷
#   ② 베이스라인 마이그레이션을 적용 (모든 문장이 멱등이라 이미 맞는 DB 는 무동작)
#   ③ 적용 후 스냅샷 → 무엇이 바뀌었는지 정확히 보여준다
#   ④ 베이스라인이 요구하는 컬럼이 **전부 존재하는지** 대조 — Hibernate validate 와 같은 판정
#
# ⚠️ 운영 DB 에 직접 돌리지 말 것. ops/09 의 복원본이나 pg_dump 사본에 돌린다.
#    (--force 를 주면 강행하지만, 그건 여러분이 사본임을 확인했다는 뜻이다.)
#
# 사용법
#   PGURL='postgresql://user:pw@host:5432/productdb' SERVICE=catalog-service ./ops/15-schema-baseline-rehearsal.sh
#   PGURL=... SERVICE=order-service ./ops/15-schema-baseline-rehearsal.sh
#
# 종료코드: 0 = validate 가 통과할 것이다 · 1 = 지금 배포하면 기동 실패한다

set -euo pipefail

PGURL="${PGURL:-}"
SERVICE="${SERVICE:-}"
FORCE="${FORCE:-0}"
APPLY="${APPLY:-1}"     # APPLY=0 이면 적용 없이 진단만

if [[ -z "$PGURL" || -z "$SERVICE" ]]; then
    cat >&2 <<'USAGE'
사용법: PGURL='postgresql://user:pw@host:5432/db' SERVICE=<서비스명> ./ops/15-schema-baseline-rehearsal.sh

  PGURL    대상 데이터베이스 (운영 DB 말고 **사본**)
  SERVICE  catalog-service | order-service | pay-service | user-service |
           cart-service | chat-service | coupon-service
  APPLY=0  적용하지 않고 진단만 (지금 배포하면 어떻게 되는지)
  FORCE=1  호스트명이 운영처럼 보여도 강행
USAGE
    exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIGRATIONS="$ROOT/$SERVICE/src/main/resources/db/migration"
BASELINE="$(ls "$MIGRATIONS"/V*__baseline_schema.sql 2>/dev/null | head -1 || true)"

if [[ -z "$BASELINE" ]]; then
    echo "ERROR: $SERVICE 에 베이스라인 마이그레이션이 없습니다 ($MIGRATIONS)" >&2
    echo "       ./gradlew -PexportSchema -PwithContainers :$SERVICE:test --tests '*ApplicationTests*'" >&2
    echo "       ./ops/tools/hbm2flyway.py build/schema/$SERVICE.sql -o $MIGRATIONS/Vn__baseline_schema.sql" >&2
    exit 2
fi

command -v psql >/dev/null || { echo "ERROR: psql 이 필요합니다" >&2; exit 2; }

# 운영 DB 로 보이는 이름이면 멈춘다. 이 스크립트는 DDL 을 실행한다.
if [[ "$APPLY" == "1" && "$FORCE" != "1" ]]; then
    if [[ "$PGURL" =~ (prod|production|live) ]]; then
        echo "REFUSED: PGURL 이 운영 DB 로 보입니다. 사본에 돌리세요 (확신하면 FORCE=1)." >&2
        exit 2
    fi
fi

PSQL=(psql "$PGURL" -v ON_ERROR_STOP=1 -qtA)
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

snapshot() {
    "${PSQL[@]}" -c "
        SELECT 'COL '||table_schema||'.'||table_name||'.'||column_name
               ||' '||data_type||coalesce('('||character_maximum_length||')','')
          FROM information_schema.columns
         WHERE table_schema NOT IN ('pg_catalog','information_schema')
        UNION ALL
        SELECT 'IDX '||schemaname||'.'||indexname FROM pg_indexes
         WHERE schemaname NOT IN ('pg_catalog','information_schema')
        UNION ALL
        SELECT 'CON '||n.nspname||'.'||r.relname||'.'||c.conname
               ||' '||c.contype::text||' '||pg_get_constraintdef(c.oid)
          FROM pg_constraint c
          JOIN pg_class r ON r.oid = c.conrelid
          JOIN pg_namespace n ON n.oid = r.relnamespace
         WHERE n.nspname NOT IN ('pg_catalog','information_schema')
    " | sort
}

# 베이스라인의 'ADD COLUMN IF NOT EXISTS' 줄은 엔티티가 요구하는 컬럼의 **완전한 목록**이다.
# validate 가 보는 것과 같은 집합이므로 이것으로 판정할 수 있다.
expected_columns() {
    sed -n 's/^ALTER TABLE \([^ ]*\) ADD COLUMN IF NOT EXISTS \([^ ]*\) .*/\1.\2/p' "$BASELINE" \
        | tr 'A-Z' 'a-z' | sort -u
}

actual_columns() {
    "${PSQL[@]}" -c "
        SELECT lower(table_schema||'.'||table_name||'.'||column_name)
          FROM information_schema.columns
         WHERE table_schema NOT IN ('pg_catalog','information_schema')
    " | sort -u
}

echo "════════════════════════════════════════════════════════════"
echo " 스키마 베이스라인 리허설 — $SERVICE"
echo " 대상   : $(sed 's|://[^@]*@|://***@|' <<<"$PGURL")"
echo " 마이그 : $(basename "$BASELINE")"
echo " 모드   : $([[ "$APPLY" == "1" ]] && echo '적용' || echo '진단만 (APPLY=0)')"
echo "════════════════════════════════════════════════════════════"
echo

"${PSQL[@]}" -c "SELECT 1" >/dev/null || { echo "ERROR: 접속 실패" >&2; exit 1; }

echo "① 적용 전 스냅샷"
snapshot > "$WORK/before.txt"
echo "   항목 $(wc -l < "$WORK/before.txt") 개"
echo

fail=0

if [[ "$APPLY" == "1" ]]; then
    echo "② 베이스라인 적용"
    # NOTICE(이미 존재함)는 정상이므로 걸러내고, 진짜 오류만 보여준다.
    if ! psql "$PGURL" -v ON_ERROR_STOP=1 -q -f "$BASELINE" 2> "$WORK/err.txt"; then
        echo "   ❌ 적용 실패:"
        sed 's/^/      /' "$WORK/err.txt"
        exit 1
    fi
    if grep -q '^ERROR' "$WORK/err.txt"; then
        echo "   ❌ 오류가 보고됐습니다:"
        grep '^ERROR' "$WORK/err.txt" | sed 's/^/      /'
        fail=1
    else
        skipped=$(grep -c 'already exists, skipping' "$WORK/err.txt" || true)
        echo "   ✅ 오류 없음 (이미 존재해서 건너뛴 객체 $skipped 개)"
    fi
    echo

    echo "③ 적용 후 변화"
    snapshot > "$WORK/after.txt"
    if diff -q "$WORK/before.txt" "$WORK/after.txt" >/dev/null; then
        echo "   변화 없음 — 이 DB 는 이미 베이스라인과 일치했습니다"
    else
        # diff 는 차이가 있으면 1 을 반환한다. pipefail + set -e 아래에서는 그것만으로
        # 스크립트가 여기서 죽어 ④ 판정을 못 보게 되므로 상태를 삼킨다.
        { diff "$WORK/before.txt" "$WORK/after.txt" || true; } \
            | grep '^[<>]' | sed 's/^</   제거: /; s/^>/   추가: /' || true
    fi
    echo
fi

echo "④ Hibernate validate 판정 — 엔티티가 요구하는 컬럼이 전부 있는가"
expected_columns > "$WORK/expected.txt"
actual_columns   > "$WORK/actual.txt"
missing="$(comm -23 "$WORK/expected.txt" "$WORK/actual.txt" || true)"

if [[ -n "$missing" ]]; then
    echo "   ❌ 없는 컬럼 $(wc -l <<<"$missing") 개 — 지금 배포하면 기동 실패합니다:"
    sed 's/^/      /' <<<"$missing"
    echo
    echo "   → 베이스라인을 적용하면(APPLY=1) 대부분 해소됩니다."
    echo "     그래도 남는다면 스키마가 엔티티와 어긋난 것이므로 마이그레이션을 따로 써야 합니다."
    fail=1
else
    echo "   ✅ 요구 컬럼 $(wc -l < "$WORK/expected.txt") 개 전부 존재 — validate 가 통과합니다"
fi
echo

# ddl-auto 가 남긴 잔재 중 가장 흔한 것: @OneToOne 이 만들어 둔 UNIQUE.
# Flyway 는 이런 것을 명시적으로 지워야 하므로(V4 가 그 예), 발견되면 알려준다.
echo "⑤ 참고 — ddl-auto 시절의 잔재 점검"
orphan_unique="$("${PSQL[@]}" -c "
    SELECT n.nspname||'.'||r.relname||'.'||c.conname
      FROM pg_constraint c
      JOIN pg_class r ON r.oid = c.conrelid
      JOIN pg_namespace n ON n.oid = r.relnamespace
     WHERE c.contype = 'u'
       AND n.nspname NOT IN ('pg_catalog','information_schema')
       AND c.conname ~ '^uk[0-9a-z]{20,}\$'
" || true)"
if [[ -n "$orphan_unique" ]]; then
    echo "   ⚠️  Hibernate 가 생성한 이름의 UNIQUE 제약이 남아 있습니다:"
    sed 's/^/      /' <<<"$orphan_unique"
    echo "      의도한 것인지 확인하세요. ddl-auto 는 제약을 **지우지 않으므로**,"
    echo "      매핑을 고쳐도 이런 제약은 남습니다(V4 가 바로 그 사례)."
else
    echo "   Hibernate 생성 이름의 UNIQUE 제약 없음"
fi
echo

echo "════════════════════════════════════════════════════════════"
if [[ "$fail" == "0" ]]; then
    echo " ✅ 통과 — 이 DB 에서 ddl-auto: validate 로 기동할 수 있습니다"
else
    echo " ❌ 실패 — 위 항목을 해소하기 전에는 배포하지 마세요"
fi
echo "════════════════════════════════════════════════════════════"
exit "$fail"
