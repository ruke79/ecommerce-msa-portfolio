#!/usr/bin/env bash
# 운영 액션 ㉗ — Secret 의 자격증명이 **실제로 통하는지** 확인한다.
#
# ── 왜 필요한가 ────────────────────────────────────────────────────────
#
# ops/25 는 프로비저닝 시점에 `product` 로 로그인해 보고 끝난다. 그 순간에는 맞다.
# 문제는 그 뒤다. 두 시스템(관리형 DB 와 클러스터 Secret)이 **각자 따로 바뀔 수 있고,
# 어긋나도 아무도 알려주지 않는다.** 어긋난 상태는 이렇게 드러난다:
#
#   · 이미 떠 있는 파드는 **계속 잘 돈다** — 기동할 때 읽은 값을 들고 있다
#   · 그래서 `kubectl get pods` 는 전부 초록불이다
#   · 다음 롤아웃·재시작·노드 교체 때 **한꺼번에** 죽는다
#   · 그때의 오류는 `password authentication failed for user 'product'` 하나뿐이라,
#     호스트인지 TLS 인지 역할인지 비밀번호인지 메시지가 알려주지 않는다
#
# 실제 배포에서 이 상태로 몇 시간을 보냈다(docs/33 §13). 5시간 된 파드는 Running 인데
# 새로 뜨는 파드만 CrashLoopBackOff 였고, 원인은 Secret 의 비밀번호였다.
#
# ── 어떻게 확인하는가 ──────────────────────────────────────────────────
#
# **값을 비교하지 않는다. 접속해 본다.**
#
# 이번 배포에서 반복해 확인한 것이 있다 — "이름이 해석된다"가 "거기 뭔가 있다"를 뜻하지
# 않는다(Neon 의 와일드카드 DNS, 파드 없는 Service 의 ClusterIP). 값 비교도 같은 함정을
# 갖는다: Secret 과 어딘가에 적어 둔 값이 같다고 해서 **DB 가 그 값을 받아들인다는 보장이
# 없다.** 유일하게 믿을 수 있는 검사는 그 자격증명으로 실제 로그인해 보는 것이다.
#
# 그리고 검사는 **클러스터 안에서** 돌린다. 이유가 둘이다:
#   · 비밀번호가 로컬 셸·히스토리·프로세스 목록에 나오지 않는다 (Secret 참조로 주입한다)
#   · 서비스가 받는 값과 **정확히 같은 값**을 시험하게 된다. 로컬 파일을 읽어 시험하면
#     "파일은 맞는데 클러스터 Secret 이 다른" 경우를 놓친다 — 그게 실제로 있었던 일이다
#
# ── 사용 ───────────────────────────────────────────────────────────────
#
#   ./ops/27-check-secret-drift.sh                 # 전부 점검
#   ./ops/27-check-secret-drift.sh --only postgres # postgres | redis | mongo | file
#   NAMESPACE=ecommerce ./ops/27-check-secret-drift.sh
#
# 종료 코드: 0 = 전부 통과, 1 = 하나라도 어긋남, 2 = 점검 불가(클러스터 미접속 등)
#
# ⚠️ 이 스크립트는 값을 **절대 출력하지 않는다.** 어긋났다는 사실과 어디가 어긋났는지만
#    알린다. 실패 메시지에 비밀번호를 실으면 그 메시지가 로그·이슈·스크린샷으로 퍼진다.
set -uo pipefail

NAMESPACE="${NAMESPACE:-ecommerce}"
SECRET="${SECRET:-ecommerce-secrets}"
CONFIGMAP="${CONFIGMAP:-ecommerce-config}"
ONLY=""
# 하나만 붙어 보면 충분하다 — 같은 역할·같은 서버다. 전부 돌면 느리기만 하다.
PROBE_DB="${PROBE_DB:-userdb}"

while [ $# -gt 0 ]; do
    case "$1" in
        --only) ONLY="$2"; shift 2 ;;
        -h|--help) sed -n '2,45p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
    esac
done

log()  { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
ok()   { printf '  \033[0;32m✓\033[0m %s\n' "$*"; PASS=$((PASS+1)); }
bad()  { printf '  \033[0;31m✗\033[0m %s\n' "$*"; FAIL=$((FAIL+1)); }
skip() { printf '  \033[0;33m⏭\033[0m %s\n' "$*"; SKIP=$((SKIP+1)); }
note() { printf '      %s\n' "$*"; }
PASS=0; FAIL=0; SKIP=0

want() { [ -z "$ONLY" ] || [ "$ONLY" = "$1" ]; }

# ── 전제 ───────────────────────────────────────────────────────────────

command -v kubectl >/dev/null || { echo "kubectl 이 필요합니다" >&2; exit 2; }

# ⚠️ 붙지 못한 채 진행하면 "없습니다"를 "괜찮습니다"로 보고하게 된다. ops/24 가 정확히
#    그렇게 잘못 보고했고, 그 때문에 자격증명이 엉뚱한 클러스터에 만들어졌다.
if ! CONN_ERR=$(kubectl cluster-info 2>&1 >/dev/null); then
    printf '%s\n' "$CONN_ERR" | head -2 | sed 's/^/      /'
    echo "클러스터에 붙지 못했습니다 — 점검할 수 없습니다." >&2
    exit 2
fi
printf '  컨텍스트: %s / 네임스페이스: %s\n' \
    "$(kubectl config current-context 2>/dev/null || echo '?')" "$NAMESPACE"

kubectl -n "$NAMESPACE" get secret "$SECRET" >/dev/null 2>&1 \
    || { echo "Secret '$SECRET' 이 없습니다 (네임스페이스 $NAMESPACE)" >&2; exit 2; }

# 클러스터 안에서 한 컨테이너를 돌리고 종료 코드와 출력을 돌려준다.
#   $1 = 이름, $2 = 이미지, $3 = sh -c 로 넘길 스크립트, $4.. = env 주입 JSON 조각
run_probe() {
    local name="$1" image="$2" script="$3" envjson="$4"
    kubectl -n "$NAMESPACE" run "$name" \
        --rm --attach --restart=Never --quiet \
        --image="$image" \
        --overrides="{\"spec\":{\"containers\":[{\"name\":\"$name\",\"image\":\"$image\",\"command\":[\"/bin/sh\",\"-c\"],\"args\":[$(printf '%s' "$script" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')],\"env\":$envjson}]}}" \
        2>&1
}

secret_env() {  # $1 = 환경변수명, $2 = Secret 키
    printf '{"name":"%s","valueFrom":{"secretKeyRef":{"name":"%s","key":"%s"}}}' "$1" "$SECRET" "$2"
}
config_env() {  # $1 = 환경변수명, $2 = ConfigMap 키
    printf '{"name":"%s","valueFrom":{"configMapKeyRef":{"name":"%s","key":"%s"}}}' "$1" "$CONFIGMAP" "$2"
}

# ── 1. PostgreSQL ──────────────────────────────────────────────────────

if want postgres; then
    log "1. PostgreSQL — Secret 의 비밀번호로 실제 로그인"

    if ! kubectl -n "$NAMESPACE" get configmap "$CONFIGMAP" \
            -o jsonpath='{.data.POSTGRES_HOST}' >/dev/null 2>&1; then
        skip "ConfigMap '$CONFIGMAP' 에 POSTGRES_HOST 가 없습니다"
    else
        ENVJSON="[$(secret_env PGPASSWORD LOCAL_DB_PASSWORD),$(config_env PGHOST POSTGRES_HOST),$(config_env PGPORT POSTGRES_PORT),{\"name\":\"PGUSER\",\"value\":\"${PGUSER:-product}\"},{\"name\":\"PGDATABASE\",\"value\":\"$PROBE_DB\"},{\"name\":\"PGSSLMODE\",\"value\":\"require\"},{\"name\":\"PGCONNECT_TIMEOUT\",\"value\":\"10\"}]"
        # ⚠️ 오류 출력에 비밀번호가 섞일 여지가 없도록 psql 의 stderr 만 남긴다.
        OUT=$(run_probe pgdrift-check postgres:16-alpine \
            'psql -X -q -t -A -c "select 1" >/dev/null 2>/tmp/e && echo PROBE_OK || { echo PROBE_FAIL; cat /tmp/e; }' \
            "$ENVJSON")

        case "$OUT" in
            *PROBE_OK*)
                ok "Secret 의 LOCAL_DB_PASSWORD 로 ${PGUSER:-product} 로그인 성공" ;;
            *"password authentication failed"*)
                bad "**비밀번호가 어긋나 있습니다** (28P01)"
                note "호스트·TLS·역할은 전부 맞았습니다 — 비밀번호만 다릅니다."
                note "지금 떠 있는 파드는 기동 시점의 값을 들고 있어 멀쩡히 돕니다."
                note "**다음 롤아웃에서 한꺼번에 죽습니다.** 지금 맞추세요:"
                note "  ops/25-provision-external-postgres.sh  로 새 비밀번호를 설정하고"
                note "  같은 셸에서 Secret 과 redis-auth 를 함께 갱신 (docs/33 §7)" ;;
            *"does not exist"*)
                bad "역할 또는 데이터베이스가 없습니다 — 비밀번호 이전 단계입니다"
                printf '%s\n' "$OUT" | grep -i "does not exist" | head -2 | sed 's/^/      /' ;;
            *"could not translate host name"*|*"could not connect"*|*"timeout expired"*)
                bad "호스트에 닿지 못했습니다 — POSTGRES_HOST 를 확인하세요"
                note "⚠️ Neon 은 와일드카드 DNS 라 오타가 나도 이름은 해석됩니다."
                printf '%s\n' "$OUT" | grep -iv "^PROBE" | head -2 | sed 's/^/      /' ;;
            *)
                bad "판단할 수 없는 실패입니다"
                printf '%s\n' "$OUT" | head -4 | sed 's/^/      /' ;;
        esac
    fi
fi

# ── 2. Redis ───────────────────────────────────────────────────────────

if want redis; then
    # 애플리케이션이 쓰는 Redis 비밀번호: SPRING_DATA_REDIS_PASSWORD 가 있으면 그것(ops/36 으로 DB 와 분리한 뒤 — 환경 변수가
    # 설정 파일의 ${LOCAL_DB_PASSWORD} 를 덮는다), 없으면 LOCAL_DB_PASSWORD. 값은 읽지 않고 키가 있는지만 본다.
    REDIS_APP_KEY=LOCAL_DB_PASSWORD
    if [ -n "$(kubectl -n "$NAMESPACE" get secret "$SECRET" -o jsonpath='{.data.SPRING_DATA_REDIS_PASSWORD}' 2>/dev/null)" ]; then
        REDIS_APP_KEY=SPRING_DATA_REDIS_PASSWORD
    fi
    log "2. Redis — redis-auth 가 애플리케이션의 비밀번호($REDIS_APP_KEY)와 맞는가"

    # ⚠️ 이 둘은 **같아야 한다.** 애플리케이션 설정이 spring.data.redis.password 를
    #    ${LOCAL_DB_PASSWORD} 로 잡고 있는데, redis 서버는 별도 Secret(redis-auth)의
    #    값으로 requirepass 를 건다. DB 비밀번호만 바꾸면 다음 재시작에서 Redis 를 쓰는
    #    **7개 서비스**(cart·catalog·chat·notification·order·pay·user)가 죽는다.
    if ! kubectl -n redis get secret redis-auth >/dev/null 2>&1; then
        skip "redis-auth Secret 이 없습니다 (관리형 Redis 를 쓰는 구성이면 정상)"
    else
        ENVJSON="[$(secret_env APP_PW "$REDIS_APP_KEY"),{\"name\":\"REDIS_HOST\",\"value\":\"${REDIS_HOST:-redis.redis.svc.cluster.local}\"}]"
        OUT=$(run_probe redisdrift-check redis:7-alpine \
            'REDISCLI_AUTH="$APP_PW" redis-cli -h "$REDIS_HOST" ping 2>&1' \
            "$ENVJSON")

        case "$OUT" in
            *PONG*)   ok "애플리케이션이 쓰는 비밀번호로 Redis 인증 성공" ;;
            *WRONGPASS*|*"invalid username-password"*)
                bad "**redis-auth 와 $REDIS_APP_KEY 가 어긋나 있습니다**"
                note "다음 재시작에서 Redis 를 쓰는 7개 서비스가 죽습니다:"
                note "  cart · catalog · chat · notification · order · pay · user"
                note "맞추기:"
                note "  kubectl -n redis create secret generic redis-auth \\"
                note "    --from-literal=password=\"\$LOCAL_DB_PASSWORD\" \\"
                note "    --dry-run=client -o yaml | kubectl apply -f -"
                note "  kubectl -n redis rollout restart deploy/redis" ;;
            *"Connection refused"*|*"Could not connect"*)
                bad "Redis 에 닿지 못했습니다 — 파드가 없을 수 있습니다"
                note "⚠️ Service 는 남아 있어 이름 해석과 ClusterIP 는 정상입니다."
                note "  kubectl -n redis get endpoints redis    # <none> 이면 파드가 없습니다" ;;
            *)
                bad "판단할 수 없는 실패입니다"
                printf '%s\n' "$OUT" | head -3 | sed 's/^/      /' ;;
        esac
    fi
fi

# ── 3. MongoDB ─────────────────────────────────────────────────────────

if want mongo; then
    log "3. MongoDB — 접속 문자열이 아직 통하는가"

    if ! kubectl -n "$NAMESPACE" get secret "$SECRET" \
            -o jsonpath='{.data.SPRING_DATA_MONGODB_CLIENT}' 2>/dev/null | grep -q .; then
        skip "SPRING_DATA_MONGODB_CLIENT 키가 없습니다"
    else
        ENVJSON="[$(secret_env MONGO_URI SPRING_DATA_MONGODB_CLIENT)]"
        OUT=$(run_probe mongodrift-check mongo:7 \
            'mongosh "$MONGO_URI" --quiet --eval "db.runCommand({ping:1}).ok" 2>&1' \
            "$ENVJSON")

        # ⚠️ 실패 판정을 **먼저** 한다. mongosh 의 에러 문구에도 숫자 1 이 섞일 수 있어,
        #    성공(ok 가 1)을 먼저 매칭하면 실패를 성공으로 읽는다.
        case "$OUT" in
            *"Authentication failed"*|*"bad auth"*|*"AuthenticationFailed"*)
                bad "**Mongo 자격증명이 어긋나 있습니다**"
                note "Atlas 콘솔에서 사용자 비밀번호가 바뀌었는지 확인하세요."
                note "chat-service 는 다음 재시작에서 기동에 실패합니다." ;;
            *getaddrinfo*|*ENOTFOUND*|*ETIMEDOUT*|*ServerSelection*|*"connection timed out"*)
                bad "Mongo 에 닿지 못했습니다 — 자격증명 이전 단계입니다"
                note "Atlas Network Access 에 이 클러스터의 공인 IP 가 있는지 확인하세요." ;;
            *)
                # ping 이 성공하면 `db.runCommand({ping:1}).ok` 가 1 한 줄만 찍는다.
                if printf '%s\n' "$OUT" | grep -qx '1'; then
                    ok "Mongo 접속 문자열로 ping 성공"
                else
                    bad "판단할 수 없는 실패입니다"
                    printf '%s\n' "$OUT" | head -3 | sed 's/^/      /'
                fi ;;
        esac
    fi
fi

# ── 4. 파일 ↔ 클러스터 ─────────────────────────────────────────────────

if want file; then
    log "4. k8s/20-secret.yaml 과 클러스터 Secret 이 같은가"

    # ⚠️ 파일이 달라도 지금 당장은 아무 일도 없다. 그러다 누군가
    #    `kubectl apply -f k8s/20-secret.yaml` 을 돌리는 순간 **클러스터가 파일 쪽으로
    #    되돌아간다.** 이번 배포에서 이 방식으로 비밀번호가 세 번 되살아났다.
    if [ ! -f k8s/20-secret.yaml ]; then
        skip "k8s/20-secret.yaml 이 없습니다 (이 머신에서 관리하지 않는 구성)"
    elif ! command -v python3 >/dev/null; then
        skip "python3 가 없어 비교할 수 없습니다"
    else
        # 값은 출력하지 않는다. 해시만 비교한다.
        # ⚠️ 파이썬을 `python3 -c '...'` 로 넘기지 않는다. 코드 안에 작은따옴표가 있으면
        #    셸 인용이 그 자리에서 닫혀 스크립트 전체가 깨진다(실제로 한 번 그랬다).
        #    인용된 히어독은 안에 무엇이 들어와도 셸이 건드리지 않는다.
        LIVEJSON=$(mktemp)
        trap 'rm -f "$LIVEJSON"' EXIT
        kubectl -n "$NAMESPACE" get secret "$SECRET" -o json > "$LIVEJSON"
        DIFFKEYS=$(python3 - "$LIVEJSON" <<'PYEOF' 2>/dev/null
import base64, hashlib, json, sys, re, pathlib

live = {k: base64.b64decode(v)
        for k, v in (json.load(open(sys.argv[1])).get("data") or {}).items()}

text = pathlib.Path("k8s/20-secret.yaml").read_text()
docs, cur = [], []
for line in text.splitlines():
    if line.strip() == "---":
        docs.append("\n".join(cur)); cur = []
    else:
        cur.append(line)
docs.append("\n".join(cur))

filevals = {}
for d in docs:
    if not re.search(r"^\s*name:\s*ecommerce-secrets\s*$", d, re.M):
        continue
    section = None
    for line in d.splitlines():
        if re.match(r"^(stringData|data):\s*$", line):
            section = line.split(":")[0]; continue
        if re.match(r"^\S", line):
            section = None; continue
        m = re.match(r"^\s{2}([A-Za-z0-9_.-]+):\s*(.*)$", line)
        if section and m:
            k, v = m.group(1), m.group(2).strip()
            if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
                v = v[1:-1]
            filevals[k] = base64.b64decode(v) if section == "data" else v.encode()

if not filevals:
    print("NOFILEKEYS"); raise SystemExit

h = lambda b: hashlib.sha256(b).hexdigest()
diff = [k for k, v in filevals.items() if k in live and h(v) != h(live[k])]
missing = [k for k in filevals if k not in live]
for k in sorted(diff):    print("DIFF " + k)
for k in sorted(missing): print("ONLYFILE " + k)
PYEOF
)

        if [ -z "$DIFFKEYS" ]; then
            ok "파일과 클러스터의 값이 일치합니다"
        elif [ "$DIFFKEYS" = "NOFILEKEYS" ]; then
            skip "파일에서 ecommerce-secrets 항목을 읽지 못했습니다"
        else
            bad "파일과 클러스터가 다릅니다 — 다음 apply 에서 클러스터가 되돌아갑니다"
            printf '%s\n' "$DIFFKEYS" | sed 's/^DIFF /      다름:     /; s/^ONLYFILE /      파일에만: /'
            note "지금 클러스터 값이 맞다면 파일을 그 값으로 고치세요."
            note "⚠️ 값 자체는 출력하지 않았습니다 — 키 이름만 보여 줍니다."
        fi
    fi
fi

# ── 결과 ───────────────────────────────────────────────────────────────

printf '\n════════════════════════════════════════\n'
printf '  통과 %d · 실패 %d · 생략 %d\n' "$PASS" "$FAIL" "$SKIP"
if [ "$FAIL" -gt 0 ]; then
    printf '\n  \033[0;31m어긋난 자격증명이 있습니다.\033[0m 지금은 파드가 멀쩡해 보여도\n'
    printf '  다음 롤아웃에서 드러납니다 — 배포 전에 맞추세요.\n\n'
    exit 1
fi
printf '  \033[0;32m자격증명이 실제로 통합니다.\033[0m\n\n'
exit 0
