#!/usr/bin/env bash
# 운영 액션 ⑥ — 스테이징 스모크 테스트 (docs/14 의 "다음 단계"를 실행형으로)
#
# 이 세션의 기동 테스트에서 드러났듯, 서비스는 "컴파일되지만 뜨지 않는" 상태일 수 있다.
# 배포 직후 이 스크립트로 **떴는지 · 서로 보이는지 · 핵심 경로가 도는지**를 3분 안에 확인한다.
#
# 사용:
#   GATEWAY=https://localhost:8000 ./ops/06-smoke-test.sh
#   GATEWAY=https://shop.example.com ./ops/06-smoke-test.sh   # 터널/Ingress 경유
#   INSECURE=1                 # 자가서명 TLS (-k)
#   SKIP_SAGA=1                # 사가 E2E 생략(읽기 전용 점검만)
#   SKIP_DIRECT=0|1            # 개별 서비스 포트 직접 점검. 기본은 $GATEWAY 를 보고 자동 판단
#   EUREKA=https://localhost:8761  KAFKA_BOOTSTRAP=localhost:29092
#   PROM=http://localhost:9090
#
# 종료 코드: 0=전부 통과, 1=실패 있음 (CI/배포 파이프라인 게이트로 사용 가능)
set -uo pipefail

GATEWAY="${GATEWAY:-https://localhost:8000}"
EUREKA="${EUREKA:-}"
PROM="${PROM:-}"
KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-}"

CURL=(curl -sS --max-time 10)
[ "${INSECURE:-0}" = "1" ] && CURL+=(-k)

pass=0; fail=0; skip=0
ok()   { echo "  ✅ $1"; pass=$((pass+1)); }
bad()  { echo "  ❌ $1"; fail=$((fail+1)); }
skipm(){ echo "  ⏭  $1"; skip=$((skip+1)); }

# 서비스명:포트 — 직접 접근이 가능한 환경에서 개별 헬스를 확인한다.
SERVICES="user-service:8080 catalog-service:8081 cart-service:8082 order-service:8084 chat-service:8086 pay-service:8087 coupon-service:8183"

# $GATEWAY 가 원격이면 localhost:<포트> 직접 점검은 의미가 없다 — 개별 서비스 포트는
# 클러스터 밖으로 나오지 않는다. 실배포에서 이걸 켜지 않아 **가짜 실패 7개**가 났고,
# 진짜 문제(§2)가 그 사이에 묻혔다. 그래서 명시적으로 끄지 않아도 알아서 판단한다.
if [ -z "${SKIP_DIRECT:-}" ]; then
    case "$GATEWAY" in
        *//localhost*|*//127.0.0.1*|*//[*) SKIP_DIRECT=0 ;;
        *) SKIP_DIRECT=1
           echo "  ℹ️  \$GATEWAY 가 원격($GATEWAY)이라 개별 포트 직접 점검을 생략합니다."
           echo "      로컬 실행이라면 SKIP_DIRECT=0 으로 강제할 수 있습니다." ;;
    esac
fi

echo "════ 1. 서비스 기동 여부 (actuator readiness) ════"
if [ "${SKIP_DIRECT:-0}" = "1" ]; then
    # k8s 배포에서는 개별 포트가 클러스터 밖으로 노출되지 않는다. 같은 검증(개별 readiness)을
    # kubelet 의 readinessProbe + rollout status 가 이미 수행했으므로 여기서는 생략한다(ops/13).
    skipm "SKIP_DIRECT=1 — 개별 서비스 직접 점검 생략 (k8s 프로브가 대신 수행)"
else
echo "   기동 실패는 이 프로젝트의 실제 이력이 있는 실패 모드다 (docs/14)."
for s in $SERVICES; do
    name="${s%%:*}"; port="${s##*:}"
    for scheme in https http; do
        code=$("${CURL[@]}" -o /dev/null -w "%{http_code}" "$scheme://localhost:$port/actuator/health/readiness" 2>/dev/null)
        if [ "$code" = "200" ]; then
            ok "$name readiness UP ($scheme)"
            # readiness 그룹은 기본적으로 readinessState 만 본다 — 의존성(DB/Mongo/Redis)이
            # 죽어도 UP 을 돌려준다. 실제로 chat-service 는 MongoDB 가 꺼진 상태에서도
            # readiness=UP / health=DOWN 이었다(docs/14 B-19). 그래서 종합 health 도 함께 본다.
            hstatus=$("${CURL[@]}" "$scheme://localhost:$port/actuator/health" 2>/dev/null)
            case "$hstatus" in
                *'"status":"UP"'*) ;;
                "") bad "$name health 무응답/타임아웃 — 헬스 인디케이터가 죽은 의존성에 매달려 있을 수 있다(docs/14 B-19)" ;;
                *)  bad "$name 은 readiness=UP 이지만 종합 health 가 UP 이 아님 → 의존성 점검 필요: $hstatus" ;;
            esac
            break
        fi
        [ "$scheme" = "http" ] && bad "$name readiness 응답 없음/비정상 (마지막 코드: $code)"
    done
done
fi

echo "════ 2. 게이트웨이 ════"
# ⚠️ 상태 코드만 보면 안 된다. $GATEWAY 가 Cloudflare Tunnel 처럼 **공유 호스트명**이면
#    게이트웨이로 라우팅되지 않는 경로는 프론트엔드로 흘러가고, SPA 는 어떤 경로에든
#    index.html 을 200 으로 돌려준다. 그래서 `/actuator/health` 가 200 이어도 게이트웨이는
#    쳐다보지도 않은 것일 수 있다 — 실배포에서 이 스크립트가 정확히 그렇게 통과했다.
#    본문을 보고 **누가 대답했는지**부터 가린다.
is_html() { case "$1" in *'<!DOCTYPE html'*|*'<!doctype html'*|*'<html'*) return 0 ;; *) return 1 ;; esac; }

# Cloudflare 는 오리진에 닿지 못하면 HTML 이 아니라 `error code: 502` 같은 **평문**을
# 돌려준다. 그러면 위의 is_html 에 걸리지 않아 "게이트웨이가 UP 이 아니다"로 보고되는데,
# 사실은 게이트웨이를 쳐다보지도 못한 것이다. 원인이 전혀 다른 곳(터널 ingress 규칙,
# 오리진 파드)에 있으므로 구분해서 알려 준다.
is_edge_error() { case "$1" in 'error code: '*) return 0 ;; *) return 1 ;; esac; }

body=$("${CURL[@]}" "$GATEWAY/actuator/health" 2>/dev/null)
if case "$body" in *'"status":"UP"'*) true ;; *) false ;; esac; then
    ok "gateway health UP"
elif is_edge_error "$body"; then
    bad "엣지가 오리진에 닿지 못했다 ($body) — 게이트웨이가 아니라 터널·Ingress 를 보라"
    echo "      이 경로가 어느 오리진으로 가는지부터 확인한다:"
    echo "        kubectl -n ecommerce get cm cloudflared -o yaml   # ingress 규칙"
    echo "        kubectl -n ecommerce get pods -l app=frontend"
elif is_html "$body"; then
    skipm "gateway /actuator/health 가 게이트웨이가 아니라 **프론트엔드**로 갔다 — 라우팅되지 않는 경로다"
    echo "      터널·Ingress 구성에서는 정상이다. actuator 는 일부러 공개하지 않는다."
    echo "      게이트웨이 헬스는 클러스터 안에서 확인한다:"
    echo "        kubectl -n ecommerce exec deploy/gateway-server -- \\"
    echo "          curl -sk https://localhost:8000/actuator/health"
elif [ -z "$body" ]; then
    bad "gateway health 무응답 — $GATEWAY 에 닿지 않는다"
else
    bad "gateway health 가 UP 이 아니다: $(printf '%.200s' "$body")"
fi

# 폴백은 서킷브레이커가 열렸을 때 사용자에게 돌려주는 경로 — 살아있어야 한다.
# Resilience4j 가 `forward:/fallback` 으로 **게이트웨이 내부에서** 전달하므로 외부에서
# 닿지 않아도 기능은 정상이다. 직접 때릴 수 있을 때만 검증한다.
fb=$("${CURL[@]}" -o /dev/null -w "%{http_code}" "$GATEWAY/fallback" 2>/dev/null)
fb_body=$("${CURL[@]}" "$GATEWAY/fallback" 2>/dev/null)
if [ "$fb" = "503" ]; then
    ok "fallback 503 (서킷브레이커 폴백 경로 정상)"
elif is_edge_error "$fb_body"; then
    skipm "/fallback 이 엣지에서 막혔다 ($fb_body) — 외부에 노출되지 않는 경로다(내부 forward 라 정상)"
elif is_html "$fb_body"; then
    skipm "/fallback 이 프론트엔드로 갔다 — 외부에 노출되지 않는 경로다(내부 forward 라 정상)"
else
    bad "fallback 응답 $fb (503 기대)"
fi

echo "════ 3. 레이트리미터 실동작 (429 발생 확인) ════"
# ⚠️ 이 검사는 **버킷을 비워 놓고 끝난다.** 7번이 같은 URL 을 쓰므로 그 영향을 그대로
#    받는다 — 7번에 재시도가 있는 이유가 이것이다. 여기 URL 을 바꾸거나 발수를 늘릴
#    때는 7번 주석을 같이 읽을 것.
# ⚠️ 반드시 **동시에** 쏴야 한다. 토큰버킷이 burst 40 + 초당 20 보충이므로, 순차 curl 은
# (TLS 핸드셰이크 포함 요청당 수십 ms) 보충 속도를 못 이겨 429 가 한 번도 안 나온다 —
# 스테이징 리허설에서 순차 70발이 전부 통과해 버리는 것을 실측했다.
# 🐛 2026-10-24 운영 배포: 막 뜬 게이트웨이에서 첫 70발이 모두 통과해 자동 복귀했다. Redis 명령 타임아웃(500ms)을 넘긴 유입
#    제한 명령은 통과(fail-open)인데, 첫 명령들이 연결 · 스크립트 적재를 치렀다(게이트웨이는 이제 readiness 전에 데운다 —
#    RateLimiterWarmup). 그래도 전 서비스가 한꺼번에 뜬 단일 노드는 느리다 — 429 가 없으면 3초 쉬고 **한 번만** 다시 쏜다.
#    제한이 빠진 게이트웨이는 몇 번을 쏴도 429 가 없으므로 이 재시도가 그 결함을 가리지 않는다. 결과마다 코드별 수를 남긴다.
# [JA] 起動直後の最初の 70 発がすべて通過して自動復帰した(Redis タイムアウト超過は通過)。429 がなければ 3 秒後に一度だけ撃ち直す。
rl_burst() {
    local out; out=$(mktemp)
    for _ in $(seq 1 70); do
        "${CURL[@]}" -o /dev/null -w "%{http_code}\n" "$GATEWAY/catalog-service/api/category/categories" 2>/dev/null >> "$out" &
    done
    wait
    cat "$out"; rm -f "$out"
}
rl_summary() { sort | uniq -c | awk '{printf "%s×%s ", $2, $1}'; }
codes=$(rl_burst)
n429=$(echo "$codes" | grep -c '^429$')
if [ "$n429" -eq 0 ]; then
    echo "  … 첫 70발에 429 없음 ($(echo "$codes" | rl_summary)) — 3초 뒤 한 번 더"
    sleep 3
    codes=$(rl_burst)
    n429=$(echo "$codes" | grep -c '^429$')
fi
if [ "$n429" -gt 0 ]; then ok "레이트리미터 동작 (70회 중 429 ${n429}회 — $(echo "$codes" | rl_summary))"
else bad "429 미발생 ($(echo "$codes" | rl_summary)) — Redis 미연결이거나 RequestRateLimiter 미배선 가능성"; fi

echo "════ 4. 서비스 디스커버리 ════"
if [ -n "$EUREKA" ]; then
    n=$("${CURL[@]}" -H "Accept: application/json" "$EUREKA/eureka/apps" 2>/dev/null | grep -o '"name"' | wc -l)
    [ "$n" -ge 3 ] && ok "eureka 등록 인스턴스 ${n}개" || bad "eureka 등록이 ${n}개뿐"
else skipm "EUREKA 미지정 — 디스커버리 점검 생략"; fi

echo "════ 5. 관측성 ════"
if [ -n "$PROM" ]; then
    down=$("${CURL[@]}" "$PROM/api/v1/targets" 2>/dev/null | grep -o '"health":"down"' | wc -l)
    [ "$down" = "0" ] && ok "Prometheus 스크레이프 타깃 전부 up" || bad "down 상태 타깃 ${down}개"
else skipm "PROM 미지정 — 관측성 점검 생략"; fi

echo "════ 6. Kafka 토픽 — DLT 존재 + 상태 토픽 컴팩션 ════"
if [ -n "$KAFKA_BOOTSTRAP" ] && command -v kafka-topics.sh >/dev/null; then
    missing=0
    # ⚠️ ops/02 의 TOPICS 와 같은 목록이어야 한다. 늘릴 때 한쪽만 고치면 이 점검이
    #    "전부 있다"고 말하면서 실제로는 빠진 토픽을 보지 않는다.
    DLT_TOPICS="order payment product-update user-created cart-empty cart-rollback coupon-use coupon-rollback chat outgoing-mail user-presence"
    expected=0
    for t in $DLT_TOPICS; do
        expected=$((expected+1))
        kafka-topics.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --list 2>/dev/null | grep -qx "$t.DLT" || { missing=$((missing+1)); }
    done
    [ "$missing" = "0" ] && ok "DLT 토픽 ${expected}종 존재" || bad "DLT 토픽 ${missing}/${expected}종 누락 → ops/02-create-dlt-topics.sh 실행 필요"

    # saga-projection 의 출력은 **컴팩션이어야** 한다. 존재만 보면 안 된다 — 브로커가 자동 생성한
    # delete 정책 토픽도 "존재"하고, 그 차이는 retention 이 지난 뒤에야 드러난다(docs/38 §1.17).
    STATE_TOPIC_NAME="${STATE_TOPIC:-order-saga-state}"
    if kafka-topics.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --list 2>/dev/null | grep -qx "$STATE_TOPIC_NAME"; then
        if command -v kafka-configs.sh >/dev/null \
           && kafka-configs.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --describe --all \
                --entity-type topics --entity-name "$STATE_TOPIC_NAME" 2>/dev/null \
              | sed -n 's/^[[:space:]]*cleanup\.policy=\([a-z,]*\).*/\1/p' | head -1 | grep -q compact; then
            ok "$STATE_TOPIC_NAME 컴팩션"
        else
            bad "$STATE_TOPIC_NAME 이 컴팩션이 아님 → ops/02-create-dlt-topics.sh 실행 필요"
        fi
    else
        bad "$STATE_TOPIC_NAME 없음 → ops/02-create-dlt-topics.sh 실행 필요"
    fi
else skipm "KAFKA_BOOTSTRAP/kafka-topics.sh 없음 — DLT 점검 생략"; fi

echo "════ 7. 핵심 경로: 상품 조회 (읽기) ════"
#
# ⚠️ 3번이 **바로 이 URL 로** 70발을 동시에 쏴서 토큰버킷을 비워 놓는다(burst 40,
#    초당 20 보충). 4·5·6번이 전부 SKIP 이면 그 사이 시간이 0초라, 여기서 빈 버킷을
#    때리고 429 가 나온다 — 그러면 ops/28 이 **멀쩡한 배포를 실패로 판정하고 되돌린다.**
#
#    실제로 그랬다. 한 번은 3번에서 429 가 29회 나오고 7번이 통과했고, 다음 배포에서는
#    18회가 나오고 7번이 429 로 죽었다. 코드도 클러스터도 그대로였다 — 순전히 경합이다.
#    "가끔 실패하는 게이트"는 게이트가 아니다. 아무도 그 실패를 믿지 않게 된다.
#
#    그래서 버킷이 다시 찰 때까지 기다렸다가 본다. 429 는 **여기서만** 재시도 사유다.
#    다른 코드(5xx 등)는 진짜 실패이므로 즉시 보고한다.
code=""
for attempt in 1 2 3 4 5; do
    code=$("${CURL[@]}" -o /dev/null -w "%{http_code}" "$GATEWAY/catalog-service/api/category/categories" 2>/dev/null)
    [ "$code" = "429" ] || break
    [ "$attempt" = "1" ] && printf '  … 레이트리미터가 아직 회복 중(3번이 비워 둔 버킷) — 기다립니다'
    printf '.'
    sleep 2
done
[ "$attempt" = "1" ] || echo
if [ "$code" = "200" ]; then
    if [ "$attempt" = "1" ]; then ok "카탈로그 조회 200"
    else ok "카탈로그 조회 200 (레이트리미터 회복 대기 후, 시도 $attempt)"; fi
elif [ "$code" = "429" ]; then
    # 10초를 기다려도 안 풀린다면 그건 3번의 여파가 아니다 — 한도 자체가 너무 빡빡하거나
    # 다른 트래픽이 있다는 뜻이므로 실패로 본다.
    bad "카탈로그 조회 429 — 10초를 기다려도 레이트리미터가 풀리지 않습니다(한도 설정 확인)"
else
    bad "카탈로그 조회 $code"
fi

echo "════ 8. 사가 E2E (주문→결제→재고) ════"
if [ "${SKIP_SAGA:-0}" = "1" ]; then
    skipm "SKIP_SAGA=1 — 생략"
else
    cat <<'MANUAL'
  ⚠️  자동화되지 않음. 사가는 PayPal 결제 콜백이 필요해 스크립트로 완결할 수 없다.
      아래를 수동으로 1회 수행하고 결과를 확인하세요 (docs/03 §2.2 흐름):
        1) 로그인 → 장바구니 담기 → 체크아웃 → PayPal 샌드박스 결제 승인
        2) order-service 로그에 'payment-response' 소비 기록
        3) catalog: 해당 상품 재고가 주문 수량만큼 차감 (모든 주문 항목에 대해!)
        4) cart: 장바구니 비워짐
        5) 같은 주문의 product-update 이벤트를 재전송해도 재고가 더 줄지 않을 것
           (멱등성, docs/12 §2 — product.processed_event 에 order-<id> 기록 확인)
MANUAL
    skipm "사가 E2E는 수동 확인 항목"
fi

echo
echo "════════════════════════════════════════"
echo "  통과 $pass · 실패 $fail · 생략 $skip"
[ "$fail" -eq 0 ] && { echo "  스모크 테스트 통과"; exit 0; } || { echo "  실패 항목 확인 필요"; exit 1; }
