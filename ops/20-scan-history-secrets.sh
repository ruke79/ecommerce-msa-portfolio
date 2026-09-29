#!/usr/bin/env bash
#
# git 이력 전체에서 자격증명을 찾는다. **값은 절대 출력하지 않는다.**
#
# 왜 필요한가: 작업 트리를 깨끗하게 만드는 것과 이력을 깨끗하게 만드는 것은 전혀 다른 일이다.
# 이 저장소는 docs/13 · ops/01 로 작업 트리에서 비밀을 걷어냈지만, **이력에는 그대로 남아 있었다.**
# 공개 저장소이므로 커밋 SHA 만 알면 누구나 브라우저로 읽을 수 있다.
#
#   https://github.com/ruke79/ecommerce-msa/blob/<SHA>/backend/src/main/resources/application.properties
#
# ⚠️ 이 스크립트는 **탐지만** 한다. 발견되면 할 일은 두 가지이고, 순서가 중요하다:
#      1) ops/README.md 의 폐기 체크리스트대로 **자격증명을 폐기·재발급**한다  ← 진짜 해결책
#      2) ops/21-purge-history-secrets.sh 로 이력을 정리한다                  ← 뒷정리
#    2 를 먼저 하고 1 을 미루면 아무것도 해결되지 않는다. 이력을 지워도 이미 유출된 값은
#    유효한 채로 남기 때문이다(docs/30 §2).
#
# 사용:
#   ops/20-scan-history-secrets.sh            # 이력 전체
#   ops/20-scan-history-secrets.sh --head     # 현재 체크아웃만 (빠름, 커밋 전 점검용)
#   ops/20-scan-history-secrets.sh --remote   # ⭐ GitHub 의 refs/pull/* 까지 — 정리 후 반드시
#   ops/20-scan-history-secrets.sh --range A..B  # 그 범위의 커밋이 들인 blob 만 — CI 가 푸시·PR 마다(docs/57 §6)
#
set -euo pipefail
# SECRET-SCANNER-SELF-EXCLUDE — 이 파일은 자격증명 **패턴**을 담고 있어서 자기 자신에게 걸린다.
# 경로로 거르지 않고 표식으로 거르는 이유: 이름이 바뀌어도, 이력의 옛 버전을
# 검사할 때도 그대로 동작해야 하기 때문이다.


# 이미 어떤 git 저장소 안에서 실행 중이면 **거기를** 검사한다.
#
# 자기 저장소로 cd 하지 않는 이유: ops/21 이 재작성한 클론을 이 스크립트로 다시 검사한다.
# 무조건 자기 부모로 이동하면 그때 원본 저장소를 검사하게 되어, "정리했다"는 확인이
# 정리되지 않은 저장소를 보고 나오는 셈이 된다 — 검증으로서 최악의 실패다.
if ! git rev-parse --git-dir >/dev/null 2>&1; then
    cd "$(cd "$(dirname "$0")/.." && pwd)"
fi

MODE="history"
RANGE=""
case "${1:-}" in
    --head)   MODE="head" ;;
    --remote) MODE="remote" ;;
    # 범위 모드: "현재 트리" 만 보면 PR 안에서 넣었다가 다음 커밋에서 지운 비밀을 놓친다 — 그 커밋은 머지되면
    # 이력에 그대로 남는다. 이력 전체는 매번 돌리기에 무겁다. 이번에 들어온 커밋들이 만든 blob 만 본다.
    --range)  MODE="range"; RANGE="${2:-}" ;;
esac

log()  { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
info() { printf '  \033[0;32m✓\033[0m %s\n' "$*"; }
bad()  { printf '  \033[0;31m🔴\033[0m %s\n' "$*"; }
die()  { printf '\n\033[0;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

command -v git >/dev/null || die "git 이 필요합니다"
command -v python3 >/dev/null || die "python3 이 필요합니다"

# 얕은 클론이면 이력의 대부분이 없다. 그걸 모르고 "깨끗하다"고 결론 내면 최악이다.
if [ "$MODE" = "history" ] && [ "$(git rev-parse --is-shallow-repository)" = "true" ]; then
    die "얕은 클론입니다 — 이력의 일부만 있어 검사 결과를 믿을 수 없습니다.
    먼저: git fetch --unshallow"
fi

# ── 원격 모드: GitHub 의 refs/pull/* 까지 본다 ───────────────────────────
#
# ⭐ 이 모드가 없으면 **"정리했다"가 거짓말이 된다.**
#
# 이력을 재작성해 force-push 해도 GitHub 은 닫힌 PR 의 참조(refs/pull/N/head)를 그대로
# 유지한다. 그 참조는 옛 커밋을 붙잡고 있고, **클라이언트는 지울 수도 덮어쓸 수도 없다**
# (푸시하면 403). 즉 브랜치를 아무리 깨끗하게 만들어도 파일은 계속 읽힌다.
#
# 실제로 이 저장소에서 그렇게 됐다: 브랜치 5개를 전부 force-push 한 뒤에도
# refs/pull/1~4/head 에서 문제의 커밋과 파일이 그대로 조회됐다(docs/30 §4.5).
#
# 일반 클론은 refs/pull 을 가져오지 않으므로 평소 검사로는 보이지 않는다. 명시적으로 받는다.
if [ "$MODE" = "remote" ]; then
    REMOTE_URL="${2:-$(git remote get-url origin 2>/dev/null || true)}"
    [ -n "$REMOTE_URL" ] || die "원격 URL 을 찾을 수 없습니다: ops/20 --remote <url>"

    TMP_REMOTE="$(mktemp -d)"
    trap 'rm -rf "$TMP_REMOTE"' EXIT
    log "원격 검사 — refs/pull/* 포함해서 새로 받습니다"
    git clone -q --mirror "$REMOTE_URL" "$TMP_REMOTE/repo.git" \
        || die "클론 실패: $REMOTE_URL"
    n=$(git -C "$TMP_REMOTE/repo.git" for-each-ref --format='%(refname)' refs/pull/ | wc -l | tr -d ' ')
    info "PR 참조 ${n}개를 포함해 검사합니다"
    cd "$TMP_REMOTE/repo.git"
    MODE="history"
fi

if [ "$MODE" = "range" ]; then
    [ -n "$RANGE" ] || die "범위가 필요합니다: ops/20 --range <base>..<head>"
    git rev-list --quiet "$RANGE" >/dev/null 2>&1 || die "범위를 읽을 수 없습니다(얕은 클론이면 fetch-depth: 0): $RANGE"
fi

log "자격증명 검사 (${MODE}${RANGE:+ $RANGE})"

MODE="$MODE" RANGE="$RANGE" python3 - <<'PY'
import os, re, subprocess, collections, sys

MODE = os.environ["MODE"]
RANGE = os.environ.get("RANGE", "")

# ⚠️ 구분자 뒤는 `[ \t]*` 다. `\s*` 로 쓰면 **줄바꿈까지 먹어** 값이 빈 줄일 때
#    다음 줄 내용을 값으로 오인한다. 실제로 `#  client-secret:` 이라는 주석 한 줄이
#    바로 아래 줄을 값으로 물어 유출로 보고됐다.
#
# 패턴은 **오탐보다 미탐을 더 두려워하게** 잡는다. 놓친 비밀 하나가 계정 탈취로 이어지는 반면,
# 오탐은 사람이 1초 보고 넘기면 된다. 다만 완전히 무의미한 오탐(${PLACEHOLDER}, secretKeyRef:)은
# 아래 IGNORE 로 걸러 낸다 — 노이즈가 많으면 아무도 이 스크립트를 안 돌린다.
PATTERNS = {
    "AWS access key":          (rb'AKIA[0-9A-Z]{16}',                          "🔴 최우선 — 공개 저장소의 AKIA 키는 자동 스크레이퍼가 몇 분 만에 찾아 채굴에 쓴다"),
    "Google OAuth secret":     (rb'GOCSPX-[A-Za-z0-9_\-]{10,}',                "🔴 앱 사칭 가능"),
    "GitHub OAuth client id":  (rb'\bOv23[A-Za-z0-9]{10,}',                    "   id 자체는 비밀이 아니지만, 같은 파일에 secret 이 있었다는 신호"),
    "Slack/Discord webhook":   (rb'https://hooks\.slack\.com/services/\S+|https://discord(app)?\.com/api/webhooks/\S+', "인증 자료다"),
    "Private key block":       (rb'-----BEGIN [A-Z ]*PRIVATE KEY-----',        "🔴 개인키는 절대 커밋 금지"),
    "SMTP/메일 비밀번호":        (rb'(?i)mail\.password\s*[=:][ \t]*(?!\$\{)[^\r\n]{4,}', "🔴 Gmail 앱 비밀번호면 본인 주소로 피싱 발송이 가능하다"),
    # 🐛 값을 `[^\r\n]{8,}`(줄 끝까지 아무거나)로 봤다 — 설정 파일 주석의 설명문("client-secret = 앱의 Client Secret …")과
    #    문서의 표(docs/33 LINE 행)까지 비밀로 잡아, CI 의 현재 트리 검사가 카카오 · LINE 로그인 커밋(b96a11e) 이후 계속 실패했다.
    #    실제 client secret 은 공백 없는 ASCII 토큰이다 — JSON password 패턴과 같은 규칙으로 값을 본다(공백 · 비ASCII · <> 제외).
    "OAuth client-secret":     (rb'(?i)client[-_.]secret\s*[=:][ \t]*(?!\$\{)["\']?[^\s"\'<>*\x80-\xff]{8,}', "🔴"),
    "JWT secret":              (rb'(?i)jwt[-_.]?secret\w*\s*[=:][ \t]*(?!\$\{)\S{16,}', "🔴 아무 사용자로든 토큰을 위조할 수 있다"),
    "DB password":             (rb'(?i)(datasource\.password|spring\.datasource\.password)\s*[=:][ \t]*(?!\$\{)[^\r\n]{4,}', "ops/01 로테이션 대상"),
    # 🐛 위 패턴은 properties/yml 모양만 봤다. Debezium 커넥터 JSON 의 `"database.password": "…"` 은
    #    키가 따옴표로 닫혀 `password"` 가 되어 걸리지 않았다 — 그래서 "자격증명 0건" 이 나왔는데
    #    커밋 740f81a 의 커넥터 4개에 product 암호가 그대로 있었다(docs/30 §6). 따옴표 키 전부를 본다.
    #    값은 **ASCII 토큰**만 본다 — 공백·비ASCII(번역 문구 "비밀번호"·"パスワード")·`<자리표시>`·`****`(마스킹
    #    결과)는 비밀이 아니다. 이걸 거르지 않으면 i18n 파일마다 7건씩 빨갛게 떠서 아무도 안 돌린다.
    "JSON 안의 password":       (rb'(?i)"[\w.\-]*password"\s*:\s*"(?!\$\{)[^"\s<>*\x80-\xff]{6,}"', "ops/01 로테이션 대상 — 커넥터·설정 JSON"),
    "PayPal secret":           (rb'(?i)paypal[^\n]{0,40}secret\s*[=:][ \t]*(?!\$\{)\S{8,}', "🔴 결제"),
    "Cloudinary 자격증명":       (rb'(?i)cloudinary[a-z_]*(?:secret|key)\s*[=:][ \t]*(?!\$\{)\S{8,}', "🔴 미디어 업로드/삭제 권한"),
    # ⭐ REACT_APP_* 에 든 비밀은 종류를 불문하고 문제다.
    #    Create React App 은 이 접두사가 붙은 값을 **번들에 그대로 박아** 배포한다.
    #    즉 git 이력뿐 아니라 **사이트를 연 모든 방문자**에게 노출됐다는 뜻이다.
    #    이건 이력을 정리해도 되돌아오지 않는다 — 폐기 외에 방법이 없다.
    "REACT_APP_* 안의 비밀":     (rb'(?i)REACT_APP_[A-Z_]*(?:SECRET|PASSWORD|PRIVATE)[A-Z_]*\s*=[ \t]*(?!\$\{)\S{8,}', "🔴 번들에 박혀 공개 배포됐다"),
}

# 명백한 비밀이 아닌 것들. 값이 아니라 **구조**를 보고 거른다.
#
# 오탐을 남겨 두면 안 되는 이유: 매번 같은 3건이 빨갛게 뜨는 스캐너는 아무도 안 돌린다.
# 그리고 결국 "어차피 오탐이겠지" 하고 진짜 유출을 넘기게 된다.
#
# `0123456789…` 는 개발·테스트용 더미 키다(ops/12, ops/13, ChatStompIntegrationTest).
# 실제 비밀이 이런 모양일 리 없고, 이 값들은 의도적으로 저장소에 있어야 한다 —
# 테스트가 결정적으로 돌려면 고정된 키가 필요하기 때문이다.
IGNORE = re.compile(rb'(?i)REPLACE_ME|CHANGE_?ME|example\.com|changeit|localhost|'
                    rb'secretKeyRef|secretName|password\.file|your[-_]|xxx+|\.\.\.|'
                    rb'0123456789012345|abcdef0123|local-dev|dry-run|test-?pw|hunter2|'
                    rb'\$\{|\$\(')

# ops/21 이 남기는 치환 표식. **값 전체가 표식일 때만** 넘긴다.
#
# ⚠️ 그냥 `***REMOVED` 를 IGNORE 에 넣으면 안 된다. 부분 치환된 값
#    (`password=***REMOVED*** cubv ieio rodd`)까지 통과시켜, 비밀이 절반 남아 있는데
#    "정리 완료"라고 보고하게 된다. 실제로 그렇게 만들었다가 검증에서 잡았다.
#    그래서 표식이 **줄 끝**에 있을 때만 무시한다.
REDACTED_ONLY = re.compile(rb'\*\*\*REMOVED[A-Z0-9_-]*\*\*\*\s*$')

SELF = b"SECRET-SCANNER-SELF-EXCLUDE"

def scan(data):
    # 스캐너·정리 스크립트 자신은 건너뛴다. 패턴 정의가 곧 패턴에 걸리므로,
    # 그러지 않으면 이 도구는 **영원히 자기 자신을 유출로 보고**한다.
    if SELF in data:
        return []
    found = []
    for name, (rx, note) in PATTERNS.items():
        for m in re.finditer(rx, data):
            frag = m.group(0)
            if IGNORE.search(frag) or REDACTED_ONLY.search(frag):
                continue
            found.append(name)
            break
    return found

hits = collections.defaultdict(set)      # 종류 -> {blob}
blob_paths = {}

if MODE == "head":
    files = subprocess.run(['git','ls-files'], capture_output=True, text=True).stdout.split('\n')
    for f in files:
        if not f: continue
        try:
            data = open(f,'rb').read()
        except OSError:
            continue
        for name in scan(data):
            hits[name].add(f)
            blob_paths[f] = f
elif MODE == "range":
    # 범위의 커밋이 가리키는 객체 중 blob 만 — 경로도 함께 온다("sha path").
    listed = subprocess.run(['git','rev-list','--objects', RANGE], capture_output=True, text=True).stdout
    candidates = {}
    for line in listed.splitlines():
        parts = line.split(' ', 1)
        if len(parts) == 2:
            candidates.setdefault(parts[0], parts[1])
    kinds = subprocess.run(['git','cat-file','--batch-check=%(objecttype) %(objectname)'],
                           input='\n'.join(candidates), capture_output=True, text=True).stdout
    blobs = [l.split()[1] for l in kinds.splitlines() if l.startswith('blob ')]
    print(f"  blob {len(blobs)}개 검사 중...", file=sys.stderr)
    for sha in blobs:
        p = subprocess.run(['git','cat-file','-p',sha], capture_output=True)
        if b'\0' in p.stdout[:8000]:
            continue
        for name in scan(p.stdout):
            hits[name].add(sha)
            blob_paths[sha] = candidates[sha]
else:
    # 모든 blob 을 한 번씩만 읽는다. 커밋마다 훑으면 같은 blob 을 수백 번 읽게 된다.
    shas = subprocess.run(
        ['git','cat-file','--batch-all-objects','--batch-check=%(objecttype) %(objectname)'],
        capture_output=True, text=True).stdout
    blobs = [l.split()[1] for l in shas.splitlines() if l.startswith('blob ')]
    print(f"  blob {len(blobs)}개 검사 중...", file=sys.stderr)

    for sha in blobs:
        p = subprocess.run(['git','cat-file','-p',sha], capture_output=True)
        # 바이너리는 건너뛴다. 소스가 아닌 곳의 우연한 일치는 의미가 없다.
        if b'\0' in p.stdout[:8000]:
            continue
        for name in scan(p.stdout):
            hits[name].add(sha)

    if hits:
        allsha = {s for v in hits.values() for s in v}
        out = subprocess.run(['git','rev-list','--objects','--all'],
                             capture_output=True, text=True).stdout
        for line in out.splitlines():
            parts = line.split(' ', 1)
            if len(parts) == 2 and parts[0] in allsha:
                blob_paths.setdefault(parts[0], parts[1])

if not hits:
    print("\n  \033[0;32m✓ 자격증명이 발견되지 않았습니다\033[0m")
    sys.exit(0)

print()
for name, (rx, note) in PATTERNS.items():
    where = hits.get(name)
    if not where:
        continue
    print(f"  \033[0;31m🔴 {name}\033[0m  — {len(where)}곳")
    if note.strip():
        print(f"       {note}")
    paths = sorted({blob_paths.get(w, w) for w in where})
    for p in paths[:6]:
        print(f"       {p}")
    if len(paths) > 6:
        print(f"       … 외 {len(paths)-6}개 경로")
    print()

print("\033[1;33m  다음 할 일 — 순서가 중요합니다\033[0m")
print("    1) 자격증명을 폐기·재발급한다   ← 진짜 해결책 (docs/30 §3)")
print("    2) ops/21-purge-history-secrets.sh 로 이력을 정리한다  ← 뒷정리")
print()
print("  이력을 지워도 이미 유출된 값은 유효합니다. 1 을 건너뛰면 아무것도 해결되지 않습니다.")
sys.exit(2)
PY
