#!/usr/bin/env bash
# 운영 액션 ㊽ — 운영 데모 · 부하 테스트용 사용자 대량 생성 · 확인 · 삭제 (docs/79)
# [JA] 運用アクション ㊽ — 運用デモ・負荷テスト用ユーザーの大量生成・確認・削除(docs/79)
#
# ops/tools/generate-demo-users.py 가 낸 SQL 을 PostgreSQL 파드의 psql 로 흘려 넣는다. 만든 계정은 **실제로 로그인된다**
# (enabled · BCrypt $2a$10$ · ROLE_USER). 사람마다 user-created 아웃박스 행을 같은 트랜잭션에 써서, 웰컴 쿠폰 · 채팅 회원
# 복제본이 정상 가입과 같은 길로 생긴다(docs/47 · docs/48).
#
# ── 사용 ────────────────────────────────────────────────────────────────
#
#   ./ops/48-bulk-users.sh estimate                 # 행 수 · 용량 · 이벤트가 다 나가기까지 (기본 10만 명)
#   ./ops/48-bulk-users.sh load                     # 확인 → 비밀번호 준비 → 묶음별 적재(다시 실행하면 이어서) → 결과
#   ./ops/48-bulk-users.sh status                   # 데모 사용자 · 배송지 · 이벤트(STARTED/COMPLETED/FAILED) · 쿠폰 · 채팅 복제본
#   ./ops/48-bulk-users.sh retry-failed             # 발행 실패(FAILED)로 멈춘 데모 user-created 를 다시 STARTED 로
#   GATEWAY=https://<게이트웨이> ./ops/48-bulk-users.sh login-check   # 표본 몇 명이 실제로 로그인되는지(초당 1회 — 로그인 제한)
#   ./ops/48-bulk-users.sh purge                    # 데모 사용자 · 그 흔적 · 쿠폰 · 채팅 복제본만 지운다
#
#   USERS=500000 ./ops/48-bulk-users.sh load        # 개수 바꾸기(purge · status 도 같은 값으로)
#
# 환경 변수(기본값):
#   USERS=100000  ID_OFFSET=7000000000  CHUNK=5000
#   PG_NS=postgres PG_DEPLOY=postgres PG_USER=product USER_DB=userdb COUPON_DB=coupondb CHAT_DB=chatdb
#   PASSWORD_FILE=~/.ecommerce-msa/demo-users.password   # 데모 비밀번호(0600, 저장소 밖). 없으면 무작위로 만든다
#   DEMO_PASSWORD=…        # 직접 정하려면(파일 대신). 화면에는 길이만 나온다
#   NO_ADDRESSES=1  NO_EVENTS=1   # 배송지 · user-created 를 빼기(이벤트를 빼면 쿠폰 · 채팅 복제본이 없다 — docs/47)
#   SAMPLE=5  INSECURE=0          # login-check 표본 수 · 자체 서명 인증서 허용(실습 클러스터만)
#   FORCE=1   백업 시간대(03:00~05:00 UTC) 경고를 무시     YES=1   확인을 묻지 않음
#
# 🔒 데모 계정은 모두 같은 비밀번호다. 공개 사이트(운영)에 만들면 그 비밀번호 하나로 수만 계정에 들어갈 수 있다 — 운영
#    클러스터에서는 데모가 끝나는 대로 purge 한다. 비밀번호는 화면 · 명령줄 · 셸 기록에 남기지 않는다(파일 0600 · stdin).
#    이메일은 demo<번호>@demo.invalid — 메일이 어디에도 가지 않는다(RFC 2606).
set -euo pipefail

USERS="${USERS:-100000}"
ID_OFFSET="${ID_OFFSET:-7000000000}"
CHUNK="${CHUNK:-5000}"
PG_NS="${PG_NS:-postgres}"
PG_DEPLOY="${PG_DEPLOY:-postgres}"
PG_USER="${PG_USER:-product}"
USER_DB="${USER_DB:-userdb}"
COUPON_DB="${COUPON_DB:-coupondb}"
CHAT_DB="${CHAT_DB:-chatdb}"
PASSWORD_FILE="${PASSWORD_FILE:-$HOME/.ecommerce-msa/demo-users.password}"
SAMPLE="${SAMPLE:-5}"
DOMAIN="demo.invalid"

HERE="$(cd "$(dirname "$0")" && pwd)"
GEN="$HERE/tools/generate-demo-users.py"

die()  { echo "ERROR: $1" >&2; exit 1; }
info() { echo ">>> $*"; }
warn() { echo "  ⚠️  $*" >&2; }
need() { command -v "$1" >/dev/null || die "$1 이 필요합니다"; }

gen() {
    local extra=()
    [ "${NO_ADDRESSES:-0}" = "1" ] && extra+=(--no-addresses)
    [ "${NO_EVENTS:-0}" = "1" ] && extra+=(--no-events)
    python3 -I "$GEN" --users "$USERS" --id-offset "$ID_OFFSET" --chunk "$CHUNK" ${extra[@]+"${extra[@]}"} "$@"
}

# psql 을 PostgreSQL 파드 안에서 — SQL 은 stdin 으로(ops/42 · ops/47 과 같다). DB 비밀번호가 필요 없다.
psql_db() {   # psql_db <db> [psql 인자…]
    local db="$1"; shift
    kubectl -n "$PG_NS" exec -i "deploy/$PG_DEPLOY" -- psql -X -q -U "$PG_USER" -d "$db" -v ON_ERROR_STOP=1 "$@"
}

confirm() {   # confirm <질문>
    [ "${YES:-0}" = "1" ] && return 0
    printf '  %s [y/N] ' "$1" >&2
    local a; read -r a
    [ "$a" = "y" ] || [ "$a" = "Y" ] || die "그만둡니다"
}

check_window() {
    local h; h=$(date -u +%H)
    if [ "$h" = "03" ] || [ "$h" = "04" ]; then
        [ "${FORCE:-0}" = "1" ] || die "백업 시간대(03:00~05:00 UTC)다 — 끝난 뒤에, 또는 FORCE=1"
        warn "백업 시간대지만 FORCE=1 — 그대로 간다"
    fi
}

# 앱과 같은 BCrypt(강도 10, 접두사 없음)로 — ops/30 과 같은 방법. 평문은 stdin 으로만 지나간다.
# spring-security-crypto 의 **클래스** jar — Gradle 캐시에는 IDE 가 받은 -sources · -javadoc jar 도 같은 이름 앞부분으로 있다.
# 🐛 예전: find | head -1 이 그것을 집으면 클래스가 없어 "package org.springframework.security.crypto.bcrypt does not exist" 로
#    해시를 만들지 못했다(2026-10-17, IDE 가 있는 기계). 판이 여럿이면 가장 새것, BCrypt.class 가 든 것만.
# [JA] Gradle キャッシュには IDE が落とした -sources · -javadoc の jar もある。以前は head -1 がそれを拾い、クラスがなく失敗した。
crypto_jar() {
    local j
    find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches" -name 'spring-security-crypto-*.jar' \
            ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name '*-tests.jar' 2>/dev/null | sort -rV \
        | while read -r j; do
            if command -v unzip >/dev/null 2>&1; then
                unzip -Z1 "$j" 2>/dev/null | grep -qx 'org/springframework/security/crypto/bcrypt/BCrypt.class' || continue
            elif command -v jar >/dev/null 2>&1; then
                jar tf "$j" 2>/dev/null | grep -qx 'org/springframework/security/crypto/bcrypt/BCrypt.class' || continue
            fi
            echo "$j"; break
        done
}

bcrypt_hash() {
    local jar workdir rc
    jar=$(crypto_jar)
    [ -n "$jar" ] || die "spring-security-crypto 의 클래스 jar 를 찾지 못했다(-sources · -javadoc 은 제외) — 먼저 ./gradlew :user-service:bootJar 로 의존성을 받는다"
    need java
    workdir=$(mktemp -d)
    cat > "$workdir/Bc.java" <<'JAVA'
import org.springframework.security.crypto.bcrypt.BCrypt;
import java.io.*;
public class Bc {
    public static void main(String[] args) throws Exception {
        String pw = new BufferedReader(new InputStreamReader(System.in)).readLine();
        if (pw == null || pw.isEmpty()) { System.err.println("입력이 없다"); System.exit(1); }
        String hash = BCrypt.hashpw(pw, BCrypt.gensalt(10));
        if (!BCrypt.checkpw(pw, hash)) { System.err.println("자체 검증 실패"); System.exit(1); }
        System.out.println(hash);
    }
}
JAVA
    java -cp "$jar" "$workdir/Bc.java"
    rc=$?
    rm -rf "$workdir"
    return $rc
}

# 데모 비밀번호 — DEMO_PASSWORD 가 있으면 그것, 없으면 파일, 파일도 없으면 무작위로 만들어 파일에 둔다(저장소 밖, 0600).
demo_password() {
    if [ -n "${DEMO_PASSWORD:-}" ]; then
        printf '%s' "$DEMO_PASSWORD"
        return
    fi
    if [ ! -f "$PASSWORD_FILE" ]; then
        local dir; dir="$(dirname "$PASSWORD_FILE")"
        mkdir -p "$dir" && chmod 700 "$dir"
        if git -C "$dir" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
            die "저장소 안에는 비밀번호 파일을 만들지 않는다: $dir — PASSWORD_FILE 을 저장소 밖으로"
        fi
        (umask 077; LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 16 > "$PASSWORD_FILE"; printf '!9' >> "$PASSWORD_FILE")
        echo "  데모 비밀번호를 새로 만들었다 → $PASSWORD_FILE (0600)" >&2
    fi
    [ "$(stat -c '%a' "$PASSWORD_FILE" 2>/dev/null || stat -f '%Lp' "$PASSWORD_FILE")" = "600" ] \
        || die "$PASSWORD_FILE 의 권한이 600 이 아니다 — chmod 600 후 다시"
    cat "$PASSWORD_FILE"
}

range_sql() { echo "user_id BETWEEN $ID_OFFSET AND $((ID_OFFSET + USERS - 1)) AND email LIKE '%@$DOMAIN'"; }

existing() {
    psql_db "$USER_DB" -At -c "SELECT count(*) FROM users.users WHERE $(range_sql)" | tr -d '[:space:]'
}

cmd_estimate() {
    need python3
    gen --estimate
}

cmd_load() {
    need kubectl; need python3
    [ -f "$GEN" ] || die "$GEN 이 없다"
    check_window
    info "대상: $(kubectl config current-context 2>/dev/null || echo '?') · $PG_NS/$PG_DEPLOY · $USER_DB"
    gen --estimate | sed 's/^/    /'
    local have; have="$(existing)" || die "users.users 를 읽지 못했다 — PG_NS · PG_DEPLOY · USER_DB 확인"
    info "이 범위에 이미 있는 데모 사용자: $have 명"
    confirm "데모 사용자 $USERS 명을 만든다(같은 비밀번호). 공개 운영이면 데모가 끝나면 purge 한다 — 진행할까?"

    local pw hash
    pw="$(demo_password)" || die "데모 비밀번호를 준비하지 못했다"
    [ "${#pw}" -ge 8 ] || die "데모 비밀번호는 8자 이상"
    hash="$(printf '%s\n' "$pw" | bcrypt_hash)" || die "해시를 만들지 못했다"
    unset pw
    case "$hash" in \$2a\$10\$*) ;; *) die "예상과 다른 해시 형식: ${hash:0:7}…" ;; esac
    info "비밀번호 해시 준비(BCrypt 10) — 평문은 어디에도 넘기지 않았다"

    local chunks=$(( (USERS + CHUNK - 1) / CHUNK ))
    # 이어서: 꽉 찬 묶음은 건너뛴다(묶음 SQL 자체도 ON CONFLICT DO NOTHING 이라 다시 넣어도 같다).
    local first=$(( have / CHUNK ))
    local i
    for i in $(seq "$first" $((chunks - 1))); do
        DEMO_PASSWORD_HASH="$hash" gen --chunk-index "$i" | psql_db "$USER_DB" >/dev/null \
            || die "묶음 $i 실패 — 같은 명령으로 다시 실행하면 이어서 넣는다"
        printf '\r    묶음 %d/%d' $((i + 1)) "$chunks"
    done
    echo
    psql_db "$USER_DB" -c "ANALYZE users.users; ANALYZE users.shipping_address;" >/dev/null
    cmd_status
    echo "  이벤트(user-created)는 user-service 의 폴러가 보낸다 — 레플리카당 10초에 100건. 빨리 비우려면 docs/79 §4."
}

cmd_status() {
    need kubectl
    info "데모 사용자 (id $ID_OFFSET … $((ID_OFFSET + USERS - 1)), @$DOMAIN)"
    psql_db "$USER_DB" <<SQL
SELECT (SELECT count(*) FROM users.users WHERE $(range_sql)) AS "사용자",
       (SELECT count(*) FROM users.shipping_address a JOIN users.users u USING (user_id)
         WHERE $(range_sql | sed 's/user_id/u.user_id/; s/email/u.email/')) AS "배송지";
SELECT o.outbox_status AS "user-created", count(*)
  FROM users.outbox_event o
 WHERE o.event_type = 'user-created'
   AND o.aggregate_id IN (SELECT user_id::text FROM users.users WHERE $(range_sql))
 GROUP BY 1 ORDER BY 1;
SQL
    local lo="$ID_OFFSET" hi=$((ID_OFFSET + USERS - 1))
    printf '  웰컴 쿠폰(coupon): '
    psql_db "$COUPON_DB" -At -c "SELECT count(*) FROM coupon.coupon WHERE user_id BETWEEN $lo AND $hi" 2>/dev/null || echo "(읽지 못함)"
    printf '  채팅 회원(chat)  : '
    psql_db "$CHAT_DB" -At -c "SELECT count(*) FROM chat.member WHERE member_id BETWEEN $lo AND $hi" 2>/dev/null || echo "(읽지 못함)"
}

cmd_retry_failed() {
    need kubectl
    # 폴러는 발행 실패를 FAILED 로 남기고 다시 집지 않는다(UserOutboxScheduler) — 데모 범위의 것만 되살린다.
    psql_db "$USER_DB" <<SQL
UPDATE users.outbox_event SET outbox_status = 'STARTED'
 WHERE outbox_status = 'FAILED' AND event_type = 'user-created'
   AND aggregate_id IN (SELECT user_id::text FROM users.users WHERE $(range_sql));
SQL
}

cmd_login_check() {
    need curl
    [ -n "${GATEWAY:-}" ] || die "GATEWAY=https://<게이트웨이> 가 필요하다"
    local pw; pw="$(demo_password)" || die "데모 비밀번호가 없다 — load 를 먼저"
    local tls=(); [ "${INSECURE:-0}" = "1" ] && tls=(-k)
    local ok=0 n i idx email code
    info "표본 $SAMPLE 명 로그인 — 게이트웨이의 로그인 제한(초당 1회)에 맞춰 1.2초 간격. 토큰은 출력하지 않는다"
    for i in $(seq 1 "$SAMPLE"); do
        idx=$(( (RANDOM * 32768 + RANDOM) % USERS ))
        email=$(printf 'demo%07d@%s' "$idx" "$DOMAIN")
        # 비밀번호는 본문(stdin)으로만 — 명령줄에 올리지 않는다. 응답 본문 · 헤더(ACCESS)는 버린다.
        code=$(printf '%s\n%s\n' "$email" "$pw" \
            | python3 -I -c 'import json,sys; e,p=sys.stdin.read().split("\n")[:2]; print(json.dumps({"email":e,"password":p}))' \
            | curl -sS ${tls[@]+"${tls[@]}"} -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
                   --data-binary @- "$GATEWAY/user-service/api/auth/public/signin" || echo 000)
        printf '    %-28s %s\n' "$email" "$code"
        [ "$code" = "200" ] && ok=$((ok + 1))
        sleep 1.2
    done
    n="$SAMPLE"
    info "성공 $ok/$n — 429 면 로그인 제한, 401 이면 비밀번호 파일이 load 때와 다르다"
    [ "$ok" = "$n" ]
}

cmd_purge() {
    need kubectl; need python3
    local have; have="$(existing)"
    info "지울 데모 사용자: $have 명 (id $ID_OFFSET … $((ID_OFFSET + USERS - 1)), @$DOMAIN 만)"
    warn "이 사용자들이 낸 주문(orderdb)은 남는다 — 주문 이력은 지우지 않는다"
    confirm "데모 사용자와 그 흔적(배송지 · 기기 · 알림 · 리뷰 · 아직 안 나간 이벤트), 쿠폰 · 채팅 복제본을 지운다 — 진행할까?"
    gen --purge | psql_db "$USER_DB" || die "users 삭제 실패 — 아무것도 지우지 않았다(한 트랜잭션)"
    gen --purge-replicas coupon | psql_db "$COUPON_DB" || warn "쿠폰 복제본을 지우지 못했다"
    gen --purge-replicas chat | psql_db "$CHAT_DB" || warn "채팅 복제본을 지우지 못했다"
    cmd_status
}

case "${1:-estimate}" in
    estimate)     cmd_estimate ;;
    load)         cmd_load ;;
    status)       cmd_status ;;
    retry-failed) cmd_retry_failed ;;
    login-check)  cmd_login_check ;;
    purge)        cmd_purge ;;
    -h|--help)    sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//' ;;
    *)            die "알 수 없는 동작: $1 (estimate | load | status | retry-failed | login-check | purge)" ;;
esac
