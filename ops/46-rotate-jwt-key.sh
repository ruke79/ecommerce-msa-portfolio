#!/usr/bin/env bash
#
# 운영 액션 ㊻ — JWT 서명 키를 **아무도 로그아웃시키지 않고** 바꾼다(docs/68 §17). 값은 출력하지 않는다.
# [JA] 運用アクション ㊻ — JWT 署名鍵を誰もログアウトさせずに交換する。値は出力しない。
#
# 왜: 키가 하나(JWT_SECRET_KEY)일 때는 바꾸는 순간 그 전의 모든 토큰이 무효 — 전원 로그아웃이었다. 그래서 키가 샌 것이
# 의심돼도 미루게 된다. 이제 토큰 머리에 kid 를 적고, 검증자(gateway · user · chat)는 그 이름으로 키를 찾는다.
#
# 순서 — 하나씩, 사이에 배포가 끝나야 한다:
#   ① add       새 키를 링(JWT_SIGNING_KEYS)에 넣고 세 서비스를 재시작한다. 서명은 아직 옛 키 — 모두가 새 키를 "알게" 될 뿐이다.
#   ② activate  서명 키(JWT_ACTIVE_KID)를 새 키로. 옛 토큰은 옛 키로 계속 검증된다.
#               ①의 재시작이 세 서비스 모두 끝났는지 확인한 뒤에만 한다 — 새 키를 모르는 검증자가 남아 있으면 새 토큰을 거절한다.
#   ③ retire    옛 키로 서명된 토큰이 모두 만료된 뒤(전환 후 리프레시 절대 수명 = 30일) 링에서 뺀다. legacy(JWT_SECRET_KEY)는
#               JWT_ACCEPT_LEGACY=false 로 은퇴시킨다 — JWT_SECRET_KEY 값 자체는 지우지 않는다(다른 키를 파생하는 뿌리).
#
# 값은 인자 · 로그 · 화면에 남지 않는다 — 생성은 셸 변수, 클러스터에는 stdin(kubectl patch --patch-file /dev/stdin),
# 로컬 k8s/20-secret.yaml 에는 환경 변수로 넘겨 같은 값을 적는다(ops/27 --only file 이 어긋남을 보지 않게).
#
# 사용:
#   ./ops/46-rotate-jwt-key.sh status
#   ./ops/46-rotate-jwt-key.sh add [kid]          # kid 기본값: kYYYYMMDD
#   ./ops/46-rotate-jwt-key.sh activate <kid>
#   ./ops/46-rotate-jwt-key.sh retire <kid|legacy> [--force]   # --force: 30일을 기다리지 않는다(키가 샜을 때 — 그 키의 토큰은 즉시 무효)
#   --dry-run 을 붙이면 무엇을 할지만 보여 준다.
set -euo pipefail

NS="${NAMESPACE:-ecommerce}"
SECRET="${SECRET:-ecommerce-secrets}"
SECRET_FILE="${SECRET_FILE:-k8s/20-secret.yaml}"
DEPLOYS="${DEPLOYS:-user-service gateway-server chat-service}"
# 전환한 시각 — retire 가 "옛 키의 토큰이 다 만료됐나" 를 판단한다.
ACTIVATED_ANN="ecommerce.io/jwt-activated-at"
# 리프레시 토큰의 절대 수명(JWT_REFRESH_ABSOLUTE_MS 와 같아야 한다) — 기본 30일.
ABSOLUTE_SEC="${ABSOLUTE_SEC:-2592000}"

die()  { echo "ERROR: $1" >&2; exit 1; }
step() { echo; echo "▶ $*"; }
need() { command -v "$1" >/dev/null || die "$1 이 필요합니다"; }

DRY=0; FORCE=0; ARGS=()
for a in "$@"; do
    case "$a" in
        --dry-run) DRY=1 ;;
        --force)   FORCE=1 ;;
        -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
        *)         ARGS+=("$a") ;;
    esac
done
CMD="${ARGS[0]:-status}"
ARG="${ARGS[1]:-}"

need kubectl

secret_value() {   # secret_value KEY — 화면에 찍지 않고 값만 돌려준다(호출하는 쪽이 변수에 담는다)
    kubectl -n "$NS" get secret "$SECRET" -o "jsonpath={.data.$1}" 2>/dev/null | base64 -d 2>/dev/null || true
}

kids_of() {        # kids_of "kid=비밀,kid2=비밀2" → 이름만
    printf '%s' "$1" | tr ',' '\n' | sed -n 's/^[[:space:]]*\([^=[:space:]]*\)=.*/\1/p'
}

patch_secret() {   # patch_secret KEY VALUE — 값은 stdin 으로만
    local b64; b64="$(printf '%s' "$2" | base64 | tr -d '\n')"
    printf '{"data":{"%s":"%s"}}' "$1" "$b64" \
        | kubectl -n "$NS" patch secret "$SECRET" --type merge --patch-file /dev/stdin >/dev/null
}

sync_file() {      # sync_file KEY VALUE — 로컬 20-secret.yaml 도 같은 값으로(없으면 건너뛴다)
    [ -f "$SECRET_FILE" ] || { echo "  · $SECRET_FILE 이 없다 — 클러스터만 바꿨다"; return 0; }
    command -v python3 >/dev/null || { echo "  ⚠️ python3 가 없어 $SECRET_FILE 은 손으로 맞춰야 한다($1)"; return 0; }
    K="$1" V="$2" F="$SECRET_FILE" python3 - <<'EOF'
import os, re
k, v, f = os.environ["K"], os.environ["V"], os.environ["F"]
s = open(f, encoding="utf-8").read()
line = f'  {k}: "{v}"'
pat = re.compile(rf'^\s*{re.escape(k)}:.*$', re.M)
if pat.search(s):
    s = pat.sub(lambda _: line, s, count=1)
elif re.search(r'^stringData:\s*$', s, re.M):
    s = re.sub(r'^(stringData:\s*\n)', lambda m: m.group(1) + line + "\n", s, count=1, flags=re.M)
else:
    raise SystemExit(f"{f} 에 stringData: 가 없다 — {k} 를 손으로 넣어야 한다")
open(f, "w", encoding="utf-8").write(s)
EOF
    echo "  · $SECRET_FILE 의 $1 도 맞췄다(값은 출력하지 않음)"
}

restart_all() {
    for d in $DEPLOYS; do
        kubectl -n "$NS" rollout restart "deploy/$d" >/dev/null || die "deploy/$d 가 없다"
    done
    for d in $DEPLOYS; do
        kubectl -n "$NS" rollout status "deploy/$d" --timeout=300s >/dev/null 2>&1 \
            || die "$d 롤아웃이 끝나지 않았다 — 키 설정이 틀리면 기동이 멈춘다(로그의 [jwt] · JwtKeyRing 문구). 다음 단계로 가지 말 것"
        echo "  ✅ $d"
    done
}

secret_changed_at() {   # Secret 이 마지막으로 바뀐 시각(epoch)
    local t
    t="$(kubectl -n "$NS" get secret "$SECRET" -o jsonpath='{range .metadata.managedFields[*]}{.time}{"\n"}{end}' | sort | tail -1)"
    [ -n "$t" ] && date -d "$t" +%s 2>/dev/null || echo 0
}

all_pods_newer_than() {  # 세 서비스의 모든 파드가 그 시각 뒤에 떴나(= 지금 Secret 을 읽었나)
    local since="$1" d started bad=""
    for d in $DEPLOYS; do
        local sel; sel="$(kubectl -n "$NS" get deploy "$d" -o jsonpath='{.spec.selector.matchLabels.app}')"
        for started in $(kubectl -n "$NS" get pods -l "app=$sel" -o jsonpath='{range .items[*]}{.status.startTime}{"\n"}{end}'); do
            [ "$(date -d "$started" +%s)" -ge "$since" ] || bad="$bad $d"
        done
    done
    [ -z "$bad" ] || { echo "$bad" | tr ' ' '\n' | sort -u | tr '\n' ' '; return 1; }
}

KEYS="$(secret_value JWT_SIGNING_KEYS)"
ACTIVE="$(secret_value JWT_ACTIVE_KID)"; ACTIVE="${ACTIVE:-legacy}"
LEGACY_OK="$(secret_value JWT_ACCEPT_LEGACY)"; LEGACY_OK="${LEGACY_OK:-true}"

case "$CMD" in
status)
    echo "서명 키(active): $ACTIVE"
    echo "아는 키: legacy$( [ "$LEGACY_OK" = false ] && echo '(은퇴)') $(kids_of "$KEYS" | tr '\n' ' ')"
    at="$(kubectl -n "$NS" get secret "$SECRET" -o "jsonpath={.metadata.annotations.ecommerce\.io/jwt-activated-at}" 2>/dev/null || true)"
    if [ -n "$at" ]; then
        left=$(( at + ABSOLUTE_SEC - $(date +%s) ))
        [ "$left" -gt 0 ] && echo "마지막 전환: $(date -d "@$at" '+%F %T') — 옛 키 은퇴까지 $(( (left + 86399) / 86400 ))일" \
                          || echo "마지막 전환: $(date -d "@$at" '+%F %T') — 옛 키를 은퇴시켜도 된다"
    fi
    if stale=$(all_pods_newer_than "$(secret_changed_at)"); then
        echo "세 서비스 모두 지금 Secret 으로 떠 있다 ✅"
    else
        echo "⚠️ 지금 Secret 을 아직 읽지 않은 서비스: $stale — 재시작이 필요하다"
    fi
    ;;

add)
    need openssl
    KID="${ARG:-k$(date +%Y%m%d)}"
    [[ "$KID" =~ ^[A-Za-z0-9._-]{1,32}$ ]] || die "kid 는 영문 · 숫자 · . _ - 1~32자"
    [ "$KID" != legacy ] || die "legacy 는 JWT_SECRET_KEY 의 이름이다"
    kids_of "$KEYS" | grep -qx "$KID" && die "$KID 는 이미 링에 있다"
    if [ "$DRY" = 1 ]; then
        echo "① $KID 를 JWT_SIGNING_KEYS 에 더하고(비밀은 새로 만든다) $DEPLOYS 재시작 — 서명 키는 그대로($ACTIVE)"; exit 0
    fi
    step "① 새 키 $KID 를 링에 더한다 — 서명은 아직 $ACTIVE"
    NEW="$(openssl rand -hex 32)"
    [ "${#NEW}" -eq 64 ] || die "키 생성 실패"
    NEXT="${KEYS:+$KEYS,}$KID=$NEW"
    patch_secret JWT_SIGNING_KEYS "$NEXT"
    sync_file JWT_SIGNING_KEYS "$NEXT"
    unset NEW NEXT
    step "세 서비스 재시작 — 모두가 $KID 를 알게 한다"
    restart_all
    echo
    echo "✅ 다음: ./ops/46-rotate-jwt-key.sh activate $KID"
    ;;

activate)
    KID="$ARG"; [ -n "$KID" ] || die "activate <kid>"
    [ "$KID" = legacy ] || kids_of "$KEYS" | grep -qx "$KID" || die "$KID 가 링에 없다 — 먼저 add"
    [ "$KID" != "$ACTIVE" ] || die "이미 $KID 로 서명하고 있다"
    if ! stale=$(all_pods_newer_than "$(secret_changed_at)"); then
        die "아직 지금 Secret 을 읽지 않은 서비스가 있다: $stale — 그 상태에서 전환하면 그 서비스가 새 토큰을 거절한다. 재시작이 끝난 뒤 다시"
    fi
    if [ "$DRY" = 1 ]; then echo "② 서명 키 $ACTIVE → $KID, $DEPLOYS 재시작"; exit 0; fi
    step "② 서명 키 $ACTIVE → $KID"
    patch_secret JWT_ACTIVE_KID "$KID"
    sync_file JWT_ACTIVE_KID "$KID"
    kubectl -n "$NS" annotate secret "$SECRET" --overwrite "$ACTIVATED_ANN=$(date +%s)" >/dev/null
    restart_all
    echo
    echo "✅ 이제 새 토큰은 $KID 로 서명된다. 옛 키($ACTIVE)는 $(( ABSOLUTE_SEC / 86400 ))일 뒤: ./ops/46-rotate-jwt-key.sh retire $ACTIVE"
    ;;

retire)
    KID="$ARG"; [ -n "$KID" ] || die "retire <kid|legacy>"
    [ "$KID" != "$ACTIVE" ] || die "$KID 로 지금 서명하고 있다 — 먼저 다른 키로 activate"
    at="$(kubectl -n "$NS" get secret "$SECRET" -o "jsonpath={.metadata.annotations.ecommerce\.io/jwt-activated-at}" 2>/dev/null || true)"
    if [ "$FORCE" != 1 ]; then
        [ -n "$at" ] || die "전환 기록이 없다 — 이 키로 서명된 토큰이 아직 살아 있을 수 있다. 정말이면 --force(그 키의 토큰은 즉시 무효 = 그 사용자들 로그아웃)"
        left=$(( at + ABSOLUTE_SEC - $(date +%s) ))
        [ "$left" -le 0 ] || die "전환 뒤 $(( (left + 86399) / 86400 ))일이 더 지나야 한다 — 그 전에 빼면 그 키로 로그인한 사용자가 로그아웃된다. 키가 샜다면 --force"
    fi
    if [ "$KID" = legacy ]; then
        [ "$LEGACY_OK" != false ] || die "legacy 는 이미 은퇴했다"
        if [ "$DRY" = 1 ]; then echo "③ JWT_ACCEPT_LEGACY=false, $DEPLOYS 재시작"; exit 0; fi
        step "③ legacy(JWT_SECRET_KEY) 은퇴 — 그 키로 서명된 토큰을 거절한다(값은 지우지 않는다)"
        patch_secret JWT_ACCEPT_LEGACY false
        sync_file JWT_ACCEPT_LEGACY false
    else
        kids_of "$KEYS" | grep -qx "$KID" || die "$KID 가 링에 없다"
        if [ "$DRY" = 1 ]; then echo "③ JWT_SIGNING_KEYS 에서 $KID 를 빼고 $DEPLOYS 재시작"; exit 0; fi
        step "③ $KID 를 링에서 뺀다"
        NEXT="$(printf '%s' "$KEYS" | tr ',' '\n' | grep -v "^[[:space:]]*$KID=" | paste -sd, -)"
        patch_secret JWT_SIGNING_KEYS "$NEXT"
        sync_file JWT_SIGNING_KEYS "$NEXT"
        unset NEXT
    fi
    restart_all
    echo
    echo "✅ $KID 은퇴. 상태: ./ops/46-rotate-jwt-key.sh status"
    ;;

*)
    sed -n '2,30p' "$0"; exit 1 ;;
esac
unset KEYS
