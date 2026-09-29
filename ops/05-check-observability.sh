#!/usr/bin/env bash
# 운영 액션 ⑤ — 관측성 스택 기동 후 상태 점검 (docs/11 §1, docs/12 §3)
#
# 사용: ./ops/05-check-observability.sh
#   PROM_URL=http://localhost:9090 AM_URL=http://localhost:9093
#   GRAFANA_URL=http://localhost:3001 JAEGER_URL=http://localhost:16686 OTELCOL_HEALTH_URL=http://localhost:13133
#   LOKI_URL=http://localhost:3100 ALLOY_URL=http://localhost:12345
set -euo pipefail

PROM_URL="${PROM_URL:-http://localhost:9090}"
AM_URL="${AM_URL:-http://localhost:9093}"
GRAFANA_URL="${GRAFANA_URL:-http://localhost:3001}"
JAEGER_URL="${JAEGER_URL:-http://localhost:16686}"
OTELCOL_HEALTH_URL="${OTELCOL_HEALTH_URL:-http://localhost:13133}"
LOKI_URL="${LOKI_URL:-http://localhost:3100}"
ALLOY_URL="${ALLOY_URL:-http://localhost:12345}"
fail=0
# ⚠️ 아래 python3 -c 조각에는 f-string 을 쓰지 않는다. 바깥이 작은따옴표라 안에서 \" 로 적게 되는데,
#    f-string 식 안의 역슬래시는 Python 3.12 미만에서 SyntaxError 다(서버의 python3 에서 실제로 났다).

check() { # name, url
    if curl -sSf -o /dev/null --max-time 5 "$2"; then echo "OK   $1"; else echo "FAIL $1 ($2)"; fail=1; fi
}

echo "=== 컴포넌트 헬스 ==="
check "Prometheus"   "$PROM_URL/-/ready"
check "Alertmanager" "$AM_URL/-/ready"
check "Grafana"      "$GRAFANA_URL/api/health"
check "OTel Collector" "$OTELCOL_HEALTH_URL/"
check "Jaeger"       "$JAEGER_URL/api/services"
check "Loki"         "$LOKI_URL/ready"
check "Alloy"        "$ALLOY_URL/-/ready"

echo
echo "=== Prometheus 스크레이프 타깃 (down 이 있으면 해당 서비스/방화벽 확인) ==="
curl -sf "$PROM_URL/api/v1/targets" | python3 -c '
import json,sys
d=json.load(sys.stdin)
for t in d["data"]["activeTargets"]:
    svc=t["labels"].get("service", t["labels"].get("job","?"))
    print("  %-5s %-20s %s" % (t["health"], svc, t["scrapeUrl"]))' || { echo "  타깃 조회 실패"; fail=1; }

echo
echo "=== 로드된 경보 규칙 ==="
curl -sf "$PROM_URL/api/v1/rules" | python3 -c '
import json,sys
d=json.load(sys.stdin)
n=0
for g in d["data"]["groups"]:
    for r in g["rules"]:
        n+=1; print("  [%-8s] %s" % (r.get("state", "-"), r["name"]))
print("  총 %d개 규칙" % n)' || { echo "  규칙 조회 실패"; fail=1; }

echo
echo "=== Loki 에 들어온 서비스 로그 (docs/51 §4) ==="
# 비어 있으면: k3s 라면 Alloy 가 /var/log/pods 를 읽는지(docker compose ... logs alloy),
#              로컬이라면 서비스를 모듈 디렉터리에서 띄웠는지(<모듈>/logs/*.json) 본다.
curl -sf -G "$LOKI_URL/loki/api/v1/label/service/values" | python3 -c '
import json,sys
d=json.load(sys.stdin).get("data") or []
print("  " + (", ".join(d) if d else "아직 없음 — k3s: /var/log/pods/ecommerce_* 를 Alloy 가 읽는지, 로컬: <모듈>/logs/*.json 이 생기는지 확인"))' || { echo "  Loki 조회 실패"; fail=1; }

echo
echo "=== Loki 로그 경보 규칙 (docs/51 §4i) ==="
# 비어 있으면: observability/loki-rules 가 /etc/loki/rules/fake 에 붙었는지(compose), loki-config.yml 의
#              ruler 블록이 있는지. 경보 상태는 inactive/pending/firing 이다.
curl -sf "$LOKI_URL/prometheus/api/v1/rules" | python3 -c '
import json,sys
groups=(json.load(sys.stdin).get("data") or {}).get("groups") or []
rules=[(r.get("name"), r.get("state")) for g in groups for r in g.get("rules", [])]
for name, state in rules:
    print("  %-24s %s" % (name, state))
need={"ManualActionRequired","ProductSoldOut"}
missing=need-set(n for n,_ in rules)
if missing:
    print("  빠진 규칙: %s" % ", ".join(sorted(missing))); sys.exit(1)' || { echo "  Loki 경보 규칙이 없다 — ruler 설정·규칙 마운트를 확인"; fail=1; }

echo
echo "=== 추적 파이프라인 (docs/63) ==="
# 서비스 → Collector → Jaeger · span_metrics. 어느 한 곳이 끊겨도 서비스는 오류 없이 돈다 — 그래서 여기서 본다.
curl -sf "$JAEGER_URL/api/services" | python3 -c '
import json,sys
d=[s for s in (json.load(sys.stdin).get("data") or []) if s != "jaeger"]
print("  Jaeger 에 추적을 보낸 서비스: " + (", ".join(sorted(d)) if d else "아직 없음 — 서비스가 OTLP 로 보내는지(아래 파드 점검) 본다"))' || { echo "  Jaeger 조회 실패"; fail=1; }
# Collector 가 받은 · 거절한 · 못 내보낸 스팬(지난 1시간). 거절은 memory_limiter, 실패는 Jaeger 쪽 문제다.
for m in receiver_accepted receiver_refused exporter_send_failed; do
    curl -sf -G "$PROM_URL/api/v1/query" \
        --data-urlencode "query=sum(increase({__name__=~\"otelcol_${m}_spans(_total)?\"}[1h]))" \
        | M="$m" python3 -c '
import json,os,sys
r=json.load(sys.stdin)["data"]["result"]
print("  %-22s %8.0f  (지난 1시간)" % (os.environ["M"], float(r[0]["value"][1]) if r else 0))' \
        || { echo "  Collector 지표 조회 실패($m)"; fail=1; }
done
# 꼬리 샘플링(docs/63 §5) — Jaeger 에 남긴 추적 / 버린 추적. 오류 · 느린 요청 · 주문/결제/배송은 모두, 나머지는 10%.
curl -sf -G "$PROM_URL/api/v1/query" \
    --data-urlencode 'query=sum by (sampled) (increase({__name__=~"otelcol_processor_tail_sampling_global_count_traces_sampled(_total)?"}[1h]))' \
    | python3 -c '
import json,sys
r={x["metric"].get("sampled"): float(x["value"][1]) for x in json.load(sys.stdin)["data"]["result"]}
kept, dropped = r.get("true", 0), r.get("false", 0)
total = kept + dropped
print("  꼬리 샘플링            남김 %.0f / 버림 %.0f  (%s, 지난 1시간)" % (kept, dropped, ("%.0f%% 남김" % (100 * kept / total)) if total else "추적 없음"))' \
    || { echo "  꼬리 샘플링 지표 조회 실패"; fail=1; }
curl -sf -G "$PROM_URL/api/v1/query" --data-urlencode 'query=count(count by (service_name) (traces_span_metrics_calls_total))' \
    | python3 -c '
import json,sys
r=json.load(sys.stdin)["data"]["result"]
print("  span_metrics 가 있는 서비스 수: %s" % (r[0]["value"][1] if r else "0 — 추적이 아직 없거나 otel-span-metrics 타깃이 down"))' || true

echo
echo "=== k3s 파드 → 노드의 OTel Collector (docs/51 §4d, docs/63) ==="
# 서비스 파드는 OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://<노드 IP>:4318/v1/traces 로 추적을 보낸다. 방화벽이
# 막으면 오류 없이 조용히 버려지므로 파드 안에서 직접 보내 본다 — 빈 요청({})은 스팬 0개라 저장되지 않는다.
# (GET 은 405 라 쓸 수 없다.) kubectl 이 없으면(로컬 PC) 건너뛴다.
if command -v kubectl >/dev/null 2>&1 && kubectl get ns ecommerce >/dev/null 2>&1; then
    NODE_IP=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')
    if kubectl -n ecommerce run otlp-probe --rm -i --restart=Never --quiet --image=busybox:1.36 \
            --command -- wget -q -T 5 -O /dev/null --header 'Content-Type: application/json' --post-data '{}' \
            "http://$NODE_IP:4318/v1/traces" >/dev/null 2>&1; then
        echo "OK   파드에서 http://$NODE_IP:4318/v1/traces 에 닿는다"
    else
        echo "FAIL 파드에서 http://$NODE_IP:4318 에 닿지 않는다 — otel-collector 가 떠 있는지, 호스트 방화벽이"
        echo "     파드 대역(k3s 기본 10.42.0.0/16)→4318 을 막는지 본다(docs/51 §4d). 옛 9411 규칙은 이제 필요 없다."
        fail=1
    fi
else
    echo "  (kubectl 이 없거나 ecommerce 네임스페이스가 없다 — 건너뜀)"
fi

echo
if [ "$fail" = "0" ]; then
    echo "모든 점검 통과. 남은 것: observability/alertmanager.yml 의 receiver 를 실제 채널(Slack/웹훅)로 교체"
else
    echo "일부 점검 실패 — 위 FAIL 항목을 확인하세요"; exit 1
fi
