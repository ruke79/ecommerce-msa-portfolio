#!/usr/bin/env bash
# 운영 액션 ㉚ — 관리자 계정 부트스트랩 (첫 관리자 · 비밀번호 재설정 · 활성화)
#
# ── 왜 스크립트인가 ─────────────────────────────────────────────────────
#
# "관리자를 만들려면 관리자가 필요하다"는 순환이 있다. `AdminController` 는 클래스 전체가
# {@code @PreAuthorize("hasRole('ROLE_ADMIN')")} 라서, 관리자가 0명이면 API 로는 관리자를
# 만들 수 없다. 가입 폼에서 role 을 받던 경로는 **보안 수정으로 막혔다** — 예전에는 요청
# 본문에 "admin" 한 줄이면 관리자가 됐다(UserServiceImpl 주석 참조).
#
# 실무에서도 이 순환은 UI 가 아니라 **배포 경로**로 끊는다: Grafana 의
# GF_SECURITY_ADMIN_PASSWORD, Keycloak 의 KEYCLOAK_ADMIN, Django 의 createsuperuser.
# 공개 URL 에 "관리자 등록 화면"을 두지 않는 것이 핵심이다 — 어떤 초대코드로 가려도
# 결국 유출되고, 그 화면 자체가 영구적인 공격면이 된다.
#
# 이 스크립트는 그 중 CLI 계열이다. 두 번째 관리자부터는 이걸 쓰지 말고 기존 관리자가
# 화면에서 승격해야 한다(감사 기록이 남는 경로가 그쪽이다).
#
# ── 비밀번호를 어떻게 다루나 ────────────────────────────────────────────
#
#   · 인자로 받지 않는다. argv 는 `ps` 로 다른 사용자에게 보이고 셸 히스토리에도 남는다.
#   · 화면에 표시하지 않는다(read -s).
#   · 해시는 **앱이 쓰는 것과 같은 BCrypt** 로 만든다(spring-security-crypto,
#     BCryptPasswordEncoder 기본값 = strength 10). 다른 도구로 만든 해시를 넣으면
#     로그인만 조용히 실패하고 원인이 화면에 드러나지 않는다.
#   · 평문은 어디에도 기록하지 않는다 — 로그·파일·임시파일 전부.
#
# ── 사용 ────────────────────────────────────────────────────────────────
#
#   ./ops/30-admin-account.sh status                    # 계정·역할·활성화 상태 (기본)
#   ./ops/30-admin-account.sh promote  <이메일>          # ROLE_ADMIN 으로 승격 + 활성화
#   ./ops/30-admin-account.sh password <이메일>          # 비밀번호 재설정(대화형 입력)
#   ./ops/30-admin-account.sh enable   <이메일>          # 활성화·잠금 해제만
#
#   PG_NS=postgres PG_DEPLOY=postgres PG_USER=product PG_DB=userdb   # 기본값
#
# ⚠️ 승격·재설정 뒤에는 반드시 **로그아웃 → 재로그인**할 것. 이미 발급된 JWT 에는 옛 역할이
#    박혀 있어서, 화면은 열리는데 저장할 때만 403 이 난다(AdminWriteInterceptor 가
#    User-Role 헤더로 판정한다). 증상이 원인을 가리는 대표적인 경우다.
set -uo pipefail

ACTION="${1:-status}"
EMAIL="${2:-}"

PG_NS="${PG_NS:-postgres}"
PG_DEPLOY="${PG_DEPLOY:-postgres}"
PG_USER="${PG_USER:-product}"
PG_DB="${PG_DB:-userdb}"

ok()   { echo "  ✅ $1"; }
info() { echo "  ℹ️  $1"; }
warn() { echo "  ⚠️  $1" >&2; }
die()  { echo "ERROR: $1" >&2; exit 1; }

command -v kubectl >/dev/null || die "kubectl 이 필요합니다"
command -v java    >/dev/null || die "java 가 필요합니다 (해시 생성에 씁니다)"

# psql 을 파드 안에서 돌린다. SQL 은 stdin 으로, 값은 -v 로 넘긴다.
#
# 값을 SQL 문자열에 이어 붙이지 않는 이유: 이메일에 작은따옴표가 들어가면 문장이 깨지고,
# 최악의 경우 주입이 된다. psql 의 :'name' 은 값을 제대로 인용해 준다.
psql_in() {
    local extra=()
    [ -n "${EMAIL:-}" ] && extra+=(-v "email=$EMAIL")
    [ -n "${HASH:-}"  ] && extra+=(-v "hash=$HASH")
    kubectl -n "$PG_NS" exec -i "deploy/$PG_DEPLOY" -- \
        psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 "${extra[@]}" "$@"
}

require_email() {
    [ -n "$EMAIL" ] || die "이메일을 지정하세요:  $0 $ACTION <이메일>"
}

# ---------------------------------------------------------------- 상태

status() {
    echo "════ 계정 상태 ($PG_NS/$PG_DEPLOY → $PG_DB) ════"
    # ⚠️ 토큰 테이블 이름의 오타(verfication)는 엔티티·마이그레이션에 함께 박혀 있어
    #    실제 이름이 그렇다. 고치려면 둘을 같이 옮겨야 한다.
    psql_in <<'SQL'
SELECT u.user_id,
       u.email,
       u.enabled,
       u.account_non_locked AS 잠금해제,
       -- 2FA 가 켜져 있으면 로그인이 1단계에서 멈추고 코드를 요구한다. SMS·메일이 닿지
       -- 않는 환경에서는 그 지점에서 영영 막히는데, 화면에는 "로그인 실패"로만 보인다.
       u.is_two_factor_enabled AS 이중인증,
       COALESCE(r.role_name, '(없음)') AS 역할,
       (SELECT count(*) FROM users.verfication_token t WHERE t.user_id = u.user_id) AS 미인증토큰
  FROM users.users u
  LEFT JOIN users.roles r ON r.role_id = u.role_id
 ORDER BY u.user_id;
SQL
    echo
    echo "  ROLE_ADMIN 이 하나도 없으면:  $0 promote <이메일>"
}

# ---------------------------------------------------------------- 승격

promote() {
    require_email
    # 역할 행이 없으면 승격할 대상 자체가 없다. V3 마이그레이션이 넣는다.
    local roles
    roles=$(psql_in -At -c "SELECT count(*) FROM users.roles WHERE role_name = 'ROLE_ADMIN'" 2>/dev/null)
    [ "${roles:-0}" = "1" ] || die "users.roles 에 ROLE_ADMIN 행이 없습니다 — V3 마이그레이션이 적용됐는지 확인하세요"

    # 승격과 함께 계정을 연다. 인증 메일을 못 받은 계정이 enabled=false 로 남아 있으면
    # 역할만 올려도 로그인 자체가 막힌다.
    EMAIL="$EMAIL" psql_in <<'SQL'
UPDATE users.users
   SET role_id = (SELECT role_id FROM users.roles WHERE role_name = 'ROLE_ADMIN'),
       enabled = true,
       account_non_locked = true,
       account_non_expired = true,
       credentials_non_expired = true
 WHERE email = :'email'
RETURNING user_id, email, enabled, role_id;
SQL
}

# ---------------------------------------------------------------- 활성화

enable_account() {
    require_email
    EMAIL="$EMAIL" psql_in <<'SQL'
UPDATE users.users
   SET enabled = true,
       account_non_locked = true,
       account_non_expired = true,
       credentials_non_expired = true
 WHERE email = :'email'
RETURNING user_id, email, enabled;
SQL
}

# ---------------------------------------------------------------- 비밀번호

# 앱과 같은 BCrypt 로 해시를 만든다. BCryptPasswordEncoder 의 생성자는 commons-logging 을
# 끌어오므로, 의존성이 없는 저수준 BCrypt 클래스를 직접 쓴다(결과는 동일한 $2a$10$ 형식).
bcrypt_hash() {   # stdin 으로 평문을 받아 stdout 으로 해시를 낸다
    local jar workdir
    jar=$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches" -name 'spring-security-crypto-*.jar' 2>/dev/null | head -1)
    [ -n "$jar" ] || die "spring-security-crypto jar 를 찾지 못했습니다 — 먼저 ./gradlew :user-service:bootJar 로 의존성을 받으세요"

    workdir=$(mktemp -d)
    cat > "$workdir/Bc.java" <<'JAVA'
import org.springframework.security.crypto.bcrypt.BCrypt;
import java.io.*;
public class Bc {
    public static void main(String[] args) throws Exception {
        String pw = new BufferedReader(new InputStreamReader(System.in)).readLine();
        if (pw == null) { System.err.println("입력이 없습니다"); System.exit(1); }
        String hash = BCrypt.hashpw(pw, BCrypt.gensalt(10));
        if (!BCrypt.checkpw(pw, hash)) { System.err.println("자체 검증 실패"); System.exit(1); }
        System.out.println(hash);
    }
}
JAVA
    # 소스에 비밀번호는 들어가지 않지만(stdin 으로 받는다) 임시물은 남기지 않는다.
    java -cp "$jar" "$workdir/Bc.java"
    local rc=$?
    rm -rf "$workdir"
    return $rc
}

set_password() {
    require_email

    local pw1 pw2 hash
    printf '  새 비밀번호: ' >&2; read -rs pw1; echo >&2
    printf '  다시 입력  : ' >&2; read -rs pw2; echo >&2
    [ "$pw1" = "$pw2" ] || die "두 입력이 다릅니다"
    [ -n "$pw1" ] || die "비어 있습니다"
    # 길이만 본다. 복잡도 정책은 앱(회원가입 검증)이 정할 일이고, 여기서 다시 정의하면
    # 두 곳의 규칙이 어긋난다.
    [ "${#pw1}" -ge 8 ] || die "8자 이상이어야 합니다"

    hash=$(printf '%s\n' "$pw1" | bcrypt_hash) || die "해시 생성에 실패했습니다"
    unset pw1 pw2
    case "$hash" in
        \$2a\$10\$*) ;;
        *) die "예상과 다른 해시 형식입니다: ${hash:0:7}…" ;;
    esac

    # 해시는 psql 변수(-v)로 넘어가므로 kubectl 의 argv 에는 들어간다. 평문이 아니라
    # **해시**이고 어차피 DB 에 저장될 값이라 그대로 둔다 — 평문만은 argv 에 절대 올리지 않는다.
    # 🔒 password_changed_at 을 함께 세운다(V10).
    #
    #    앱의 비밀번호 변경·재설정은 이 값을 세우고, RefreshTokenController 가 이보다 먼저
    #    발급된 리프레시 토큰을 거절한다. 여기서 빠뜨리면 이 경로로만 **기존 세션이 살아남아**
    #    앱과 동작이 갈린다 — 계정을 도둑맞아 관리자가 대신 비밀번호를 바꿔 주는 경우가
    #    바로 이 스크립트를 쓰는 상황이라 더 그렇다.
    #
    #    액세스 토큰은 서명만으로 검증되므로 남은 수명(기본 15분) 동안은 살아 있다.
    EMAIL="$EMAIL" HASH="$hash" psql_in <<'SQL'
UPDATE users.users
   SET password = :'hash',
       password_changed_at = now(),
       credentials_non_expired = true
 WHERE email = :'email'
RETURNING user_id, email, '비밀번호 변경됨' AS 결과;
SQL
}

case "$ACTION" in
    status)   status ;;
    promote)  promote;  warn "로그아웃 → 재로그인해야 새 역할이 토큰에 실립니다" ;;
    password) set_password; ok "완료 — 새 비밀번호로 로그인하세요" ;;
    enable)   enable_account ;;
    *)        die "ACTION 은 status | promote | password | enable 입니다 (받은 값: $ACTION)" ;;
esac
