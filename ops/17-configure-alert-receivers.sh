#!/usr/bin/env bash
# 경보 수신 채널 설정 + **실제로 도달하는지 증명**.
#
# 왜 필요한가
# -----------
# 이 저장소에는 경보 규칙 9종이 있고 발화까지 실측했다(docs/12). 그런데 수신 채널이
# 플레이스홀더 주석이라 **경보가 아무 데도 가지 않았다.** 대시보드는 초록불이고 규칙도
# 잘 돌지만, 새벽에 서비스가 죽으면 아무도 모른다. 가장 나쁜 종류의 "설정된 것처럼 보이는
# 미설정"이다.
#
# 이 스크립트가 하는 일:
#   ① 웹훅 URL 을 커밋되지 않는 파일에 쓴다(URL 자체가 인증 수단이므로 설정이 아니라 비밀이다)
#   ② k8s 를 쓰면 같은 값으로 Secret 을 만든다
#   ③ Alertmanager 설정을 재적재하고 문법을 검증한다
#   ④ **테스트 경보를 실제로 밀어 넣어** 채널에 도착하는지 확인한다 — 이게 핵심이다.
#      설정 파일이 그럴듯한 것과 알림이 도착하는 것은 다른 문제다.
#
# 채널은 Discord 다(2026-10-20 — observability/alertmanager.yml 의 discord_configs, Alertmanager 0.28+).
# Discord: 서버 설정 → 연동 → 웹후크 → 새 웹후크 → 채널 고르기 → "웹후크 URL 복사".
#
# 사용법 — 주소를 명령줄에 적지 않는다(셸 기록 · ps 에 남는다). 비워 두면 묻는다(입력은 화면에 안 보인다):
#   sudo ./ops/17-configure-alert-receivers.sh          # root 여야 비밀 파일을 Alertmanager(uid 65534)가 읽게 둘 수 있다
#   (warning 채널을 따로 두려면 두 번째 질문에 다른 주소, 같은 채널이면 Enter)
#
#   MODE=verify ./ops/17-configure-alert-receivers.sh      # 설정만 점검 (URL 불필요)
#   MODE=test   ./ops/17-configure-alert-receivers.sh      # 테스트 경보 발사
#   K8S=1 NAMESPACE=observability ./ops/17-configure-alert-receivers.sh   # Secret 도 생성

set -euo pipefail

MODE="${MODE:-configure}"
ALERTMANAGER_URL="${ALERTMANAGER_URL:-http://localhost:9093}"
SECRET_DIR="${SECRET_DIR:-observability/secrets}"
K8S="${K8S:-0}"
NAMESPACE="${NAMESPACE:-observability}"
# prom/alertmanager 컨테이너가 도는 UID. 시크릿 파일을 읽을 수 있어야 한다.
ALERTMANAGER_UID="${ALERTMANAGER_UID:-65534}"
CURL_OPTS=(-sS --max-time 10)
[[ "${INSECURE:-0}" == "1" ]] && CURL_OPTS+=(-k)

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

pass=0; fail=0
ok()   { echo "  ✅ $*"; pass=$((pass+1)); }
bad()  { echo "  ❌ $*"; fail=$((fail+1)); }
note() { echo "     $*"; }

echo "════════════════════════════════════════════════════════════"
echo " 경보 수신 채널 — 모드: $MODE"
echo "════════════════════════════════════════════════════════════"
echo

# ── ① 웹훅 URL 을 파일로 ──────────────────────────────────────────────────────
if [[ "$MODE" == "configure" ]]; then
    if [[ -z "${WEBHOOK_CRITICAL:-}" && -t 0 ]]; then
        read -rsp "  critical 채널의 Discord 웹후크 URL: " WEBHOOK_CRITICAL; echo
        read -rsp "  warning 채널의 Discord 웹후크 URL (같으면 Enter): " WEBHOOK_WARNING; echo
    fi
    if [[ -z "${WEBHOOK_CRITICAL:-}" ]]; then
        echo "ERROR: WEBHOOK_CRITICAL 이 필요합니다 — 터미널에서 실행하면 묻습니다." >&2
        exit 2
    fi
    WEBHOOK_WARNING="${WEBHOOK_WARNING:-$WEBHOOK_CRITICAL}"

    # 🔒 주소 자체가 비밀이다 — 길이 · 앞부분만 찍는다.
    # 🐛 2026-10-20 전에는 Slack 주소를 예로 들었는데 수신 설정이 webhook_configs(Alertmanager JSON)라 Slack · Discord 가 형식으로 거절했다.
    for url in "$WEBHOOK_CRITICAL" "$WEBHOOK_WARNING"; do
        if [[ ! "$url" =~ ^https://(discord|discordapp)\.com/api/webhooks/[0-9]+/[A-Za-z0-9_-]+$ ]]; then
            echo "ERROR: Discord 웹후크 URL 형식이 아닙니다(길이 ${#url}, 앞부분 '${url:0:30}')" >&2
            echo "       https://discord.com/api/webhooks/<숫자>/<토큰> 이어야 합니다 — 뒤에 /slack · ?wait= 를 붙이지 않습니다." >&2
            exit 2
        fi
    done

    mkdir -p "$SECRET_DIR"
    if [[ ! -w "$SECRET_DIR" ]]; then
        # Docker 가 없는 디렉터리를 root 소유로 만들어 둔 경우(운영 2026-09-24)
        echo "ERROR: $SECRET_DIR 에 쓸 수 없습니다(소유자 $(stat -c %U "$SECRET_DIR")) — sudo 로 다시 실행하세요." >&2
        exit 2
    fi
    printf '%s' "$WEBHOOK_CRITICAL" > "$SECRET_DIR/webhook-url-critical"
    printf '%s' "$WEBHOOK_WARNING"  > "$SECRET_DIR/webhook-url-warning"

    # ⚠️ 권한을 600 으로 두면 **컨테이너가 읽지 못한다.** prom/alertmanager 이미지는
    #    nobody(65534)로 돌기 때문이다. 처음 이 스크립트를 그렇게 만들었더니 설정 점검은
    #    전부 초록불인데 전달만 조용히 실패했다:
    #      "notify retry canceled ... open /etc/alertmanager/secrets/...: permission denied"
    #    그래서 그룹 읽기(640)를 주고 컨테이너 UID 를 소유자로 지정한다. 여전히
    #    world-readable 은 아니다. k8s 에서는 Secret 이 이 문제를 대신 처리한다.
    chmod 640 "$SECRET_DIR"/webhook-url-*
    if [[ "$(id -u)" == "0" ]]; then
        chown "$ALERTMANAGER_UID:$ALERTMANAGER_UID" "$SECRET_DIR"/webhook-url-* 2>/dev/null || true
        ok "웹후크 URL 을 $SECRET_DIR/ 에 기록 (640, uid $ALERTMANAGER_UID — critical 길이 ${#WEBHOOK_CRITICAL})"
    else
        bad "root 가 아니라 소유자를 uid $ALERTMANAGER_UID 로 바꾸지 못했다 — Alertmanager 가 읽지 못한다(permission denied)"
        note "sudo chown $ALERTMANAGER_UID:$ALERTMANAGER_UID $SECRET_DIR/webhook-url-*  또는 sudo 로 다시 실행"
    fi

    # gitignore 안전장치. 값이 커밋되면 채널이 공개되는 것과 같다.
    if git -C "$ROOT" check-ignore -q "$SECRET_DIR/webhook-url-critical" 2>/dev/null; then
        ok "$SECRET_DIR/ 가 gitignore 되어 있다"
    else
        bad "$SECRET_DIR/ 가 gitignore 되지 않았다 — 커밋되면 채널이 공개된다"
        note ".gitignore 에 'observability/secrets/' 를 추가하세요"
    fi

    if [[ "$K8S" == "1" ]]; then
        command -v kubectl >/dev/null || { echo "ERROR: kubectl 이 필요합니다" >&2; exit 2; }
        kubectl create secret generic alertmanager-secrets \
            --namespace "$NAMESPACE" \
            --from-file="webhook-url-critical=$SECRET_DIR/webhook-url-critical" \
            --from-file="webhook-url-warning=$SECRET_DIR/webhook-url-warning" \
            --dry-run=client -o yaml | kubectl apply -f -
        ok "k8s Secret alertmanager-secrets 를 $NAMESPACE 에 적용"
    fi
    echo
fi

# ── ② 설정 상태 점검 ─────────────────────────────────────────────────────────
echo "설정 점검"

if [[ -s "$SECRET_DIR/webhook-url-critical" ]]; then
    ok "critical 채널 파일 존재"
else
    bad "critical 채널이 설정되지 않았다 — 서비스 다운·백업 정지 경보가 아무 데도 가지 않는다"
fi
if [[ -s "$SECRET_DIR/webhook-url-warning" ]]; then
    ok "warning 채널 파일 존재"
else
    bad "warning 채널이 설정되지 않았다"
fi

# 예전 상태(주석 처리된 플레이스홀더)로 되돌아가지 않았는지 본다.
if grep -qE '^\s*#.*hooks\.slack\.com/services/XXX' observability/alertmanager.yml 2>/dev/null; then
    bad "alertmanager.yml 이 플레이스홀더 주석 상태다"
fi
if grep -q 'url_file:' observability/alertmanager.yml; then
    ok "alertmanager.yml 이 URL 을 파일에서 읽는다 (값이 커밋되지 않는다)"
else
    bad "alertmanager.yml 에 url_file 이 없다 — URL 이 설정에 직접 박혀 있지 않은지 확인하세요"
fi

# 실행 중이라면 Alertmanager 자신에게 물어본다.
if curl "${CURL_OPTS[@]}" -o /dev/null "$ALERTMANAGER_URL/-/healthy" 2>/dev/null; then
    ok "Alertmanager 응답 ($ALERTMANAGER_URL)"

    # 설정을 다시 읽게 하고, 받아들여졌는지 확인한다.
    if curl "${CURL_OPTS[@]}" -X POST -o /dev/null -w '' "$ALERTMANAGER_URL/-/reload" 2>/dev/null; then
        ok "설정 재적재 요청 성공"
    fi

    receivers="$(curl "${CURL_OPTS[@]}" "$ALERTMANAGER_URL/api/v2/receivers" 2>/dev/null || echo '')"
    if grep -q 'critical' <<<"$receivers" && grep -q 'warning' <<<"$receivers"; then
        ok "리시버 등록 확인: critical, warning"
    else
        bad "리시버가 등록되지 않았다 (응답: ${receivers:-없음})"
        note "url_file 이 가리키는 파일이 컨테이너 안에 마운트됐는지 확인하세요"
    fi
else
    note "Alertmanager 가 응답하지 않아 런타임 점검은 건너뜁니다 ($ALERTMANAGER_URL)"
    note "docker compose -f docker-compose-observability.yml -f docker-compose-observability.oci.yml up -d alertmanager"
fi
echo

# ── ③ 실제 도달 시험 ─────────────────────────────────────────────────────────
if [[ "$MODE" == "test" || "$MODE" == "configure" ]]; then
    echo "도달 시험 — 테스트 경보를 실제로 밀어 넣는다"

    if ! curl "${CURL_OPTS[@]}" -o /dev/null "$ALERTMANAGER_URL/-/healthy" 2>/dev/null; then
        note "Alertmanager 가 없어 건너뜁니다"
    else
        # 짧게 끝나도록 endsAt 을 가까운 미래로 둔다 — 남아서 계속 알림이 가면 곤란하다.
        ends="$(date -u -d '+2 minutes' '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null \
                || date -u -v+2M '+%Y-%m-%dT%H:%M:%SZ')"
        payload=$(cat <<JSON
[{
  "labels": {
    "alertname": "AlertPipelineTest",
    "severity": "critical",
    "service": "ops-17"
  },
  "annotations": {
    "summary": "경보 전달 경로 시험 — 이 메시지가 보이면 채널이 살아 있습니다",
    "description": "ops/17-configure-alert-receivers.sh 가 발사했습니다. 2분 뒤 자동 해소됩니다."
  },
  "endsAt": "$ends"
}]
JSON
)
        if curl "${CURL_OPTS[@]}" -X POST -H 'Content-Type: application/json' \
                -d "$payload" -o /dev/null "$ALERTMANAGER_URL/api/v2/alerts"; then
            ok "테스트 경보 전송"
        else
            bad "테스트 경보 전송 실패"
        fi

        sleep 3
        active="$(curl "${CURL_OPTS[@]}" "$ALERTMANAGER_URL/api/v2/alerts" 2>/dev/null || echo '')"
        if grep -q 'AlertPipelineTest' <<<"$active"; then
            ok "Alertmanager 가 경보를 수용했다"
        else
            bad "경보가 활성 목록에 없다"
        fi

        # ⚠️ 여기서 멈추면 안 된다. "수용됐다"와 "전달됐다"는 다른 얘기다 — 실제로 이 스크립트를
        # 처음 돌렸을 때 위 검사는 전부 통과했는데 웹훅은 permission denied 로 실패하고 있었다.
        # Alertmanager 자신의 지표로 전달 성공/실패를 확인한다. 채널에 접근할 필요가 없다.
        echo "     전달 결과를 기다리는 중 (critical group_wait 10s)..."
        delivered=0; failed=0
        for _ in $(seq 1 12); do
            sleep 5
            metrics="$(curl "${CURL_OPTS[@]}" "$ALERTMANAGER_URL/metrics" 2>/dev/null || echo '')"
            failed=$(awk '/^alertmanager_notifications_failed_total\{.*integration="discord"/ {s+=$NF} END {printf "%d", s+0}' <<<"$metrics")
            delivered=$(awk '/^alertmanager_notifications_total\{.*integration="discord"/ {s+=$NF} END {printf "%d", s+0}' <<<"$metrics")
            [[ "$delivered" -gt 0 ]] && break
        done

        if [[ "$delivered" -gt 0 && "$failed" -eq 0 ]]; then
            ok "Discord 전달 성공 (시도 $delivered · 실패 0) — 채널에 '경보 전달 경로 시험' 이 보여야 한다"
        elif [[ "$failed" -gt 0 ]]; then
            bad "Discord 전달 실패 (시도 $delivered · 실패 $failed)"
            note "Alertmanager 로그에 원인이 있습니다:"
            note "  docker logs alertmanager 2>&1 | grep 'Notify for alerts failed'"
            note "가장 흔한 원인 두 가지:"
            note "  · 시크릿 파일 권한 — 컨테이너는 uid $ALERTMANAGER_UID 로 돈다 (permission denied)"
            note "  · 웹훅 URL 이 틀렸거나 컨테이너 네트워크에서 닿지 않는다"
        else
            bad "전달 시도 자체가 없었다 — 라우팅이 리시버에 닿지 않는다"
        fi
    fi
    echo
fi

echo "════════════════════════════════════════════════════════════"
echo " 통과 $pass · 실패 $fail"
if [[ "$fail" -eq 0 ]]; then
    echo " ✅ 경보 전달 경로가 설정되었습니다"
else
    echo " ❌ 경보가 도달하지 않는 상태입니다 — 장애가 나도 아무도 모릅니다"
fi
echo "════════════════════════════════════════════════════════════"
exit $(( fail > 0 ? 1 : 0 ))
