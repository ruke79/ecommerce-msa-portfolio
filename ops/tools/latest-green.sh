#!/usr/bin/env bash
#
# 기본 브랜치의 가장 최근 "초록" 커밋 — build.yml 의 push 실행이 성공으로 끝난 것(= 이미지 발행 · 릴리스 후보까지, docs/36) 의 SHA 12자.
# ops/28 의 `latest` 가 부른다(4단계 ② — 배포할 SHA 를 Actions 화면에서 찾아 옮겨 적는 손일을 없앤다).
# [JA] 既定ブランチの最も新しい「緑」のコミット — build.yml の push 実行が成功したものの SHA 12 文字。ops/28 の latest が呼ぶ。
#
#   ops/tools/latest-green.sh                 # → a1b2c3d4e5f6
#   BRANCH=… WORKFLOW=build.yml API=https://api.github.com ops/tools/latest-green.sh
#
# 토큰: GITHUB_TOKEN, 없으면 git 자격 증명 저장소(github.com) — 운영 노드는 git fetch 에 쓰는 그 자격. **출력하지 않는다**.
# 필요한 권한: Actions 읽기(비공개 저장소). 실패하면 종료 코드 1 · 이유는 stderr.
# [JA] トークン: GITHUB_TOKEN、なければ git の資格情報ストア。出力しない。必要な権限: Actions の読み取り。
set -euo pipefail

BRANCH="${BRANCH:-main}"
WORKFLOW="${WORKFLOW:-build.yml}"
API="${API:-https://api.github.com}"

die() { printf 'latest-green: %s\n' "$*" >&2; exit 1; }

remote="$(git remote get-url origin 2>/dev/null)" || die "origin 원격이 없다"
# https://github.com/owner/repo(.git) · git@github.com:owner/repo(.git) · https://user@github.com/owner/repo
repo="$(printf '%s' "$remote" | sed -E 's#^(https?://([^@/]+@)?github\.com/|git@github\.com:)##; s#\.git$##; s#/+$##')"
case "$repo" in */*) ;; *) die "origin 이 GitHub 저장소가 아니다: $remote" ;; esac

token="${GITHUB_TOKEN:-}"
if [ -z "$token" ]; then
    token="$(printf 'protocol=https\nhost=github.com\n\n' | GIT_TERMINAL_PROMPT=0 git credential fill 2>/dev/null \
        | sed -n 's/^password=//p' || true)"
fi
[ -n "$token" ] || die "GitHub 토큰이 없다 — GITHUB_TOKEN 을 주거나 git 자격 증명(github.com)을 둔다"

branch_q="$(python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$BRANCH")"
url="$API/repos/$repo/actions/workflows/$WORKFLOW/runs?branch=$branch_q&event=push&status=success&per_page=1"

# 토큰은 curl 의 설정(표준 입력)으로만 넘긴다 — 명령줄(ps)에 남기지 않는다.
# [JA] トークンは curl の設定(標準入力)でだけ渡す — コマンドライン(ps)に残さない。
body="$(printf 'header = "Authorization: Bearer %s"\n' "$token" \
    | curl -fsS --max-time 20 -K - -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28" "$url")" \
    || die "GitHub API 호출 실패(권한 · 네트워크) — $API/repos/$repo/actions/workflows/$WORKFLOW"

sha="$(printf '%s' "$body" | python3 -c '
import json, re, sys
runs = json.load(sys.stdin).get("workflow_runs") or []
s = runs[0].get("head_sha", "") if runs else ""
print(s[:12] if re.fullmatch(r"[0-9a-f]{40}", s) else "")
')"
[ -n "$sha" ] || die "$BRANCH 에 성공한 $WORKFLOW push 실행이 없다"
printf '%s\n' "$sha"
