#!/usr/bin/env bash
# nginx 를 통과하는 SSE 가 **실제로 스트리밍되는지** 검증한다.
#
# 왜 이 테스트가 필요한가
# -----------------------
# nginx 의 기본 설정은 SSE 를 조용히 망가뜨린다. 연결은 맺어지고 HTTP 200 이 오고 오류도 없다.
# 이벤트만 도착하지 않다가, 스트림이 끊길 때 그동안의 것이 한꺼번에 쏟아진다.
#
# 그래서 "받은 내용"만 확인하는 테스트는 **버퍼링을 잡아내지 못한다** — 끝까지 기다리면 결국
# 다 오기 때문이다. 잡으려면 **도착 시각**을 봐야 한다. 이 스크립트는 서버가 1초 간격으로
# 보내는 이벤트가 클라이언트에도 1초 간격으로 도착하는지 잰다.
#
# 검증 방식
#   ① 1초 간격으로 이벤트를 보내는 SSE 오리진을 띄운다
#   ② 실제 nginx 를 저장소의 설정(frontend/nginx.conf)으로 띄운다
#   ③ 첫 이벤트까지의 시간과 이벤트 간 간격을 잰다 → 버퍼링되면 전부 마지막에 몰린다
#   ④ **대조군**: 버퍼링을 켠 설정으로 같은 측정을 해서, 이 테스트가 실패를 실제로
#      잡아내는지 확인한다 (실패하지 않는 테스트는 테스트가 아니다)
#
# 사용법:  ./ops/18-test-nginx-sse.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

NGINX_IMAGE="${NGINX_IMAGE:-nginx:1.27-alpine}"
PY_IMAGE="${PY_IMAGE:-python:3.12-alpine}"
NET="sse-test-net"
WORK="$(mktemp -d)"

pass=0; fail=0
ok()  { echo "  ✅ $*"; pass=$((pass+1)); }
bad() { echo "  ❌ $*"; fail=$((fail+1)); }

cleanup() {
    docker rm -f sse-origin sse-nginx sse-nginx-buffered >/dev/null 2>&1 || true
    docker network rm "$NET" >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT

command -v docker >/dev/null || { echo "ERROR: docker 가 필요합니다" >&2; exit 2; }

echo "════════════════════════════════════════════════════════════"
echo " nginx SSE 스트리밍 검증"
echo "════════════════════════════════════════════════════════════"
echo

docker network create "$NET" >/dev/null 2>&1 || true

# ── ① SSE 오리진 ─────────────────────────────────────────────────────────────
# 1초 간격으로 5개의 이벤트를 보낸다. 게이트웨이/user-service 의 SSE 엔드포인트를 흉내 낸다.
cat > "$WORK/origin.py" <<'PY'
import http.server, socketserver, time, sys

class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if not self.path.startswith("/sse"):
            self.send_response(404); self.end_headers(); return

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        # 업스트림이 이 헤더를 주면 nginx 는 **설정과 무관하게** 그 응답의 버퍼링을 끈다.
        # 실무에서는 설정과 함께 두 겹으로 두지만, 대조군에서는 이걸 빼야 한다 —
        # 그렇지 않으면 proxy_buffering on 을 켜도 버퍼링이 일어나지 않아
        # "테스트가 버퍼링을 잡아내는가"를 확인할 수 없다. (실제로 그 함정을 밟았다.)
        if not self.path.startswith("/sse-raw/"):
            self.send_header("X-Accel-Buffering", "no")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()

        for i in range(1, 6):
            payload = f"id: {i}\nevent: tick\ndata: {{\"seq\": {i}}}\n\n".encode()
            chunk = f"{len(payload):X}\r\n".encode() + payload + b"\r\n"
            try:
                self.wfile.write(chunk)
                self.wfile.flush()
            except BrokenPipeError:
                return
            time.sleep(1)
        try:
            self.wfile.write(b"0\r\n\r\n"); self.wfile.flush()
        except BrokenPipeError:
            pass

    def log_message(self, *a):
        pass

socketserver.ThreadingTCPServer.allow_reuse_address = True
with socketserver.ThreadingTCPServer(("", 8000), Handler) as srv:
    srv.serve_forever()
PY

docker run -d --name sse-origin --network "$NET" \
    -v "$WORK/origin.py:/origin.py:ro" \
    "$PY_IMAGE" python3 /origin.py >/dev/null
sleep 2
ok "SSE 오리진 기동 (1초 간격 × 5회)"

# ── ② 저장소 설정으로 nginx 기동 ──────────────────────────────────────────────
docker run -d --name sse-nginx --network "$NET" -p 18081:8080 \
    -e GATEWAY_URL="http://sse-origin:8000" \
    -e NGINX_ENVSUBST_FILTER='^(GATEWAY_URL)$' \
    -v "$ROOT/frontend/nginx.conf:/etc/nginx/templates/default.conf.template:ro" \
    "$NGINX_IMAGE" >/dev/null
sleep 3

if ! docker exec sse-nginx nginx -t >/dev/null 2>&1; then
    bad "nginx 설정 문법 오류"
    docker exec sse-nginx nginx -t 2>&1 | sed 's/^/     /'
    exit 1
fi
ok "nginx 설정 문법 정상 (저장소의 frontend/nginx.conf)"

# 템플릿이 실제로 치환됐는지 — ${GATEWAY_URL} 이 남아 있으면 프록시가 동작하지 않는다.
if docker exec sse-nginx grep -q 'GATEWAY_URL' /etc/nginx/conf.d/default.conf 2>/dev/null; then
    bad "envsubst 치환이 되지 않았다 — \${GATEWAY_URL} 이 그대로 남아 있다"
else
    ok "envsubst 치환 완료"
fi

# nginx 자체 변수가 살아남았는지 — 필터가 없으면 \$host 까지 지워진다.
if docker exec sse-nginx grep -qE 'proxy_set_header +Host +\$host' /etc/nginx/conf.d/default.conf 2>/dev/null; then
    ok "nginx 자체 변수(\$host)가 보존됨 — ENVSUBST_FILTER 가 동작한다"
else
    bad "nginx 자체 변수가 치환돼 사라졌다 — NGINX_ENVSUBST_FILTER 를 확인하세요"
fi
echo

# ── ③ 도착 시각 측정 ─────────────────────────────────────────────────────────
# curl 로 받으면서 각 줄의 도착 시각을 기록한다. 버퍼링되면 전부 마지막에 몰린다.
measure() {
    local url="$1" out="$2" extra="${3:-}"
    # shellcheck disable=SC2086
    curl -sN --max-time 20 $extra "$url" 2>/dev/null \
        | while IFS= read -r line; do
              printf '%s %s\n' "$(date +%s.%N)" "$line"
          done > "$out" || true
}

echo "도착 시각 측정 (오리진은 1초 간격으로 보낸다)"
start="$(date +%s.%N)"
measure "http://localhost:18081/sse/notifications" "$WORK/timed.txt"

data_times=$(awk '/data:/ {print $1}' "$WORK/timed.txt")
count=$(wc -l <<<"$data_times" | tr -d ' ')

if [[ "$count" -lt 5 ]]; then
    bad "이벤트를 5건 받지 못했다 (받은 수: $count)"
    sed 's/^/     /' "$WORK/timed.txt" | head -10
else
    ok "이벤트 5건 수신"

    first="$(head -1 <<<"$data_times")"
    last="$(tail -1 <<<"$data_times")"
    first_delay="$(awk -v a="$first" -v b="$start" 'BEGIN{printf "%.2f", a-b}')"
    span="$(awk -v a="$last" -v b="$first" 'BEGIN{printf "%.2f", a-b}')"

    echo "     첫 이벤트까지: ${first_delay}초 · 첫~마지막 간격: ${span}초"

    # 버퍼링되면 첫 이벤트가 스트림 종료(약 5초) 시점에야 온다.
    if awk -v d="$first_delay" 'BEGIN{exit !(d < 2.5)}'; then
        ok "첫 이벤트가 즉시 도착 (${first_delay}초) — 버퍼링되지 않는다"
    else
        bad "첫 이벤트가 ${first_delay}초 뒤에 도착 — 버퍼링되고 있다"
    fi

    # 이벤트들이 시간에 걸쳐 흩어져 있어야 한다. 한꺼번에 오면 span 이 0 에 가깝다.
    if awk -v s="$span" 'BEGIN{exit !(s > 2.5)}'; then
        ok "이벤트가 시간에 걸쳐 도착 (${span}초) — 한꺼번에 쏟아지지 않는다"
    else
        bad "이벤트가 ${span}초 안에 몰려 도착 — 버퍼에 모였다가 한 번에 나온 것이다"
    fi
fi
echo

# ── ④ 대조군: 이 테스트가 실패를 실제로 잡는가 ────────────────────────────────
# 버퍼링을 켠 설정으로 같은 측정을 한다. 여기서 통과해 버리면 위의 통과는 의미가 없다.
echo "대조군 — gzip 을 적용하면 이 테스트가 실패를 잡아내는가"
# ⚠️ 처음에는 `proxy_buffering on` 을 대조군으로 썼는데 **재현되지 않았다** — nginx 1.27 은
#    소량 청크를 그대로 흘려보내 첫 이벤트가 0.02초에 도착했다. HTTP/1.0 도 마찬가지였다.
#    실제로 SSE 를 죽이는 것은 **gzip** 이었다(12초 동안 한 건도 도착하지 않음).
#    대조군은 재현되는 것으로 두어야 의미가 있다.
cat > "$WORK/buffered.conf" <<'CONF'
server {
    listen 8080;
    location /sse-raw/ {
        proxy_pass http://sse-origin:8000;
        proxy_http_version 1.1;
        # SSE 응답에 gzip 을 적용한다 — 압축은 블록을 채워야 출력이 나오므로 스트림이 멈춘다.
        # 저장소 설정에도 server 레벨 `gzip on` 이 있어, 누군가 gzip_types 에
        # text/event-stream 을 추가하면 그대로 재현되는 시나리오다.
        gzip on;
        gzip_types text/event-stream;
        gzip_proxied any;
        gzip_min_length 0;
    }
}
CONF
docker run -d --name sse-nginx-buffered --network "$NET" -p 18082:8080 \
    -v "$WORK/buffered.conf:/etc/nginx/conf.d/default.conf:ro" \
    "$NGINX_IMAGE" >/dev/null
sleep 3

start2="$(date +%s.%N)"
measure "http://localhost:18082/sse-raw/notifications" "$WORK/timed-buffered.txt" "-H Accept-Encoding:gzip"
btimes=$(awk '/data:/ {print $1}' "$WORK/timed-buffered.txt")

if [[ -z "$btimes" ]]; then
    ok "gzip 설정에서는 이벤트가 아예 도착하지 않았다 — 테스트가 차이를 잡아낸다"
else
    bfirst="$(head -1 <<<"$btimes")"
    bdelay="$(awk -v a="$bfirst" -v b="$start2" 'BEGIN{printf "%.2f", a-b}')"
    echo "     gzip 설정의 첫 이벤트까지: ${bdelay}초"
    if awk -v d="$bdelay" 'BEGIN{exit !(d > 2.5)}'; then
        ok "gzip 설정에서는 ${bdelay}초 지연 — 이 테스트가 차이를 잡아낸다"
    else
        bad "gzip 을 적용했는데도 즉시 도착했다 — 이 테스트는 실패를 잡아내지 못한다"
    fi
fi
echo

echo "════════════════════════════════════════════════════════════"
echo " 통과 $pass · 실패 $fail"
if [[ "$fail" -eq 0 ]]; then
    echo " ✅ nginx 를 통과하는 SSE 가 실제로 스트리밍된다"
else
    echo " ❌ SSE 가 정상 동작하지 않는다"
fi
echo "════════════════════════════════════════════════════════════"
exit $(( fail > 0 ? 1 : 0 ))
