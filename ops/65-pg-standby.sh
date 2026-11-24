#!/usr/bin/env bash
# 운영 액션 65 — PostgreSQL 스탠바이(k8s/91-postgres-standby.yaml): primary 준비 · 적용 · 승격 · 되돌리기
# [JA] 運用アクション 65 — PostgreSQL スタンバイ: primary の準備 · 適用 · 昇格 · 取り外し
#
# 손으로 하면 순서 · 빠뜨림이 위험한 일을 한곳에 모았다 — 특히 되돌릴 때 슬롯을 지우지 않으면 primary 가 WAL 을 계속 붙잡는다.
# 모든 단계는 **이미 된 것은 다시 하지 않는다**(Secret · 계정 · 슬롯 · 설정 · 적용 · 승격 · Service 마다 먼저 본다), 바꾸는 동작은
# 한 번에 하나만 돈다(flock — 두 사람이 동시에 돌리면 두 번째가 멈춘다), 그리고 바꾸기 전에 묻는다(터미널이 없으면 YES=1).
# [JA] 済んだ段階は二度としない · 変える動作は同時に一つだけ(flock) · 変える前に聞く(端末がなければ YES=1)。
#
#   ./ops/65-pg-standby.sh check       # 지금 상태 — 읽기만(Secret · 계정 · 슬롯 · WAL 상한 · 데이터 노드 · 스탠바이 · 복제)
#   ./ops/65-pg-standby.sh prepare     # primary 쪽 준비 — Secret · 복제 계정 · 슬롯 · max_slot_wal_keep_size(재시작 없음, 재적재만)
#   ./ops/65-pg-standby.sh apply       # 매니페스트 적용 · 첫 받기(약 5.3GB) · 복제 확인 — 이미 있으면 다시 적용하지 않는다
#   ./ops/65-pg-standby.sh promote     # 승격(primary 를 잃었을 때만) — primary 가 Ready 면 거절(FORCE=1), 옛 primary 0 · Service 를 스탠바이로
#   ./ops/65-pg-standby.sh remove      # 스탠바이를 뺀다 — Deployment · PVC · **슬롯까지**. 승격된 스탠바이(= 지금의 운영)는 거절
#
#   감시(경보 그룹 postgres-standby — observability/alert-rules.yml):
#   ./ops/65-pg-standby.sh metrics          # 지표 파일 한 번 — <TEXTFILE_DIR>/pg_standby.prom(node-exporter textfile). 읽기만, 원자적으로 바꿔 쓴다
#   ./ops/65-pg-standby.sh monitor-install  # 운영 노드에 1분 타이머(systemd · root) — 스크립트는 /usr/local/sbin 에 복사(체크아웃 판과 무관). 같으면 할 것 없음
#   ./ops/65-pg-standby.sh monitor-remove   # 타이머 · 복사본 · 지표 파일을 뺀다(스탠바이를 아주 뺄 때)
#   질의가 실패하면 지표 파일을 그대로 둔다 — 마지막 성공 시각이 낡아 PgStandbyMetricsStale 이 울린다(말없이 '정상' 으로 보이지 않게).
#
# 가드 — 사전에 한 일을 다시 하지 않는다:
#   · Secret 이 있으면 새로 만들지 않는다(비밀번호를 바꾸면 이미 만든 계정과 어긋난다)
#   · 계정이 있으면 만들지 않는다. 계정은 있는데 Secret 이 없으면 멈춘다(비밀번호를 모른다 — 손으로 맞춘다)
#   · 슬롯이 있으면 만들지 않는다(같은 이름의 논리 슬롯이면 멈춘다)
#   · WAL 상한이 이미 그 값이면 건너뛰고, 누군가 다른 값을 정해 두었으면 덮지 않는다(알리기만)
#   · 스탠바이 Deployment 가 있으면 apply 하지 않는다 · 이미 승격 + Service 가 스탠바이면 promote 는 할 것 없음
#
# 비밀번호는 화면 · 명령줄 · 파일에 남기지 않는다 — Secret 은 mktemp(0600, 끝나면 지움)에서, 계정은 psql 표준 입력으로(ps 에 안 보인다).
#
# 환경 변수: NS=postgres · PRIMARY=postgres(Deployment · Service) · STANDBY=postgres-standby · ROLE=replicator · SLOT=standby_oci2 ·
#            SECRET=pg-replication · WAL_KEEP=10GB · MANIFEST=k8s/91-postgres-standby.yaml · APPLY_TIMEOUT=1800s ·
#            LOCK_FILE=/tmp/ecommerce-pg-standby.lock · YES=1(묻지 않는다) · FORCE=1(promote — primary 가 Ready 여도) ·
#            APP_NAME=standby-oci2(primary_conninfo 의 application_name) · TEXTFILE_DIR=/var/lib/node_exporter ·
#            INSTALL_PATH=/usr/local/sbin/ecommerce-pg-standby.sh · SYSTEMD_DIR=/etc/systemd/system · SUDO='sudo -n'
# 시험: PgStandbyScriptTest
set -euo pipefail

ACTION="${1:-check}"
HERE="$(cd "$(dirname "$0")" && pwd)"
NS="${NS:-postgres}"
PRIMARY="${PRIMARY:-postgres}"
STANDBY="${STANDBY:-postgres-standby}"
ROLE="${ROLE:-replicator}"
SLOT="${SLOT:-standby_oci2}"
SECRET="${SECRET:-pg-replication}"
WAL_KEEP="${WAL_KEEP:-10GB}"
MANIFEST="${MANIFEST:-$HERE/../k8s/91-postgres-standby.yaml}"
APPLY_TIMEOUT="${APPLY_TIMEOUT:-1800s}"
LOCK_FILE="${LOCK_FILE:-/tmp/ecommerce-pg-standby.lock}"
APP_NAME="${APP_NAME:-standby-oci2}"
TEXTFILE_DIR="${TEXTFILE_DIR:-/var/lib/node_exporter}"
INSTALL_PATH="${INSTALL_PATH:-/usr/local/sbin/ecommerce-pg-standby.sh}"
SYSTEMD_DIR="${SYSTEMD_DIR:-/etc/systemd/system}"
SUDO="${SUDO-sudo -n}"
UNIT=ecommerce-pg-standby-metrics

say()  { printf '>>> %s\n' "$*"; }
ok()   { printf '  ✅ %s\n' "$*"; }
skip() { printf '  ⏭️  %s\n' "$*"; }
warn() { printf '  ⚠️  %s\n' "$*" >&2; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# SQL 에 그대로 들어가는 이름 · 값 — 모양을 먼저 본다
# [JA] SQL にそのまま入る名前 · 値 — 先に形を見る
[[ "$ROLE" =~ ^[a-z_][a-z0-9_]*$ ]] || die "ROLE 모양이 이상하다: $ROLE"
[[ "$SLOT" =~ ^[a-z_][a-z0-9_]*$ ]] || die "SLOT 모양이 이상하다: $SLOT"
[[ "$WAL_KEEP" =~ ^[1-9][0-9]*(MB|GB)$ ]] || die "WAL_KEEP 은 <수>MB · <수>GB: $WAL_KEEP"
[[ "$APP_NAME" =~ ^[a-z0-9-]+$ ]] || die "APP_NAME 모양이 이상하다: $APP_NAME"
for v in NS PRIMARY STANDBY SECRET; do [[ "${!v}" =~ ^[a-z0-9]([a-z0-9-]*[a-z0-9])?$ ]] || die "$v 모양이 이상하다: ${!v}"; done

kc() { kubectl -n "$NS" "$@"; }
# primary · 스탠바이에 질의 하나 — 값만(머리말 없이). 표준 입력은 붙이지 않는다(-i 없음)
# [JA] primary · スタンバイに問い合わせ一つ — 値だけ(見出しなし)。標準入力は付けない(-i なし)
sql()         { kc exec "deploy/$PRIMARY" -c postgres -- psql -U postgres -tA -v ON_ERROR_STOP=1 -c "$1"; }
sql_standby() { kc exec "deploy/$STANDBY" -c postgres -- psql -U postgres -tA -v ON_ERROR_STOP=1 -c "$1"; }

secret_exists()  { kc get secret "$SECRET" >/dev/null 2>&1; }
role_state()     { sql "select rolreplication::text || ',' || rolcanlogin::text from pg_roles where rolname = '$ROLE'"; }   # "" | "true,true" …
slot_state()     { sql "select slot_type || ',' || active::text from pg_replication_slots where slot_name = '$SLOT'"; }       # "" | "physical,false" …
keep_setting()   { sql "select current_setting('max_slot_wal_keep_size')"; }
standby_exists() { kc get deploy "$STANDBY" >/dev/null 2>&1; }
pvc_exists()     { kc get pvc pg-standby-data >/dev/null 2>&1; }
svc_target()     { kc get svc "$PRIMARY" -o jsonpath='{.spec.selector.app}' 2>/dev/null || true; }
primary_ready()  { local r; r="$(kc get deploy "$PRIMARY" -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true)"; echo "${r:-0}"; }
data_node()      { kubectl get nodes -l ecommerce.io/data=true --no-headers 2>/dev/null | awk '$2 == "Ready" { print $1; exit }'; }
# 답하지 않으면 빈 값 — set -e · pipefail 아래에서 말없이 끝나지 않게(받는 중 · 죽음은 부르는 쪽이 판단한다)
# [JA] 答えなければ空の値 — set -e · pipefail の下で黙って終わらないように(取得中 · 停止は呼ぶ側が判断する)
in_recovery()    { { sql_standby "select pg_is_in_recovery()" 2>/dev/null || true; } | tr -d '[:space:]'; }   # t | f | ""

confirm() {   # confirm <무엇을>
    # [JA] confirm <何を>
    [ "${YES:-}" = "1" ] && return 0
    [ -t 0 ] || die "확인 질문에 답할 터미널이 없다 — 바꾸려면 YES=1 로 명시한다"
    local a
    read -r -p "  $1 — 진행할까? [y/N] " a
    case "$a" in y|Y) ;; *) die "그만둔다(아무것도 바꾸지 않았다)" ;; esac
}

# 바꾸는 동작은 한 번에 하나 — 두 셸에서 동시에 prepare 하면 Secret · 계정이 서로 다른 비밀번호로 만들어질 수 있다
# [JA] 変える動作は一度に一つ — 二つのシェルで同時に prepare すると Secret · アカウントが別々のパスワードで作られうる
lock() {
    command -v flock >/dev/null 2>&1 || die "flock 이 없다(util-linux) — 동시 실행을 막을 수 없어 멈춘다"
    exec 9>"$LOCK_FILE"
    flock -n 9 || die "다른 ops/65 가 이미 돌고 있다($LOCK_FILE) — 그것이 끝난 뒤 다시"
}

# ---------------------------------------------------------------- check

check() {
    say "PostgreSQL 스탠바이 상태 — 네임스페이스 $NS (읽기만)"
    if secret_exists; then ok "Secret $SECRET 있음"; else echo "  · Secret $SECRET 없음"; fi
    local rs ss ks node target rec
    rs="$(role_state)"
    case "$rs" in
        "") echo "  · 복제 계정 $ROLE 없음" ;;
        true,true) ok "복제 계정 $ROLE(REPLICATION · LOGIN)" ;;
        *) warn "계정 $ROLE 은 있는데 REPLICATION · LOGIN 이 아니다($rs)" ;;
    esac
    ss="$(slot_state)"
    if [ -z "$ss" ]; then echo "  · 슬롯 $SLOT 없음"; else ok "슬롯 $SLOT($ss)"; fi
    ks="$(keep_setting)"
    if [ "$ks" = "$WAL_KEEP" ]; then ok "max_slot_wal_keep_size=$ks"; else echo "  · max_slot_wal_keep_size=$ks(목표 $WAL_KEEP)"; fi
    node="$(data_node)"
    if [ -n "$node" ]; then ok "데이터 노드 $node Ready"; else warn "Ready 인 데이터 노드(ecommerce.io/data=true)가 없다"; fi
    if standby_exists; then
        rec="$(in_recovery)"
        case "$rec" in
            t) ok "스탠바이 $STANDBY — 복구 중(스트리밍)" ;;
            f) warn "스탠바이 $STANDBY 가 **승격돼 있다**(쓰기 가능)" ;;
            *) echo "  · 스탠바이 $STANDBY 있음 — 아직 답하지 않는다(받는 중일 수 있다: kubectl -n $NS logs deploy/$STANDBY -c basebackup)" ;;
        esac
    else
        echo "  · 스탠바이 $STANDBY 없음"
    fi
    target="$(svc_target)"
    [ "$target" = "$STANDBY" ] && warn "Service $PRIMARY 가 스탠바이를 가리킨다(승격 뒤)"
    [ -n "$ss" ] && sql "select 'replication ' || application_name || ' ' || state || ' ' || sync_state || ' replay_lag=' || coalesce(replay_lag::text, '-') from pg_stat_replication" | sed 's/^/  · /' || true
    if [ -f "$TEXTFILE_DIR/pg_standby.prom" ]; then
        local age=$(( $(date +%s) - $(stat -c %Y "$TEXTFILE_DIR/pg_standby.prom") ))
        if [ "$age" -le 180 ]; then ok "감시 지표 $TEXTFILE_DIR/pg_standby.prom — ${age}초 전"; else warn "감시 지표가 ${age}초째 갱신되지 않았다(타이머 $UNIT.timer)"; fi
    else
        echo "  · 감시 지표 없음 — ./ops/65-pg-standby.sh monitor-install"
    fi
    return 0
}

# ---------------------------------------------------------------- prepare

prepare() {
    lock
    local had_secret=0 rs ss ks todo=()
    secret_exists && had_secret=1
    rs="$(role_state)"
    ss="$(slot_state)"
    ks="$(keep_setting)"

    # 어긋남 — 바꾸기 전에 멈춘다
    # [JA] 食い違い — 変える前に止まる
    if [ -n "$rs" ] && [ "$had_secret" = 0 ]; then
        die "계정 $ROLE 은 있는데 Secret $SECRET 이 없다 — 그 계정의 비밀번호를 모른다. 새 Secret 을 만들고 ALTER ROLE 로 맞추거나(손으로), 계정을 지운 뒤 다시 prepare"
    fi
    [ -z "$rs" ] || [ "$rs" = "true,true" ] || die "계정 $ROLE 은 있는데 REPLICATION · LOGIN 이 아니다($rs) — 다른 용도의 계정일 수 있다, 손으로 확인"
    [ -z "$ss" ] || [[ "$ss" == physical,* ]] || die "슬롯 $SLOT 이 물리 슬롯이 아니다($ss) — 다른 용도일 수 있다, 손으로 확인"

    say "primary 쪽 준비 — 네임스페이스 $NS · Deployment $PRIMARY"
    if [ "$had_secret" = 1 ]; then skip "Secret $SECRET — 이미 있다(그대로 쓴다)"; else echo "  · Secret $SECRET — 만든다(난수, 화면에 안 나온다)"; todo+=(secret); fi
    if [ -n "$rs" ]; then skip "복제 계정 $ROLE — 이미 있다"; else echo "  · 복제 계정 $ROLE — 만든다(Secret 의 비밀번호로)"; todo+=(role); fi
    if [ -n "$ss" ]; then skip "슬롯 $SLOT — 이미 있다($ss)"; else echo "  · 슬롯 $SLOT — 만든다"; todo+=(slot); fi
    if [ "$ks" = "$WAL_KEEP" ]; then
        skip "max_slot_wal_keep_size — 이미 $WAL_KEEP"
    elif [ "$ks" = "-1" ]; then
        echo "  · max_slot_wal_keep_size -1(무제한) → $WAL_KEEP(재적재만)"; todo+=(keep)
    else
        warn "max_slot_wal_keep_size 가 이미 다른 값($ks) — 누군가 정한 값이라 덮지 않는다(바꾸려면 손으로 ALTER SYSTEM)"
    fi
    if [ "${#todo[@]}" -eq 0 ]; then
        ok "이미 끝났다 — 할 것 없음"
        return 0
    fi
    confirm "위의 ${#todo[@]}가지를 primary 에 한다(재시작 없음)"

    local t
    for t in "${todo[@]}"; do
        case "$t" in
            secret)
                local tmp
                tmp="$(umask 077; mktemp)"
                trap 'rm -f -- "${tmp:-}"' EXIT
                openssl rand -base64 24 | tr -d '\n' > "$tmp"
                kc create secret generic "$SECRET" --from-file=password="$tmp" >/dev/null
                rm -f -- "$tmp"
                ok "Secret $SECRET 만듦"
                ;;
            role)
                local pw
                pw="$(kc get secret "$SECRET" -o jsonpath='{.data.password}' | base64 -d)"
                [ -n "$pw" ] || die "Secret $SECRET 의 password 가 비었다"
                [[ "$pw" =~ ^[A-Za-z0-9+/=._-]+$ ]] || die "Secret $SECRET 의 password 에 SQL 에 넣을 수 없는 글자가 있다 — 손으로"
                # 비밀번호는 표준 입력으로 — 명령줄(ps)에 보이지 않는다
                # [JA] パスワードは標準入力で — コマンドライン(ps)に見えない
                printf "CREATE ROLE %s WITH REPLICATION LOGIN PASSWORD '%s';\n" "$ROLE" "$pw" \
                    | kc exec -i "deploy/$PRIMARY" -c postgres -- psql -U postgres -v ON_ERROR_STOP=1 -q
                unset pw
                ok "복제 계정 $ROLE 만듦"
                ;;
            slot)
                sql "select pg_create_physical_replication_slot('$SLOT')" >/dev/null
                ok "슬롯 $SLOT 만듦 — 지금부터 WAL 을 붙잡는다(상한 max_slot_wal_keep_size). 너무 오래 apply 를 미루지 않는다"
                ;;
            keep)
                sql "ALTER SYSTEM SET max_slot_wal_keep_size = '$WAL_KEEP'" >/dev/null
                sql "select pg_reload_conf()" >/dev/null
                ok "max_slot_wal_keep_size=$WAL_KEEP(재적재)"
                ;;
        esac
    done
    echo
    check
    echo "  다음: ./ops/65-pg-standby.sh apply"
}

# ---------------------------------------------------------------- apply

apply() {
    lock
    [ -f "$MANIFEST" ] || die "매니페스트가 없다: $MANIFEST"
    secret_exists || die "Secret $SECRET 이 없다 — 먼저 ./ops/65-pg-standby.sh prepare"
    [ "$(role_state)" = "true,true" ] || die "복제 계정 $ROLE 이 없다 — 먼저 prepare"
    [[ "$(slot_state)" == physical,* ]] || die "슬롯 $SLOT 이 없다 — 먼저 prepare"
    if standby_exists; then
        skip "스탠바이 $STANDBY 가 이미 있다 — 다시 적용하지 않는다(상태만)"
        check
        return 0
    fi
    [ "$(svc_target)" = "$STANDBY" ] && die "Service $PRIMARY 가 스탠바이를 가리킨다 — 승격 뒤의 상태다, 새 스탠바이는 손으로 판단"
    local node
    node="$(data_node)"
    [ -n "$node" ] || die "Ready 인 데이터 노드(ecommerce.io/data=true)가 없다 — 스탠바이가 뜰 자리가 없다"
    say "스탠바이 적용 — $MANIFEST → 데이터 노드 $node, 처음 한 번 primary 에서 통째로 받는다"
    confirm "스탠바이를 만든다(앱은 그대로 primary 만 본다)"
    kubectl apply -f "$MANIFEST"
    say "받기를 기다린다(최대 $APPLY_TIMEOUT) — 진행: kubectl -n $NS logs -f deploy/$STANDBY -c basebackup"
    kc rollout status "deploy/$STANDBY" --timeout="$APPLY_TIMEOUT"
    echo
    check
}

# ---------------------------------------------------------------- promote

promote() {
    lock
    standby_exists || die "스탠바이 $STANDBY 가 없다"
    local rec target ready i
    rec="$(in_recovery)"
    target="$(svc_target)"
    if [ "$rec" = "f" ] && [ "$target" = "$STANDBY" ]; then
        ok "이미 승격됐고 Service 도 스탠바이를 가리킨다 — 할 것 없음"
        return 0
    fi
    [ "$rec" = "t" ] || [ "$rec" = "f" ] || die "스탠바이가 답하지 않는다(pg_is_in_recovery) — 승격할 수 없다"
    ready="$(primary_ready)"
    if [ "$rec" = "t" ] && [ "$ready" != "0" ] && [ "${FORCE:-}" != "1" ]; then
        die "primary $PRIMARY 가 아직 Ready($ready) — 살아 있는 primary 옆에서 승격하면 쓰기가 둘로 갈린다. primary 를 잃었을 때만 — 그래도 하려면 FORCE=1"
    fi
    say "승격 — 스탠바이 $STANDBY 를 새 primary 로, 옛 $PRIMARY 는 0, Service $PRIMARY 를 스탠바이로"
    confirm "승격한다(되돌릴 수 없다 — 옛 primary 는 다시 쓰지 않는다)"
    if [ "$rec" = "t" ]; then
        sql_standby "select pg_promote()" >/dev/null
        for i in $(seq 1 30); do
            [ "$(in_recovery)" = "f" ] && break
            sleep 2
        done
        [ "$(in_recovery)" = "f" ] || die "승격이 끝나지 않았다 — kubectl -n $NS logs deploy/$STANDBY"
        ok "승격됨(pg_is_in_recovery = f)"
    else
        skip "스탠바이는 이미 승격돼 있다"
    fi
    if [ "$(kc get deploy "$PRIMARY" -o jsonpath='{.spec.replicas}' 2>/dev/null || echo 0)" != "0" ]; then
        kc scale "deploy/$PRIMARY" --replicas=0 >/dev/null
        ok "옛 primary $PRIMARY → 0(돌아와도 쓰기를 받지 않게)"
    else
        skip "옛 primary $PRIMARY 는 이미 0"
    fi
    if [ "$target" != "$STANDBY" ]; then
        kc patch svc "$PRIMARY" -p "{\"spec\":{\"selector\":{\"app\":\"$STANDBY\"}}}" >/dev/null
        ok "Service $PRIMARY → app=$STANDBY"
    else
        skip "Service 는 이미 스탠바이를 가리킨다"
    fi
    warn "k8s/90-dev-infra.yaml 을 다시 apply 하면 Service 가 옛 primary 로 돌아간다 — 그 파일을 고치기 전까지 apply 하지 않는다"
    warn "앱의 커넥션 풀은 끊긴 연결을 버리고 다시 붙는다 — 몇 분 동안 오류 로그 · 지표 11 · 사이트를 본다"
}

# ---------------------------------------------------------------- remove

remove() {
    lock
    local target rec ss todo=()
    target="$(svc_target)"
    [ "$target" = "$STANDBY" ] && die "Service $PRIMARY 가 스탠바이를 가리킨다 — 승격된 스탠바이가 지금의 운영 DB 다. 지우지 않는다"
    if standby_exists; then
        rec="$(in_recovery)"
        [ "$rec" = "f" ] && die "스탠바이가 승격돼 있다(쓰기 가능) — 그 데이터를 지우지 않는다, 손으로 판단"
        todo+=(deploy)
    elif pvc_exists; then
        todo+=(deploy)
    fi
    ss="$(slot_state)"
    [ -n "$ss" ] && todo+=(slot)
    say "스탠바이 빼기"
    if [[ " ${todo[*]:-} " == *" deploy "* ]]; then echo "  · Deployment $STANDBY · PVC pg-standby-data(데이터 노드의 사본) — 지운다"; else skip "Deployment · PVC — 없다"; fi
    if [ -n "$ss" ]; then echo "  · 슬롯 $SLOT — 지운다(남기면 primary 가 WAL 을 $WAL_KEEP 까지 쌓는다)"; else skip "슬롯 $SLOT — 없다"; fi
    if [ "${#todo[@]}" -eq 0 ]; then
        ok "이미 빠져 있다 — 할 것 없음"
        return 0
    fi
    confirm "스탠바이를 뺀다"
    if [[ " ${todo[*]} " == *" deploy "* ]]; then
        kubectl delete -f "$MANIFEST" --ignore-not-found --wait=true >/dev/null
        ok "Deployment · PVC 지움"
    fi
    if [ -n "$ss" ]; then
        local i
        for i in $(seq 1 30); do
            [[ "$(slot_state)" == *,true ]] || break
            sleep 2
        done
        [[ "$(slot_state)" == *,true ]] && die "슬롯 $SLOT 이 아직 쓰이고 있다(active) — 스탠바이가 다른 곳에서 돌고 있는지 확인"
        sql "select pg_drop_replication_slot('$SLOT')" >/dev/null
        ok "슬롯 $SLOT 지움"
    fi
    echo "  남긴 것: 복제 계정 $ROLE · Secret $SECRET · max_slot_wal_keep_size — 다시 붙일 때 그대로 쓴다(prepare 가 건너뛴다)"
}

# ---------------------------------------------------------------- metrics · monitor

# 지금 primary(= postgres Service 뒤의 Deployment — 승격 뒤에는 스탠바이)에 한 번만 묻는다. 7개 값 — 한 줄, 공백 구분.
# [JA] 今の primary(Service の後ろ)に一度だけ聞く。失敗したら指標ファイルを書き換えない — 古くなって警報が鳴る。
metrics() {
    local dep row configured active retained lost streaming lag_s lag_b v
    dep="$(svc_target)"; dep="${dep:-$PRIMARY}"
    [[ "$dep" =~ ^[a-z0-9-]+$ ]] || die "Service 셀렉터가 이상하다: $dep"
    [ -d "$TEXTFILE_DIR" ] || die "textfile 디렉터리가 없다: $TEXTFILE_DIR"
    row="$(kc exec "deploy/$dep" -c postgres -- psql -U postgres -tA -F ' ' -v ON_ERROR_STOP=1 -c "select
        (select count(*) from pg_replication_slots where slot_name = '$SLOT'),
        coalesce((select active::int from pg_replication_slots where slot_name = '$SLOT'), 0),
        coalesce((select pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)::bigint from pg_replication_slots where slot_name = '$SLOT'), 0),
        coalesce((select (wal_status = 'lost')::int from pg_replication_slots where slot_name = '$SLOT'), 0),
        coalesce((select (state = 'streaming')::int from pg_stat_replication where application_name = '$APP_NAME' limit 1), 0),
        coalesce((select round(extract(epoch from replay_lag)::numeric, 3) from pg_stat_replication where application_name = '$APP_NAME' limit 1), 0),
        coalesce((select pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn)::bigint from pg_stat_replication where application_name = '$APP_NAME' limit 1), 0)")" \
        || die "primary($dep)에 묻지 못했다 — 지표 파일을 그대로 둔다(낡으면 PgStandbyMetricsStale)"
    read -r configured active retained lost streaming lag_s lag_b <<<"$row"
    for v in "$configured" "$active" "$retained" "$lost" "$streaming" "$lag_s" "$lag_b"; do
        [[ "$v" =~ ^-?[0-9]+(\.[0-9]+)?$ ]] || die "질의 결과의 모양이 이상하다: '$row' — 지표 파일을 그대로 둔다"
    done
    local tmp
    tmp="$(mktemp "$TEXTFILE_DIR/.pg_standby.prom.XXXXXX")"
    cat > "$tmp" <<EOF
# HELP pg_standby_configured 1 = primary 에 스탠바이 슬롯이 있다(감시 대상). 0 이면 다른 지표의 경보는 울리지 않는다.
# TYPE pg_standby_configured gauge
pg_standby_configured{slot="$SLOT"} $configured
# HELP pg_standby_streaming 1 = 스탠바이가 primary 에서 스트리밍 중(pg_stat_replication state=streaming).
# TYPE pg_standby_streaming gauge
pg_standby_streaming{application_name="$APP_NAME"} $streaming
# HELP pg_standby_replay_lag_seconds 스탠바이 재생 지연(초, pg_stat_replication.replay_lag — 쓰기가 없으면 0 으로 돌아간다).
# TYPE pg_standby_replay_lag_seconds gauge
pg_standby_replay_lag_seconds{application_name="$APP_NAME"} $lag_s
# HELP pg_standby_replay_lag_bytes primary 의 지금 WAL 과 스탠바이가 재생한 WAL 의 차이(바이트).
# TYPE pg_standby_replay_lag_bytes gauge
pg_standby_replay_lag_bytes{application_name="$APP_NAME"} $lag_b
# HELP pg_standby_slot_active 1 = 슬롯에 스탠바이가 붙어 있다.
# TYPE pg_standby_slot_active gauge
pg_standby_slot_active{slot="$SLOT"} $active
# HELP pg_standby_slot_retained_bytes 슬롯이 primary 에 붙잡아 둔 WAL(바이트) — max_slot_wal_keep_size 를 넘으면 슬롯이 버려진다.
# TYPE pg_standby_slot_retained_bytes gauge
pg_standby_slot_retained_bytes{slot="$SLOT"} $retained
# HELP pg_standby_slot_lost 1 = 슬롯이 버려졌다(wal_status=lost) — 스탠바이를 다시 받아야 한다.
# TYPE pg_standby_slot_lost gauge
pg_standby_slot_lost{slot="$SLOT"} $lost
# HELP pg_standby_metrics_last_success_timestamp_seconds 이 파일을 마지막으로 성공해 쓴 시각(유닉스 초).
# TYPE pg_standby_metrics_last_success_timestamp_seconds gauge
pg_standby_metrics_last_success_timestamp_seconds $(date +%s)
EOF
    chmod 644 "$tmp"
    mv -f "$tmp" "$TEXTFILE_DIR/pg_standby.prom"
    echo "pg_standby.prom — primary=$dep configured=$configured streaming=$streaming lag=${lag_s}s/${lag_b}B slot_active=$active retained=$retained lost=$lost"
}

unit_service() {
    cat <<EOF
[Unit]
Description=ecommerce-msa — PostgreSQL 스탠바이 감시 지표(ops/65 metrics → $TEXTFILE_DIR/pg_standby.prom)
[Service]
Type=oneshot
Environment=KUBECONFIG=/etc/rancher/k3s/k3s.yaml
Environment=PATH=/usr/local/bin:/usr/local/sbin:/usr/bin:/bin
Environment=NS=$NS
Environment=PRIMARY=$PRIMARY
Environment=SLOT=$SLOT
Environment=APP_NAME=$APP_NAME
Environment=TEXTFILE_DIR=$TEXTFILE_DIR
ExecStart=$INSTALL_PATH metrics
EOF
}

unit_timer() {
    cat <<EOF
[Unit]
Description=ecommerce-msa — PostgreSQL 스탠바이 감시 지표 1분마다(ops/65)
[Timer]
OnBootSec=1min
OnUnitActiveSec=1min
AccuracySec=10s
[Install]
WantedBy=timers.target
EOF
}

# 같은 판이 이미 깔려 돌고 있으면 할 것 없음 — 다르면(스크립트 · 유닛이 바뀌었으면) 다시 깐다
# [JA] 同じ版がすでに入って動いていれば何もしない — 違えば(スクリプト · ユニットが変わっていれば)入れ直す
monitor_install() {
    lock
    local svc="$SYSTEMD_DIR/$UNIT.service" tim="$SYSTEMD_DIR/$UNIT.timer" same=1
    $SUDO cmp -s "$0" "$INSTALL_PATH" 2>/dev/null || same=0
    [ "$($SUDO cat "$svc" 2>/dev/null)" = "$(unit_service)" ] || same=0
    [ "$($SUDO cat "$tim" 2>/dev/null)" = "$(unit_timer)" ] || same=0
    $SUDO systemctl is-active --quiet "$UNIT.timer" 2>/dev/null || same=0
    if [ "$same" = 1 ]; then
        ok "감시 타이머 $UNIT.timer — 같은 판이 이미 돌고 있다, 할 것 없음"
        return 0
    fi
    say "감시 설치 — $INSTALL_PATH · $UNIT.service · $UNIT.timer(1분) → $TEXTFILE_DIR/pg_standby.prom"
    confirm "운영 노드에 감시 타이머를 깐다(읽기만 하는 질의 · 지표 파일 하나)"
    local t
    t="$(mktemp)"; trap 'rm -f -- "${t:-}"' EXIT
    $SUDO install -m 0755 "$0" "$INSTALL_PATH"
    unit_service > "$t"; $SUDO install -m 0644 "$t" "$svc"
    unit_timer > "$t";   $SUDO install -m 0644 "$t" "$tim"
    rm -f -- "$t"
    $SUDO systemctl daemon-reload
    $SUDO systemctl enable --now "$UNIT.timer" >/dev/null 2>&1
    $SUDO systemctl start "$UNIT.service" || die "첫 실행이 실패했다 — journalctl -u $UNIT.service"
    ok "설치 · 첫 실행 끝 — 지표: $TEXTFILE_DIR/pg_standby.prom"
    echo "  Prometheus 가 경보 그룹 postgres-standby 를 읽으려면 관측 스택을 다시 띄운다(규칙 파일이 바뀐 체크아웃에서 — /-/reload 는 꺼져 있다)"
}

monitor_remove() {
    lock
    local svc="$SYSTEMD_DIR/$UNIT.service" tim="$SYSTEMD_DIR/$UNIT.timer" any=0
    for f in "$svc" "$tim" "$INSTALL_PATH" "$TEXTFILE_DIR/pg_standby.prom"; do $SUDO test -e "$f" && any=1; done
    if [ "$any" = 0 ]; then ok "감시가 깔려 있지 않다 — 할 것 없음"; return 0; fi
    confirm "감시 타이머 · 복사본 · 지표 파일을 뺀다"
    $SUDO systemctl disable --now "$UNIT.timer" >/dev/null 2>&1 || true
    $SUDO rm -f -- "$svc" "$tim" "$INSTALL_PATH" "$TEXTFILE_DIR/pg_standby.prom"
    $SUDO systemctl daemon-reload
    ok "감시를 뺐다"
}

case "$ACTION" in
    check|status) check ;;
    prepare)      prepare ;;
    apply)        apply ;;
    promote)      promote ;;
    remove)       remove ;;
    metrics)      metrics ;;
    monitor-install) monitor_install ;;
    monitor-remove)  monitor_remove ;;
    help|-h|--help) sed -n '2,36p' "$0" ;;
    *) die "알 수 없는 동작: $ACTION (check · prepare · apply · promote · remove · metrics · monitor-install · monitor-remove)" ;;
esac
