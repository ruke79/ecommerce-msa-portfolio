#!/usr/bin/env bash
#
# 운영 액션 ㊲ — 서비스 간 mTLS 가 실제로 판정하고 있는지 확인한다 (docs/57 §16).
#
# 무엇을 보나:
#   ② 대조 — 잠깐 뜨는 curl 파드에서 order-service 의 내부 API 를 두 번 부른다:
#        인증서 없이          → enforce 면 403, observe·off 면 200
#        내부 인증서로(tls-id-user-service — 이 API 를 부를 수 있는 서비스의 신원. 없으면 공유 tls-keystore) → 언제나 200
#      "인증서를 내면 되고 안 내면 안 된다" 가 둘 다 맞아야 판정이 살아 있다.
#   ① observe 경고(먼저 — ②의 "인증서 없이" 호출이 경고를 남기므로) — 백엔드 로그에서 "[mTLS observe]" 를 센다. 서비스끼리의 호출이 인증서를 내지 못하고 있다는 뜻이다.
#      **0 이어야 enforce 로 올릴 수 있다.** (0 이 아닌데 올리면 그 호출이 403 으로 끊긴다.)
#   ①-2 규칙 위반 — "[mTLS rule]"(허용 주체가 아니거나 그 경로를 부를 수 없는 서비스). **0 이어야 RULES_MODE 를 enforce 로 올린다.**
#      caller=localhost 가 보이면 아직 공유 인증서로 부르는 파드다 — 신원 마운트 뒤 재시작이 안 됐다.
#   ①-3 신원 헤더 — "[mTLS identity]"(게이트웨이 인증서가 아닌 곳에서 온 신원 헤더 · 서비스가 실은 User-Role/User-Email).
#      **0 이어야 INTERNAL_AUTH_IDENTITY_MODE 를 enforce 로 올린다.** caller=(인증서 없음) 이 게이트웨이에서 오면 게이트웨이가
#      아직 자기 인증서(tls-id-gateway-server)를 내지 않는 것이다 — ops/38 · k8s/96 발급 뒤 게이트웨이 재시작.
#   ②-2 대조 — 인증서 없이 User-Role: ROLE_ADMIN 을 붙여 같은 내부 API 를 부른다. identity enforce 면 403, 아니면 200.
#
# 사용:
#   ./ops/37-check-mtls.sh            # 지금 모드(ConfigMap 의 INTERNAL_AUTH_MODE)에 맞춰 기대값을 정한다
#   SINCE=2h ./ops/37-check-mtls.sh   # 로그를 몇 시간 치 볼지(기본 1h)
#   ./ops/37-check-mtls.sh --callers  # 실제로 누가 어느 내부 경로를 불렀나([mTLS caller]) — 경로 규칙을 만들고 고칠 때
#
# enforce 로 올리기(INTERNAL_AUTH_MODE · INTERNAL_AUTH_RULES_MODE 모두 같은 방법):
#   k8s/10-configmap.yaml 의 값을 바꿔 커밋 → ./ops/28-deploy.sh local --config-only
#   kubectl -n ecommerce rollout restart deploy/<백엔드 서비스들>   # 환경 변수는 재시작해야 읽는다
#   ⚠️ kubectl patch 로만 바꾸면 다음 ops/28 배포가 매니페스트 값으로 조용히 되돌린다(k8s/10-configmap.yaml 주석).
#   ./ops/37-check-mtls.sh
#
# 종료 코드: 0 = 기대대로, 1 = 어긋남, 2 = 확인 불가
#
# 비밀은 출력하지 않는다 — 인증서·키는 Secret 을 파드에 마운트해 쓰고, 응답은 상태 코드만 본다.
set -uo pipefail

NS="${NS:-ecommerce}"
CONFIGMAP="${CONFIGMAP:-ecommerce-config}"
SINCE="${SINCE:-1h}"
IMAGE="${CURL_IMAGE:-mirror.gcr.io/curlimages/curl:8.10.1}"
TARGET="https://order-service.${NS}.svc.cluster.local:8084/api/order/internal/purchase-stats?months=1&afterCustomerId=0&size=1"
BACKENDS="user-service catalog-service cart-service order-service pay-service coupon-service chat-service notification-service delivery-service saga-projection"

ok()   { echo "  ✅ $1"; }
bad()  { echo "  🔴 $1"; FAIL=1; }
info() { echo "  ℹ️  $1"; }
FAIL=0

command -v kubectl >/dev/null || { echo "kubectl 이 없습니다" >&2; exit 2; }
kubectl -n "$NS" get secret tls-keystore >/dev/null 2>&1 || { echo "$NS/tls-keystore Secret 이 없습니다(ops/07)" >&2; exit 2; }
# 🐛 예전: ConfigMap 이 없으면(앱을 아직 배포하지 않은 클러스터 · 다른 컨텍스트) 모드를 조용히 off 로 읽고 빈 결과를 냈다 —
#    "아무도 안 불렀다" 와 구분되지 않았다. 어느 클러스터를 보는지 먼저 적고, ConfigMap 이 없으면 확인 불가(2)로 멈춘다.
# [JA] 以前は ConfigMap がなければ(未デプロイのクラスタ・別コンテキスト)モードを黙って off と読み空の結果を出していた。
CONTEXT=$(kubectl config current-context 2>/dev/null)
echo "▶ 클러스터: ${CONTEXT:-(컨텍스트 알 수 없음)} · 네임스페이스: $NS"
if ! kubectl -n "$NS" get configmap "$CONFIGMAP" >/dev/null 2>&1; then
    echo "$NS/$CONFIGMAP ConfigMap 이 없습니다 — 이 클러스터에는 앱이 아직 배포되지 않았습니다(컨텍스트가 맞는지: kubectl config get-contexts)" >&2
    exit 2
fi

MODE=$(kubectl -n "$NS" get configmap "$CONFIGMAP" -o jsonpath='{.data.INTERNAL_AUTH_MODE}' 2>/dev/null)
MODE="${MODE:-off}"
RULES_MODE=$(kubectl -n "$NS" get configmap "$CONFIGMAP" -o jsonpath='{.data.INTERNAL_AUTH_RULES_MODE}' 2>/dev/null)
HAS_RULES=$(kubectl -n "$NS" get configmap "$CONFIGMAP" -o jsonpath='{.data.INTERNAL_AUTH_PATH_RULES}{.data.INTERNAL_AUTH_ALLOWED_SUBJECTS}' 2>/dev/null)
IDENTITY_MODE=$(kubectl -n "$NS" get configmap "$CONFIGMAP" -o jsonpath='{.data.INTERNAL_AUTH_IDENTITY_MODE}' 2>/dev/null)
IDENTITY_MODE="${IDENTITY_MODE:-observe}"
echo "▶ 모드: $MODE${HAS_RULES:+ · 규칙: ${RULES_MODE:-enforce}} · 신원 헤더: $IDENTITY_MODE"

if [ "${1:-}" = "--callers" ]; then
    echo
    echo "▶ 누가 어느 내부 경로를 불렀나 — [mTLS caller] (파드가 뜬 뒤 처음 본 짝마다 한 줄)"
    if [ "$MODE" = off ]; then
        # off 면 필터가 인증서를 보지 않고 바로 넘긴다 — 호출자 기록이 생기지 않는다(InternalCallerFilter)
        echo "  ℹ️  모드가 off 라 호출자를 기록하지 않습니다 — observe 이상에서만 [mTLS caller] 가 남습니다"
        exit 0
    fi
    found=0 lines=0
    for s in $BACKENDS; do
        kubectl -n "$NS" get "deploy/$s" >/dev/null 2>&1 || continue
        found=$((found + 1))
        out=$(kubectl -n "$NS" logs "deploy/$s" --all-containers 2>/dev/null | grep -o "\[mTLS caller\] caller=[^ ]* path=[^ \"]*" \
            | sed "s/^\[mTLS caller\] /  $s ← /" | sort -u)
        if [ -n "$out" ]; then
            echo "$out"
            lines=$((lines + $(printf '%s\n' "$out" | wc -l)))
        fi
    done
    if [ "$found" -eq 0 ]; then
        echo "  ℹ️  $NS 에 백엔드 Deployment 가 없습니다"
    elif [ "$lines" -eq 0 ]; then
        echo "  ℹ️  기록 없음 — 백엔드 ${found}개의 지금 파드 로그에 내부 호출이 아직 없습니다(재시작하면 로그가 비워진다 — 화면을 한 번 돌린 뒤 다시)"
    fi
    exit 0
fi

# 대조에 쓸 신원 — 이 API(구매 집계)를 부를 수 있는 서비스는 user-service 다(경로 규칙). 서비스별 신원이 없으면 공유 인증서.
CERT_SECRET=tls-keystore
if kubectl -n "$NS" get secret tls-id-user-service >/dev/null 2>&1; then
    CERT_SECRET=tls-id-user-service
fi

# 로그를 **먼저** 본다 — 아래 대조의 "인증서 없이" 호출이 경고를 하나 남기므로, 뒤에 세면 자기 자신을 잡는다.
echo
echo "▶ ① observe 경고 — 인증서 없이 내부 경로에 온 요청(최근 $SINCE)"
TOTAL=0
for s in $BACKENDS; do
    kubectl -n "$NS" get "deploy/$s" >/dev/null 2>&1 || continue
    n=$(kubectl -n "$NS" logs "deploy/$s" --since="$SINCE" --all-containers 2>/dev/null | grep -c "\[mTLS observe\]")
    if [ "${n:-0}" -gt 0 ]; then
        bad "$s: $n 건 — 예:"
        kubectl -n "$NS" logs "deploy/$s" --since="$SINCE" 2>/dev/null | grep "\[mTLS observe\]" | tail -3 | sed 's/^/        /'
        TOTAL=$((TOTAL + n))
    fi
done
[ "$TOTAL" = 0 ] && ok "없다 — 서비스끼리의 호출이 모두 인증서를 낸다"

RULE_TOTAL=0
if [ -n "$HAS_RULES" ]; then
    echo
    echo "▶ ①-2 규칙 위반 — 허용 주체가 아니거나 그 경로를 부를 수 없는 서비스(최근 $SINCE)"
    for s in $BACKENDS; do
        kubectl -n "$NS" get "deploy/$s" >/dev/null 2>&1 || continue
        n=$(kubectl -n "$NS" logs "deploy/$s" --since="$SINCE" --all-containers 2>/dev/null | grep -c "\[mTLS rule\]")
        if [ "${n:-0}" -gt 0 ]; then
            bad "$s: $n 건 — 예:"
            kubectl -n "$NS" logs "deploy/$s" --since="$SINCE" 2>/dev/null | grep "\[mTLS rule\]" | tail -3 | sed 's/^/        /'
            RULE_TOTAL=$((RULE_TOTAL + n))
        fi
    done
    if [ "$RULE_TOTAL" = 0 ]; then
        ok "없다 — 모든 호출이 자기 신원으로, 부를 수 있는 경로만 부른다"
    else
        info "caller=localhost 면 아직 공유 인증서로 부르는 파드다(신원 마운트 뒤 재시작). 그 밖이면 경로 규칙(INTERNAL_AUTH_PATH_RULES)에 빠진 호출이다 — docs/57 §16 표"
    fi
fi

ID_TOTAL=0
if [ "$MODE" != "off" ] && [ "$IDENTITY_MODE" != "off" ]; then
    echo
    echo "▶ ①-3 신원 헤더 — 게이트웨이 인증서가 아닌 곳에서 온 신원 헤더(최근 $SINCE)"
    for s in $BACKENDS; do
        kubectl -n "$NS" get "deploy/$s" >/dev/null 2>&1 || continue
        n=$(kubectl -n "$NS" logs "deploy/$s" --since="$SINCE" --all-containers 2>/dev/null | grep -c "\[mTLS identity\]")
        if [ "${n:-0}" -gt 0 ]; then
            bad "$s: $n 건 — 예:"
            kubectl -n "$NS" logs "deploy/$s" --since="$SINCE" 2>/dev/null | grep "\[mTLS identity\]" | tail -3 | sed 's/^/        /'
            ID_TOTAL=$((ID_TOTAL + n))
        fi
    done
    if [ "$ID_TOTAL" = 0 ]; then
        ok "없다 — 신원 헤더는 모두 게이트웨이 인증서(또는 User-Id 만 실은 서비스)로 왔다"
    else
        info "caller=(인증서 없음) 이면 게이트웨이가 아직 자기 인증서를 내지 않는다(tls-id-gateway-server 발급 → 게이트웨이 재시작). 서비스 이름이면 그 Feign 이 User-Role/User-Email 을 싣는다"
    fi
fi

# curl 파드 — tls-keystore 를 /certs 에 마운트한다. 상태 코드와 curl 의 종료 코드·오류 문구만 찍는다(응답 본문은 버린다).
OVERRIDES=$(cat <<JSON
{"spec":{"containers":[{"name":"probe","image":"$IMAGE","command":["sh","-c",
 "ls /certs/ca.crt /certs/tls.crt /certs/tls.key >/dev/null 2>&1 || echo MISSING=\$(for f in ca.crt tls.crt tls.key; do [ -r /certs/\$f ] || printf '%s ' \$f; done); i=0; while :; do N=\$(curl -s -m 10 -o /dev/null -w '%{http_code} %{exitcode} %{errormsg}' -H 'X-Mtls-Probe: ops-37' --cacert /certs/ca.crt '$TARGET'); case \"\$N\" in '000 7 '*) i=\$((i+1)); [ \$i -ge 8 ] && break; sleep 2;; *) break;; esac; done; echo TRIES=\$i; echo NOCERT=\$N; echo FORGED=\$(curl -s -m 10 -o /dev/null -w '%{http_code} %{exitcode} %{errormsg}' -H 'X-Mtls-Probe: ops-37' -H 'User-Id: 1' -H 'User-Role: ROLE_ADMIN' --cacert /certs/ca.crt '$TARGET'); echo WITHCERT=\$(curl -s -m 10 -o /dev/null -w '%{http_code} %{exitcode} %{errormsg}' --cacert /certs/ca.crt --cert /certs/tls.crt --key /certs/tls.key '$TARGET')"],
 "volumeMounts":[{"name":"certs","mountPath":"/certs","readOnly":true}]}],
 "volumes":[{"name":"certs","secret":{"secretName":"$CERT_SECRET"}}],"restartPolicy":"Never"}}
JSON
)
echo
echo "▶ ② 대조 — 인증서 없이 / 내부 인증서($CERT_SECRET)로 order-service 내부 API"
# 모드를 바꾸고 재시작한 직후면 옛 파드(옛 모드)가 아직 응답한다 — 롤아웃이 끝날 때까지 기다린 뒤 두드린다.
# 🐛 enforce 로 올리고 곧바로 돌리면 옛 observe 파드가 200 을 줘 "판정이 켜지지 않았다" 로 읽혔다.
if ! kubectl -n "$NS" rollout status deploy/order-service --timeout=5s >/dev/null 2>&1; then
    info "order-service 롤아웃 중 — 끝날 때까지 기다린다(최대 5분)"
    kubectl -n "$NS" rollout status deploy/order-service --timeout=300s >/dev/null 2>&1 \
        || { echo "  확인 불가 — order-service 롤아웃이 끝나지 않았다" >&2; exit 2; }
fi
# 떠 있는 파드가 어떤 모드로 기동했는가(기동 로그 한 줄) — ConfigMap 과 다르면 재시작하지 않은 것이다.
POD_MODE=$(kubectl -n "$NS" logs deploy/order-service 2>/dev/null | grep -o "서비스 간 mTLS 판정: mode=[A-Z]*" | tail -1 | sed 's/.*mode=//')
info "order-service 파드의 기동 모드: ${POD_MODE:-알 수 없음(로그에 없음 — off 이거나 로그가 잘렸다)}"

# Service 에 준비된 파드가 있는가 — 없으면 Service 로 가는 연결은 곧바로 거부된다(curl 7, "000"). 게이트웨이는 Eureka 로 파드 IP
# 에 직접 가므로 화면은 될 수 있다 — 그래서 "화면은 되는데 여기만 000" 이 나온다. 서비스끼리의 Feign(FEIGN_ORDER_URL)은 Service
# 를 타므로 같이 끊긴다.
READY_IPS=$(kubectl -n "$NS" get endpoints order-service -o jsonpath='{.subsets[*].addresses[*].ip}' 2>/dev/null)
NOT_READY_IPS=$(kubectl -n "$NS" get endpoints order-service -o jsonpath='{.subsets[*].notReadyAddresses[*].ip}' 2>/dev/null)
if [ -z "$READY_IPS" ]; then
    bad "order-service Service 에 준비된 파드가 없다(준비 안 된 파드: ${NOT_READY_IPS:-없음}) — readiness 가 실패하고 있다"
    echo "     확인: kubectl -n $NS get pods -l app=order-service"
    echo "           kubectl -n $NS describe pods -l app=order-service | grep -A3 -E 'Readiness|Warning'"
    echo "           kubectl -n $NS logs deploy/order-service --since=15m | grep -E 'WARN|ERROR' | tail -30"
    echo
    echo "🔴 판정을 볼 수 없다 — order-service 가 준비된 뒤 다시."
    exit 1
fi
OUT=$(kubectl -n "$NS" run "mtls-probe-$RANDOM" --image="$IMAGE" --restart=Never --rm -i --quiet \
      --overrides="$OVERRIDES" 2>/dev/null)
# 줄 모양: "<HTTP 코드> <curl 종료 코드> <curl 오류 문구>". 000 이면 HTTP 까지 가지 못한 것이다 — 종료 코드가 이유다.
#
# 🐛 예전에는 HTTP 코드만 찍어, 연결·TLS 에서 실패하면 "000" 만 보였다. 이름을 못 찾았는지(6), 막혔는지(7·28), 서버 인증서를
#    믿지 못했는지(60 — SAN·CA), CA 파일이 Secret 에 없는지(77) 가를 수 없었다.
NOCERT_LINE=$(printf '%s\n' "$OUT" | sed -n 's/^NOCERT=//p')
WITHCERT_LINE=$(printf '%s\n' "$OUT" | sed -n 's/^WITHCERT=//p')
FORGED_LINE=$(printf '%s\n' "$OUT" | sed -n 's/^FORGED=//p')
MISSING=$(printf '%s\n' "$OUT" | sed -n 's/^MISSING=//p')
if [ -z "$NOCERT_LINE" ] || [ -z "$WITHCERT_LINE" ]; then
    echo "  확인 불가 — 파드 출력이 없습니다(이미지 받기 실패·order-service 미기동)" >&2
    exit 2
fi
NOCERT=${NOCERT_LINE%% *}
WITHCERT=${WITHCERT_LINE%% *}
FORGED=${FORGED_LINE%% *}

why() {   # curl 종료 코드 → 사람 말
    case "$1" in
        6)  echo "서비스 이름을 찾지 못했다(DNS) — order-service Service 가 $NS 에 있는가" ;;
        7)  if [ "${NETPOLS:-0}" -gt 0 ]; then
                echo "연결이 거부됐다(16초 동안 계속) — $NS 에 NetworkPolicy 가 ${NETPOLS}개 있다. 정책이 이 파드를 막는다: kubectl -n $NS get networkpolicy · ./ops/35-network-policy.sh check"
            else
                echo "연결이 거부됐다 — Service 에 준비된 파드가 없었거나(재시작·readiness 실패) 8084 에서 듣지 않는다"
            fi ;;
        28) echo "시간 초과 — NetworkPolicy 가 막았거나 파드가 응답하지 않는다" ;;
        35) echo "TLS 핸드셰이크 실패 — 서버가 TLS 가 아니거나 프로토콜이 맞지 않는다" ;;
        56) echo "연결이 끊겼다 — 서버가 클라이언트 인증서를 요구하는데(need) 내지 않았을 수 있다" ;;
        58) echo "클라이언트 인증서·키를 읽지 못했다 — tls-keystore 의 tls.crt·tls.key" ;;
        60) echo "서버 인증서를 믿지 못했다 — SAN 에 order-service.$NS.svc.cluster.local 이 없거나 ca.crt 가 다른 CA 다(ops/07 로 다시 발급)" ;;
        77) echo "CA 파일을 읽지 못했다 — tls-keystore Secret 에 ca.crt 키가 없다" ;;
        0)  echo "" ;;
        *)  echo "curl 종료 코드 $1" ;;
    esac
}
detail() {   # "<코드> <종료> <문구>" 에서 종료 코드·문구
    local rest=${1#* }; local code=${rest%% *}; local msg=${rest#* }
    [ "$msg" = "$rest" ] && msg=""
    printf '%s' "$(why "$code")${msg:+ ($msg)}"
}

TRIES=$(printf '%s\n' "$OUT" | sed -n 's/^TRIES=//p')
NETPOLS=$(kubectl -n "$NS" get networkpolicy -o name 2>/dev/null | wc -l | tr -d ' ')
[ "${TRIES:-0}" -gt 0 ] && info "처음 ${TRIES} 번은 연결이 거부돼 2초씩 기다렸다(NetworkPolicy ${NETPOLS}개 — 새 파드가 정책에 반영되기까지 걸리는 틈)"
[ -n "$MISSING" ] && bad "tls-keystore Secret 에 없는 키: $MISSING— 대조 호출이 인증서를 쓸 수 없다(ops/07 의 k8s Secret 으로 다시 적용)"
info "인증서 없이: $NOCERT · 내부 인증서로: $WITHCERT"
if [ "$NOCERT" = "000" ] && [ "$WITHCERT" = "000" ]; then
    echo
    echo "  ⚠️  둘 다 000 이면 판정(인증서)까지 가지 못한 것이다 — 아래 이유가 먼저다. mTLS 판정 자체의 문제로 읽지 말 것."
fi

if [ "$WITHCERT" = "200" ]; then
    ok "내부 인증서로 부르면 통과(200)"
elif [ "$WITHCERT" = "000" ]; then
    bad "내부 인증서로 연결조차 안 된다(000) — $(detail "$WITHCERT_LINE")"
else
    bad "내부 인증서로 불러도 $WITHCERT — 판정이 인증서를 알아보지 못한다(LOCAL_CACERT_PATH·ca.crt 확인)"
fi
case "$MODE" in
    enforce)
        [ "$NOCERT" = "403" ] && ok "인증서 없이 부르면 거절(403) — 헤더 없음만으로는 내부가 아니다" \
                               || bad "enforce 인데 인증서 없이 $NOCERT — 파드의 기동 모드: ${POD_MODE:-알 수 없음}. ENFORCE 가 아니면 재시작이 안 된 것이다" ;;
    observe|off)
        [ "$NOCERT" = "200" ] && info "인증서 없이도 통과(200) — $MODE 는 막지 않는다(기대대로)" \
                               || bad "$MODE 인데 인증서 없이 $NOCERT${NOCERT_LINE:+ — $(detail "$NOCERT_LINE")}" ;;
esac
# ②-2 — 인증서 없이 관리자 헤더. 예전에는 헤더만 있으면 믿어서 언제나 200 이었다(docs/57 §16).
if [ -n "$FORGED" ] && [ "$MODE" != "off" ]; then
    info "인증서 없이 User-Role: ROLE_ADMIN 을 붙이면: $FORGED"
    if [ "$IDENTITY_MODE" = "enforce" ]; then
        [ "$FORGED" = "403" ] && ok "게이트웨이를 거치지 않은 신원 헤더는 버린다(403)" \
                               || bad "identity enforce 인데 $FORGED — 파드가 재시작되지 않았거나(기동 로그의 identity-mode) 판정이 헤더를 믿는다"
    else
        info "identity-mode=$IDENTITY_MODE 는 아직 헤더를 믿는다(기대대로) — ①-3 이 0 이면 enforce 로 올린다"
    fi
fi


echo
if [ "$FAIL" = 0 ]; then
    [ "$MODE" = "observe" ] && echo "✅ enforce 로 올려도 된다(위 명령). 경고는 트래픽이 있어야 나오니, 주요 흐름(주문·결제·배송)이 한 번 돈 뒤에 보는 것이 좋다."
    [ "$MODE" = "enforce" ] && echo "✅ 서비스 간 mTLS 가 판정하고 있다."
    if [ -n "$HAS_RULES" ] && [ "${RULES_MODE:-enforce}" = "observe" ] && [ "$RULE_TOTAL" = 0 ]; then
        echo "✅ 규칙 위반도 없다(최근 $SINCE) — 규칙을 enforce 로 올려도 된다. 드문 흐름(쿠폰 견적 · 배송 · 리뷰 별점)까지 돈 뒤에,"
        echo "   더 긴 창으로 한 번 더 본 다음 올린다:  SINCE=24h ./ops/37-check-mtls.sh"
        echo "     k8s/10-configmap.yaml 의 INTERNAL_AUTH_RULES_MODE: \"enforce\" 로 바꾸고 커밋 →"
        echo "     ./ops/28-deploy.sh local --config-only  → 백엔드 rollout restart(환경 변수는 재시작해야 읽는다)"
        echo "   ⚠️ kubectl patch 로만 바꾸면 다음 ops/28 배포가 매니페스트 값으로 조용히 되돌린다."
    fi
    if [ "$MODE" != "off" ] && [ "$IDENTITY_MODE" = "observe" ] && [ "$ID_TOTAL" = 0 ]; then
        echo "✅ 게이트웨이가 아닌 곳에서 온 신원 헤더가 없다(최근 $SINCE) — 로그인 · 주문 · 관리자 화면이 한 번씩 돈 뒤 더 긴 창으로"
        echo "   다시 보고(SINCE=24h) INTERNAL_AUTH_IDENTITY_MODE: \"enforce\" 로 올린다 — 같은 절차(--config-only + 백엔드 재시작)."
    fi
    [ "$MODE" = "off" ] && echo "ℹ️  mTLS 판정이 꺼져 있다 — INTERNAL_AUTH_MODE=observe 로 시작한다."
    exit 0
fi
echo "🔴 어긋난 것이 있다 — 위를 보고 고친 뒤 다시."
exit 1
