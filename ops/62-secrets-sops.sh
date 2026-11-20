#!/usr/bin/env bash
#
# 운영 액션 62 — 운영 Secret(k8s/20-secret.yaml)을 SOPS + age 로 **암호화한 채 저장소에** 둔다 (docs/57 §24).
# [JA] 本番 Secret を SOPS + age で暗号化したままリポジトリに置く。
#
# 왜: 지금은 평문 `k8s/20-secret.yaml` 이 운영 노드 · 작업 PC 에 흩어져 있고(gitignore), 어느 것이 최신인지 · 언제 무엇이 바뀌었는지
# 알 길이 없다(ops/27 이 드리프트를 사후에 찾는다). 암호문(`k8s/20-secret.enc.yaml`)을 커밋하면 이력이 남고, 값은 age 개인 키가
# 있는 곳에서만 풀린다. 키 이름 · 메타데이터는 평문이라 리뷰에서 "무엇이 바뀌었는지" 는 보인다(값은 안 보인다).
# 왜 SOPS + age 인가(Vault · Sealed Secrets 가 아니라): 운영자 한 명 · 노드 한 대 — 서버 없이 파일 하나, 클러스터 키 백업 의무 없음.
#
# 사용:
#   ./ops/62-secrets-sops.sh keygen [<파일>]        # age 키를 만든다(있으면 그대로) — 공개 키만 출력. 파일을 주면 그 이름으로:
#                                                    보관용(오프라인) · 다른 기계용 키를 이 기계의 키와 따로 만든다
#                                                    (예: keygen ~/age-offline.txt → 옮긴 뒤 이 기계에서는 지운다)
#   ./ops/62-secrets-sops.sh init <age1…> [<age1…>] # .sops.yaml 의 받는 사람(공개 키)을 정한다 — 운영 노드 · 작업 PC · 보관용 하나씩
#   ./ops/62-secrets-sops.sh encrypt                # 평문 → 암호문(왕복 확인 뒤 바꾼다)
#   ./ops/62-secrets-sops.sh check                  # 암호문이 모두 암호화됐는지 · 풀리는지 · 예시와 키 이름이 같은지 · 평문과 같은지
#   ./ops/62-secrets-sops.sh apply                  # 풀어서 곧바로 kubectl apply — 평문 파일을 만들지 않는다
#   ./ops/62-secrets-sops.sh decrypt                # 평문 파일로 푼다(0600) — 다른 ops 스크립트가 평문을 읽을 때만
#   ./ops/62-secrets-sops.sh updatekeys             # .sops.yaml 의 받는 사람이 바뀐 뒤 — 값은 그대로, 받는 사람만 다시
#   ./ops/62-secrets-sops.sh drift                  # 암호문 ↔ 클러스터의 Secret — 어느 키가 다른지(값 없이 지문). ops/28 이 배포 전에 부른다
#   ./ops/62-secrets-sops.sh drill <키 파일>         # 보관 키 복구 훈련 — 그 키 하나로 풀리는가 · 평문 · 클러스터와 같은가(분기마다, docs/57 §24)
#   ./ops/62-secrets-sops.sh rotation               # 교체 점검표 — 키마다 값이 마지막으로 바뀐 날(이력의 판마다 지문) · 오래된 순
#
# 지키는 것: 값을 출력하지 않는다(키 이름 · 개수 · 지문 앞 12자만). 평문은 umask 077 · 임시 파일은 지운다.
# 개인 키 위치: SOPS_AGE_KEY_FILE(기본 ~/.config/sops/age/keys.txt) — 저장소 밖. 잃으면 그 키로만 풀리던 암호문은 못 푼다 →
# 받는 사람을 셋(운영 노드 · 작업 PC · 오프라인 보관)으로 두는 이유.
set -euo pipefail

cd "$(dirname "$0")/.."
PLAIN="${SOPS_PLAIN:-k8s/20-secret.yaml}"
ENC="${SOPS_ENC:-k8s/20-secret.enc.yaml}"
EXAMPLE="k8s/20-secret.example.yaml"
CONFIG="${SOPS_CONFIG:-.sops.yaml}"
KEY_FILE="${SOPS_AGE_KEY_FILE:-$HOME/.config/sops/age/keys.txt}"
NS="${SECRET_NAMESPACE:-ecommerce}"
SECRET_NAME="${SECRET_NAME:-ecommerce-secrets}"
export SOPS_AGE_KEY_FILE="$KEY_FILE"
SOPS="${SOPS_BIN:-sops}"

die() { echo "🔴 $*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "$1 이 없다 — $2"; }

# stringData · data 의 키 이름(정렬) — 값은 보지 않는다. [JA] キー名だけ(値は見ない)。
# 🐛 처음에는 두 칸 들여쓰기만 봤다 — sops 는 네 칸으로 다시 쓴다. 그래서 암호문의 "평문 섞임" 검사가 아무 줄도 보지 않고 통과했다.
#    들여쓰기와 상관없이 그 절의 첫 단계 키를 읽는다(주석 · 빈 줄은 뺀다). [JA] インデント幅に関係なく読む(sops は 4 桁で書き直す)。
section_lines() {
    awk '/^(stringData|data):/ { inside = 1; next }
         /^[^ #]/ { inside = 0 }
         inside && /^[ ]+[A-Za-z0-9_.-]+:/ { print }' "$1"
}

key_names() {
    section_lines "$1" | sed -E 's/^[ ]+//; s/:.*//' | sort
}

# 값마다 sha256 앞 12자 — 평문과 암호문이 같은 값을 가졌는지 값 없이 비교한다.
# [JA] 値ごとに sha256 の先頭 12 文字 — 平文と暗号文が同じ値を持つかを値なしで比べる。
fingerprints() {
    python3 - "$1" <<'PY'
import hashlib, sys, yaml
doc = yaml.safe_load(open(sys.argv[1]))
for section in ("stringData", "data"):
    for k, v in sorted((doc.get(section) or {}).items()):
        print(k, hashlib.sha256(str(v).encode()).hexdigest()[:12])
PY
}

recipients_ok() {
    [ -f "$CONFIG" ] || die "$CONFIG 이 없다 — ./ops/62-secrets-sops.sh init <age 공개 키…>"
    if grep -q 'age1REPLACE_ME' "$CONFIG"; then
        die "$CONFIG 의 받는 사람이 자리표시(age1REPLACE_ME)다 — ./ops/62-secrets-sops.sh init <age 공개 키…>"
    fi
}

plain_not_tracked() {
    if git ls-files --error-unmatch "$PLAIN" >/dev/null 2>&1; then
        die "$PLAIN 이 git 에 추적되고 있다 — 'git rm --cached $PLAIN' 뒤 값을 교체한다(이미 이력에 남았다)"
    fi
}

cmd_keygen() {
    need age-keygen "age 를 설치한다(apt install age)"
    # 🐛 2026-11-19 사용자 지적: 파일 이름이 SOPS_AGE_KEY_FILE(기본 ~/.config/sops/age/keys.txt) 하나로 고정이라 보관용 키를 따로 만들
    #    수 없었다. 인자로 받는다 — 이 기계가 쓰는 키(SOPS_AGE_KEY_FILE)는 건드리지 않는다.
    # [JA] ファイル名を引数で受け取る — 保管用の鍵を別に作れなかった。この機械の鍵は触らない。
    local KEY_FILE="${1:-$KEY_FILE}"
    if [ -d "$KEY_FILE" ]; then die "$KEY_FILE 은 디렉터리다 — 파일 이름을 준다(예: $KEY_FILE/age-offline.txt)"; fi
    if [ -f "$KEY_FILE" ]; then
        echo "ℹ️  이미 있다: $KEY_FILE"
    else
        install -d -m 700 "$(dirname "$KEY_FILE")"
        (umask 077; age-keygen -o "$KEY_FILE" 2>/dev/null)
        echo "✅ 만들었다: $KEY_FILE (0600 — 저장소 밖, 백업은 오프라인으로)"
    fi
    echo "공개 키(받는 사람으로 init 에 넘긴다):"
    age-keygen -y "$KEY_FILE"
    if [ "$KEY_FILE" != "$SOPS_AGE_KEY_FILE" ]; then
        echo "ℹ️  이 파일로 풀려면: SOPS_AGE_KEY_FILE=$KEY_FILE ./ops/62-secrets-sops.sh check — 보관용이면 옮긴 뒤 이 기계에서는 지운다"
    fi
}

cmd_init() {
    [ "$#" -ge 1 ] || die "받는 사람(age 공개 키)을 하나 이상 — 예: init age1… age1…"
    local r
    for r in "$@"; do
        [[ "$r" =~ ^age1[0-9a-z]{58}$ ]] || die "age 공개 키 모양이 아니다: ${r:0:12}…"
    done
    # 같은 키를 두 번 넘겨도 한 번만 쓴다(순서 유지) — 2026-11-19 운영 노드의 .sops.yaml 에 같은 키가 둘 들어가 있었다.
    # sops 는 중복을 거절하지 않아 조용히 남고, 리뷰에서 "받는 사람이 셋" 으로 보여 누가 더 있는지 세게 만든다.
    # [JA] 同じ鍵を二度渡しても一度だけ書く(順序は保つ)— sops は重複を拒まないため静かに残り、レビューで数を誤らせる。
    local uniq=() seen
    for r in "$@"; do
        seen=0
        for u in "${uniq[@]:-}"; do [ "$u" = "$r" ] && seen=1 && break; done
        [ "$seen" = 1 ] || uniq+=("$r")
    done
    local joined
    joined=$(IFS=,; echo "${uniq[*]}")
    cat > "$CONFIG" <<EOF
# SOPS 규칙 — ops/62-secrets-sops.sh init 이 쓴다(docs/57 §24). 받는 사람은 공개 키라 커밋해도 된다.
# [JA] SOPS の規則 — 受け取る人は公開鍵なのでコミットしてよい。
creation_rules:
  - path_regex: k8s/20-secret\\.enc\\.yaml\$
    # 값만 암호화한다 — kind · metadata · 키 이름은 평문으로 남아 리뷰에서 무엇이 바뀌었는지 보인다.
    # [JA] 値だけを暗号化する — kind・metadata・キー名は平文で残り、レビューで何が変わったかが見える。
    encrypted_regex: ^(data|stringData)\$
    age: $joined
EOF
    echo "✅ $CONFIG — 받는 사람 ${#uniq[@]}명$([ "${#uniq[@]}" -ne "$#" ] && echo " (중복 $(( $# - ${#uniq[@]} ))개 제거)"). 이미 암호문이 있으면: ./ops/62-secrets-sops.sh updatekeys"
}

cmd_encrypt() {
    need "$SOPS" "sops 를 설치한다(docs/57 §24 — 릴리스 바이너리 + 체크섬)"
    recipients_ok
    plain_not_tracked
    [ -f "$PLAIN" ] || die "$PLAIN 이 없다 — 평문을 이 자리에 두고 다시"
    local tmp
    tmp=$(mktemp "${ENC}.XXXXXX")
    trap 'rm -f "$tmp"' RETURN
    "$SOPS" --config "$CONFIG" --encrypt --filename-override "$ENC" --input-type yaml --output-type yaml "$PLAIN" > "$tmp"
    # 왕복 확인 — 풀어서 같은 키 · 같은 값(지문)인지 본 뒤에만 바꾼다.
    # [JA] 往復確認 — 復号して同じキー · 同じ値(指紋)かを確かめてからだけ置き換える。
    local back
    back=$(mktemp)
    (umask 077; "$SOPS" --decrypt --input-type yaml --output-type yaml "$tmp" > "$back") \
        || { rm -f "$back"; die "방금 만든 암호문을 이 기계의 키로 풀지 못했다 — 이 기계의 공개 키가 받는 사람에 있는가(keygen 의 출력)"; }
    if [ "$(fingerprints "$PLAIN")" != "$(fingerprints "$back")" ]; then
        rm -f "$back"; die "왕복 결과가 평문과 다르다 — 암호문을 바꾸지 않는다"
    fi
    rm -f "$back"
    mv "$tmp" "$ENC"
    echo "✅ $ENC — 키 $(key_names "$PLAIN" | wc -l)개 암호화. 커밋한다: git add $ENC $CONFIG"
}

cmd_check() {
    [ -f "$ENC" ] || die "$ENC 이 없다 — encrypt 먼저"
    local bad=0
    # ① 값이 모두 암호문인가 — 평문이 섞인 채 커밋되는 것을 막는다(가드 B-118 도 같은 것을 본다).
    # [JA] ① 値がすべて暗号文か — 平文が混ざったままコミットされるのを防ぐ(ガード B-118 も同じものを見る)。
    local leaked
    # 빈 값("")은 sops 가 암호화하지 않는다 — 숨길 것이 없으니 누출이 아니다. [JA] 空の値は sops が暗号化しない(漏えいではない)。
    leaked=$(section_lines "$ENC" | grep -v 'ENC\[AES256_GCM,' | grep -vE ':[ ]*(""|'"''"')?[ ]*$' \
        | sed -E 's/^[ ]+//; s/:.*//' || true)
    local count
    count=$(key_names "$ENC" | wc -l)
    [ "$count" -gt 0 ] || { echo "🔴 암호문에서 키를 하나도 읽지 못했다 — 모양이 다르다"; bad=1; }
    if [ -n "$leaked" ]; then echo "🔴 암호화되지 않은 값: $(tr '\n' ' ' <<<"$leaked")"; bad=1; else echo "✅ 값이 모두 암호문"; fi
    grep -q '^sops:' "$ENC" || { echo "🔴 sops 메타데이터가 없다"; bad=1; }
    # ② 예시와 키 이름이 같은가 — 새 키를 예시에만 · 암호문에만 넣은 것을 찾는다.
    # [JA] ② 例示とキー名が同じか — 新しいキーを例示だけ · 暗号文だけに入れたものを見つける。
    local missing extra
    missing=$(comm -23 <(key_names "$EXAMPLE") <(key_names "$ENC") | tr '\n' ' ')
    extra=$(comm -13 <(key_names "$EXAMPLE") <(key_names "$ENC") | tr '\n' ' ')
    [ -z "${missing// /}" ] && echo "✅ 예시의 키가 모두 있다" || { echo "⚠️  예시에는 있고 암호문에 없는 키: $missing"; }
    [ -z "${extra// /}" ] || echo "ℹ️  암호문에만 있는 키(예시에 더할지 본다): $extra"
    # ③ 풀리는가 · 평문이 있으면 같은가(값 없이 지문으로).
    # [JA] ③ 復号できるか · 平文があれば同じか(値なしで指紋で)。
    if command -v "$SOPS" >/dev/null 2>&1 && [ -f "$KEY_FILE" ]; then
        local back
        back=$(mktemp)
        if (umask 077; "$SOPS" --decrypt --input-type yaml --output-type yaml "$ENC" > "$back" 2>/dev/null); then
            echo "✅ 이 기계의 키로 풀린다"
            if [ -f "$PLAIN" ]; then
                local differ
                differ=$(diff <(fingerprints "$PLAIN") <(fingerprints "$back") | awk '/^[<>]/ {print $2}' | sort -u | tr '\n' ' ' || true)  # diff 는 다르면 1 — pipefail 로 조용히 끝나지 않게 ／ [JA] diff は違えば 1 — pipefail で黙って終わらないように
                if [ -z "${differ// /}" ]; then echo "✅ 평문 $PLAIN 과 같다"
                else echo "⚠️  평문과 다른 키: $differ— 어느 쪽이 최신인지 보고 encrypt 또는 decrypt"; bad=1; fi
            fi
        else
            echo "🔴 이 기계의 키($KEY_FILE)로 풀리지 않는다 — 받는 사람에 이 기계가 없다"; bad=1
        fi
        rm -f "$back"
    else
        echo "ℹ️  sops · 개인 키가 없어 풀어 보지 않았다"
    fi
    plain_not_tracked
    return $bad
}

cmd_apply() {
    need "$SOPS" "sops 를 설치한다"
    need kubectl "클러스터에 닿는 곳에서 돌린다"
    [ -f "$ENC" ] || die "$ENC 이 없다"
    # 평문 파일 없이 파이프로 — 디스크에 남지 않는다.
    # [JA] 平文ファイルなしでパイプで — ディスクに残らない。
    "$SOPS" --decrypt --input-type yaml --output-type yaml "$ENC" | kubectl apply -f -
    echo "✅ 적용 — 값이 바뀐 키를 읽는 Deployment 는 재시작해야 새 값을 읽는다(ops/28 --restart 또는 해당 ops)"
}

cmd_decrypt() {
    need "$SOPS" "sops 를 설치한다"
    [ -f "$ENC" ] || die "$ENC 이 없다"
    local tmp
    tmp=$(mktemp "${PLAIN}.XXXXXX")
    (umask 077; "$SOPS" --decrypt --input-type yaml --output-type yaml "$ENC" > "$tmp") || { rm -f "$tmp"; die "풀지 못했다"; }
    if [ -f "$PLAIN" ] && [ "${FORCE:-0}" != 1 ] && [ "$(fingerprints "$PLAIN")" != "$(fingerprints "$tmp")" ]; then
        local differ
        differ=$(diff <(fingerprints "$PLAIN") <(fingerprints "$tmp") | awk '/^[<>]/ {print $2}' | sort -u | tr '\n' ' ' || true)  # diff 는 다르면 1 — pipefail 로 조용히 끝나지 않게 ／ [JA] diff は違えば 1 — pipefail で黙って終わらないように
        rm -f "$tmp"
        die "있는 평문과 다른 키: $differ— 덮어쓰지 않는다(평문이 최신이면 encrypt, 암호문이 최신이면 FORCE=1)"
    fi
    chmod 600 "$tmp"; mv "$tmp" "$PLAIN"
    plain_not_tracked
    echo "✅ $PLAIN (0600) — 다 쓴 뒤 지워도 된다(암호문이 원본)"
}

cmd_updatekeys() {
    need "$SOPS" "sops 를 설치한다"
    recipients_ok
    [ -f "$ENC" ] || die "$ENC 이 없다"
    "$SOPS" --config "$CONFIG" updatekeys --yes "$ENC"
    echo "✅ 받는 사람을 $CONFIG 대로 — 뺀 사람의 키로는 이제 못 푼다(그 사람이 값을 이미 봤다면 값 자체를 교체한다)"
}

# 값을 정규화한 지문 — stringData 는 값 그대로, data · 클러스터 Secret 은 base64 를 푼 바이트. 같은 값이면 같은 지문이다.
# [JA] 値を正規化した指紋 — stringData はそのまま、data · クラスターの Secret は base64 を解いたバイト。
normalized_fingerprints() {   # $1 = 매니페스트 yaml | secret json, $2 = yaml|json ／ [JA] $1 = マニフェスト yaml | secret json、$2 = yaml|json
    python3 - "$1" "$2" <<'PY'
import base64, hashlib, json, sys, yaml
path, kind = sys.argv[1], sys.argv[2]
doc = json.load(open(path)) if kind == "json" else yaml.safe_load(open(path))
out = {}
for k, v in (doc.get("stringData") or {}).items():
    out[k] = hashlib.sha256(("" if v is None else str(v)).encode()).hexdigest()[:12]
for k, v in (doc.get("data") or {}).items():
    out[k] = hashlib.sha256(base64.b64decode(v or "")).hexdigest()[:12]
for k in sorted(out):
    print(k, out[k])
PY
}

# 두 지문 목록의 차이를 키 이름으로만. 같으면 0, 다르면 1. [JA] 二つの指紋の差をキー名だけで。
compare_fingerprints() {   # $1 왼쪽 이름, $2 왼쪽 파일, $3 오른쪽 이름, $4 오른쪽 파일 ／ [JA] $1 左の名前、$2 左のファイル、$3 右の名前、$4 右のファイル
    local only_l only_r differ
    only_l=$(join -v1 <(cut -d' ' -f1 "$2") <(cut -d' ' -f1 "$4") | tr '\n' ' ')
    only_r=$(join -v2 <(cut -d' ' -f1 "$2") <(cut -d' ' -f1 "$4") | tr '\n' ' ')
    differ=$(join "$2" "$4" | awk '$2 != $3 { print $1 }' | tr '\n' ' ')
    [ -z "${only_l// /}" ] || echo "  ⚠️  $1 에만 있는 키: $only_l"
    [ -z "${only_r// /}" ] || echo "  ⚠️  $3 에만 있는 키: $only_r"
    [ -z "${differ// /}" ] || echo "  ⚠️  값이 다른 키: $differ"
    [ -z "${only_l// /}${only_r// /}${differ// /}" ]
}

cmd_drift() {
    need "$SOPS" "sops 를 설치한다"
    need kubectl "클러스터에 닿는 곳에서 돌린다"
    [ -f "$ENC" ] || die "$ENC 이 없다 — 암호문을 아직 만들지 않았다(encrypt)"
    local dir
    dir=$(mktemp -d); chmod 700 "$dir"
    trap 'rm -rf "$dir"' RETURN
    (umask 077; "$SOPS" --decrypt --input-type yaml --output-type yaml "$ENC" > "$dir/plain.yaml" 2>/dev/null) \
        || die "이 기계의 키($KEY_FILE)로 암호문이 풀리지 않는다"
    (umask 077; kubectl -n "$NS" get secret "$SECRET_NAME" -o json > "$dir/cluster.json" 2>/dev/null) \
        || die "클러스터에서 $NS/$SECRET_NAME 를 읽지 못했다(컨텍스트: $(kubectl config current-context 2>/dev/null || echo 없음))"
    normalized_fingerprints "$dir/plain.yaml" yaml > "$dir/a"
    normalized_fingerprints "$dir/cluster.json" json > "$dir/b"
    echo "▶ 암호문 $ENC ↔ 클러스터 $NS/$SECRET_NAME (키 $(wc -l < "$dir/a")개 · $(wc -l < "$dir/b")개)"
    if compare_fingerprints "암호문" "$dir/a" "클러스터" "$dir/b"; then
        echo "  ✅ 같다"
    else
        echo "  → 암호문이 최신이면 ./ops/62-secrets-sops.sh apply, 클러스터가 최신이면(손으로 바꿨다) decrypt → 그 값으로 고치기 → encrypt"
        return 1
    fi
}

cmd_drill() {
    [ "$#" -ge 1 ] || die "보관 키 파일을 준다 — 예: drill /media/usb/age-offline-backup.txt"
    local key="$1"
    [ -f "$key" ] || die "$key 이 없다"
    need "$SOPS" "sops 를 설치한다"
    need age-keygen "age 를 설치한다"
    [ -f "$ENC" ] || die "$ENC 이 없다"
    local pub
    pub=$(age-keygen -y "$key" 2>/dev/null) || die "$key 은 age 개인 키가 아니다"
    echo "▶ 보관 키 복구 훈련 — $key (공개 키 ${pub:0:16}…)"
    local bad=0
    if grep -q "$pub" "$CONFIG" 2>/dev/null; then echo "  ✅ $CONFIG 의 받는 사람에 있다"
    else echo "  🔴 $CONFIG 의 받는 사람에 없다 — 이 키로는 다음 encrypt 부터 풀 수 없다(init 에 넣고 updatekeys)"; bad=1; fi
    local dir
    dir=$(mktemp -d); chmod 700 "$dir"
    trap 'rm -rf "$dir"' RETURN
    # 이 기계의 키는 쓰지 않는다 — 보관 키 하나만으로 풀려야 한다.
    # [JA] このマシンの鍵は使わない — 保管鍵一つだけで復号できなければならない。
    if (umask 077; SOPS_AGE_KEY_FILE="$key" "$SOPS" --decrypt --input-type yaml --output-type yaml "$ENC" > "$dir/plain.yaml" 2>/dev/null); then
        normalized_fingerprints "$dir/plain.yaml" yaml > "$dir/a"
        echo "  ✅ 이 키 하나로 풀린다 — 키 $(wc -l < "$dir/a")개"
        if [ -f "$PLAIN" ]; then
            normalized_fingerprints "$PLAIN" yaml > "$dir/p"
            if compare_fingerprints "보관 키로 푼 것" "$dir/a" "평문 $PLAIN" "$dir/p"; then echo "  ✅ 평문과 같다"; else bad=1; fi
        fi
        if command -v kubectl >/dev/null 2>&1 && kubectl -n "$NS" get secret "$SECRET_NAME" -o json > "$dir/cluster.json" 2>/dev/null; then
            normalized_fingerprints "$dir/cluster.json" json > "$dir/b"
            if compare_fingerprints "보관 키로 푼 것" "$dir/a" "클러스터" "$dir/b"; then echo "  ✅ 클러스터와 같다"; else bad=1; fi
        else
            echo "  ℹ️  클러스터에 닿지 않아 비교하지 않았다"
        fi
    else
        echo "  🔴 이 키로 풀리지 않는다 — 받는 사람에서 빠졌거나 다른 키다. 다른 키가 모두 사라지면 운영 비밀을 잃는다"; bad=1
    fi
    [ "$bad" = 0 ] && echo "✅ 훈련 통과 — 날짜를 docs/57 §24 의 점검표에 적는다(키는 다시 오프라인으로)"
    return $bad
}

# 분기 교체 점검표 — 키마다 값이 마지막으로 바뀐 커밋 날짜, 오래된 순. 암호문의 줄이 아니라 **값의 지문**으로 본다: encrypt 는 값을 모두
# 새로 암호화하므로(새 IV) 줄은 매번 바뀐다. 이력의 판마다 이 기계의 키로 풀어 지문만 남긴다(값 · 평문 파일은 남기지 않는다).
# [JA] 四半期の交換点検表 — キーごとに値が最後に変わったコミット日、古い順。暗号文の行ではなく値の指紋で見る(encrypt は毎回すべて再暗号化)。
cmd_rotation() {
    need "$SOPS" "sops 를 설치한다"
    [ -f "$ENC" ] || die "$ENC 이 없다"
    # 이력은 암호문이 든 저장소에서 읽는다(SOPS_ENC 로 다른 곳을 가리켜도).
    # [JA] 履歴は暗号文が入っているリポジトリから読む(SOPS_ENC で別の場所を指していても)。
    local gdir gname
    gdir=$(dirname "$ENC"); gname=$(basename "$ENC")
    git -C "$gdir" ls-files --error-unmatch "$gname" >/dev/null 2>&1 || die "$ENC 이 아직 커밋되지 않았다 — 이력이 없다"
    local days="${ROTATION_DAYS:-90}" now dir i=0 h ct cs
    now=$(date -u +%s)
    dir=$(mktemp -d); chmod 700 "$dir"
    trap 'rm -rf "$dir"' RETURN
    # 새것부터: 판마다 "판번호 커밋시각 날짜 키 지문" 줄. 풀리지 않는 판(이 기계가 받는 사람이 아니던 때)에서 멈춘다.
    # [JA] 新しいものから: 版ごとに「版番号 コミット時刻 日付 キー 指紋」の行。復号できない版(このマシンが受信者でなかった頃)で止まる。
    while read -r h ct cs; do
        git -C "$gdir" show "$h:./$gname" > "$dir/enc.yaml" 2>/dev/null || continue
        (umask 077; "$SOPS" --decrypt --input-type yaml --output-type yaml "$dir/enc.yaml" > "$dir/plain.yaml" 2>/dev/null) || {
            echo "  ℹ️  $cs 판부터는 이 기계의 키로 풀리지 않는다 — 그 앞은 보지 않는다"; break; }
        normalized_fingerprints "$dir/plain.yaml" yaml | sed "s/^/$i $ct $cs /" >> "$dir/history"
        rm -f "$dir/plain.yaml"
        i=$((i + 1))
    done < <(git -C "$gdir" log --format='%H %ct %cs' -- "$gname")
    [ -s "$dir/history" ] || die "이력의 어느 판도 풀리지 않았다"
    echo "▶ 분기 교체 점검표 — 값이 마지막으로 바뀐 날(판 $i개 · 기준 ${days}일)"
    # 키마다: 지금(판 0)의 지문과 같은 판이 끊기지 않고 이어지는 가장 오래된 판 = 그 값이 들어온 커밋
    # [JA] キーごとに: 現在(版 0)の指紋と同じ版が途切れずに続く最も古い版 = その値が入ったコミット
    python3 - "$dir/history" > "$dir/since" <<'PY'
import sys
by = {}
for line in open(sys.argv[1]):
    idx, ct, cs, k, fp = line.split()
    by.setdefault(int(idx), {})[k] = (ct, cs, fp)
for k, (ct, cs, fp) in by[0].items():
    since, i = (ct, cs), 1
    while i in by and by[i].get(k, (None, None, None))[2] == fp:
        since, i = by[i][k][:2], i + 1
    print(f"{since[0]}\t{since[1]}\t{k}")
PY
    local when d k age
    while IFS=$'\t' read -r when d k; do
        age=$(( (now - when) / 86400 ))
        if [ "$age" -ge "$days" ]; then echo "  ⏰ $k — $d (${age}일)"; else echo "  ✅ $k — $d (${age}일)"; fi
    done < <(sort -n "$dir/since")
    echo "  ⏰ 는 교체 차례 — 어떤 값을 바꾸면 무엇을 재시작하는지는 docs/57 §24 의 점검표"
}

case "${1:-}" in
  keygen)     shift; cmd_keygen "$@" ;;
  init)       shift; cmd_init "$@" ;;
  encrypt)    cmd_encrypt ;;
  check)      cmd_check ;;
  apply)      cmd_apply ;;
  decrypt)    cmd_decrypt ;;
  updatekeys) cmd_updatekeys ;;
  drift)      cmd_drift ;;
  drill)      shift; cmd_drill "$@" ;;
  rotation)   cmd_rotation ;;
  *) sed -n '2,27p' "$0"; exit 1 ;;
esac
