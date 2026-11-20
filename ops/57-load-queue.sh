#!/usr/bin/env bash
# 운영 액션 57 — 부하 대기열: 지금 도는 k6 회차가 끝나면 정해 둔 회차들을 이어서 돌린다 (docs/83)
# [JA] 運用アクション 57 — 負荷キュー: いま動いている k6 の回が終わったら、決めておいた回を続けて流す
#
# 밤새 오래 돌리기(mixed 8시간) 뒤에 사람이 깨어 있지 않아도 다음 측정이 이어지게 한다. ops/53 의 회차를 그대로 부른다 —
# 결과는 ops/53 과 같은 폴더(load-results/<시나리오>-<시각>/)에, 이 대기열의 진행은 따로 로그에 남는다.
#
# ── 사용 ────────────────────────────────────────────────────────────────
#
#   ./ops/57-load-queue.sh plan                       # 무엇을 어떤 순서로 · 시간 합계
#   nohup ./ops/57-load-queue.sh run > ~/.ecommerce-msa/load-queue.out 2>&1 &   # 지금 k6 가 끝나기를 기다렸다가 시작
#   ./ops/57-load-queue.sh status                     # 기다리는 중인가 · 몇 번째 회차인가 · 로그 끝
#   ./ops/57-load-queue.sh stop                       # 대기열을 멈춘다(돌고 있는 k6 회차는 그대로 끝까지 — 끊으려면 그 k6 를)
#
# ── 회차(after-soak) ────────────────────────────────────────────────────
#
#   1. order-sustained — 주문(쓰기) 경로를 초당 5건으로 55분 유지. 재고 예약 · 주문 아웃박스 · Kafka · 무통장 입금 · 취소가 오래 돈다
#      (10-17 의 order 회차는 중간에 끊겼다 — 쓰기 경로를 오래 잰 적이 없다). 회차 뒤 아웃박스 미전송 · FAILED 를 센다
#   2. capacity-steps — 조회를 초당 100 → 200 → 300 → 400 → 500 으로 10분씩. 어느 단계에서 무엇(게이트웨이 격벽 · 검색 격벽 ·
#      catalog 풀)이 먼저 막히는지, HPA 가 단계마다 따라오는지(spike 에서 CPU 4% 로 늘지 않았다)
#
# 환경 변수(기본값):
#   COOLDOWN=600     앞 회차가 끝난 뒤 쉬는 초 — 캐시 · HPA 가 가라앉고 시작
#   POLL=60          지금 k6 가 끝났는지 보는 간격(초)
#   CLEANUP=1        끝나면 측정용 설정을 되돌린다(ops/53 reviews default · limits default). 0 이면 그대로 둔다
#   LOAD=./ops/53-load-k6.sh   PGREP=pgrep   KUBECTL=kubectl   LOG_DIR=~/.ecommerce-msa/load-queue   (테스트가 바꿔 끼운다)
#
# 어디서 돌리나 — 지금 k6 가 도는 기계에서(pgrep 이 그 k6 를 봐야 한다). 실습에서는 loadgen VM 이고, 거기에는 kubectl 이 없다:
# 그러면 회차 사이 상태 기록 · limits load · 끝의 되돌리기를 건너뛰고, 호스트에서 칠 명령을 로그 끝에 남긴다.
# 유입 제한은 오래 돌리기 때 호스트에서 올린 그대로 쓴다 — 내려가 있으면 회차의 시작 전 확인(limitsRaisedOrFail)이 429 에서 멈춘다.
# [JA] 夜通しの長時間実行(mixed 8 時間)の後、人が起きていなくても次の計測が続くようにする。ops/53 の回をそのまま呼ぶ —
#      結果は ops/53 と同じフォルダー(load-results/<シナリオ>-<時刻>/)に、この待ち行列の進行は別のログに残る。
#      使い方: plan = 何をどの順で・時間の合計、run = 今の k6 が終わるのを待ってから開始(nohup で起動)、status = 待機中か・何番目の回か・
#      ログの末尾、stop = 待ち行列を止める(動いている k6 の回はそのまま最後まで — 切るならその k6 を)。
#      回(after-soak):
#        1. order-sustained — 注文(書き込み)経路を毎秒 5 件で 55 分維持。在庫予約・注文アウトボックス・Kafka・銀行振込・キャンセルが長く回る
#           (10-17 の order の回は途中で切れた — 書き込み経路を長く測ったことがない)。回の後にアウトボックスの未送信・FAILED を数える
#        2. capacity-steps — 照会を毎秒 100 → 200 → 300 → 400 → 500 と 10 分ずつ。どの段で何(ゲートウェイの隔壁・検索の隔壁・
#           catalog のプール)が先に詰まるか、HPA が段ごとに追いつくか(spike では CPU 4% で増えなかった)
#      環境変数(既定値): COOLDOWN=600 = 前の回の後に休む秒数 — キャッシュ・HPA が落ち着いてから開始、POLL=60 = 今の k6 が終わったかを
#      見る間隔(秒)、CLEANUP=1 = 終わったら計測用の設定を戻す(ops/53 reviews default・limits default)、0 ならそのまま、
#      LOAD・PGREP・KUBECTL・LOG_DIR はテストが差し替える。
#      どこで動かすか — 今 k6 が動いている機械で(pgrep がその k6 を見る必要がある)。実習では loadgen VM で、そこには kubectl がない:
#      その場合は回の間の状態記録・limits load・最後の戻しを飛ばし、ホストで打つコマンドをログの末尾に残す。
#      流入制限は長時間実行のときにホストで上げたものをそのまま使う — 下がっていれば回の開始前の確認(limitsRaisedOrFail)が 429 で止まる。
#      ⚠️ 計測中は CI ランナーを止める — 同じ CPU を分け合う。実習クラスタでだけ(ops/53 が本番アドレスを拒否する)。
#
# ⚠️ 측정 중에는 CI 러너를 멈춘다 — 같은 CPU 를 나눠 쓴다. 실습 클러스터에서만(ops/53 이 운영 주소를 거절한다).
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
LOAD="${LOAD:-$HERE/53-load-k6.sh}"
PGREP="${PGREP:-pgrep}"
KUBECTL="${KUBECTL:-kubectl}"
COOLDOWN="${COOLDOWN:-600}"
POLL="${POLL:-60}"
CLEANUP="${CLEANUP:-1}"
LOG_DIR="${LOG_DIR:-$HOME/.ecommerce-msa/load-queue}"
PID_FILE="$LOG_DIR/queue.pid"
STATE_FILE="$LOG_DIR/state"

# 회차: 이름|RATE|STAGES|시나리오 — STAGES 는 ops/53 과 같은 '길이:목표 도착률' 쉼표 목록.
# RATE 는 가장 높은 단계와 같게 — 시나리오는 VU 수(preAllocated · max)를 RATE 로 정한다. STAGES 만 올리면 VU 가 모자라
# '못 보낸 반복' 이 쌓여, 시스템이 아니라 부하 기계의 한계를 잰다(browse 기본 RATE 100 → 최대 400 VU).
# [JA] RATE は最も高い段と同じに — VU 数は RATE で決まる。STAGES だけ上げると VU が足りず、負荷側の限界を測ることになる。
STEPS=(
  "order-sustained|5|5m:2,5m:5,55m:5,5m:0|order"
  "capacity-steps|500|5m:100,10m:100,2m:200,10m:200,2m:300,10m:300,2m:400,10m:400,2m:500,10m:500,2m:0|browse"
)

has_kubectl() { command -v "$KUBECTL" >/dev/null 2>&1; }
die() { echo "✗ $*" >&2; exit 1; }
now() { date -u '+%Y-%m-%dT%H:%M:%SZ'; }
log() { echo "[$(now)] $*" | tee -a "$LOG_FILE"; }

minutes_of() {   # STAGES → 분(소수 버림) ／ [JA] STAGES → 分(小数切り捨て)
    local total=0 part d
    IFS=',' read -r -a parts <<< "$1"
    for part in "${parts[@]}"; do
        d="${part%%:*}"
        case "$d" in
            *h) total=$(( total + ${d%h} * 3600 )) ;; *m) total=$(( total + ${d%m} * 60 )) ;; *s) total=$(( total + ${d%s} )) ;;
        esac
    done
    echo $(( total / 60 ))
}

cmd_plan() {
    local sum=0 name rate stages scenario m i=1
    echo "대기열 after-soak — 지금 도는 k6 가 끝나면, 회차마다 ${COOLDOWN}초 쉰 뒤:"
    for s in "${STEPS[@]}"; do
        IFS='|' read -r name rate stages scenario <<< "$s"
        m="$(minutes_of "$stages")"; sum=$(( sum + m + COOLDOWN / 60 ))
        printf '  %d. %-16s %-7s %3d분  RATE=%s STAGES=%s\n' "$i" "$name" "$scenario" "$m" "$rate" "$stages"
        i=$(( i + 1 ))
    done
    echo "  합계 약 ${sum}분(쉬는 시간 포함, 주문 회차의 로그인 준비 몇 분 별도)"
    if [ "$CLEANUP" = "1" ]; then echo "  끝나면: report 저장 → reviews default · limits default"; else echo "  끝나면: report 저장(측정용 설정은 그대로 — CLEANUP=0)"; fi
}

# 회차 사이의 상태 — 재시작 합계 · 아웃박스 미전송 · FAILED(값은 읽지 않는다, 수만)
# [JA] 回の間の状態 — 再起動の合計 · アウトボックス未送信 · FAILED(値は読まない、数だけ)
snapshot() {
    local restarts outbox
    if ! has_kubectl; then
        log "  상태($1): kubectl 없음 — 호스트에서 본다(재시작 · 주문 아웃박스, 로그 끝의 명령)"
        return 0
    fi
    restarts="$("$KUBECTL" -n ecommerce get pods -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount}{"\n"}{end}' 2>/dev/null \
        | awk '{s+=$1} END {print s+0}')"
    outbox="$("$KUBECTL" -n postgres exec -i deploy/postgres -- psql -U product -d orderdb -tAqX -c \
        "select outbox_status || '=' || count(*) from orders.payment_outbox_event where outbox_status in ('STARTED','FAILED') group by 1 order by 1" \
        2>/dev/null | tr '\n' ' ')"
    log "  상태($1): ecommerce 재시작 합계 ${restarts:-?} · 주문 아웃박스 ${outbox:-STARTED=0 FAILED=0}"
}

wait_for_running_k6() {
    local waited=0
    while "$PGREP" -x k6 >/dev/null 2>&1; do
        [ "$waited" = 0 ] && log "지금 k6 회차가 돌고 있다 — 끝나기를 기다린다(${POLL}초마다)"
        echo "waiting" > "$STATE_FILE"
        sleep "$POLL"; waited=$(( waited + 1 ))
    done
    [ "$waited" -gt 0 ] && log "앞 회차가 끝났다"
    return 0
}

cmd_run() {
    mkdir -p "$LOG_DIR"; chmod 700 "$LOG_DIR"
    if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
        die "대기열이 이미 돌고 있다(pid $(cat "$PID_FILE")) — ./ops/57-load-queue.sh status"
    fi
    echo $$ > "$PID_FILE"
    LOG_FILE="$LOG_DIR/$(date -u +%Y%m%dT%H%M%SZ).log"
    trap 'rm -f "$PID_FILE"; echo "done" > "$STATE_FILE"' EXIT
    log "대기열 after-soak 시작 — 로그 $LOG_FILE"
    cmd_plan | tee -a "$LOG_FILE" >/dev/null

    wait_for_running_k6
    snapshot "오래 돌리기 뒤"
    if has_kubectl; then
        "$LOAD" limits load >>"$LOG_FILE" 2>&1 || log "  ⚠️ limits load 실패 — 회차가 429 에서 멈출 수 있다"
    else
        log "  kubectl 없음 — 유입 제한은 호스트에서 올린 그대로 쓴다(내려가 있으면 회차가 시작 전 확인에서 멈춘다)"
    fi

    local name rate stages scenario rc i=1
    for s in "${STEPS[@]}"; do
        IFS='|' read -r name rate stages scenario <<< "$s"
        log "쉰다 ${COOLDOWN}초 — 다음: $i. $name"
        echo "cooldown $i $name" > "$STATE_FILE"
        sleep "$COOLDOWN"
        echo "running $i $name" > "$STATE_FILE"
        log "▶ $i. $name — RATE=$rate STAGES=$stages ./ops/53-load-k6.sh $scenario"
        RATE="$rate" STAGES="$stages" "$LOAD" "$scenario" >>"$LOG_FILE" 2>&1; rc=$?
        # 임계값을 넘으면 k6 는 99 로 끝난다 — 그것도 결과다. 다음 회차는 그대로 간다.
        # [JA] 閾値を超えると k6 は 99 で終わる — それも結果だ。次の回はそのまま進む。
        log "■ $i. $name 끝 — 종료 코드 $rc$([ "$rc" = 99 ] && echo ' (임계값 넘음 — 보고서에서 본다)')"
        snapshot "$name 뒤"
        i=$(( i + 1 ))
    done

    log "보고서 저장"
    "$LOAD" report >>"$LOG_FILE" 2>&1 || true
    if [ "$CLEANUP" = "1" ] && ! has_kubectl; then
        log "kubectl 없음 — 호스트에서 되돌린다: ./ops/53-load-k6.sh reviews default && ./ops/53-load-k6.sh limits default"
        log "  상태는 호스트에서: kubectl -n ecommerce get pods · 주문 아웃박스 FAILED 수(docs/83 §3 '대기열')"
    elif [ "$CLEANUP" = "1" ]; then
        log "측정용 설정을 되돌린다 — reviews default · limits default"
        "$LOAD" reviews default >>"$LOG_FILE" 2>&1 || log "  ⚠️ reviews default 실패 — 손으로"
        "$LOAD" limits default >>"$LOG_FILE" 2>&1 || log "  ⚠️ limits default 실패 — 손으로"
    else
        log "측정용 설정은 그대로 둔다(CLEANUP=0) — 끝나면 ./ops/53-load-k6.sh reviews default · limits default"
    fi
    log "대기열 끝"
}

cmd_status() {
    local pid state latest
    pid="$(cat "$PID_FILE" 2>/dev/null || true)"
    state="$(cat "$STATE_FILE" 2>/dev/null || echo '-')"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then echo "대기열 돌고 있음(pid $pid) — $state"; else echo "대기열 없음 — 마지막 상태: $state"; fi
    latest="$(ls -t "$LOG_DIR"/*.log 2>/dev/null | head -1)"
    [ -n "$latest" ] && { echo "── $latest"; tail -8 "$latest"; }
    return 0
}

cmd_stop() {
    local pid; pid="$(cat "$PID_FILE" 2>/dev/null || true)"
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null || die "돌고 있는 대기열이 없다"
    kill "$pid" && echo "대기열을 멈췄다(pid $pid) — 돌고 있던 k6 회차는 끝까지 간다: pgrep -a k6"
}

case "${1:-plan}" in
    plan)   cmd_plan ;;
    run)    cmd_run ;;
    status) cmd_status ;;
    stop)   cmd_stop ;;
    *)      die "알 수 없는 동작: $1 (plan | run | status | stop)" ;;
esac
