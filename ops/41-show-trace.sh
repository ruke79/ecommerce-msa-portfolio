#!/usr/bin/env bash
# 운영 액션 ㊶ — 추적 하나를 터미널에 나무로 그린다 (docs/63 §3)
#
# 브라우저 없이 "주문 한 건이 서비스 · Kafka 를 어떻게 건넜나" 를 본다. ruke79/kafka 의 test/show-trace.sh 를
# 이 저장소에 옮겼다 — 거기서는 재시도 사다리(5s · 30s 대기)가 스팬 사이 빈틈으로 보였고, 여기서는 아웃박스
# 릴레이의 지연과 사가의 왕복(order → pay → order → delivery)이 그렇게 보인다.
#
# 사용:
#   ./ops/41-show-trace.sh                     # gateway-server 의 가장 최근 추적
#   ./ops/41-show-trace.sh --longest           # 스팬이 가장 많은 것 — 대개 주문 · 결제 사가
#   ./ops/41-show-trace.sh --errors            # 오류가 난 추적 중 가장 최근 것
#   ./ops/41-show-trace.sh 4bf92f3577b34da6a3ce929d0e0e4736   # 로그 줄의 traceId 로 바로
#   SERVICE=order-service LOOKBACK=6h ./ops/41-show-trace.sh --longest
#
# 서버에서는 Jaeger 가 루프백(127.0.0.1:16686)에만 열려 있으므로 그 서버에서 돌린다. 내 PC 에서 보려면
#   ssh -L 16686:localhost:16686 <서버>   를 열어 두고 여기서 돌린다.
set -euo pipefail

JAEGER="${JAEGER:-http://localhost:16686}"
SERVICE="${SERVICE:-gateway-server}"
LOOKBACK="${LOOKBACK:-1h}"
PICK=newest
TRACE_ID=""
case "${1:-}" in
    --longest) PICK=longest ;;
    --errors)  PICK=errors ;;
    -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
    "")        ;;
    *)
        # 로그의 traceId(32자리 16진수. 옛 Brave 로그는 16자리)
        if [[ "$1" =~ ^[0-9a-f]{16,32}$ ]]; then TRACE_ID="$1"; else echo "알 수 없는 인자: $1 (--help)"; exit 2; fi ;;
esac

if [ -n "$TRACE_ID" ]; then
    URL="$JAEGER/api/traces/$TRACE_ID"
elif [ "$PICK" = errors ]; then
    URL="$JAEGER/api/traces?service=$SERVICE&limit=20&lookback=$LOOKBACK&tags=%7B%22error%22%3A%22true%22%7D"
else
    URL="$JAEGER/api/traces?service=$SERVICE&limit=20&lookback=$LOOKBACK"
fi

if ! BODY=$(curl -fsS --max-time 10 "$URL" 2>/dev/null); then
    echo "Jaeger 에서 가져오지 못했다: $URL"
    echo "  Jaeger 가 떠 있는지(./ops/05-check-observability.sh) 본다. id 로 찾았는데 없다면: 빠르고 정상인 둘러보기 요청은"
    echo "  꼬리 샘플링이 10% 만 남긴다(오류 · 느린 요청 · 주문/결제/배송은 모두 남는다), 보관은 7일이다(docs/63 §5)."
    exit 1
fi

PICK="$PICK" python3 -c '
import json, os, sys

traces = json.load(sys.stdin).get("data") or []
if not traces:
    print("추적이 아직 없다 — 그 서비스로 요청을 한 번 보내고 다시 본다(샘플링: TRACING_SAMPLING_PROBABILITY)")
    raise SystemExit

pick = os.environ["PICK"]
if pick == "longest":
    trace = max(traces, key=lambda t: len(t["spans"]))
else:
    trace = max(traces, key=lambda t: min(s["startTime"] for s in t["spans"]))

names = {k: v["serviceName"] for k, v in trace["processes"].items()}
spans = {s["spanID"]: s for s in trace["spans"]}
start = min(s["startTime"] for s in trace["spans"])

def tag(span, key):
    for t in span.get("tags", []):
        if t["key"] == key:
            return t["value"]
    return None

children, roots = {}, []
for span in trace["spans"]:
    parents = [r["spanID"] for r in span.get("references", []) if r["refType"] == "CHILD_OF"]
    if parents and parents[0] in spans:
        children.setdefault(parents[0], []).append(span)
    else:
        roots.append(span)

def show(span, depth=0):
    at = (span["startTime"] - start) / 1000.0
    took = span["duration"] / 1000.0
    err = tag(span, "error") in (True, "true") or tag(span, "otel.status_code") == "ERROR"
    mark = "✗" if err else " "
    name = ("  " * depth + span["operationName"])[:52]
    # 시작 시각의 차이가 핵심이다: 부모가 끝난 뒤 한참 뒤에 시작한 자식은 아웃박스 릴레이 · Kafka 를 건넌 것이고,
    # 그 빈틈이 "그동안 아무 일도 안 했다" 이다.
    print("%s %-16s %-52s +%8.0fms %9.1fms" % (mark, names[span["processID"]][:16], name, at, took))
    for child in sorted(children.get(span["spanID"], []), key=lambda s: s["startTime"]):
        show(child, depth + 1)

services = sorted(set(names[s["processID"]] for s in trace["spans"]))
print("trace %s  스팬 %d개  서비스 %s" % (trace["traceID"], len(trace["spans"]), ", ".join(services)))
print("  %-16s %-52s %10s %11s" % ("service", "span", "at", "took"))
for root in sorted(roots, key=lambda s: s["startTime"]):
    show(root)
' <<<"$BODY"
