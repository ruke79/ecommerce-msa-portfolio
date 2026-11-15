#!/usr/bin/env bash
# k6 를 클러스터 안의 Job 으로 돌린다 — k6 와 같은 인자를 받는 대역(ops/53 의 K6= 로 꽂는다, docs/91 §4-2)
# [JA] k6 をクラスター内の Job として動かす — k6 と同じ引数を受ける代役(ops/53 の K6= に差す)
#
#   K6=ops/tools/k6-job.sh ./ops/53-load-k6.sh browse …        # ops/53 이 부르는 그대로
#   ops/tools/k6-job.sh run --summary-export out/summary.json -e GATEWAY=… ops/k6/browse.js
#
# 왜: 운영 노드의 게이트웨이를 Cloudflare 를 거치지 않고 재려면 부하기가 클러스터 안에 있어야 한다(노드에서는 클러스터 DNS 가
# 풀리지 않는다). 시나리오(ops/k6/*.js 전부 — 서로 import 한다)를 ConfigMap 으로 싣고 grafana/k6 Job 을 한 번 띄운 뒤, 출력은
# 그대로 흘리고 요약(summary.json)은 로그의 표지 사이에서 꺼내 요청한 경로에 쓴다. 종료 코드는 k6 의 것(임계값을 넘으면 99).
#
# 🔒 TLS 는 검증한다 — tls-keystore 의 공개 CA(ca.crt)만 마운트해 SSL_CERT_FILE 로 믿는다(키스토어 · 비밀번호는 싣지 않는다).
#    게이트웨이 인증서의 SAN 에 *.ecommerce.svc.cluster.local 이 있다(ops/07). 비밀번호 같은 비밀은 넘기지 않는다 —
#    -e DEMO_PASSWORD 가 오면 거절한다(Job 명세 · 이벤트에 남는다). 로그인 시나리오는 이 대역으로 돌리지 않는다.
#
# 환경 변수: APP_NS=ecommerce  K6_IMAGE=grafana/k6:0.54.0  K6_CPU=1  K6_MEMORY=1Gi  K6_TIMEOUT_SECONDS=3600  KEEP_JOB=0
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
K6_DIR="$HERE/k6"
APP_NS="${APP_NS:-ecommerce}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:0.54.0}"
K6_CPU="${K6_CPU:-1}"
K6_MEMORY="${K6_MEMORY:-1Gi}"
K6_TIMEOUT_SECONDS="${K6_TIMEOUT_SECONDS:-3600}"
BEGIN_MARK="=====K6-SUMMARY-BEGIN====="
END_MARK="=====K6-SUMMARY-END====="

die() { echo "ERROR(k6-job): $1" >&2; exit 2; }

[ "${1:-}" = "run" ] || die "k6-job.sh run [k6 인자…] <ops/k6 의 시나리오.js> — k6 run 만 대신한다"
shift
command -v kubectl >/dev/null 2>&1 || die "kubectl 이 필요하다"
[[ "$K6_IMAGE" =~ ^[a-z0-9./-]+:[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "K6_IMAGE 는 정확한 판(예 grafana/k6:0.54.0)"
[[ "$K6_CPU" =~ ^[0-9]+(\.[0-9]+|m)?$ ]] && [[ "$K6_MEMORY" =~ ^[0-9]+(Mi|Gi)$ ]] || die "K6_CPU · K6_MEMORY 형식(예 1 · 1Gi)"

# 인자 — 요약 경로와 시나리오만 바꾸고 나머지는 그대로 넘긴다
summary=""; script=""; args=(); prev=""
for a in "$@"; do
    if [ "$prev" = "--summary-export" ]; then
        summary="$a"; args+=("/out/summary.json"); prev=""; continue
    fi
    case "$a" in
        DEMO_PASSWORD=*) die "비밀(DEMO_PASSWORD)은 Job 명세에 싣지 않는다 — 로그인 시나리오는 이 대역으로 돌리지 않는다" ;;
        *.js) script="$a"; args+=("/scripts/$(basename "$a")"); prev=""; continue ;;
    esac
    args+=("$a"); prev="$a"
done
[ -n "$script" ] && [ -f "$K6_DIR/$(basename "$script")" ] || die "시나리오가 ops/k6 에 없다: ${script:-없음}"
[ -n "${DEMO_PASSWORD:-}" ] && die "DEMO_PASSWORD 가 환경에 있다 — 이 대역은 비밀을 넘기지 않는다(로그인 시나리오는 로컬 k6 로)"

stamp="$(date -u +%Y%m%d%H%M%S)-$$"
job="k6-load-$stamp"
cm="k6-scripts-$stamp"

cleanup() {
    [ "${KEEP_JOB:-0}" = "1" ] && return 0
    kubectl -n "$APP_NS" delete job "$job" --ignore-not-found --wait=false >/dev/null 2>&1 || true
    kubectl -n "$APP_NS" delete configmap "$cm" --ignore-not-found >/dev/null 2>&1 || true
}
trap cleanup EXIT

# 시나리오 전부를 ConfigMap 으로(서로 import 한다)
files=()
for f in "$K6_DIR"/*.js; do files+=("--from-file=$(basename "$f")=$f"); done
kubectl -n "$APP_NS" create configmap "$cm" "${files[@]}" >/dev/null

# 컨테이너 인자 — sh -c 의 스크립트, $0, 그다음 k6 run 의 인자. JSON 배열로(따옴표 · 공백이 섞여도 그대로)
runner="k6 \"\$@\"; rc=\$?; echo $BEGIN_MARK; cat /out/summary.json 2>/dev/null; echo; echo $END_MARK; exit \$rc"
args_json="$(python3 -I -c 'import json,sys; print(json.dumps(sys.argv[1:]))' "$runner" k6 run "${args[@]}")"

# Job — 한 번만(backoffLimit 0), 끝나면 지운다. 같은 네임스페이스라 NetworkPolicy(k8s/95)가 게이트웨이로 가는 길을 연다.
kubectl -n "$APP_NS" apply -f - >/dev/null <<EOF
apiVersion: batch/v1
kind: Job
metadata:
  name: $job
  labels: { app: k6-load }
spec:
  backoffLimit: 0
  activeDeadlineSeconds: $K6_TIMEOUT_SECONDS
  ttlSecondsAfterFinished: 600
  template:
    metadata:
      labels: { app: k6-load }
    spec:
      restartPolicy: Never
      automountServiceAccountToken: false
      securityContext: { runAsNonRoot: true, runAsUser: 12345 }
      containers:
        - name: k6
          image: $K6_IMAGE
          imagePullPolicy: IfNotPresent
          command: ["sh", "-c"]
          args: $args_json
          env:
            - { name: SSL_CERT_FILE, value: /ca/ca.crt }
          resources:
            requests: { cpu: "$K6_CPU", memory: "$K6_MEMORY" }
            limits: { cpu: "$K6_CPU", memory: "$K6_MEMORY" }
          volumeMounts:
            - { name: scripts, mountPath: /scripts, readOnly: true }
            - { name: ca, mountPath: /ca, readOnly: true }
            - { name: out, mountPath: /out }
      volumes:
        - name: scripts
          configMap: { name: $cm }
        # 공개 인증서(ca.crt)만 — tls-keystore 의 키스토어 · 비밀번호는 싣지 않는다
        - name: ca
          secret: { secretName: tls-keystore, items: [ { key: ca.crt, path: ca.crt } ] }
        - name: out
          emptyDir: {}
EOF

echo ">>> k6 Job $job ($K6_IMAGE · cpu $K6_CPU · $K6_MEMORY) — $(basename "$script")"

# 파드가 뜰 때까지
pod=""
for _ in $(seq 1 120); do
    pod="$(kubectl -n "$APP_NS" get pods -l job-name="$job" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
    [ -n "$pod" ] && phase="$(kubectl -n "$APP_NS" get pod "$pod" -o jsonpath='{.status.phase}' 2>/dev/null || true)" || phase=""
    case "${phase:-}" in Running|Succeeded|Failed) break ;; esac
    sleep 2
done
[ -n "$pod" ] || die "Job 파드가 뜨지 않았다 — kubectl -n $APP_NS describe job $job"

log="$(mktemp)"
kubectl -n "$APP_NS" logs -f "$pod" 2>&1 | tee "$log" | sed "/$BEGIN_MARK/,/$END_MARK/d" || true

# 요약 — 표지 사이
if [ -n "$summary" ]; then
    mkdir -p "$(dirname "$summary")"
    sed -n "/$BEGIN_MARK/,/$END_MARK/p" "$log" | sed '1d;$d' > "$summary"
    [ -s "$summary" ] || echo "  ⚠️  요약을 받지 못했다(k6 가 요약 전에 끝났다)" >&2
fi
rm -f "$log"

rc="$(kubectl -n "$APP_NS" get pod "$pod" -o jsonpath='{.status.containerStatuses[0].state.terminated.exitCode}' 2>/dev/null || true)"
[[ "$rc" =~ ^[0-9]+$ ]] || rc=1
exit "$rc"
