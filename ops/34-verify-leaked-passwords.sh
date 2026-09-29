#!/usr/bin/env bash
#
# 이력의 JSON 에 남은 password 값들이 **지금도 통하는지** 확인한다. **값은 절대 출력하지 않는다.**
#
# 왜 필요한가: ops/20 은 "이력에 password 가 있다" 까지만 말한다(값을 내지 않으므로). 그 값이 이미 죽은 값인지,
# 지금 운영에 배선된 값인지는 알 수 없다 — 2026-09-25 에 커넥터 JSON 넷의 값은 죽은 값임을 확인했지만(docs/30 §6),
# 그 뒤 전체 클론의 재검사에서 **다른 경로**(kafka_2.12-3.7.2/*-outbox-connector.json)가 더 나왔다.
# 같은 값인지, 다른 값이라면 살아 있는지를 사람이 값을 보지 않고 가린다.
#
# 무엇을 하나 (값마다 — 화면에는 A·B·C 같은 이름만 나온다):
#   ① 이력의 모든 *.json blob 에서 password 키의 값을 모은다(ops/20 과 같은 기준: 6자 이상, ${…}·**** 제외)
#   ② 클러스터의 **모든 Secret**(접속 문자열 안 포함)과 로컬 k8s/20-secret.yaml*·k8s-overlays/ 에 같은 값이 있는지
#   ③ 운영 PostgreSQL 에 product·postgres 로, Redis 에 로그인해 본다
#      — 값은 kubectl exec 의 **stdin** 으로만 넘긴다(인자·파일·로그에 남지 않는다)
#      — 대조군(무작위 값 → 거절)과 양성 대조(파드의 현재 암호 → 성공)로 검사 자체가 유효한지 먼저 본다.
#        대조가 어긋나면 결론을 내지 않는다.
#
# 사용 (서버의 **전체 클론**에서 — 얕은 클론은 이력이 없어 거부한다):
#   ops/34-verify-leaked-passwords.sh
#
# 종료 코드: 0 = 모든 값이 죽었다 · 2 = 살아 있거나 Secret 에 있는 값이 있다(회전 필요) · 1 = 검사가 유효하지 않다
#
# SECRET-SCANNER-SELF-EXCLUDE — 이 파일은 password 패턴을 담고 있어 ops/20 에 걸린다.
set -euo pipefail

if [ "$(git rev-parse --is-shallow-repository)" = "true" ]; then
    echo "❌ 얕은 클론입니다 — 이력이 없어 결론을 낼 수 없습니다. 전체 클론에서 실행하세요." >&2
    exit 1
fi
command -v kubectl >/dev/null || { echo "❌ kubectl 이 없습니다." >&2; exit 1; }
command -v python3 >/dev/null || { echo "❌ python3 가 없습니다." >&2; exit 1; }

exec python3 - "$@" <<'PY'
import base64, glob, json, os, re, secrets, subprocess, sys
from collections import defaultdict

PG_NS, PG_DEPLOY = "postgres", "deploy/postgres"
REDIS_NS, REDIS_DEPLOY = "redis", "deploy/redis"

PAIR = re.compile(r'"([^"]*password[^"]*)"\s*:\s*"([^"]*)"', re.IGNORECASE)

def ignorable(v):
    return len(v) < 6 or "${" in v or set(v) <= {"*"} or "REPLACE_ME" in v

# ── ① 이력의 JSON 에서 값 모으기 ───────────────────────────────────────────────
objects = subprocess.run(["git", "rev-list", "--all", "--objects"], capture_output=True, text=True, check=True).stdout
paths_by_blob = defaultdict(set)
for line in objects.splitlines():
    sha, _, path = line.partition(" ")
    if path.lower().endswith(".json"):
        paths_by_blob[sha].add(path)

where = defaultdict(set)          # 값 → 경로들 (값은 이 프로세스 메모리에만 있다)
for sha, paths in paths_by_blob.items():
    body = subprocess.run(["git", "cat-file", "-p", sha], capture_output=True).stdout.decode("utf-8", "replace")
    for _, value in PAIR.findall(body):
        if not ignorable(value):
            where[value].update(paths)

if not where:
    print("이력의 JSON 에 password 값이 없습니다.")
    sys.exit(0)

values = sorted(where, key=lambda v: sorted(where[v]))
labels = {v: chr(ord("A") + i) if i < 26 else f"V{i}" for i, v in enumerate(values)}
print(f"이력의 JSON 에서 서로 다른 password 값 {len(values)}개\n")
for v in values:
    print(f"  값 {labels[v]} — {len(where[v])}개 경로")
    for p in sorted(where[v]):
        print(f"      {p}")
print()

# ── ② Secret·로컬 파일에 같은 값이 있나 ────────────────────────────────────────
def kubectl(args, stdin=None):
    return subprocess.run(["kubectl", *args], input=stdin, capture_output=True, text=True)

res = kubectl(["get", "secrets", "-A", "-o", "json"])
if res.returncode != 0:
    print("❌ Secret 목록을 읽지 못했습니다 — 검사가 유효하지 않습니다.", file=sys.stderr)
    sys.exit(1)
decoded = []                       # (ns/name, key, 값) — 값은 출력하지 않는다
for item in json.loads(res.stdout)["items"]:
    ref = f'{item["metadata"]["namespace"]}/{item["metadata"]["name"]}'
    for key, b64 in (item.get("data") or {}).items():
        try:
            decoded.append((ref, key, base64.b64decode(b64).decode("utf-8", "replace")))
        except Exception:
            pass
local_files = [f for pattern in ("k8s/20-secret.yaml*", "k8s-overlays/**/*") for f in glob.glob(pattern, recursive=True)
               if os.path.isfile(f)]
local = []
for f in local_files:
    try:
        local.append((f, open(f, encoding="utf-8", errors="replace").read()))
    except OSError:
        pass

# ── ③ 로그인 — 값은 stdin 으로만 ────────────────────────────────────────────────
PG_TRY = ('PGPASSWORD="$(cat)" PGCONNECT_TIMEOUT=5 psql -h "$(hostname -i | cut -d" " -f1)" '
          '-U "$1" -d postgres -tAc "select 1" 2>&1')
PG_POSITIVE = {"postgres": 'PGPASSWORD="$POSTGRES_PASSWORD"', "product": 'PGPASSWORD="$PRODUCT_PASSWORD"'}

def pg_login(user, value=None):
    """True=성공 · False=암호 거절 · None=판단 불가. 루프백은 trust 일 수 있어 파드 IP 로 붙는다."""
    if value is None:   # 양성 대조 — 파드 안의 현재 암호, 값은 파드 밖으로 나오지 않는다
        script = (f'{PG_POSITIVE[user]} PGCONNECT_TIMEOUT=5 psql -h "$(hostname -i | cut -d" " -f1)" '
                  f'-U "$1" -d postgres -tAc "select 1" 2>&1')
        out = kubectl(["-n", PG_NS, "exec", "-i", PG_DEPLOY, "--", "sh", "-c", script, "_", user], stdin="")
    else:
        out = kubectl(["-n", PG_NS, "exec", "-i", PG_DEPLOY, "--", "sh", "-c", PG_TRY, "_", user], stdin=value)
    text = out.stdout + out.stderr
    if out.returncode == 0 and text.strip().endswith("1"):
        return True
    if "password authentication failed" in text or "no password supplied" in text:
        return False
    return None

def redis_login(value=None):
    script = ('REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli ping 2>&1' if value is None
              else 'REDISCLI_AUTH="$(cat)" redis-cli ping 2>&1')
    out = kubectl(["-n", REDIS_NS, "exec", "-i", REDIS_DEPLOY, "--", "sh", "-c", script], stdin="" if value is None else value)
    text = (out.stdout + out.stderr).upper()
    if "PONG" in text:
        return True
    if "WRONGPASS" in text or "NOAUTH" in text or "INVALID PASSWORD" in text or "AUTH FAILED" in text:
        return False
    return None

print("대조 — 검사 자체가 유효한가")
bogus = secrets.token_urlsafe(24)
controls = {
    "PostgreSQL postgres": (pg_login("postgres"), pg_login("postgres", bogus)),
    "PostgreSQL product":  (pg_login("product"),  pg_login("product", bogus)),
    "Redis":               (redis_login(),        redis_login(bogus)),
}
valid = {}
for name, (positive, negative) in controls.items():
    ok = positive is True and negative is False
    valid[name] = ok
    print(f"  {'✅' if ok else '⚠️ '} {name}: 현재 암호 {'성공' if positive else '실패/불명'} · 무작위 값 {'거절' if negative is False else '거절 안 됨/불명'}"
          + ("" if ok else "  → 이 대상의 결과는 결론에 쓰지 않는다"))
print()

alive = False
invalid = not any(valid.values())
for v in values:
    label = labels[v]
    hits = [f"Secret {ref} ({key})" for ref, key, s in decoded if v == s or v in s]
    hits += [f"로컬 {f}" for f, body in local if v in body]
    logins = []
    for name, fn in (("PostgreSQL postgres", lambda: pg_login("postgres", v)),
                     ("PostgreSQL product", lambda: pg_login("product", v)),
                     ("Redis", lambda: redis_login(v))):
        if not valid[name]:
            continue
        r = fn()
        logins.append((name, r))
    live = [n for n, r in logins if r is True]
    unknown = [n for n, r in logins if r is None]
    verdict = "🔴 살아 있다 — 회전 필요" if (live or hits) else ("⚠️  판단 불가" if unknown else "✅ 죽은 값")
    alive |= bool(live or hits)
    print(f"  값 {label}: {verdict}")
    for n, r in logins:
        print(f"      {n}: {'성공' if r else '거절' if r is False else '불명'}")
    for h in hits:
        print(f"      같은 값 발견: {h}")
    if not hits:
        print(f"      Secret {len(decoded)}개 키·로컬 파일 {len(local)}개: 같은 값 없음")

print()
if invalid:
    print("❌ 대조가 모두 어긋났습니다 — 결론을 내지 않습니다.")
    sys.exit(1)
if alive:
    print("🔴 살아 있는 값이 있습니다. docs/30 §3 순서대로 먼저 회전하고, 그다음 ops/21 로 이력을 정리하세요.")
    sys.exit(2)
print("✅ 이력의 JSON password 값은 모두 어디에도 통하지 않습니다. docs/30 §6 에 결과를 적으세요.")
PY
