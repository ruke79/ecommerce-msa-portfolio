#!/usr/bin/env bash
# 운영 액션 ㉙ — 클러스터 안 PostgreSQL 을 PITR 가능한 상태로 만든다
#
# ── 왜 ops/08 만으로는 안 되나 ──────────────────────────────────────────
#
# ops/08 은 **서버와 같은 머신에서 도는 것**을 전제로 쓰였다. 클러스터 안 PostgreSQL
# (k8s/90-dev-infra.yaml)에 대해서는 두 가지를 할 수 없다:
#
#   1. `archive_command` 의 목적지 디렉터리. 그 경로는 **파드 안**에 있어야 한다 —
#      archive_command 를 실행하는 것은 서버이지 스크립트가 아니다. 호스트에서
#      mkdir 해 봐야 서버는 그 경로를 모른다.
#
#   2. `pg_hba.conf` 의 replication 항목. pg_basebackup 은 일반 접속이 아니라
#      **replication 접속**을 쓰는데, initdb 가 만드는 기본 pg_hba 에는 로컬
#      (127.0.0.1/::1) 항목밖에 없다. 파드 밖에서 붙으면 이렇게 거절당한다:
#
#        FATAL: no pg_hba.conf entry for replication connection from host "10.42.0.x"
#
#      이건 파일이라 SQL(ALTER SYSTEM)로는 못 고친다. k8s/85-backup-cronjobs.yaml 의
#      머리말이 "실제로 이 매니페스트를 컨테이너에 돌려 보고서야 확인했다"고 적어 둔
#      바로 그 지점이다.
#
# 그래서 이 스크립트는 `kubectl exec` 로 **파드 안에서** 그 둘을 처리한다.
# 백업 자체는 여전히 ops/08 이 한다 — 여기서 다시 구현하지 않는다.
#
# ── ⚠️ WAL 아카이브를 어디에 두나 ───────────────────────────────────────
#
# 기본값은 데이터 볼륨 안(`$PGDATA/../wal_archive`)이다. **재해 복구로는 부족하다** —
# 디스크가 죽으면 원본과 아카이브가 같이 죽는다. 그래도 기본으로 둔 이유는, 이 값으로도
# **복원 훈련(ops/09)의 시나리오는 정확히 재현되기 때문이다**: DROP TABLE 사고를 내고
# 사고 직전 시점으로 되돌리는 것. 디스크는 멀쩡한데 데이터가 틀린 경우가 실제로 더 흔하다.
#
# 진짜 재해 복구를 하려면 ARCHIVE_DIR 을 별도 볼륨(PVC)이나 오브젝트 스토리지로 옮긴다.
# 그때는 파드에 그 볼륨을 마운트하는 것이 선행이다.
#
# ── 사용 ────────────────────────────────────────────────────────────────
#
#   ./ops/29-enable-pitr-in-cluster.sh                 # 점검만 (기본, 아무것도 바꾸지 않는다)
#   ACTION=setup ./ops/29-enable-pitr-in-cluster.sh    # 적용 + 재기동
#   PG_NS=postgres PG_DEPLOY=postgres ARCHIVE_DIR=/var/lib/postgresql/wal_archive ...
#   YES=1 ...                                          # 재기동 확인 프롬프트 생략
#
# 종료 코드: 0 = PITR 준비 완료, 1 = 준비되지 않음(또는 실패)
set -uo pipefail

ACTION="${ACTION:-check}"
PG_NS="${PG_NS:-postgres}"
PG_DEPLOY="${PG_DEPLOY:-postgres}"
PG_SUPERUSER="${PG_SUPERUSER:-postgres}"
ARCHIVE_DIR="${ARCHIVE_DIR:-/var/lib/postgresql/wal_archive}"
ARCHIVE_TIMEOUT="${ARCHIVE_TIMEOUT:-60}"
# 파드 네트워크. replication 을 허용할 대역이다. 기본은 k3s 의 기본 파드 CIDR.
POD_CIDR="${POD_CIDR:-10.42.0.0/16}"
YES="${YES:-0}"

pass=0; fail=0
ok()   { echo "  ✅ $1"; pass=$((pass+1)); }
bad()  { echo "  ❌ $1"; fail=$((fail+1)); }
info() { echo "  ℹ️  $1"; }
warn() { echo "  ⚠️  $1" >&2; }
die()  { echo "ERROR: $1" >&2; exit 1; }

command -v kubectl >/dev/null || die "kubectl 이 필요합니다"

# 파드 안에서 psql 을 돌린다. -A -t 로 값만 받는다.
q() { kubectl -n "$PG_NS" exec "deploy/$PG_DEPLOY" -- \
        psql -U "$PG_SUPERUSER" -At -c "$1" 2>/dev/null; }
sh_in() { kubectl -n "$PG_NS" exec "deploy/$PG_DEPLOY" -- sh -c "$1"; }

kubectl -n "$PG_NS" get "deploy/$PG_DEPLOY" >/dev/null 2>&1 \
    || die "$PG_NS/$PG_DEPLOY 를 찾을 수 없습니다 (PG_NS·PG_DEPLOY 로 지정하세요)"

# ---------------------------------------------------------------- 점검

check() {
    echo "════ 클러스터 안 PostgreSQL PITR 준비 상태 ($PG_NS/$PG_DEPLOY) ════"

    local version wal_level archive_mode archive_command archive_timeout senders
    version="$(q 'SHOW server_version')"
    [ -n "$version" ] || die "psql 에 접속하지 못했습니다 — 파드가 Ready 인지 확인하세요"
    info "server_version = $version"

    wal_level="$(q 'SHOW wal_level')"
    archive_mode="$(q 'SHOW archive_mode')"
    archive_command="$(q 'SHOW archive_command')"
    archive_timeout="$(q 'SHOW archive_timeout')"
    senders="$(q 'SHOW max_wal_senders')"

    # wal_level=minimal 이면 WAL 에 복구용 정보가 아예 안 들어간다 — PITR 불가.
    case "$wal_level" in
        replica|logical) ok "wal_level = $wal_level" ;;
        *)               bad "wal_level = $wal_level (replica 이상이어야 합니다)" ;;
    esac

    [ "$archive_mode" = "on" ] \
        && ok "archive_mode = on" \
        || bad "archive_mode = $archive_mode — WAL 이 보관되지 않습니다"

    { [ -n "$archive_command" ] && [ "$archive_command" != "(disabled)" ]; } \
        && ok "archive_command = $archive_command" \
        || bad "archive_command 가 비어 있습니다"

    # archive_timeout=0 이면 WAL 세그먼트(16MB)가 다 찰 때까지 아카이브되지 않는다.
    # 한산한 시스템에서는 며칠이 걸릴 수 있고, 그만큼이 그대로 RPO 가 된다.
    [ "$archive_timeout" != "0" ] \
        && ok "archive_timeout = $archive_timeout (RPO 상한)" \
        || bad "archive_timeout = 0 — 세그먼트가 찰 때까지 아카이브되지 않습니다"

    [ "${senders:-0}" -ge 1 ] 2>/dev/null \
        && ok "max_wal_senders = $senders" \
        || bad "max_wal_senders = $senders — pg_basebackup 이 붙지 못합니다"

    # 아카이브가 **조용히 실패**하면 pg_wal 이 차올라 결국 디스크가 터진다.
    # 실패 수는 pg_stat_archiver 가 안다.
    local failed last_failed
    failed="$(q 'SELECT failed_count FROM pg_stat_archiver')"
    last_failed="$(q "SELECT coalesce(last_failed_wal, '-') FROM pg_stat_archiver")"
    if [ -n "$failed" ] && [ "$failed" != "0" ]; then
        bad "아카이브 실패 $failed 건 (마지막 실패 WAL: ${last_failed:-?})"
    elif [ -n "$failed" ]; then
        ok "아카이브 실패 0 건"
    fi

    # pg_hba 의 replication 항목 — pg_basebackup 이 여기서 막힌다.
    if sh_in "grep -qE '^host[[:space:]]+replication[[:space:]]' \"\$PGDATA/pg_hba.conf\" 2>/dev/null \
                && grep -E '^host[[:space:]]+replication[[:space:]]' \"\$PGDATA/pg_hba.conf\" \
                   | grep -qv '127.0.0.1\\|::1'" 2>/dev/null; then
        ok "pg_hba.conf 에 원격 replication 항목이 있습니다"
    else
        bad "pg_hba.conf 에 원격 replication 항목이 없습니다 — pg_basebackup 이 거절됩니다"
    fi

    # 아카이브 디렉터리
    if sh_in "test -d '$ARCHIVE_DIR' && test -w '$ARCHIVE_DIR'" 2>/dev/null; then
        local n
        n="$(sh_in "ls -1 '$ARCHIVE_DIR' 2>/dev/null | wc -l" 2>/dev/null | tr -d ' ')"
        ok "아카이브 디렉터리 $ARCHIVE_DIR (WAL ${n:-0} 개)"
    else
        bad "아카이브 디렉터리 $ARCHIVE_DIR 가 없거나 쓸 수 없습니다"
    fi

    echo
    echo "  통과 $pass · 실패 $fail"
    if [ "$fail" -gt 0 ]; then
        echo
        echo "  적용하려면:  ACTION=setup $0"
        echo "  (wal_level·archive_mode 변경은 **재기동**이 필요합니다 — 이 스크립트가 합니다)"
        return 1
    fi
    echo
    echo "  다음 단계:"
    echo "    1) 백업 자격증명 Secret 생성 (k8s/85-backup-cronjobs.yaml 머리말 참조)"
    echo "    2) kubectl -n ecommerce patch cronjob postgres-backup -p '{\"spec\":{\"suspend\":false}}'"
    echo "    3) 복원 훈련:  ./ops/09-restore-drill.sh"
    return 0
}

# ---------------------------------------------------------------- 적용

setup() {
    echo "════ PITR 설정 적용 ($PG_NS/$PG_DEPLOY) ════"
    warn "이 작업은 PostgreSQL 파드를 **재기동**합니다. 그동안 모든 서비스의 DB 접속이 끊깁니다."
    if [ "$YES" != "1" ]; then
        printf '  계속할까요? [y/N] '
        read -r answer
        case "$answer" in y|Y|yes|YES) ;; *) echo "  취소했습니다."; exit 1 ;; esac
    fi

    # ① 아카이브 디렉터리 — 서버가 쓸 수 있어야 하므로 파드 안에서 만든다.
    sh_in "mkdir -p '$ARCHIVE_DIR' && chown postgres:postgres '$ARCHIVE_DIR'" \
        || die "아카이브 디렉터리를 만들지 못했습니다"
    ok "아카이브 디렉터리 준비: $ARCHIVE_DIR"

    # ② 설정. ALTER SYSTEM 은 postgresql.auto.conf 에 기록되어 재기동에도 남는다.
    #    archive_command 는 **실패하면 0 이 아닌 코드**를 돌려줘야 한다. 성공했다고
    #    거짓말하면 PostgreSQL 이 그 WAL 을 지워 버리고, 복구는 그 지점에서 끊긴다.
    #    `test ! -f` 로 덮어쓰기를 막는 것도 같은 이유다(이미 있는 파일을 덮으면
    #    이전 아카이브가 소리 없이 사라진다).
    q "ALTER SYSTEM SET wal_level = 'replica'"        >/dev/null
    q "ALTER SYSTEM SET archive_mode = 'on'"          >/dev/null
    q "ALTER SYSTEM SET archive_command = 'test ! -f $ARCHIVE_DIR/%f && cp %p $ARCHIVE_DIR/%f'" >/dev/null
    q "ALTER SYSTEM SET archive_timeout = '${ARCHIVE_TIMEOUT}s'" >/dev/null
    q "ALTER SYSTEM SET max_wal_senders = '10'"       >/dev/null
    ok "ALTER SYSTEM 적용 (postgresql.auto.conf)"

    # ③ pg_hba — 파일이라 SQL 로는 못 고친다. 중복 추가를 막고 덧붙인다.
    sh_in "grep -q 'ops/29 PITR' \"\$PGDATA/pg_hba.conf\" || \
           printf '%s\n' '' '# ops/29 PITR — pg_basebackup 용 replication 접속' \
             'host replication all $POD_CIDR scram-sha-256' >> \"\$PGDATA/pg_hba.conf\"" \
        || die "pg_hba.conf 를 고치지 못했습니다"
    ok "pg_hba.conf 에 replication 항목 추가 ($POD_CIDR)"

    # ④ 재기동. wal_level·archive_mode 는 reload 로는 적용되지 않는다.
    echo "  ▶ 파드 재기동 중…"
    kubectl -n "$PG_NS" rollout restart "deploy/$PG_DEPLOY" >/dev/null \
        || die "재기동에 실패했습니다"
    kubectl -n "$PG_NS" rollout status "deploy/$PG_DEPLOY" --timeout=300s >/dev/null \
        || die "재기동이 끝나지 않았습니다 — kubectl -n $PG_NS get pods 로 확인하세요"
    ok "재기동 완료"

    echo
    pass=0; fail=0
    check
}

case "$ACTION" in
    check) check ;;
    setup) setup ;;
    *)     die "ACTION 은 check 또는 setup 입니다 (받은 값: $ACTION)" ;;
esac
