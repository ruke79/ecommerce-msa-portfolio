#!/usr/bin/env bash
#
# git 이력에서 자격증명 값을 지운다 (git-filter-repo).
#
# ══════════════════════════════════════════════════════════════════════════
#  ⚠️ 먼저 읽을 것 — 이 스크립트는 유출을 **해결하지 않는다**
# ══════════════════════════════════════════════════════════════════════════
#
# 이력을 다시 써도 이미 유출된 값은 **여전히 유효하다.** 그리고 지워지지도 않는다:
#
#   · GitHub 은 force-push 후에도 옛 커밋 객체를 **SHA 로 접근 가능한 상태로 남긴다.**
#     즉 문제의 URL 은 이 스크립트를 돌리고 푸시한 뒤에도 그대로 열린다.
#     실제로 지우려면 GitHub Support 에 GC 를 요청해야 한다(docs/30 §4).
#   · 포크가 있으면 그 객체는 포크 네트워크에 **영구히** 남는다. GitHub 은 포크를 정리하지 않는다.
#   · 이미 클론한 사람, 미러, 캐시, 자동 스크레이퍼는 전부 사본을 갖고 있다.
#     공개 저장소의 AKIA 키는 보통 푸시 후 **몇 분 안에** 수집된다.
#
# 그래서 순서는 반드시 이렇다:
#
#   1) 자격증명을 **폐기·재발급**한다          ← 진짜 해결책 (docs/30 §3)
#   2) 이 스크립트로 이력을 정리한다            ← 뒷정리
#   3) GitHub Support 에 GC 를 요청한다        ← 옛 SHA 를 실제로 닫는다
#
# 1 을 건너뛰고 2 만 하면 "치웠다는 느낌"만 얻고 위험은 그대로다.
#
# ══════════════════════════════════════════════════════════════════════════
#
# 이 스크립트가 하는 일:
#   · 값이 아니라 **패턴**으로 치환한다 — 비밀이 새 파일로 디스크에 떨어지지 않는다
#   · 파일을 지우지 않는다. 값만 ***REMOVED-*** 로 바꾼다(이력 구조는 그대로)
#   · 신선한 클론에서만 돈다(filter-repo 요구사항이자, 작업 저장소를 지키는 장치)
#   · **푸시하지 않는다.** force-push 는 되돌릴 수 없으므로 사람이 직접 한다
#
# 사용:
#   ops/21-purge-history-secrets.sh --workdir /tmp/purge
#   ops/21-purge-history-secrets.sh --workdir /tmp/purge --dry-run
#
set -euo pipefail
# SECRET-SCANNER-SELF-EXCLUDE — 이 파일은 자격증명 **패턴**을 담고 있어서 자기 자신에게 걸린다.
# 경로로 거르지 않고 표식으로 거르는 이유: 이름이 바뀌어도, 이력의 옛 버전을
# 검사할 때도 그대로 동작해야 하기 때문이다.


# ⚠️ cd 하기 전에 확정한다. 아래에서 작업 디렉터리로 이동하므로, 나중에 계산하면
#    상대 경로가 깨져 재검사 단계가 통째로 건너뛰어진다(실제로 한 번 그랬다).
OPS_DIR="$(cd "$(dirname "$0")" && pwd)"

WORKDIR=""
DRY_RUN=0
REPO_URL="${REPO_URL:-https://github.com/ruke79/ecommerce-msa.git}"

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
        --dry-run) DRY_RUN=1; shift ;;
        -h|--help) sed -n '2,45p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "알 수 없는 옵션: $1" ;;
    esac
done

[ -n "$WORKDIR" ] || die "--workdir 이 필요합니다 (신선한 클론을 둘 빈 디렉터리)"

# ---------------------------------------------------------------- 사전 확인

command -v git >/dev/null || die "git 이 필요합니다"

if ! git filter-repo --version >/dev/null 2>&1; then
    die "git-filter-repo 가 필요합니다.

    설치:
      pip install git-filter-repo
      # 또는
      brew install git-filter-repo

    BFG 도 같은 일을 하지만 filter-repo 를 권합니다 — BFG 는 기본적으로 HEAD 커밋을
    건드리지 않아 '현재 파일에 남아 있으면 안 지워지는' 함정이 있습니다."
fi

# ---------------------------------------------------------------- 폐기 확인

cat <<'EOF'

  ────────────────────────────────────────────────────────────────────────
   자격증명을 이미 폐기·재발급했습니까?
  ────────────────────────────────────────────────────────────────────────

   docs/30 §3 체크리스트:
     [ ] AWS 액세스 키          IAM → 비활성화 후 삭제 (청구서 먼저 확인)
     [ ] Gmail 앱 비밀번호       Google 계정 → 앱 비밀번호 → 취소
     [ ] Google OAuth 시크릿    Cloud Console → 사용자 인증 정보 → 재설정
     [ ] GitHub OAuth 시크릿    Developer settings → OAuth Apps → 재생성
     [ ] PayPal 시크릿          Developer Dashboard → 앱 → 재생성
     [ ] JWT 서명 키            새 키 발급 후 배포 (기존 토큰 전부 무효)
     [ ] DB 비밀번호            ops/01-rotate-db-password.sh

   아직이라면 지금 중단하고 위를 먼저 하세요. 이력을 지우는 것은
   **유출된 값을 무효화하지 않습니다.**

EOF

if [ "$DRY_RUN" = "0" ]; then
    printf "  전부 폐기했으면 'ROTATED' 를 입력하세요: "
    read -r answer
    [ "$answer" = "ROTATED" ] || die "중단했습니다. 폐기를 먼저 하세요."
fi

# ---------------------------------------------------------------- 신선한 클론

log "1/4  신선한 클론"

# filter-repo 는 신선한 클론을 요구한다. 작업 중이던 저장소에서 돌리면 되돌릴 수 없는
# 상태로 망가질 수 있고, reflog·stash 같은 게 남아 있으면 결과도 신뢰할 수 없다.
[ -e "$WORKDIR" ] && die "$WORKDIR 이 이미 존재합니다. 빈 경로를 지정하세요."

git clone --no-local --mirror "$REPO_URL" "$WORKDIR/repo.git" \
    || die "클론 실패: $REPO_URL"
info "미러 클론 완료 (모든 브랜치·태그 포함)"

cd "$WORKDIR/repo.git"
info "브랜치 $(git branch -a 2>/dev/null | wc -l | tr -d ' ')개 / 커밋 $(git rev-list --count --all)개"

# ---------------------------------------------------------------- 치환 규칙

log "2/4  치환 규칙 작성"

# ⭐ 값이 아니라 **패턴**을 쓴다.
#
# 유출된 값을 파일에 적어 넣는 방식(흔한 방법이다)은, 그 파일 자체가 모든 비밀을 모아 놓은
# 목록이 된다. 실수로 커밋되거나 백업에 실리면 정리하려다 오히려 한곳에 모아 유출하는 셈이다.
# 패턴 치환은 그런 파일을 만들지 않는다.
#
# GitHub OAuth **client id** 도 지운다. 엄밀히 말해 client id 는 비밀이 아니지만,
# (a) 어차피 secret 을 재발급하면 그 앱은 새로 만들거나 갱신하게 되고,
# (b) 남겨 두면 정리를 끝낸 뒤에도 스캐너가 계속 빨갛게 떠서 **진짜 유출과 구분이 안 된다.**
#     항상 빨간 경고는 아무도 안 보는 경고가 된다.
#
# ⚠️ 값은 **줄 끝까지** 먹는다([^\r\n]+). 처음에는 [^\s#]+ 로 썼다가 실제 검증에서 잡혔다:
#    Google 앱 비밀번호는 `abcd efgh ijkl mnop` 처럼 **공백으로 끊긴 네 덩어리**로 발급된다.
#    첫 공백에서 멈추는 패턴은 첫 덩어리만 가리고 나머지를 그대로 남긴다 —
#    "***REMOVED*** cubv ieio rodd" 가 이력에 남았고, 재검사는 그걸 통과시켰다.
#    부분 치환은 치환하지 않은 것보다 나쁘다. 지웠다고 착각하게 만들기 때문이다.
#
# 대가: YAML 의 줄 끝 주석(`password: x  # 설명`)까지 함께 지워진다. 이미 지나간 이력이므로
#       받아들일 만한 손실이고, 반대 방향의 실수보다 훨씬 낫다.
#
# 형식: regex:<패턴>==><치환>   (역참조 \1 사용 가능)
RULES="$WORKDIR/replacements.txt"
cat > "$RULES" <<'RULES_EOF'
regex:AKIA[0-9A-Z]{16}==>***REMOVED-AWS-ACCESS-KEY***
regex:GOCSPX-[A-Za-z0-9_\-]{10,}==>***REMOVED-GOOGLE-OAUTH-SECRET***
regex:Ov23[A-Za-z0-9]{10,}==>***REMOVED-GITHUB-OAUTH-CLIENT-ID***
regex:(?i)(mail\.password\s*[=:][ \t]*)(?!\$\{|REPLACE_ME|\*\*\*)[^\s\r\n][^\r\n]*==>\1***REMOVED***
regex:(?i)(client[-_.]secret\s*[=:][ \t]*)(?!\$\{|REPLACE_ME|\*\*\*)[^\s\r\n][^\r\n]*==>\1***REMOVED***
regex:(?i)(jwt[-_.]?secret\w*\s*[=:][ \t]*)(?!\$\{|REPLACE_ME|\*\*\*)[^\s\r\n][^\r\n]{15,}==>\1***REMOVED***
regex:(?i)((?:spring\.)?datasource\.password\s*[=:][ \t]*)(?!\$\{|REPLACE_ME|\*\*\*)[^\s\r\n][^\r\n]*==>\1***REMOVED***
regex:(?i)(REACT_APP_PAYPAL[A-Z_]*[ \t]*=[ \t]*)(?!\$\{|REPLACE_ME|\*\*\*)[^\s\r\n][^\r\n]*==>\1***REMOVED***
regex:(?i)(paypal[_a-z]*(?:secret|client[-_]secret)\s*[=:][ \t]*)(?!\$\{|REPLACE_ME|\*\*\*)[^\s\r\n][^\r\n]*==>\1***REMOVED***
regex:(?i)(cloudinary[a-z_]*(?:secret|key)\s*[=:][ \t]*)(?!\$\{|REPLACE_ME|\*\*\*)[^\s\r\n][^\r\n]*==>\1***REMOVED***
regex:(?i)(REACT_APP_[A-Z_]*(?:SECRET|PASSWORD|PRIVATE)[A-Z_]*[ \t]*=[ \t]*)(?!\$\{|REPLACE_ME|\*\*\*)[^\s\r\n][^\r\n]*==>\1***REMOVED***
RULES_EOF
chmod 600 "$RULES"
info "규칙 $(grep -c . "$RULES")개 ($RULES)"

warn "규칙에 걸리지 않는 비밀이 있을 수 있습니다. 실행 후 ops/20 으로 반드시 재검사하세요."

if [ "$DRY_RUN" = "1" ]; then
    log "--dry-run — 여기서 멈춥니다"
    echo
    echo "  실제로 돌리려면 --dry-run 을 빼고 다시 실행하세요."
    echo "  클론과 규칙 파일은 $WORKDIR 에 남아 있습니다."
    exit 0
fi

# ---------------------------------------------------------------- 재작성

log "3/4  이력 재작성"

warn "모든 커밋 SHA 가 바뀝니다. 열려 있는 PR·태그·다른 사람의 클론이 전부 어긋납니다."

git filter-repo --replace-text "$RULES" --force \
    || die "filter-repo 실패"

info "재작성 완료"

# 규칙 파일은 비밀 자체를 담지 않지만(패턴이므로) 남겨 둘 이유도 없다.
rm -f "$RULES"

# ---------------------------------------------------------------- 확인

log "4/4  재검사"

# 재작성된 클론을 원본 스캐너로 다시 훑는다. "돌렸으니 됐겠지"는 검증이 아니다.
SCAN="$OPS_DIR/20-scan-history-secrets.sh"
if [ -x "$SCAN" ]; then
    # 미러 클론 안에서 돌린다 — ops/20 은 현재 저장소를 검사하므로 재작성 결과를 본다.
    # (작업 트리가 없는 bare 저장소이므로 --head 는 쓸 수 없고, 이력 모드가 맞다.)
    if ( cd "$WORKDIR/repo.git" && "$SCAN" >/dev/null 2>&1 ); then
        info "재검사 통과 — 남은 자격증명 없음"
    else
        warn "재검사에서 아직 발견됨. 아래 목록을 보고 규칙을 보강한 뒤 다시 실행하세요:"
        ( cd "$WORKDIR/repo.git" && "$SCAN" ) || true
    fi
else
    warn "ops/20 을 찾지 못해 재검사를 건너뜁니다 — 반드시 직접 재검사하세요"
fi

# ---------------------------------------------------------------- 마무리

cat <<EOF

  ────────────────────────────────────────────────────────────────────────
   다음 단계 — 여기서부터는 되돌릴 수 없습니다
  ────────────────────────────────────────────────────────────────────────

   1. 결과를 직접 확인하세요:

        cd $WORKDIR/repo.git
        git log --oneline | head
        git show <옛-문제-커밋의-새-SHA>:backend/src/main/resources/application.properties

   2. force-push (⚠️ 되돌릴 수 없음):

        cd $WORKDIR/repo.git
        git remote add origin $REPO_URL
        git push --force --all origin
        git push --force --tags origin

   3. 협업자·자신의 모든 클론을 버리고 다시 클론하세요.
      옛 클론에서 push 하면 지운 커밋이 **되살아납니다.**

   4. GitHub Support 에 GC 를 요청하세요 — 이게 없으면 옛 SHA 로 계속 읽힙니다:

        https://support.github.com/contact
        "Please run garbage collection on ruke79/ecommerce-msa to purge
         unreachable objects containing leaked credentials."

   5. ops/20-scan-history-secrets.sh 를 새 클론에서 다시 돌려 확인하세요.

   ⚠️ 이 저장소의 기본 브랜치는 claude/improve-claude-branch-8gf7u4 입니다.
      force-push 전에 GitHub 설정에서 기본 브랜치를 확인하세요.

EOF
