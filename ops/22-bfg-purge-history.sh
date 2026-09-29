#!/usr/bin/env bash
#
# BFG Repo-Cleaner 로 git 이력에서 비밀이 든 파일을 **삭제**하고, 남은 값들을 치환한다.
#
# SECRET-SCANNER-SELF-EXCLUDE — 이 파일은 자격증명 패턴을 담고 있어 자기 자신에게 걸린다.
#
# ══════════════════════════════════════════════════════════════════════════
#  ⚠️ 이 스크립트도 유출을 **해결하지 않는다** — docs/30 §2
# ══════════════════════════════════════════════════════════════════════════
#
#   · 값은 AWS·Google·PayPal 에서 여전히 유효하다. **폐기·재발급이 유일한 해결책이다.**
#   · GitHub 은 force-push 후에도 옛 객체를 SHA 로 접근 가능하게 남긴다 → Support 에 GC 요청 필요
#   · 이미 스크레이퍼가 수집했다고 전제해야 한다 (Google 이 탐지해 메일을 보냈다면 이미 늦었다)
#
# ══════════════════════════════════════════════════════════════════════════
#
# ops/21 (git-filter-repo) 과 무엇이 다른가:
#
#   ops/21  값만 ***REMOVED*** 로 치환한다. 파일은 남는다 — 이력이 읽히는 상태로 유지된다.
#   ops/22  **파일을 통째로 삭제**한다(BFG --delete-files) + 남은 값은 치환한다.
#
# 어느 쪽이든 결과는 "비밀이 안 보인다"로 같다. 파일을 지우는 쪽이 더 확실하지만, 그 시점의
# 설정이 무엇이었는지도 함께 사라진다. 죽은 설정 파일이므로 여기서는 그 손실을 받아들인다.
#
# ══════════════════════════════════════════════════════════════════════════
#  ⚠️ BFG 의 함정 두 가지 — 모르고 쓰면 안 지워진다
# ══════════════════════════════════════════════════════════════════════════
#
# 1) **BFG 는 파일명으로 매칭한다. 경로가 아니다.**
#    `--delete-files application.properties` 는 저장소 어디에 있든 그 이름의 파일을 전부
#    지운다. 이 저장소의 이력에는 그 이름이 8개 경로에 있다(아래에서 확인시켜 준다).
#    "backend/ 것만 지우겠다"는 BFG 로는 불가능하다 — 그게 필요하면 ops/21 을 쓴다.
#
# 2) **BFG 는 HEAD 커밋을 건드리지 않는다.**
#    현재 커밋에 남아 있는 파일은 보호되어 지워지지 않는다. 즉 <b>작업 트리를 먼저 정리한 뒤에만</b>
#    제대로 동작한다. 이 저장소는 이미 정리돼 있어(HEAD 에 application.properties 0개) 문제없다.
#    이 순서를 모르고 쓰면 "돌렸는데 그대로네"가 된다.
#
# 사용:
#   ops/22-bfg-purge-history.sh --workdir /tmp/bfg
#   ops/22-bfg-purge-history.sh --workdir /tmp/bfg --dry-run
#
set -euo pipefail

OPS_DIR="$(cd "$(dirname "$0")" && pwd)"

WORKDIR=""
DRY_RUN=0
REPO_URL="${REPO_URL:-https://github.com/ruke79/ecommerce-msa.git}"
BFG_JAR="${BFG_JAR:-}"
BFG_VERSION="1.14.0"

# 삭제할 파일명. **경로가 아니라 이름**이다(§BFG 의 함정 1).
DELETE_FILES="${DELETE_FILES:-application.properties}"

log()  { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
info() { printf '  \033[0;32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[0;33m!\033[0m %s\n' "$*"; }
die()  { printf '\n\033[0;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

# ── HEAD 무결성 검사 ────────────────────────────────────────────────────
#
# ⭐ 이 검사가 이 스크립트에서 가장 중요할지도 모른다.
#
# 전제: **현재 HEAD 는 이미 깨끗하다**(그래서 이 도구를 돌리는 것이다). 그렇다면 정리 후에도
# HEAD 의 파일 내용은 **한 글자도 바뀌면 안 된다.** 바뀌었다면 규칙이 과하게 잡은 것이고,
# 그대로 푸시하면 **현재 코드가 망가진 채로 배포된다.**
#
# 실제로 이걸로 잡았다: `jwtSecret: ${JWT_SECRET_KEY}` 가 17자라 JWT 규칙(16자 이상)에 걸려
# `jwtSecret: ***REMOVED***` 로 바뀌었다. 자격증명 스캐너는 `${...}` 를 무시하도록 되어 있어
# **"재검사 통과"라고 보고했다.** 푸시했으면 전 서비스가 기동 불가가 됐을 것이다.
head_integrity_check() {
    local rewritten="$1"
    local original="$2"
    local changed=0

    while IFS= read -r f; do
        [ -z "$f" ] && continue
        local a b
        a=$(git -C "$original"  show "HEAD:$f" 2>/dev/null | git hash-object --stdin 2>/dev/null || echo x)
        b=$(git -C "$rewritten" show "HEAD:$f" 2>/dev/null | git hash-object --stdin 2>/dev/null || echo y)
        if [ "$a" != "$b" ]; then
            [ "$changed" = "0" ] && printf '\n  \033[0;31m🔴 현재 HEAD 의 파일이 바뀌었습니다 — 규칙이 과합니다\033[0m\n'
            printf '       %s\n' "$f"
            changed=$((changed + 1))
        fi
    done < <(git -C "$rewritten" ls-tree -r --name-only HEAD 2>/dev/null)

    if [ "$changed" != "0" ]; then
        return 1
    fi
    return 0
}

while [ $# -gt 0 ]; do
    case "$1" in
        --workdir) WORKDIR="$2"; shift 2 ;;
        --repo)    REPO_URL="$2"; shift 2 ;;
        --files)   DELETE_FILES="$2"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        -h|--help) sed -n '2,50p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "알 수 없는 옵션: $1" ;;
    esac
done

[ -n "$WORKDIR" ] || die "--workdir 이 필요합니다 (신선한 클론을 둘 빈 디렉터리)"

command -v git >/dev/null || die "git 이 필요합니다"
command -v java >/dev/null || die "java 가 필요합니다 (BFG 는 JVM 도구입니다)"

# ---------------------------------------------------------------- BFG 확보

if [ -z "$BFG_JAR" ]; then
    BFG_JAR="$WORKDIR/bfg-${BFG_VERSION}.jar"
fi

if [ ! -f "$BFG_JAR" ]; then
    log "BFG ${BFG_VERSION} 내려받기"
    mkdir -p "$(dirname "$BFG_JAR")"
    curl -fsSL -o "$BFG_JAR" \
        "https://repo1.maven.org/maven2/com/madgag/bfg/${BFG_VERSION}/bfg-${BFG_VERSION}.jar" \
        || die "BFG 내려받기 실패. 직접 받아 BFG_JAR=<경로> 로 지정하세요:
    https://rtyley.github.io/bfg-repo-cleaner/"
    info "$BFG_JAR"
fi
java -jar "$BFG_JAR" --version >/dev/null 2>&1 || die "BFG jar 를 실행할 수 없습니다: $BFG_JAR"

# ---------------------------------------------------------------- 폐기 확인

cat <<'EOF'

  ────────────────────────────────────────────────────────────────────────
   자격증명을 이미 폐기·재발급했습니까?
  ────────────────────────────────────────────────────────────────────────

   docs/30 §3:
     [ ] AWS 액세스 키          IAM → 비활성화 후 삭제 (청구서·CloudTrail 먼저)
     [ ] Gmail 앱 비밀번호       Google 계정 → 앱 비밀번호 → 취소
     [ ] Google OAuth 시크릿    Cloud Console → 사용자 인증 정보 → 재설정
     [ ] GitHub OAuth 시크릿    Developer settings → OAuth Apps → 재생성
     [ ] PayPal 시크릿          Developer Dashboard → 앱 → 재생성
     [ ] JWT 서명 키            새 키 발급 후 배포
     [ ] DB 비밀번호            ops/01-rotate-db-password.sh

   이력에서 파일을 지워도 위 값들은 무효화되지 않습니다.

EOF

if [ "$DRY_RUN" = "0" ]; then
    printf "  전부 폐기했으면 'ROTATED' 를 입력하세요: "
    read -r answer
    [ "$answer" = "ROTATED" ] || die "중단했습니다. 폐기를 먼저 하세요."
fi

# ---------------------------------------------------------------- 1. 미러 클론

log "1/5  신선한 미러 클론"

[ -e "$WORKDIR/repo.git" ] && die "$WORKDIR/repo.git 이 이미 존재합니다. 빈 경로를 지정하세요."
mkdir -p "$WORKDIR"

git clone --no-local --mirror "$REPO_URL" "$WORKDIR/repo.git" || die "클론 실패: $REPO_URL"
info "커밋 $(git -C "$WORKDIR/repo.git" rev-list --count --all)개"

# 재작성 전 상태를 하나 더 떠 둔다. 5단계의 HEAD 무결성 검사가 이것과 대조한다 —
# 비교 기준이 없으면 "현재 코드가 그대로인가"를 확인할 방법이 없다.
git clone --no-local --mirror "$WORKDIR/repo.git" "$WORKDIR/original.git" >/dev/null 2>&1 \
    || die "비교용 클론 실패"

# ---------------------------------------------------------------- 2. 영향 범위

log "2/5  삭제 대상 확인 (BFG 는 이름으로 매칭한다)"

for name in $DELETE_FILES; do
    echo "  '$name' 이 이력에서 존재한 경로:"
    git -C "$WORKDIR/repo.git" log --all --pretty=format: --name-only 2>/dev/null \
        | grep -E "(^|/)${name}$" | sort -u | sed 's/^/      /'
done

echo
echo "  현재 HEAD 에 남아 있는 것 (BFG 가 보호해 지우지 못하는 것):"
STILL_IN_HEAD=0
for name in $DELETE_FILES; do
    found=$(git -C "$WORKDIR/repo.git" ls-tree -r --name-only HEAD 2>/dev/null \
            | grep -E "(^|/)${name}$" || true)
    if [ -n "$found" ]; then
        echo "$found" | sed 's/^/      🔴 /'
        STILL_IN_HEAD=1
    fi
done
[ "$STILL_IN_HEAD" = "0" ] && info "없음 — BFG 가 이력 전체에서 지울 수 있습니다"

if [ "$STILL_IN_HEAD" = "1" ]; then
    die "위 파일들이 현재 커밋에 남아 있습니다.
    BFG 는 HEAD 를 보호하므로 **이 상태로는 지워지지 않습니다.**
    먼저 그 파일들을 지우고 커밋·푸시한 뒤 다시 실행하세요."
fi

if [ "$DRY_RUN" = "1" ]; then
    log "--dry-run — 여기서 멈춥니다"
    echo "  클론은 $WORKDIR/repo.git 에 남아 있습니다."
    exit 0
fi

# ---------------------------------------------------------------- 3. 파일 삭제

log "3/5  BFG — 파일 삭제"

for name in $DELETE_FILES; do
    java -jar "$BFG_JAR" --delete-files "$name" --no-blob-protection "$WORKDIR/repo.git" \
        || die "BFG --delete-files 실패: $name"
    info "삭제: $name"
done

# --no-blob-protection 을 쓰는 이유: 위에서 HEAD 에 대상 파일이 없음을 이미 확인했다.
# 보호 기능을 켜 두면 BFG 가 매번 "protected commit" 경고를 내며 일부를 건너뛸 수 있는데,
# 무엇이 건너뛰어졌는지 조용히 지나가는 편이라 "지웠다고 생각했는데 남아 있는" 상태가 된다.

# ---------------------------------------------------------------- 4. 남은 값 치환

log "4/5  BFG — 남은 값 치환"

# 파일 삭제만으로는 부족하다. AWS 키는 *.yml 에, PayPal 은 .env 에 있다 —
# application.properties 를 지워도 그것들은 그대로 남는다.
#
# 값이 아니라 **패턴**으로 치환한다(ops/21 과 같은 이유: 유출값을 파일에 모아 적으면
# 그 파일이 모든 비밀을 한곳에 담은 목록이 된다).
RULES="$WORKDIR/replacements.txt"
cat > "$RULES" <<'RULES_EOF'
regex:AKIA[0-9A-Z]{16}==>***REMOVED-AWS-ACCESS-KEY***
regex:GOCSPX-[A-Za-z0-9_\-]{10,}==>***REMOVED-GOOGLE-OAUTH-SECRET***
regex:Ov23[A-Za-z0-9]{10,}==>***REMOVED-GITHUB-OAUTH-CLIENT-ID***
regex:(?i)(mail\.password\s*[=:][ \t]*)(?!\$\{|REPLACE_ME|\*\*\*)[^\s\r\n][^\r\n]*==>$1***REMOVED***
regex:(?i)(client[-_.]secret\s*[=:][ \t]*)(?!\$\{|REPLACE_ME|\*\*\*)[^\s\r\n][^\r\n]*==>$1***REMOVED***
regex:(?i)(jwt[-_.]?secret\w*\s*[=:][ \t]*)(?!\$\{|REPLACE_ME|\*\*\*)[^\s\r\n][^\r\n]{15,}==>$1***REMOVED***
regex:(?i)((?:spring\.)?datasource\.password\s*[=:][ \t]*)(?!\$\{|REPLACE_ME|\*\*\*)[^\s\r\n][^\r\n]*==>$1***REMOVED***
regex:(?i)(REACT_APP_PAYPAL[A-Z_]*[ \t]*=[ \t]*)(?!\$\{|REPLACE_ME|\*\*\*)[^\s\r\n][^\r\n]*==>$1***REMOVED***
regex:(?i)(paypal[_a-z]*(?:secret|client[-_]secret)\s*[=:][ \t]*)(?!\$\{|REPLACE_ME|\*\*\*)[^\s\r\n][^\r\n]*==>$1***REMOVED***
regex:(?i)(cloudinary[a-z_]*(?:secret|key)\s*[=:][ \t]*)(?!\$\{|REPLACE_ME|\*\*\*)[^\s\r\n][^\r\n]*==>$1***REMOVED***
regex:(?i)(REACT_APP_[A-Z_]*(?:SECRET|PASSWORD|PRIVATE)[A-Z_]*[ \t]*=[ \t]*)(?!\$\{|REPLACE_ME|\*\*\*)[^\s\r\n][^\r\n]*==>$1***REMOVED***
RULES_EOF
chmod 600 "$RULES"

# ⚠️ 역참조가 filter-repo 와 다르다. BFG 는 Scala/Java 정규식이라 **$1** 을 쓴다.
#    filter-repo(Python)는 \1 이다. 여기에 \1 을 쓰면 치환문에 리터럴 "\1" 이 박히고,
#    비밀은 지워지지만 결과가 망가진다 — 그래서 5단계 재검사가 반드시 필요하다.
# ⚠️ 여기서는 --no-blob-protection 을 **쓰지 않는다.**
#
# BFG 의 기본 동작은 "현재 HEAD 에 있는 blob 은 건드리지 않는다"이고, 그게 정확히 우리가
# 원하는 것이다 — HEAD 는 이미 깨끗하므로 정리 과정에서 **바뀌면 안 된다.**
#
# 처음에 이 보호를 껐다가 정확히 그 사고를 냈다: `jwtSecret: ${JWT_SECRET_KEY}` 가 17자라
# JWT 규칙(16자 이상)에 걸려 `jwtSecret:***REMOVED***` 가 됐다. 자격증명 스캐너는 ${...} 를
# 무시하도록 되어 있어 "재검사 통과"로 보고했고, 그대로 푸시했으면 전 서비스가 기동 불가가
# 됐을 것이다. 보호를 켜면 이력의 옛 blob 만 치환되고 현재 파일은 그대로 남는다.
#
# BFG 가 "protected commit 에 아직 비밀이 있다"고 경고할 수 있는데, 우리 경우엔 오탐이다
# (HEAD 의 값은 ${...} 플레이스홀더다). 5단계 HEAD 무결성 검사가 이를 확정해 준다.
java -jar "$BFG_JAR" --replace-text "$RULES" "$WORKDIR/repo.git" \
    || die "BFG --replace-text 실패"
rm -f "$RULES"
info "치환 완료"

# ---------------------------------------------------------------- 5. 정리 + 재검사

log "5/5  참조 정리 + 재검사"

# BFG 는 객체를 남겨 둔다. reflog 를 만료시키고 GC 해야 실제로 사라진다.
git -C "$WORKDIR/repo.git" reflog expire --expire=now --all
git -C "$WORKDIR/repo.git" gc --prune=now --aggressive >/dev/null 2>&1
info "GC 완료"

# ① HEAD 가 그대로인가 — 과잉 치환을 잡는다(위 함수 주석 참조).
if head_integrity_check "$WORKDIR/repo.git" "$WORKDIR/original.git"; then
    info "HEAD 무결성 통과 — 현재 코드는 변경되지 않았습니다"
else
    die "정리 규칙이 현재 코드까지 바꿨습니다. 이 상태로 푸시하면 서비스가 망가집니다.
    규칙을 좁힌 뒤(예: \${...} 참조 제외) 다시 실행하세요."
fi

# ② 자격증명이 남았는가
SCAN="$OPS_DIR/20-scan-history-secrets.sh"
if [ -x "$SCAN" ]; then
    if ( cd "$WORKDIR/repo.git" && "$SCAN" >/dev/null 2>&1 ); then
        info "재검사 통과 — 남은 자격증명 없음"
    else
        warn "재검사에서 아직 발견됨:"
        ( cd "$WORKDIR/repo.git" && "$SCAN" ) || true
        warn "규칙을 보강한 뒤 다시 실행하세요. 이 상태로 푸시하지 마세요."
    fi
else
    warn "ops/20 을 찾지 못해 재검사를 건너뜁니다 — 반드시 직접 재검사하세요"
fi

cat <<EOF

  ────────────────────────────────────────────────────────────────────────
   다음 단계 — 여기서부터는 되돌릴 수 없습니다
  ────────────────────────────────────────────────────────────────────────

   1. 결과 확인:

        cd $WORKDIR/repo.git
        git log --all --pretty=format: --name-only | grep application.properties | sort -u
        # 아무것도 안 나와야 합니다

   2. force-push (⚠️ 모든 커밋 SHA 가 바뀝니다):

        cd $WORKDIR/repo.git
        git push --force --all origin
        git push --force --tags origin

      ※ --mirror 클론이므로 origin 이 이미 설정돼 있습니다.
        \`git push --mirror\` 는 원격의 다른 참조까지 지우므로 쓰지 마세요.

   3. 기존 클론을 전부 버리고 다시 클론하세요.
      옛 클론에서 push 하면 지운 커밋이 **되살아납니다.**

   4. GitHub Support 에 GC 요청 — 이게 없으면 옛 SHA 로 계속 읽힙니다:

        https://support.github.com/contact
        "Please run garbage collection on ruke79/ecommerce-msa to purge
         unreachable objects containing leaked credentials."

   5. Settings → Code security → **Push protection** 을 켜세요.

EOF
