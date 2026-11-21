#!/usr/bin/env bash
# 운영 액션 63 — 배포 전 수동 백업(~/backups/pre-<버전>-<UTC>) 보관 정리 (4단계 ⑥, docs/22 §2-2)
# [JA] 運用アクション 63 — デプロイ前の手動バックアップ(~/backups/pre-<版>-<UTC>)の保管整理
#
# 배포 전 백업(docs/72 §3 · docs/85 — 물리 · 논리 · Mongo, 하나 약 5.5G)은 지금까지 아무도 지우지 않았다 — '매월 정리' 는 실제로 없었고
# 2026-11-21 에 9개 · 34G 가 쌓여 디스크가 87% 였다. 정책은 사람이 정하고 지우기는 자동으로(Google SRE — 토일 · 데이터 무결성 장):
#
#   남긴다  · 최신 KEEP(기본 2)개 — 나이와 상관없이(방금 뜬 것 · 직전 것)
#           · MIN_AGE_DAYS(기본 7)일이 안 된 것 — 노드 밖 받기(매일)가 그 사이 여러 번 받는다
#           · 이름이 pre-<버전>-<YYYYMMDDTHHMMSSZ> 가 아닌 것 · 심볼릭 링크 · 디렉터리가 아닌 것 — 모르는 것은 지우지 않는다
#   지운다  · 그 밖(오래되고 최신 KEEP 개 밖)
#
# 운영 노드는 노드 밖 사본을 직접 확인하지 못한다 — 받는 쪽 키는 일부러 읽기 전용이다(운영 노드가 뚫려도 사본을 못 지우게).
# 대신 받는 쪽의 실패가 보인다(systemd 타이머 실패 · 'status') — 7일이면 그 사이 매일 받기가 여러 번 돈다.
#
#   ./ops/63-predeploy-backups.sh list             # 무엇이 있고 무엇을 지울지(지우지 않는다)
#   ./ops/63-predeploy-backups.sh prune            # 계산만(dry-run)
#   APPLY=1 ./ops/63-predeploy-backups.sh prune    # 지운다 — ops/28 이 운영 배포 성공 뒤 부른다(PREDEPLOY_PRUNE=0 이면 안 부른다)
#
# 환경 변수: PREDEPLOY_ROOT=~/backups · KEEP=2(1 이상) · MIN_AGE_DAYS=7(1 이상) · APPLY= · NOW_EPOCH(시험)
#
# [JA] 残すもの: 最新 KEEP 個 · MIN_AGE_DAYS 日未満 · 名前の形が違うもの · シンボリックリンクは消さない。ノードは外の写しを直接確認できない
#      (受け取る側の鍵は読み取り専用)— 代わりに受け取る側の失敗が見え、7 日あればその間に毎日の受け取りが何度も回る。
set -euo pipefail

ACTION="${1:-list}"
ROOT="${PREDEPLOY_ROOT:-$HOME/backups}"
KEEP="${KEEP:-2}"
MIN_AGE_DAYS="${MIN_AGE_DAYS:-7}"
NOW="${NOW_EPOCH:-$(date -u +%s)}"

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

[[ "$KEEP" =~ ^[0-9]+$ ]] && [ "$KEEP" -ge 1 ] || die "KEEP 는 1 이상 — 최신 백업은 늘 남긴다"
[[ "$MIN_AGE_DAYS" =~ ^[0-9]+$ ]] && [ "$MIN_AGE_DAYS" -ge 1 ] || die "MIN_AGE_DAYS 는 1 이상"
[ -d "$ROOT" ] || { echo "배포 전 백업 디렉터리가 없다: $ROOT — 정리할 것 없음"; exit 0; }

NAME_RE='^pre-[A-Za-z0-9._-]+-(20[0-9]{2})([0-9]{2})([0-9]{2})T([0-9]{2})([0-9]{2})([0-9]{2})Z$'

# "<epoch> <이름>" — 모양이 맞는 디렉터리만(심볼릭 링크는 -type d 에 걸리지 않는다), 오래된 것부터
# [JA] 形の合うディレクトリだけ(シンボリックリンクは -type d に掛からない)、古い順
entries() {
    local d name
    while IFS= read -r d; do
        name="$(basename "$d")"
        if [[ "$name" =~ $NAME_RE ]]; then
            printf '%s %s\n' "$(date -u -d "${BASH_REMATCH[1]}-${BASH_REMATCH[2]}-${BASH_REMATCH[3]}T${BASH_REMATCH[4]}:${BASH_REMATCH[5]}:${BASH_REMATCH[6]}Z" +%s)" "$name"
        fi
    done < <(find "$ROOT" -mindepth 1 -maxdepth 1 -type d -name 'pre-*' 2>/dev/null)
}

plan() {   # 한 줄에 "<결정> <이름> <나이 일>" — 결정: keep-newest · keep-young · delete ／ [JA] 一行に「<決定> <名前> <日数>」
    local list n i=0 epoch name age
    list="$(entries | sort -n)"
    [ -n "$list" ] || return 0
    n="$(printf '%s\n' "$list" | wc -l | tr -d ' ')"
    while read -r epoch name; do
        i=$((i + 1))
        age=$(( (NOW - epoch) / 86400 ))
        if [ $((n - i)) -lt "$KEEP" ]; then
            echo "keep-newest $name $age"
        elif [ "$age" -lt "$MIN_AGE_DAYS" ]; then
            echo "keep-young $name $age"
        else
            echo "delete $name $age"
        fi
    done <<< "$list"
}

show() {
    local decision name age size
    printf '%-12s %-48s %5s %8s\n' "결정" "이름" "나이(일)" "크기"
    while read -r decision name age; do
        size="$(du -sh "$ROOT/$name" 2>/dev/null | cut -f1)"
        printf '%-12s %-48s %5s %8s\n' "$decision" "$name" "$age" "${size:--}"
    done < <(plan)
}

prune() {
    local decision name age removed=0 kept=0
    show
    while read -r decision name age; do
        if [ "$decision" != "delete" ]; then kept=$((kept + 1)); continue; fi
        if [ "${APPLY:-}" = "1" ]; then
            # 한 번 더 — 이름 모양 · 디렉터리 · 링크 아님 · ROOT 바로 아래(경로 조작 방지)
            # [JA] もう一度 — 名前の形 · ディレクトリ · リンクでない · ROOT の直下(パス操作の防止)
            [[ "$name" =~ $NAME_RE ]] && [ -d "$ROOT/$name" ] && [ ! -L "$ROOT/$name" ] || { echo "건너뜀(모양이 다르다): $name" >&2; continue; }
            rm -rf -- "${ROOT:?}/$name"
            removed=$((removed + 1))
        fi
    done < <(plan)
    if [ "${APPLY:-}" = "1" ]; then
        echo "배포 전 백업 정리: 지움 $removed · 남김 $kept (최신 $KEEP 개 · ${MIN_AGE_DAYS}일 안 된 것) — 디스크 $(df -h "$ROOT" | awk 'NR==2 {print $5}')"
    else
        echo "dry-run — 지우지 않았다. 지우려면 APPLY=1"
    fi
}

case "$ACTION" in
    list)  show ;;
    prune) prune ;;
    help|-h|--help) sed -n '2,24p' "$0" ;;
    *) die "알 수 없는 동작: $ACTION (list · prune)" ;;
esac
