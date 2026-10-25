#!/usr/bin/env bash
#
# 운영 액션 ㊾ — 키스토어 · 트러스트스토어 비밀번호 교체 (docs/57 §16 "스토어 비밀번호")
#
# 왜: 2026-10-14 전 매니페스트가 비밀번호를 JAVA_TOOL_OPTIONS 로 넘겨, JVM 이 기동마다 `Picked up JAVA_TOOL_OPTIONS: …` 로
#     파드 로그 · Loki 에 평문으로 남겼다(B-83). 배포로 새 로그에는 더 남지 않지만 지난 로그는 보관 기간 동안 조회된다 —
#     새었다고 보고 바꾼다. 같은 값이 와일드카드 개인 키 · 서비스 신원 키스토어 · 트러스트스토어의 비밀번호다.
#
# 무엇을: 키와 인증서는 그대로 두고 **스토어의 비밀번호만** 바꾼다(keytool -storepasswd — PKCS12 는 키 비밀번호도 함께 바뀐다,
#   트러스트스토어의 신뢰 항목은 그대로). ops/07 로 다시 발급하지 않는 이유: CA 가 바뀌면 서비스 신원(ops/38) · PostgreSQL
#   인증서 · 브라우저 신뢰까지 모두 다시 해야 한다.
#   · 클러스터: 네임스페이스 Secret 의 .p12 · .jks 전부(tls-keystore · tls-id-<서비스> …) + ecommerce-secrets 의 비밀번호 키
#     (LOCAL_KEY_STORE_PASSWORD · LOCAL_KEY_PASSWORD · KAFKA_SSL_*_PASSWORD 중 옛 값과 같은 것만 — 다른 값은 건드리지 않는다)
#   · 이 기계: certs/ 의 스토어 · certs/certs.env · k8s/20-secret.yaml(있으면) — 나중에 다시 적용해도 옛 값으로 돌아가지 않게
#   · 재시작: ecommerce-secrets 의 LOCAL_KEY_STORE_PASSWORD 를 읽는 Deployment 전부
#
# 사용:
#   ./ops/59-rotate-store-password.sh            # check — 바꿀 것 · 지금 비밀번호로 열리는지 · 재시작할 것(값은 출력하지 않는다)
#   ./ops/59-rotate-store-password.sh apply      # 교체 — 'yes' 를 묻는다(YES=1 이면 묻지 않는다)
#   ./ops/59-rotate-store-password.sh rollback   # 교체 전으로(클러스터의 <Secret>-prerotate · 파일의 .prerotate) + 재시작
#   ./ops/59-rotate-store-password.sh cleanup    # 확인이 끝나면 되돌리기용 사본을 지운다 — 그 안에 옛 비밀번호가 있다
#
# 🔒 비밀번호는 인자 · 로그 · 화면에 나오지 않는다 — keytool 에는 0600 파일(-storepass:file)로, 길이만 출력한다. 작업 파일은
#    0700 임시 디렉터리에서 만들고 끝나면 지운다. 되돌리기용 사본은 원본과 같은 네임스페이스의 Secret 이다(접근 범위가 같다).
#
# [JA] キーストア・トラストストアのパスワード交換。鍵と証明書はそのまま、ストアのパスワードだけ keytool -storepasswd で替える。
#      クラスタの Secret 内の .p12・.jks 全部、ecommerce-secrets の同じ値のキー、この機械の certs/・20-secret.yaml、読む Deployment
#      の再起動まで。パスワードは引数・ログ・画面に出さない。戻し用の写しはクラスタ内の <Secret>-prerotate。
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077

NS="${NAMESPACE:-ecommerce}"
APP_SECRET="${APP_SECRET:-ecommerce-secrets}"
CERTS_DIR="${CERTS_DIR:-./certs}"
SECRET_FILE="${SECRET_FILE:-./k8s/20-secret.yaml}"
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-600s}"
JRE_IMAGE="${JRE_IMAGE:-$(sed -n 's/^ARG JRE_IMAGE=//p' ops/Dockerfile.runtime)}"
PW_KEYS="LOCAL_KEY_STORE_PASSWORD LOCAL_KEY_PASSWORD KAFKA_SSL_KEY_STORE_PASSWORD KAFKA_SSL_TRUST_STORE_PASSWORD"
LABEL="ecommerce.io/store-password-rotation"
SUFFIX="-prerotate"
MODE="${1:-check}"

say()  { printf '%s\n' "$*"; }
ok()   { printf '  ✅ %s\n' "$*"; }
bad()  { printf '  ❌ %s\n' "$*"; }
info() { printf '  · %s\n' "$*"; }
die()  { printf '❌ %s\n' "$*" >&2; exit 1; }

case "$MODE" in
    check|apply|rollback|cleanup) ;;
    -h|--help) sed -n '2,32p' "$0"; exit 0 ;;
    *) die "모르는 모드: $MODE (check | apply | rollback | cleanup)" ;;
esac

command -v kubectl >/dev/null 2>&1 || die "kubectl 이 없습니다"
command -v python3 >/dev/null 2>&1 || die "python3 이 없습니다"

WORK=$(mktemp -d)
chmod 700 "$WORK"
trap 'rm -rf "$WORK"' EXIT

# ── JSON 다루기(비밀 값은 파일로만 오간다) ──────────────────────────────────────────────
py() {
    python3 - "$@" <<'PY'
import base64, json, re, secrets, string, sys
cmd, args = sys.argv[1], sys.argv[2:]

def load(p):
    with open(p, encoding="utf-8") as f:
        return json.load(f)

# 서버가 정하는 칸만 뺀다. 라벨 · 주석 · ownerReferences 는 그대로 — cert-manager(ops/39)가 맡은 Secret 은 그것으로 자기
# 것을 알아본다. 빠지면 인증서를 새로 발급할 수 있다(2026-10-25 운영 check: tls-id-* 10개가 cert-manager).
# managedFields 는 요청에서 빼면 서버가 있던 것을 둔다.
def clean(obj):
    md = obj.setdefault("metadata", {})
    for k in ("resourceVersion", "uid", "creationTimestamp", "managedFields", "generation", "selfLink"):
        md.pop(k, None)
    obj.pop("status", None)
    return obj

if cmd == "stores":            # Secret 목록 → "이름\t키" (되돌리기용 사본은 뺀다)
    suffix = args[1]
    for s in load(args[0]).get("items", []):
        name = s["metadata"]["name"]
        if name.endswith(suffix):
            continue
        for key in sorted((s.get("data") or {})):
            if key.endswith(".p12") or key.endswith(".jks"):
                print(f"{name}\t{key}")
elif cmd == "backups":         # 표지가 붙은 사본의 이름
    for s in load(args[0]).get("items", []):
        if args[1] in (s["metadata"].get("labels") or {}):
            print(s["metadata"]["name"])
elif cmd == "value":           # Secret 의 한 키 → 파일(바이트 그대로). 없으면 종료 코드 3
    data = load(args[0]).get("data") or {}
    if args[1] not in data:
        sys.exit(3)
    with open(args[2], "wb") as f:
        f.write(base64.b64decode(data[args[1]]))
elif cmd == "newpass":         # 영숫자 32자 — YAML · env · JSON 어디에 넣어도 따옴표 문제가 없다
    alphabet = string.ascii_letters + string.digits
    with open(args[0], "w") as f:
        f.write("".join(secrets.choice(alphabet) for _ in range(32)))
elif cmd == "same":            # 두 파일이 같은가(종료 코드)
    sys.exit(0 if open(args[0], "rb").read() == open(args[1], "rb").read() else 1)
elif cmd == "setdata":         # Secret JSON + 키=파일 … → 바꾼 Secret JSON(조건 없는 replace — resourceVersion 을 뺀다)
    obj = clean(load(args[0]))
    for pair in args[2:]:
        key, path = pair.split("=", 1)
        obj.setdefault("data", {})[key] = base64.b64encode(open(path, "rb").read()).decode()
    json.dump(obj, open(args[1], "w"))
elif cmd == "backup":          # 원본 → <이름><suffix>. 원래 라벨 · 주석 · 소유자는 주석 하나에 접어 둔다 — 사본에 그대로 두면
    obj = clean(load(args[0]))  # cert-manager 가 사본을 자기 것으로 보고, 소유자(Certificate)가 지워지면 사본도 함께 지워진다
    md = obj["metadata"]
    original = md["name"]
    meta = {k: md.pop(k) for k in ("labels", "annotations", "ownerReferences") if k in md}
    md["name"] = original + args[2]
    md["labels"] = {args[3]: args[4]}
    md["annotations"] = {args[3] + "-of": original, args[3] + "-meta": json.dumps(meta)}
    json.dump(obj, open(args[1], "w"))
elif cmd == "restore":         # 사본 → 원래 이름 · 라벨 · 주석 · 소유자 그대로의 Secret
    obj = clean(load(args[0]))
    md = obj["metadata"]
    notes = md.pop("annotations")
    md.pop("labels", None)
    md["name"] = notes[args[2] + "-of"]
    md.update(json.loads(notes.get(args[2] + "-meta", "{}")))
    json.dump(obj, open(args[1], "w"))
elif cmd == "deploys":         # 이 Secret 의 이 키를 읽는 Deployment
    secret, key = args[1], args[2]
    for d in load(args[0]).get("items", []):
        spec = d["spec"]["template"]["spec"]
        hit = False
        for c in spec.get("containers", []) + spec.get("initContainers", []):
            for e in c.get("env", []) or []:
                ref = (e.get("valueFrom") or {}).get("secretKeyRef") or {}
                hit |= ref.get("name") == secret and ref.get("key") == key
            for e in c.get("envFrom", []) or []:
                hit |= (e.get("secretRef") or {}).get("name") == secret
        if hit:
            print(d["metadata"]["name"])
elif cmd == "textfile":        # 설정 파일(20-secret.yaml · certs.env)에서 옛 값인 비밀번호 줄 → 새 값. 바꾼 줄 수를 출력
    path, oldp, newp, keys, write = args[0], args[1], args[2], args[3].split(), args[4] == "1"
    old = open(oldp).read()
    new = open(newp).read() if newp != "-" else ""
    pat = re.compile(r'^(\s*(?:export\s+)?)(' + "|".join(map(re.escape, keys)) + r')(\s*[:=]\s*)(["\']?)(.*?)\4(\s*)$')
    out, n = [], 0
    for line in open(path, encoding="utf-8").read().split("\n"):
        m = pat.match(line)
        if m and m.group(5) == old:
            q = m.group(4) or ('"' if ":" in m.group(3) else "")
            line = f"{m.group(1)}{m.group(2)}{m.group(3)}{q}{new}{q}{m.group(6)}"
            n += 1
        out.append(line)
    if write and n:
        open(path, "w", encoding="utf-8").write("\n".join(out))
    print(n)
else:
    sys.exit(f"py: 모르는 명령 {cmd}")
PY
}

# ── keytool — 호스트에 없으면 서비스와 같은 JRE 이미지(B-98)로. 파일은 모두 WORK 안의 상대 경로 ───────────────────────
kt() {
    if command -v keytool >/dev/null 2>&1; then
        (cd "$WORK" && keytool "$@")
    elif command -v docker >/dev/null 2>&1; then
        docker run --rm --network none --user "$(id -u):$(id -g)" -v "$WORK:/w" -w /w "$JRE_IMAGE" keytool "$@"
    else
        die "keytool 도 docker 도 없습니다 — sudo apt-get install -y openjdk-17-jre-headless"
    fi
}
store_type() { case "$1" in *.jks) echo JKS ;; *) echo PKCS12 ;; esac; }
# opens <WORK 안 스토어> <WORK 안 비밀번호 파일>
opens() { kt -list -keystore "$1" -storetype "$(store_type "$1")" -storepass:file "$2" >/dev/null 2>&1; }

kube() { kubectl -n "$NS" "$@"; }

# cert-manager(ops/39)가 맡은 신원은 갱신 때 ecommerce-secrets 의 비밀번호로 keystore.p12 를 다시 만든다 — 바꾼 뒤의 값이라
# 어긋나지 않는다. 지금 바로 맞추고 싶으면 ops/39 renew.
cert_manager_note() {
    local c; c=$(kube get certificates -o name 2>/dev/null || true)
    [ -z "$c" ] || info "cert-manager Certificate $(echo "$c" | wc -l)개 — 다음 갱신부터 새 비밀번호로 만든다(지금 맞추려면 ./ops/39-cert-manager.sh renew <서비스>)"
}

read_current_password() {
    kube get secret "$APP_SECRET" -o json > "$WORK/app.json" 2>/dev/null || die "Secret $APP_SECRET 을 읽지 못했습니다"
    py value "$WORK/app.json" LOCAL_KEY_STORE_PASSWORD "$WORK/old" || die "$APP_SECRET 에 LOCAL_KEY_STORE_PASSWORD 가 없습니다"
    [ -s "$WORK/old" ] || die "LOCAL_KEY_STORE_PASSWORD 가 비어 있습니다"
}

# 클러스터의 스토어 목록 → $WORK/stores.txt ("Secret\t키")
list_stores() {
    kube get secrets -o json > "$WORK/secrets.json"
    py stores "$WORK/secrets.json" "$SUFFIX" > "$WORK/stores.txt"
}

# 이 기계의 스토어(있는 것만)
local_stores() {
    [ -d "$CERTS_DIR" ] || return 0
    find "$CERTS_DIR" -maxdepth 1 -type f \( -name '*.p12' -o -name '*.jks' \) ! -name "*$SUFFIX" | sort
}

local_textfiles() {
    for f in "$SECRET_FILE" "$CERTS_DIR/certs.env"; do [ -f "$f" ] && echo "$f"; done
    return 0
}

restart_targets() {
    kube get deploy -o json > "$WORK/deploys.json"
    py deploys "$WORK/deploys.json" "$APP_SECRET" LOCAL_KEY_STORE_PASSWORD
}

restart_all() {
    local targets failed=""
    targets=$(restart_targets)
    [ -n "$targets" ] || { info "재시작할 Deployment 가 없다"; return 0; }
    # 하나씩 — 노드 하나라 한꺼번에 띄우면 메모리가 몰린다. 등록부(eureka) · 설정(config)을 먼저, 바깥 문(gateway)은 마지막.
    local ordered="" d
    for d in eureka-server config-server; do echo "$targets" | grep -qx "$d" && ordered="$ordered $d"; done
    for d in $targets; do case " eureka-server config-server gateway-server " in *" $d "*) ;; *) ordered="$ordered $d" ;; esac; done
    echo "$targets" | grep -qx gateway-server && ordered="$ordered gateway-server"
    say "▶ 재시작 — $(echo $ordered | wc -w)개, 하나씩"
    for d in $ordered; do
        kube rollout restart "deploy/$d" >/dev/null
        if kube rollout status "deploy/$d" --timeout="$ROLLOUT_TIMEOUT" >/dev/null 2>&1; then ok "$d"; else bad "$d — 준비되지 않음"; failed="$failed $d"; fi
    done
    [ -z "$failed" ] || { say "⚠️ 준비되지 않은 Deployment:$failed — 'kubectl -n $NS logs deploy/<이름>' 에서 keystore 오류를 보고, 되돌리려면 $0 rollback"; return 1; }
}

# ── check ──────────────────────────────────────────────────────────────────────────────
do_check() {
    read_current_password
    say "▶ 지금 비밀번호: $(wc -c < "$WORK/old" | tr -d ' ')자 ($APP_SECRET/LOCAL_KEY_STORE_PASSWORD — 값은 출력하지 않는다)"
    list_stores
    local n=0 fail=0 secret key f
    say "▶ 클러스터의 스토어 (네임스페이스 $NS)"
    while IFS=$'\t' read -r secret key; do
        [ -n "$secret" ] || continue
        n=$((n + 1)); f="s$n-$key"
        kube get secret "$secret" -o json > "$WORK/one.json"
        py value "$WORK/one.json" "$key" "$WORK/$f"
        if opens "$f" old; then ok "$secret/$key ($(store_type "$key")) — 지금 비밀번호로 열린다"; else bad "$secret/$key — 지금 비밀번호로 열리지 않는다"; fail=$((fail + 1)); fi
    done < "$WORK/stores.txt"
    [ "$n" -gt 0 ] || bad "스토어가 하나도 없다"
    say "▶ $APP_SECRET 의 비밀번호 키"
    for k in $PW_KEYS; do
        if py value "$WORK/app.json" "$k" "$WORK/k" 2>/dev/null; then
            if py same "$WORK/k" "$WORK/old"; then info "$k — 같은 값(바꾼다)"; else info "$k — 다른 값(그대로 둔다)"; fi
        else info "$k — 없음"; fi
    done
    say "▶ 이 기계의 파일"
    local any=0
    while read -r f; do
        [ -n "$f" ] || continue; any=1
        cp "$f" "$WORK/local.store"
        if opens local.store old; then info "$f — 지금 비밀번호로 열린다(바꾼다)"; else info "$f — 열리지 않는다(건드리지 않는다)"; fi
    done < <(local_stores)
    while read -r f; do
        [ -n "$f" ] || continue; any=1
        info "$f — 지금 값인 줄 $(py textfile "$f" "$WORK/old" - "$PW_KEYS" 0)개(바꾼다)"
    done < <(local_textfiles)
    [ "$any" = 1 ] || info "없음($CERTS_DIR · $SECRET_FILE)"
    say "▶ 재시작할 Deployment"
    local t; t=$(restart_targets); [ -n "$t" ] && info "$(echo $t)" || info "없음"
    kube get secrets -o json > "$WORK/all.json"
    local b; b=$(py backups "$WORK/all.json" "$LABEL")
    [ -z "$b" ] || say "⚠️ 지난 교체의 되돌리기용 사본이 남아 있다: $(echo $b) — 확인이 끝났으면 $0 cleanup"
    cert_manager_note
    if [ "$fail" -gt 0 ]; then say "❌ 열리지 않는 스토어가 있어 apply 는 멈춘다 — 그 Secret 이 다른 비밀번호를 쓰는지 먼저 본다"; return 1; fi
    say "✅ apply 할 수 있다: $0 apply"
}

# ── apply ──────────────────────────────────────────────────────────────────────────────
do_apply() {
    read_current_password
    list_stores
    [ -s "$WORK/stores.txt" ] || die "바꿀 스토어가 없습니다"
    kube get secrets -o json > "$WORK/all.json"
    [ -z "$(py backups "$WORK/all.json" "$LABEL")" ] || die "지난 교체의 사본(${SUFFIX})이 남아 있습니다 — 확인했으면 $0 cleanup, 되돌리려면 $0 rollback"

    say "▶ 미리 확인 — 모든 스토어가 지금 비밀번호로 열리는가"
    local n=0 secret key f
    : > "$WORK/plan.txt"
    while IFS=$'\t' read -r secret key; do
        [ -n "$secret" ] || continue
        n=$((n + 1)); f="s$n-$key"
        kube get secret "$secret" -o json > "$WORK/one.json"
        py value "$WORK/one.json" "$key" "$WORK/$f"
        opens "$f" old || die "$secret/$key 가 지금 비밀번호로 열리지 않습니다 — 아무것도 바꾸지 않았습니다"
        printf '%s\t%s\t%s\n' "$secret" "$key" "$f" >> "$WORK/plan.txt"
    done < "$WORK/stores.txt"
    ok "스토어 $n개"
    local targets; targets=$(restart_targets)
    info "재시작할 Deployment $(echo $targets | wc -w)개"

    if [ "${YES:-0}" != "1" ]; then
        read -r -p "스토어 비밀번호를 바꾸고 위 Deployment 를 재시작합니다. 계속할까요? (yes): " a
        [ "$a" = "yes" ] || die "취소했습니다 — 아무것도 바꾸지 않았습니다"
    fi

    py newpass "$WORK/new"
    py same "$WORK/new" "$WORK/old" && die "새 값이 옛 값과 같습니다(다시 실행)"
    say "▶ 새 비밀번호: $(wc -c < "$WORK/new" | tr -d ' ')자(값은 출력하지 않는다)"

    say "▶ 스토어 다시 잠그기(키 · 인증서는 그대로)"
    while IFS=$'\t' read -r secret key f; do
        kt -storepasswd -keystore "$f" -storetype "$(store_type "$key")" -storepass:file old -new:file new >/dev/null 2>&1 \
            || die "$secret/$key 의 비밀번호를 바꾸지 못했습니다 — 클러스터는 바꾸지 않았습니다"
        opens "$f" new && ! opens "$f" old || die "$secret/$key 가 새 비밀번호로 열리지 않습니다 — 클러스터는 바꾸지 않았습니다"
    done < "$WORK/plan.txt"
    ok "작업 사본 $n개 — 새 비밀번호로 열리고 옛 비밀번호로는 열리지 않는다"

    local stamp; stamp=$(date -u +%Y%m%dT%H%M%SZ)
    say "▶ 되돌리기용 사본(<Secret>${SUFFIX}, 표지 $LABEL=$stamp)"
    for secret in "$APP_SECRET" $(cut -f1 "$WORK/plan.txt" | sort -u); do
        kube get secret "$secret" -o json > "$WORK/one.json"
        py backup "$WORK/one.json" "$WORK/bk.json" "$SUFFIX" "$LABEL" "$stamp"
        kube create -f "$WORK/bk.json" >/dev/null || die "$secret 의 사본을 만들지 못했습니다 — 바꾸기 전에 멈춥니다(남은 사본: $0 cleanup)"
        ok "$secret$SUFFIX"
    done

    # ecommerce-secrets 를 먼저 — cert-manager 는 다시 만들 때 거기서 비밀번호를 읽는다. 스토어가 먼저 바뀌면 그 사이 다시
    # 만들어진 keystore 가 옛 비밀번호가 된다. 반대 순서면 언제 다시 만들어도 새 값이다.
    # [JA] ecommerce-secrets を先に — cert-manager は作り直すときそこからパスワードを読む。逆順だと古い値で作られうる。
    say "▶ 클러스터 반영"
    kube get secret "$APP_SECRET" -o json > "$WORK/app.json"
    local keys=()
    for k in $PW_KEYS; do
        if py value "$WORK/app.json" "$k" "$WORK/k" 2>/dev/null && py same "$WORK/k" "$WORK/old"; then keys+=("$k=$WORK/new"); fi
    done
    py setdata "$WORK/app.json" "$WORK/put.json" "${keys[@]}"
    kube replace -f "$WORK/put.json" >/dev/null || die "$APP_SECRET 반영 실패 — 되돌리려면 $0 rollback"
    ok "$APP_SECRET — $(printf '%s ' "${keys[@]%%=*}")"

    for secret in $(cut -f1 "$WORK/plan.txt" | sort -u); do
        kube get secret "$secret" -o json > "$WORK/one.json"
        local pairs=()
        while IFS=$'\t' read -r s key f; do [ "$s" = "$secret" ] && pairs+=("$key=$WORK/$f"); done < "$WORK/plan.txt"
        py setdata "$WORK/one.json" "$WORK/put.json" "${pairs[@]}"
        kube replace -f "$WORK/put.json" >/dev/null || die "$secret 반영 실패 — 되돌리려면 $0 rollback"
        ok "$secret (${#pairs[@]}개)"
    done
    say "▶ 이 기계의 파일(.prerotate 사본을 남긴다)"
    while read -r f; do
        [ -n "$f" ] || continue
        cp "$f" "$WORK/local.store"
        if opens local.store old; then
            kt -storepasswd -keystore local.store -storetype "$(store_type "$f")" -storepass:file old -new:file new >/dev/null 2>&1 \
                || { bad "$f — 바꾸지 못했다"; continue; }
            cp -p "$f" "$f$SUFFIX"; cp "$WORK/local.store" "$f"; ok "$f"
        fi
    done < <(local_stores)
    while read -r f; do
        [ -n "$f" ] || continue
        if [ "$(py textfile "$f" "$WORK/old" - "$PW_KEYS" 0)" -gt 0 ]; then
            cp -p "$f" "$f$SUFFIX"
            ok "$f — $(py textfile "$f" "$WORK/old" "$WORK/new" "$PW_KEYS" 1)줄"
        fi
    done < <(local_textfiles)

    restart_all || exit 1
    cert_manager_note
    cat <<EOF

✅ 교체 끝. 확인:
   kubectl -n $NS get pods                                   # 재시작 0 · Ready
   ./ops/28-deploy.sh --report 의 스모크, 또는 사이트에서 로그인 · 상품 · 주문 한 번
   ./ops/37-check-mtls.sh                                    # 서비스 간 mTLS 그대로
문제가 있으면: $0 rollback
하루쯤 지나 이상이 없으면: $0 cleanup   (사본 안에 옛 비밀번호가 있다)
EOF
}

# ── rollback ───────────────────────────────────────────────────────────────────────────
do_rollback() {
    kube get secrets -o json > "$WORK/all.json"
    local backups; backups=$(py backups "$WORK/all.json" "$LABEL")
    [ -n "$backups" ] || die "되돌릴 사본(${SUFFIX})이 없습니다"
    if [ "${YES:-0}" != "1" ]; then
        read -r -p "교체 전 Secret 으로 되돌리고 재시작합니다. 계속할까요? (yes): " a
        [ "$a" = "yes" ] || die "취소했습니다"
    fi
    say "▶ 클러스터 되돌리기"
    backups="$(echo "$backups" | grep -x "$APP_SECRET$SUFFIX" || true) $(echo "$backups" | grep -vx "$APP_SECRET$SUFFIX" || true)"
    for b in $backups; do
        kube get secret "$b" -o json > "$WORK/one.json"
        py restore "$WORK/one.json" "$WORK/put.json" "$LABEL"
        kube replace -f "$WORK/put.json" >/dev/null || die "$b 되돌리기 실패"
        ok "${b%"$SUFFIX"}"
    done
    say "▶ 이 기계의 파일"
    local any=0
    for f in "$CERTS_DIR"/*"$SUFFIX" "$SECRET_FILE$SUFFIX"; do
        [ -f "$f" ] || continue; any=1
        mv "$f" "${f%"$SUFFIX"}"; ok "${f%"$SUFFIX"}"
    done
    [ "$any" = 1 ] || info "없음"
    restart_all || exit 1
    say "✅ 되돌렸다. 사본은 남아 있다 — 다시 시도하기 전에 $0 cleanup"
}

# ── cleanup ────────────────────────────────────────────────────────────────────────────
do_cleanup() {
    kube get secrets -o json > "$WORK/all.json"
    local backups; backups=$(py backups "$WORK/all.json" "$LABEL")
    for b in $backups; do kube delete secret "$b" >/dev/null && ok "삭제 $b"; done
    for f in "$CERTS_DIR"/*"$SUFFIX" "$SECRET_FILE$SUFFIX"; do
        [ -f "$f" ] || continue; rm -f "$f" && ok "삭제 $f"
    done
    say "✅ 되돌리기용 사본 정리 끝"
}

case "$MODE" in
    check) do_check ;;
    apply) do_apply ;;
    rollback) do_rollback ;;
    cleanup) do_cleanup ;;
esac
