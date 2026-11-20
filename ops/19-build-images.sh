#!/usr/bin/env bash
#
# 배포용 컨테이너 이미지 빌드 — 아키텍처를 골라서, 레지스트리로.
#
# ops/13-k3s-deploy.sh 와 역할이 다르다:
#   · 13 = **로컬 단일 노드 검증**. 이미지를 k3s 안으로 직접 넣고 끝난다(레지스트리 없음).
#   · 19 = **배포**. 레지스트리에 올리고, 아키텍처를 지정할 수 있다.
#
# 왜 필요한가: 개발기(Apple Silicon = arm64)와 배포 대상(WSL/일반 서버 = amd64)의
# 아키텍처가 다르면 이미지가 그대로 안 돈다. arm64 에서 만든 이미지를 amd64 노드에
# 올리면 `exec format error` 로 죽는다.
#
# ⭐ 파이프라인에서도 **이 스크립트가 그대로 쓰인다.** .github/workflows/build.yml 의
#    package 스테이지가 --skip-gradle 로 이 파일을 호출한다. CI 안에 별도의
#    docker build 단계를 두지 않는 이유는 하나다 — 두 벌이 되는 순간 반드시 어긋나고,
#    그때 "CI 에서는 되는데 손으로 하면 안 된다"가 시작된다. 스크립트는 한 벌이어야 한다.
#
# 사용:
#   ops/19-build-images.sh --registry ghcr.io/USER --platform linux/amd64 --push
#   ops/19-build-images.sh --registry ghcr.io/USER --platform linux/amd64,linux/arm64 --push
#   ops/19-build-images.sh --platform linux/amd64 --load        # 로컬 도커에만
#
#   --tag 기본값은 **현재 커밋의 SHA(12자)** 다. 사람이 고른 1.0.0 이 아니다 — 이유는
#   아래 '태그는 기본적으로 리비전이다' 참조.
#   --skip-gradle  이미 있는 jar 를 쓴다 (커밋 스테이지 산출물 재사용)
#
#   --only 로 일부만 만든다. **따옴표로 묶어 여러 개**를 줄 수 있고, frontend 도 섞을 수 있다:
#     ops/19-build-images.sh --registry ghcr.io/USER --push --only delivery-service
#     ops/19-build-images.sh --registry ghcr.io/USER --push --only "delivery-service frontend"
#   common 은 서비스가 아니라 라이브러리다 — 적지 않는다. Gradle 이 알아서 함께 만든다.
#
# ── arm64 (OCI Ampere) 로 배포한다면 ────────────────────────────────────
#
# **인스턴스 위에서 그냥 돌리는 것이 가장 빠르다.** Ampere 는 arm64 이므로 --platform 을
# 주지 않아도 호스트 기준으로 arm64 가 잡히고, 에뮬레이션이 전혀 없다:
#
#   ssh ubuntu@<Ampere-IP>
#   ./ops/19-build-images.sh --registry ghcr.io/USER --push
#
# 맥에서 원격지용으로 만들 때만 --platform 이 필요하다. Apple Silicon 도 arm64 라
# OCI Ampere 용이면 역시 네이티브다 — 느려지는 조합은 **amd64 를 arm 에서, 또는 그 반대로**
# 만들 때뿐이다(ops/Dockerfile.runtime 주석 참조: jar 는 한 번만 빌드하므로 그마저도 짧다).
#
# [JA] 配備用コンテナイメージのビルド — アーキテクチャを選んで、レジストリへ。
#
#      ops/13-k3s-deploy.sh とは役割が違う:
#        · 13 = **ローカル単一ノードの検証**。イメージを k3s の中に直接入れて終わる(レジストリなし)。
#        · 19 = **配備**。レジストリに上げ、アーキテクチャを指定できる。
#
#      なぜ必要か: 開発機(Apple Silicon = arm64)と配備先(WSL/一般サーバー = amd64)の
#      アーキテクチャが違うとイメージはそのままでは動かない。arm64 で作ったイメージを amd64 ノードに
#      載せると `exec format error` で死ぬ。
#
#      ⭐ パイプラインでも **このスクリプトがそのまま使われる。** .github/workflows/build.yml の
#         package ステージが --skip-gradle でこのファイルを呼ぶ。CI の中に別の
#         docker build 段階を置かない理由は一つ — 二系統になった瞬間に必ずずれ、
#         そこから「CI では動くのに手でやると動かない」が始まる。スクリプトは一系統でなければならない。
#
#      使い方:
#        ops/19-build-images.sh --registry ghcr.io/USER --platform linux/amd64 --push
#        ops/19-build-images.sh --registry ghcr.io/USER --platform linux/amd64,linux/arm64 --push
#        ops/19-build-images.sh --platform linux/amd64 --load        # ローカルの docker にだけ
#
#        --tag の既定値は **現在のコミットの SHA(12 文字)** である。人が選んだ 1.0.0 ではない — 理由は
#        下の「タグは基本的にリビジョンである」を参照。
#        --skip-gradle  既存の jar を使う(コミットステージの成果物の再利用)
#
#        --only で一部だけ作る。**引用符で囲んで複数** 渡せ、frontend も混ぜられる:
#          ops/19-build-images.sh --registry ghcr.io/USER --push --only delivery-service
#          ops/19-build-images.sh --registry ghcr.io/USER --push --only "delivery-service frontend"
#        common はサービスではなくライブラリである — 書かない。Gradle が自動で一緒に作る。
#
#      ── arm64(OCI Ampere)に配備するなら ──
#
#      **インスタンスの上でそのまま回すのがいちばん速い。** Ampere は arm64 なので --platform を
#      渡さなくてもホスト基準で arm64 になり、エミュレーションがまったくない:
#
#        ssh ubuntu@<Ampere-IP>
#        ./ops/19-build-images.sh --registry ghcr.io/USER --push
#
#      Mac からリモート向けに作るときだけ --platform が必要である。Apple Silicon も arm64 なので
#      OCI Ampere 向けならやはりネイティブだ — 遅くなる組み合わせは **amd64 を arm で、またはその逆で**
#      作るときだけである(ops/Dockerfile.runtime のコメント参照: jar は一度しかビルドしないのでそれすら短い)。
set -euo pipefail

# ---------------------------------------------------------------- 기본값
# [JA] 既定値

REGISTRY="${REGISTRY:-localhost:5000}"
PLATFORM=""                     # 비우면 호스트 아키텍처 ／ [JA] 空ならホストのアーキテクチャ
OUTPUT=""                       # --push | --load
# 기본 JRE 는 ops/Dockerfile.runtime 의 고정값(정확한 판 + 다이제스트, B-98) — 두 곳에 따로 적지 않는다
# [JA] 既定の JRE は ops/Dockerfile.runtime の固定値(正確な版 + ダイジェスト、B-98) — 二か所に別々に書かない
JRE_IMAGE="${JRE_IMAGE:-$(sed -n 's/^ARG JRE_IMAGE=//p' "$(dirname "$0")/Dockerfile.runtime")}"
SKIP_GRADLE="${SKIP_GRADLE:-0}"
ONLY=""

# ── 태그는 기본적으로 **리비전**이다 ────────────────────────────────────
#
# 예전 기본값은 `1.0.0` 이었다. 사람이 고른 숫자라서 두 가지가 동시에 깨졌다:
#
#   ① 추적 불가 — 클러스터에서 도는 `:1.0.0` 이 어느 커밋인지 알 방법이 없다.
#      "이 버그가 배포본에 들어 있나?" 에 답할 수 없다는 뜻이다.
#   ② 같은 태그 재사용 — 고쳐서 다시 push 해도 노드는 캐시된 이미지를 계속 쓴다
#      (imagePullPolicy 기본값이 IfNotPresent). 이 세션에서 실제로 겪었다.
#
# 커밋 SHA 는 둘 다 해결한다. 절대 재사용되지 않고, 이미지에서 소스로 되짚을 수 있다.
# 릴리스 후보의 이름은 리비전이다 — 사람이 고르는 순간 그 성질을 잃는다.
# [JA] ── タグは基本的に **リビジョン** である ──
#
#      以前の既定値は `1.0.0` だった。人が選んだ数字なので二つが同時に壊れた:
#
#        ① 追跡不能 — クラスタで動いている `:1.0.0` がどのコミットかを知る方法がない。
#           「このバグは配備物に入っているか?」に答えられないという意味だ。
#        ② 同じタグの再利用 — 直して push し直してもノードはキャッシュされたイメージを使い続ける
#           (imagePullPolicy の既定値が IfNotPresent)。このセッションで実際に経験した。
#
#      コミット SHA は両方を解決する。決して再利用されず、イメージからソースへたどれる。
#      リリース候補の名前はリビジョンである — 人が選んだ瞬間にその性質を失う。
if [ -z "${TAG:-}" ]; then
    if TAG=$(git -C "$(dirname "$0")/.." rev-parse --short=12 HEAD 2>/dev/null); then
        :
    else
        TAG="0.0.0-nogit"
    fi
fi

# 프론트엔드는 빌드 시점에 값이 번들에 박힌다(런타임 환경변수로 못 바꾼다).
#
# 값은 환경 변수로 받거나 **ops/frontend.env**(gitignore — ops/frontend.env.example 을 복사)에서 읽는다. 환경 변수가 있으면 그쪽이
# 이긴다.
#
# 🐛 셸에서 `REACT_APP_API_URL=https://…` 를 **한 줄로 따로** 치면 그 셸의 변수일 뿐 스크립트에 넘어가지 않는다(export 가
# 없으면 자식 프로세스는 못 본다). echo 로는 보이니 "설정했는데 비어 있다고 한다" 가 된다. 파일이면 그런 일이 없다.
# [JA] フロントエンドはビルド時点で値がバンドルに埋め込まれる(実行時の環境変数では変えられない)。
#
#      値は環境変数で受け取るか、**ops/frontend.env**(gitignore — ops/frontend.env.example をコピー)から読む。環境変数があればそちらが
#      勝つ。
#
#      🐛 シェルで `REACT_APP_API_URL=https://…` を **一行だけ別に** 打つと、そのシェルの変数にすぎずスクリプトには渡らない(export が
#      ないと子プロセスは見えない)。echo では見えるので「設定したのに空だと言われる」になる。ファイルならそういうことはない。
FRONTEND_ENV="${FRONTEND_ENV:-$(cd "$(dirname "$0")" && pwd)/frontend.env}"
FRONTEND_ENV_LOADED=""
if [ -f "$FRONTEND_ENV" ]; then
    # source 하지 않는다 — REACT_APP_* 키만, 값은 문자 그대로(따옴표 한 겹은 벗긴다). 명령이 섞여 있어도 실행되지 않는다.
    # [JA] source しない — REACT_APP_* のキーだけ、値は文字どおり(引用符一重は外す)。コマンドが混ざっていても実行されない。
    while IFS= read -r line || [ -n "$line" ]; do
        line="${line%$'\r'}"
        case "$line" in ''|'#'*) continue ;; esac
        key="${line%%=*}"; val="${line#*=}"
        key="${key#export }"; key="${key// /}"
        [[ "$key" =~ ^REACT_APP_[A-Z0-9_]+$ ]] || continue
        case "$val" in \"*\") val="${val:1:${#val}-2}" ;; \'*\') val="${val:1:${#val}-2}" ;; esac
        if [ -z "${!key:-}" ]; then
            printf -v "$key" '%s' "$val"
            export "${key?}"
            FRONTEND_ENV_LOADED="$FRONTEND_ENV_LOADED $key"
        fi
    done < "$FRONTEND_ENV"
fi
REACT_APP_API_URL="${REACT_APP_API_URL:-}"
REACT_APP_PAYPAL_CLIENT_ID="${REACT_APP_PAYPAL_CLIENT_ID:-}"
# 포트원(선택). 가맹점 식별코드는 공개 값이다(imp 로 시작). PG 코드는 콘솔의 결제 채널과
# 같아야 한다 — 다르면 결제창이 "등록되지 않은 PG" 로 뜬다.
# [JA] ポートワン(任意)。加盟店識別コードは公開値である(imp で始まる)。PG コードはコンソールの決済チャネルと
#      同じでなければならない — 違うと決済ウィンドウが「登録されていない PG」と出る。
REACT_APP_PORTONE_IMP_CODE="${REACT_APP_PORTONE_IMP_CODE:-}"
REACT_APP_PORTONE_PG="${REACT_APP_PORTONE_PG:-html5_inicis}"
# 포트원 V2(신규 계정). Store ID(store-…)와 채널 키(channel-key-…)는 공개 값이다. 둘이 있으면
# 화면은 V2 로 동작하고 위 V1 값은 무시된다. 청구 통화(KRW 등)는 주문이 갖고 온다.
# [JA] ポートワン V2(新規アカウント)。Store ID(store-…)とチャネルキー(channel-key-…)は公開値である。両方あれば
#      画面は V2 で動作し、上の V1 の値は無視される。請求通貨(KRW など)は注文が持ってくる。
REACT_APP_PORTONE_STORE_ID="${REACT_APP_PORTONE_STORE_ID:-}"
REACT_APP_PORTONE_CHANNEL_KEY="${REACT_APP_PORTONE_CHANNEL_KEY:-}"
# 이니시스 일본결제(CBT JPPG)는 결제창에 상점 정보가 필수다 — 상점명 · 연락처는 화면 코드(util/portone.js), 이메일만 여기(공개 값).
# [JA] イニシス日本決済(CBT JPPG)は決済ウィンドウに店舗情報が必須である — 店舗名 · 連絡先は画面のコード(util/portone.js)、メールだけここ(公開値)。
REACT_APP_PORTONE_STORE_EMAIL="${REACT_APP_PORTONE_STORE_EMAIL:-}"
# 상점 통화 — 서버의 STORE_CURRENCY 와 같아야 한다. 청구 통화는 주문이 갖고 오므로 빌드 인자가 아니다.
# [JA] ストア通貨 — サーバーの STORE_CURRENCY と同じでなければならない。請求通貨は注文が持ってくるのでビルド引数ではない。
REACT_APP_STORE_CURRENCY="${REACT_APP_STORE_CURRENCY:-USD}"

SERVICES="eureka-server config-server gateway-server user-service catalog-service cart-service order-service pay-service coupon-service chat-service notification-service delivery-service saga-projection"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

log()  { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
info() { printf '  \033[0;32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[0;33m!\033[0m %s\n' "$*"; }
die()  { printf '\n\033[0;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- 시간 측정
#
# 왜 넣는가: "빌드가 느리다"까지는 누구나 아는데 **어디가** 느린지는 아무도 모른다.
# 이 스크립트는 성격이 전혀 다른 일 셋(자바 컴파일 / 도커 빌드+푸시 / CRA 번들)을
# 연달아 하므로, 총 시간만 보고 고치려 들면 엉뚱한 데를 건드리게 된다.
#
# 실제로 그랬다. jar 가 재현 가능하지 않아 레이어가 매번 달라진다는 걸 찾아 고쳤는데
# (build.gradle 의 preserveFileTimestamps) 체감 시간이 그대로였다 — 푸시가 병목이
# 아니었기 때문이다. 측정 없이 고친 대가다.
#
# 그래서 구간마다 초를 재고 마지막에 표로 찍는다. 다음에 "느리다"는 말이 나오면
# 추측 대신 이 표를 본다.
# [JA] 時間計測
#
#      なぜ入れるか:「ビルドが遅い」までは誰でも知っているが、**どこが** 遅いかは誰も知らない。
#      このスクリプトは性格のまったく違う三つの仕事(Java コンパイル / docker ビルド+プッシュ / CRA バンドル)を
#      続けて行うので、合計時間だけを見て直そうとすると見当違いの所に手を出すことになる。
#
#      実際にそうなった。jar が再現可能でなくレイヤーが毎回変わることを見つけて直したのに
#      (build.gradle の preserveFileTimestamps)体感時間はそのままだった — プッシュがボトルネックでは
#      なかったからだ。計測せずに直した代償である。
#
#      だから区間ごとに秒を測り、最後に表で出す。次に「遅い」という話が出たら
#      推測の代わりにこの表を見る。
TIMINGS=""
now() { date +%s; }
record() {   # record <라벨> <시작초> ／ [JA] record <ラベル> <開始秒>
    TIMINGS="${TIMINGS}$(( $(now) - $2 ))|$1
"
}

# ---------------------------------------------------------------- 인자
# [JA] 引数

while [ $# -gt 0 ]; do
    case "$1" in
        --registry) REGISTRY="$2"; shift 2 ;;
        --tag)      TAG="$2";      shift 2 ;;
        --platform) PLATFORM="$2"; shift 2 ;;
        --only)     ONLY="$2";     shift 2 ;;
        --push)     OUTPUT="--push"; shift ;;
        --load)     OUTPUT="--load"; shift ;;
        --skip-gradle) SKIP_GRADLE=1; shift ;;
        -h|--help)
            sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) die "알 수 없는 옵션: $1" ;;
    esac
done

command -v docker >/dev/null || die "docker 가 필요합니다"
docker buildx version >/dev/null 2>&1 || die "docker buildx 가 필요합니다 (Docker 19.03+)"

# 플랫폼 미지정이면 호스트 아키텍처로 고정한다. 명시하지 않으면 buildx 가 호스트
# 아키텍처로 만드는데, 그러면 "왜 amd64 노드에서 안 돌지?" 를 나중에 겪게 된다.
# [JA] プラットフォーム未指定ならホストのアーキテクチャに固定する。明示しないと buildx がホストの
#      アーキテクチャで作るが、そうすると「なぜ amd64 ノードで動かないのか?」を後で経験することになる。
if [ -z "$PLATFORM" ]; then
    case "$(uname -m)" in
        x86_64|amd64)  PLATFORM="linux/amd64" ;;
        arm64|aarch64) PLATFORM="linux/arm64" ;;
        *) die "호스트 아키텍처를 알 수 없습니다: $(uname -m). --platform 으로 지정하세요" ;;
    esac
    warn "--platform 미지정 → 호스트 기준 $PLATFORM 로 빌드합니다"
fi

MULTIARCH=0
case "$PLATFORM" in *,*) MULTIARCH=1 ;; esac

# 출력 방식 결정 및 검증.
# [JA] 出力方式の決定と検証。
if [ -z "$OUTPUT" ]; then
    if [ "$MULTIARCH" = "1" ]; then
        die "멀티아키텍처 빌드는 --push 가 필요합니다 (아래 설명 참조)"
    fi
    OUTPUT="--load"
    warn "--push/--load 미지정 → --load (로컬 도커에만 저장)"
fi

# ⚠️ 도커의 기본 이미지 스토어는 **하나의 이미지에 여러 아키텍처를 담지 못한다.**
#    멀티아치 결과물은 매니페스트 리스트라서 레지스트리에만 올릴 수 있다.
#    (containerd image store 를 켜면 --load 도 되지만, 기본값이 아니라 전제하지 않는다.)
# [JA] ⚠️ docker の既定のイメージストアは **一つのイメージに複数のアーキテクチャを入れられない。**
#         マルチアーキの成果物はマニフェストリストなので、レジストリにしか上げられない。
#         (containerd image store を有効にすれば --load もできるが、既定値ではないので前提にしない。)
if [ "$MULTIARCH" = "1" ] && [ "$OUTPUT" = "--load" ]; then
    die "멀티아키텍처는 --load 로 가져올 수 없습니다. --push 를 쓰거나 플랫폼을 하나만 지정하세요"
fi

# ⚠️ frontend 는 자바 서비스가 아니다 — 3단계에서 dist 를 만들어 따로 빌드한다.
#    그런데 예전에는 `--only frontend` 가 이 목록에 그대로 들어가, 2단계가 frontend 의
#    **jar 를 찾다 죽었다**(`frontend 의 jar 를 찾을 수 없습니다`). 3단계까지 가지도 못하니
#    `--only frontend` 로는 프론트엔드를 절대 만들 수 없었다.
#    여기서 걸러 낸다. 목록이 비면 2단계는 건너뛴다.
# [JA] ⚠️ frontend は Java サービスではない — 3 段階目で dist を作って別にビルドする。
#         ところが以前は `--only frontend` がこの一覧にそのまま入り、2 段階目が frontend の
#         **jar を探して死んだ**(`frontend 의 jar 를 찾을 수 없습니다`)。3 段階目までたどり着けないので
#         `--only frontend` ではフロントエンドを絶対に作れなかった。
#         ここで除外する。一覧が空なら 2 段階目は飛ばす。
BUILD_FRONTEND=1
if [ -n "$ONLY" ]; then
    SERVICES=$(printf '%s\n' $ONLY | grep -vx frontend | tr '\n' ' ' || true)
    # 🐛 예전에는 3단계 조건이 `[ "$ONLY" = "frontend" ]` 였다. 문자열을 통째로 비교하므로
    #    `--only "delivery-service frontend"` 처럼 **둘 이상을 주면 프론트엔드가 조용히
    #    빠졌다.** 스크립트는 성공으로 끝나고, 배포한 뒤에야 "화면이 그대로"로 드러난다.
    #    낱말 단위로 본다.
    # [JA] 🐛 以前は 3 段階目の条件が `[ "$ONLY" = "frontend" ]` だった。文字列を丸ごと比較するので
    #         `--only "delivery-service frontend"` のように **二つ以上渡すとフロントエンドが静かに
    #         抜けた。** スクリプトは成功で終わり、配備した後で「画面がそのまま」として初めて表に出る。
    #         単語単位で見る。
    BUILD_FRONTEND=0
    case " $ONLY " in *" frontend "*) BUILD_FRONTEND=1 ;; esac
fi

# ---------------------------------------------------------------- 실패를 말하게 하기
#
# 예전에는 모든 빌드를 `>/dev/null 2>&1` 로 돌리고 "빌드 실패"만 찍었다. 그러면 왜
# 실패했는지 알 방법이 없다 — 게다가 "위 명령을 직접 실행하세요"라고 안내하면서
# 정작 그 명령을 출력하지도 않았다. 이제 출력을 잡아 두었다가 실패 시 보여 준다.
# [JA] 失敗を語らせる
#
#      以前はすべてのビルドを `>/dev/null 2>&1` で回し「ビルド失敗」とだけ出していた。それではなぜ
#      失敗したのか知る方法がない — しかも「上のコマンドを直接実行してください」と案内しながら
#      肝心のそのコマンドを出力すらしていなかった。今は出力を取っておき、失敗時に見せる。

BUILD_LOG="$(mktemp)"
trap 'rm -f "$BUILD_LOG"' EXIT

# 흔한 실패를 알아보고 무엇을 하라고 말해 준다. ops/26 이 발사 실패를 분류하는 것과 같다.
# [JA] よくある失敗を見分けて、何をすればよいかを伝える。ops/26 が発射の失敗を分類するのと同じである。
explain_failure() {
    # 스코프 부족과 로그인 실패를 **구분한다.** 로그에 [auth] ... DONE 이 찍혔다면 로그인은
    # 이미 성공한 것이고, 그때 "다시 로그인하세요"라고 안내하면 헛수고만 시킨다.
    # [JA] スコープ不足とログイン失敗を **区別する。** ログに [auth] ... DONE が出ているならログインは
    #      すでに成功しており、そこで「もう一度ログインしてください」と案内すると無駄足を踏ませるだけだ。
    if grep -qiE 'does not match expected scopes|insufficient_scope' "$BUILD_LOG"; then
        warn "로그인은 됐지만 **토큰의 스코프가 모자랍니다** — 다시 로그인해도 해결되지 않습니다."
        warn "  지금 토큰이 무슨 스코프를 갖고 있는지 확인하세요(토큰 값은 찍히지 않습니다):"
        warn "    curl -sI -H \"Authorization: Bearer \$GHCR_PAT\" https://api.github.com/user \\"
        warn "      | grep -i x-oauth-scopes"
        warn ""
        warn "  · write:packages 가 목록에 없다 → GitHub 토큰 설정에서 체크하고 저장하세요"
        warn "  · x-oauth-scopes 헤더가 아예 안 나온다 → **fine-grained 토큰**입니다."
        warn "    GHCR 푸시는 classic 토큰이 필요합니다. Tokens (classic) 으로 새로 만드세요"
        warn ""
        warn "  토큰을 고친 뒤에는 반드시 다시 로그인해야 합니다(자격증명이 캐시됩니다):"
        warn "    docker logout ghcr.io && echo \"\$GHCR_PAT\" | docker login ghcr.io -u <USER> --password-stdin"
    elif grep -qiE 'permission_denied: read_package|permission_denied' "$BUILD_LOG"; then
        # ⚠️ 이 실패를 위의 '로그인 문제'와 **반드시 구분해야 한다.** 로그인은 성공했고
        #    토큰도 발급됐는데(로그에 `[auth] ... pull,push token` 이 DONE 으로 찍힌다)
        #    push 만 거부된 상태다. 여기서 "PAT 를 다시 만드세요"라고 안내하면 아무 소용
        #    없는 일을 시키게 된다 — 실제로 첫 파이프라인 실행에서 이 오진을 했다.
        #
        #    원인은 **패키지와 저장소의 연결**이다. GHCR 패키지는 만든 주체의 것이고,
        #    사람이 PAT 로 먼저 push 해 둔 패키지는 저장소에 연결돼 있지 않다. 그래서
        #    Actions 의 GITHUB_TOKEN 은 packages: write 가 있어도 그 패키지를 못 건드린다.
        # [JA] ⚠️ この失敗を上の「ログインの問題」と **必ず区別しなければならない。** ログインは成功し
        #         トークンも発行されたのに(ログに `[auth] ... pull,push token` が DONE で出る)
        #         push だけが拒否された状態である。ここで「PAT を作り直してください」と案内すると何の役にも
        #         立たないことをさせることになる — 実際、最初のパイプライン実行でこの誤診をした。
        #
        #         原因は **パッケージとリポジトリの紐付け** である。GHCR のパッケージは作った主体のもので、
        #         人が PAT で先に push しておいたパッケージはリポジトリに紐付いていない。だから
        #         Actions の GITHUB_TOKEN は packages: write があってもそのパッケージを触れない。
        warn "로그인은 됐는데 **패키지 접근이 거부**됐습니다 — 자격증명 문제가 아닙니다."
        warn "  GHCR 패키지가 이 저장소에 연결돼 있지 않을 때 나오는 응답입니다."
        warn "  (사람이 PAT 로 먼저 push 해 둔 패키지는 자동으로 연결되지 않습니다.)"
        warn ""
        warn "  해결 ① 권장 — 패키지마다 한 번만 하면 끝납니다:"
        warn "    github.com/users/<OWNER>/packages/container/<패키지>/settings"
        warn "    → Manage Actions access → Add Repository → 이 저장소 → Role: Write"
        warn ""
        warn "  해결 ② 임시 — 저장소 시크릿 GHCR_PAT(classic, write:packages)를 넣으면"
        warn "    워크플로가 GITHUB_TOKEN 대신 그것을 씁니다. 다만 수명이 긴 비밀이"
        warn "    하나 늘어나므로 ①로 옮기는 것이 낫습니다."
    elif grep -qiE 'denied|unauthorized|authentication required' "$BUILD_LOG"; then
        warn "레지스트리가 거부했습니다 — 로그인이나 권한 문제입니다:"
        warn "    echo \"\$GHCR_PAT\" | docker login ghcr.io -u <USER> --password-stdin"
        warn "  PAT 는 **classic** 이어야 하고 write:packages 스코프가 필요합니다"
        warn "  --registry 의 사용자명이 로그인한 계정과 같은지도 확인하세요(소문자여야 합니다)"
    elif grep -qiE 'exec format error|no match for platform|cannot be used' "$BUILD_LOG"; then
        warn "요청한 아키텍처를 실행할 수 없습니다 — 에뮬레이션이 없습니다:"
        warn "    docker run --privileged --rm tonistiigi/binfmt --install all"
    elif grep -qiE 'pull access denied|manifest unknown|not found|failed to resolve' "$BUILD_LOG"; then
        warn "베이스 이미지를 받지 못했습니다: $JRE_IMAGE"
        warn "  레지스트리 미러를 쓰려면: JRE_IMAGE=mirror.gcr.io/library/${JRE_IMAGE#*/} $0 ...  (같은 판 · 다이제스트)"
    elif grep -qiE 'no space left on device' "$BUILD_LOG"; then
        warn "디스크가 찼습니다:  docker system prune -af  /  df -h"
    elif grep -qiE 'i/o timeout|TLS handshake|failed to dial|proxyconnect' "$BUILD_LOG"; then
        warn "네트워크 문제로 보입니다 — 프록시·DNS·아웃바운드 방화벽을 확인하세요"
    fi
}

# 이미지에 넣을 jar 를 고르고, 실행할 수 있는 jar 인지 확인한다.
#
# 예전: `ls *.jar | head -1` 을 확인 없이 넣었다 — 잘린 jar · 이름순 첫 번째의 옛 판(…-0.0.1-SNAPSHOT.jar)이
#    들어가도 빌드 · push · 레지스트리의 14개 확인이 모두 통과하고, 파드가 "Error: Invalid or corrupt jarfile
#    /app/app.jar" 로 1초 만에 죽을 때(exit 1)에야 드러난다.
#    (2026-10-18 데모의 saga-projection 이 그 증상이었지만, 그때 레지스트리 이미지의 jar 는 멀쩡했다 — 해시가
#     빌드 결과와 같았다. 깨진 것은 한 노드가 풀어 둔 사본이었다 — ops/28 show_startup_error 의 안내. 이 확인은
#     같은 증상의 빌드 쪽 원인을 push 전에 막는다.)
#    이제 가장 새 jar 를 고르고, zip 이 온전한지(CRC) · MANIFEST 에 Main-Class 가 있는지 본 뒤에만 이미지를 만든다.
# [JA] イメージに入れる jar を選び、実行できる jar かどうかを確かめる。
#      以前: `ls *.jar | head -1` を確認なしで入れていた — 切れた jar・名前順で最初の古い版(…-0.0.1-SNAPSHOT.jar)が
#      入ってもビルド・push・レジストリの 14 個の確認がすべて通り、Pod が "Error: Invalid or corrupt jarfile
#      /app/app.jar" で 1 秒で落ちたとき(exit 1)に初めて分かる。
#      (2026-10-18 デモの saga-projection がその症状だったが、そのときレジストリのイメージの jar は正常だった — ハッシュが
#      ビルド結果と同じだった。壊れていたのはあるノードが展開したコピーだった — ops/28 show_startup_error の案内。この確認は
#      同じ症状のビルド側の原因を push 前に防ぐ。)
#      今は最新の jar を選び、zip が完全か(CRC)・MANIFEST に Main-Class があるかを見てからだけイメージを作る。
pick_jar() {   # pick_jar <모듈> — jar 경로를 출력, 못 쓰면 이유를 stderr 에 쓰고 1 ／ [JA] pick_jar <モジュール> — jar のパスを出力、使えなければ理由を stderr に書いて 1
    local m="$1" jar
    jar=$(ls -t "$m"/build/libs/*.jar 2>/dev/null | grep -v plain | head -1 || true)
    if [ -z "$jar" ]; then
        echo "$m 의 jar 를 찾을 수 없습니다 (gradle 빌드가 필요합니다)" >&2
        return 1
    fi
    if command -v python3 >/dev/null 2>&1; then
        if ! python3 -I - "$jar" <<'PY'
import sys, zipfile
try:
    with zipfile.ZipFile(sys.argv[1]) as z:
        bad = z.testzip()
        if bad:
            sys.exit(f"CRC 가 맞지 않는 항목: {bad}")
        manifest = z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
except (zipfile.BadZipFile, KeyError, OSError, EOFError) as e:
    sys.exit(f"zip 으로 읽히지 않는다: {e}")
if "Main-Class:" not in manifest:
    sys.exit("MANIFEST 에 Main-Class 가 없다 — 실행 jar(bootJar)가 아니다")
PY
        then
            echo "$jar 를 실행할 수 없습니다 — 이 jar 로 만든 이미지는 'Invalid or corrupt jarfile' 로 죽습니다." >&2
            echo "  디스크(df -h)를 확인하고 지운 뒤 다시 빌드하세요:  rm -f $m/build/libs/*.jar && $0 ... --only $m" >&2
            return 1
        fi
    else
        warn "python3 가 없어 $jar 가 온전한지 확인하지 못했습니다" >&2
    fi
    printf '%s\n' "$jar"
}

# 실행하고, 실패하면 **왜** 실패했는지 보여 준 뒤 죽는다.
# [JA] 実行し、失敗したら **なぜ** 失敗したかを見せてから死ぬ。
run_build() {  # run_build <설명> <명령...> ／ [JA] run_build <説明> <コマンド...>
    local what="$1"; shift
    if "$@" >"$BUILD_LOG" 2>&1; then
        return 0
    fi
    printf '\n'
    printf '  \033[0;31m─ %s 실패 — 마지막 출력 ─────────────────────────\033[0m\n' "$what"
    tail -25 "$BUILD_LOG" | sed 's/^/      /'
    printf '\n'
    explain_failure
    printf '\n  전체 명령:\n      %s\n' "$*"
    die "$what 실패"
}

# --push 인데 로그인이 안 되어 있으면 12개를 다 빌드한 뒤 마지막에 거부당한다.
# 그 전에 말해 준다. (config.json 에 항목이 있는지만 본다 — 값은 읽지 않는다.)
# ⚠️ 같은 태그를 덮어쓰면 **노드가 새 이미지를 받지 않는다.** 매니페스트에
#    imagePullPolicy 가 없고 태그가 latest 가 아니라 기본값이 IfNotPresent 이기 때문이다.
#    실제로 이것 때문에 "이미지를 고쳐 push 했는데 왜 그대로지"를 겪었다 — kubelet 은
#    `Container image "...:1.0.0" already present on machine` 이라고만 찍는다.
# [JA] --push なのにログインしていなければ、12 個を全部ビルドした後で最後に拒否される。
#      その前に知らせる。(config.json に項目があるかだけを見る — 値は読まない。)
#      ⚠️ 同じタグを上書きすると **ノードは新しいイメージを取得しない。** マニフェストに
#         imagePullPolicy がなくタグが latest でないので、既定値が IfNotPresent だからである。
#         実際にこれのせいで「イメージを直して push したのになぜそのままか」を経験した — kubelet は
#         `Container image "...:1.0.0" already present on machine` としか出さない。
if [ "$OUTPUT" = "--push" ] && command -v docker >/dev/null 2>&1; then
    # ⚠️ ONLY 를 그대로 끼우면 여러 개일 때 "…/a b c" 라는 없는 이름이 된다. 첫 대상만 본다.
    # [JA] ⚠️ ONLY をそのまま挟むと、複数のとき "…/a b c" という存在しない名前になる。最初の対象だけを見る。
    PROBE_NAME=$(printf '%s\n' ${ONLY:-eureka-server} | head -1)
    PROBE="$REGISTRY/ecommerce-msa/$PROBE_NAME:$TAG"
    case "$PROBE" in *" "*) PROBE="" ;; esac      # --only 가 여러 개면 건너뛴다 ／ [JA] --only が複数なら飛ばす
    if [ -n "$PROBE" ] && docker manifest inspect "$PROBE" >/dev/null 2>&1; then
        warn "태그 $TAG 가 레지스트리에 **이미 있습니다** — 덮어쓰게 됩니다."
        warn "  노드에 같은 태그가 캐시돼 있으면 새 이미지를 받지 않습니다(IfNotPresent)."
        warn "  안전한 방법: --tag 를 올리고 배포 시 이미지를 바꾼다"
        warn "    $0 ... --tag <새태그>"
        warn "    kubectl -n ecommerce set image deploy/<이름> <컨테이너>=$REGISTRY/ecommerce-msa/<이름>:<새태그>"
        warn "  같은 태그를 고집한다면 노드 캐시를 지워야 합니다:"
        warn "    sudo k3s crictl rmi $PROBE"
    fi
fi

if [ "$OUTPUT" = "--push" ]; then
    REG_HOST="${REGISTRY%%/*}"
    DOCKER_CFG="${DOCKER_CONFIG:-$HOME/.docker}/config.json"
    if [ -f "$DOCKER_CFG" ] && grep -q "\"$REG_HOST\"" "$DOCKER_CFG" 2>/dev/null; then
        info "$REG_HOST 로그인 항목 확인"
    else
        warn "$REG_HOST 에 로그인한 흔적이 없습니다 — push 에서 거부될 수 있습니다"
        warn "    echo \"\$GHCR_PAT\" | docker login $REG_HOST -u <USER> --password-stdin"
        warn "  (자격증명 헬퍼를 쓰면 config.json 에 안 남을 수 있습니다 — 그 경우 이 경고는 오탐입니다)"
    fi
fi

# ---------------------------------------------------------------- buildx 빌더
# [JA] buildx ビルダー

# 기본 'docker' 드라이버는 멀티플랫폼을 못 한다. 필요할 때만 컨테이너 드라이버를 만든다.
# [JA] 既定の 'docker' ドライバーはマルチプラットフォームができない。必要なときだけコンテナドライバーを作る。
BUILDER=""
if [ "$MULTIARCH" = "1" ]; then
    if ! docker buildx inspect ecommerce-multiarch >/dev/null 2>&1; then
        log "멀티아키텍처용 buildx 빌더 생성"
        docker buildx create --name ecommerce-multiarch --driver docker-container --use >/dev/null \
            || die "buildx 빌더를 만들지 못했습니다"
    fi
    BUILDER="--builder ecommerce-multiarch"

    # 다른 아키텍처를 빌드하려면 그 아키텍처의 바이너리를 실행할 수 있어야 한다
    # (베이스 이미지의 mkdir/chown 같은 RUN). macOS 의 OrbStack/Docker Desktop 은
    # Rosetta·QEMU 를 기본 제공하지만, 리눅스 호스트는 binfmt 등록이 따로 필요하다.
    # [JA] 別のアーキテクチャをビルドするには、そのアーキテクチャのバイナリを実行できなければならない
    #      (ベースイメージの mkdir/chown のような RUN)。macOS の OrbStack/Docker Desktop は
    #      Rosetta・QEMU を標準で備えるが、Linux ホストは binfmt の登録が別途必要である。
    if ! docker buildx inspect ecommerce-multiarch 2>/dev/null | grep -q "$(echo "$PLATFORM" | cut -d, -f2)"; then
        warn "요청한 플랫폼이 빌더에 보이지 않습니다. 에뮬레이션이 없으면 실패합니다:"
        warn "  docker run --privileged --rm tonistiigi/binfmt --install all"
    fi
fi

log "설정"
info "레지스트리 : $REGISTRY"
info "태그       : $TAG"
info "플랫폼     : $PLATFORM"
info "출력       : $OUTPUT"

# 작업 트리가 더러우면 이미지의 태그(커밋 SHA)가 **거짓말이 된다.** 그 SHA 를 체크아웃해도
# 방금 만든 이미지를 재현할 수 없다. 릴리스 후보의 정의가 "리비전"인데 리비전으로 되짚을
# 수 없는 산출물이 나오는 것이므로, push 할 때는 특히 크게 경고한다.
# [JA] 作業ツリーが汚れているとイメージのタグ(コミット SHA)が **嘘になる。** その SHA をチェックアウトしても
#      今作ったイメージを再現できない。リリース候補の定義が「リビジョン」なのに、リビジョンでたどれない
#      成果物ができることになるので、push のときは特に大きく警告する。
if git rev-parse --git-dir >/dev/null 2>&1 && [ -n "$(git status --porcelain 2>/dev/null)" ]; then
    warn "작업 트리에 커밋되지 않은 변경이 있습니다 — 태그 $TAG 로 되짚어도 이 이미지를 재현할 수 없습니다"
    [ "$OUTPUT" = "--push" ] && warn "  레지스트리에 올릴 것이라면 먼저 커밋하세요. 아니면 --tag 로 임시 태그를 주세요"
fi

# ---------------------------------------------------------------- 1. jar 빌드 (한 번만)
# [JA] 1. jar のビルド(一度だけ)

# ⭐ 자바 바이트코드는 아키텍처 독립적이다. jar 는 **네이티브로 한 번만** 만들고,
#    아키텍처별로 다른 것은 JRE 베이스 이미지뿐이다. 이렇게 하면 QEMU 위에서 Gradle 을
#    돌리는 일이 아예 없다(그게 멀티아치 빌드가 느려지는 거의 유일한 원인이다).
# 자바 서비스를 하나도 만들지 않을 때(예: --only frontend)는 jar 가 필요 없다.
# 예전에는 그래도 전체 bootJar 를 돌려 몇 분을 버렸다.
# [JA] ⭐ Java のバイトコードはアーキテクチャ非依存である。jar は **ネイティブで一度だけ** 作り、
#         アーキテクチャごとに違うのは JRE のベースイメージだけだ。こうすれば QEMU の上で Gradle を
#         回すことがまったくない(それがマルチアーキビルドが遅くなるほぼ唯一の原因である)。
#      Java サービスを一つも作らないとき(例: --only frontend)は jar が要らない。
#      以前はそれでも bootJar 全体を回して数分を無駄にしていた。
BUILD_STARTED_AT=$(now)

if [ -z "${SERVICES// /}" ]; then
    warn "1/3  자바 서비스 대상이 없어 bootJar 를 건너뜁니다"
elif [ "$SKIP_GRADLE" != "1" ]; then
    # ── --only 면 **그 모듈의 bootJar 만** 만든다 ────────────────────────
    #
    # 예전에는 `--only user-service` 를 줘도 여기서 `gradlew bootJar` 를 그대로 돌려
    # **13개 모듈을 전부** 빌드했다. --only 는 2단계(이미지)만 좁혔던 것이다. 그래서
    # ops/31-deploy-one.sh("서비스 하나만 빠르게")가 전체 빌드와 거의 같은 시간을 썼고,
    # "개별 빌드를 만들었는데 왜 안 빨라지지"가 됐다.
    #
    # Gradle 은 의존 모듈(:common 등)을 알아서 함께 만든다. 그러니 여기서 필요한 것은
    # 대상 모듈의 태스크 경로뿐이다.
    # [JA] ── --only なら **そのモジュールの bootJar だけ** 作る ──
    #
    #      以前は `--only user-service` を渡してもここで `gradlew bootJar` をそのまま回し、
    #      **13 個のモジュールをすべて** ビルドしていた。--only は 2 段階目(イメージ)だけを絞っていたのだ。だから
    #      ops/31-deploy-one.sh(「サービス一つだけ素早く」)が全体ビルドとほぼ同じ時間を使い、
    #      「個別ビルドを作ったのになぜ速くならないのか」になった。
    #
    #      Gradle は依存モジュール(:common など)を自動で一緒に作る。だからここで必要なのは
    #      対象モジュールのタスクパスだけである。
    GRADLE_TASKS="bootJar"
    if [ -n "$ONLY" ] ; then
        GRADLE_TASKS=""
        for m in $SERVICES; do
            GRADLE_TASKS="$GRADLE_TASKS :$m:bootJar"
        done
    fi

    log "1/3  bootJar 빌드 (네이티브, 아키텍처 무관)${ONLY:+ —$GRADLE_TASKS}"
    t0=$(now)
    # gradlew 에 실행 권한이 없는 체크아웃이 있어서 `sh gradlew` 를 먼저 시도한다.
    # 둘 다 실패하면 두 번째 시도의 출력을 보여 준다 — 조용히 죽지 않게.
    # [JA] gradlew に実行権限がないチェックアウトがあるので、`sh gradlew` を先に試す。
    #      両方失敗したら 2 回目の試行の出力を見せる — 静かに死なないように。
    if ! sh gradlew $GRADLE_TASKS -q >/dev/null 2>&1; then
        run_build "gradle bootJar" ./gradlew $GRADLE_TASKS -q
    fi

    # ⚠️ 예전에는 여기서 무조건 `gradlew --stop` 을 했다. 그러면 **다음 빌드가 항상
    #    맨바닥에서 시작한다** — JVM 이 새로 뜨고, 설정 단계를 다시 돌고, 증분 빌드가
    #    쓰던 인메모리 상태가 전부 사라진다. 한 파일만 고쳐도 전체 빌드와 비슷한 시간이
    #    걸리는 이유가 이것이었다. 고치고 확인하기를 반복하는 개발 루프에서 가장 비싼 줄이
    #    이 한 줄이었다.
    #
    #    데몬을 남겨 두면 메모리를 몇 백 MB 물고 있다. 메모리가 빠듯한 노드(1~2GB)에서는
    #    그게 더 아플 수 있으므로 끄는 길을 남긴다 — 다만 **기본값은 남기는 쪽**이다.
    #    빌드는 하루에 여러 번 하고, 메모리가 모자라는 노드는 드물다.
    # [JA] ⚠️ 以前はここで無条件に `gradlew --stop` していた。そうすると **次のビルドが常に
    #         ゼロから始まる** — JVM が新しく起動し、設定フェーズを回し直し、増分ビルドが
    #         使っていたインメモリ状態がすべて消える。ファイル一つ直しただけでも全体ビルドと同じくらいの時間が
    #         かかる理由がこれだった。直して確認するを繰り返す開発ループで最も高くつく行が
    #         この一行だった。
    #
    #         デーモンを残しておくとメモリを数百 MB 抱えたままになる。メモリが厳しいノード(1〜2GB)では
    #         そのほうが痛いこともあるので切る道を残す — ただし **既定値は残すほう** である。
    #         ビルドは一日に何度もするし、メモリが足りないノードはまれだ。
    if [ "${GRADLE_STOP:-0}" = "1" ]; then
        sh gradlew --stop >/dev/null 2>&1 || true
        info "gradle 데몬 종료 (GRADLE_STOP=1) — 다음 빌드는 맨바닥에서 시작합니다"
    fi
    record "1/3 bootJar (gradle)" "$t0"
    info "jar 생성 완료"
else
    warn "1/3  --skip-gradle → 기존 jar 사용"
fi

# ---------------------------------------------------------------- 2. 서비스 이미지
# [JA] 2. サービスイメージ

log "2/3  서비스 이미지 빌드"
stage2=$(now)

for m in $SERVICES; do
    [ -d "$m" ] || { warn "$m 디렉터리 없음 — 건너뜀"; continue; }
    svc0=$(now)

    jar=$(pick_jar "$m") || die "$m 의 jar 를 이미지에 넣을 수 없습니다(위)"

    port=$(grep -hoP '^\s*port:\s*\K\d+' "$m"/src/main/resources/application*.yml 2>/dev/null | head -1 || echo 8080)

    # 컨텍스트를 jar 디렉터리로 좁힌다. 저장소 전체를 보내면 서비스마다 수백 MB 를 전송한다.
    # [JA] コンテキストを jar のディレクトリに絞る。リポジトリ全体を送るとサービスごとに数百 MB を転送する。
    run_build "$m 이미지 빌드" \
        docker buildx build $BUILDER \
        --platform "$PLATFORM" \
        -f ops/Dockerfile.runtime \
        --build-arg "JAR_FILE=$(basename "$jar")" \
        --build-arg "SERVICE_PORT=$port" \
        --build-arg "JRE_IMAGE=$JRE_IMAGE" \
        -t "$REGISTRY/ecommerce-msa/$m:$TAG" \
        $OUTPUT \
        "$(dirname "$jar")"

    info "$m:$TAG  (port $port)  $(( $(now) - svc0 ))s"
done
record "2/3 서비스 이미지 ($(printf '%s\n' $SERVICES | grep -c . )개)" "$stage2"

# ---------------------------------------------------------------- 3. 프론트엔드
# [JA] 3. フロントエンド

if [ "$BUILD_FRONTEND" = "1" ]; then
    log "3/3  프론트엔드 이미지 빌드"

    if [ -n "$FRONTEND_ENV_LOADED" ]; then
        info "프론트 설정 파일에서 읽음($FRONTEND_ENV):$FRONTEND_ENV_LOADED"
    fi
    # REACT_APP_* 는 번들에 박히므로 dist 를 만들기 전에 결정돼야 한다.
    # ⚠️ 여기서 잘못된 값이 통과하면 **이미지를 다시 만들어야만** 고칠 수 있다. 런타임에
    #    바꿀 방법이 없다. 그래서 비어 있는지만 보지 않고 형태까지 본다.
    # [JA] REACT_APP_* はバンドルに埋め込まれるので、dist を作る前に決まっていなければならない。
    #      ⚠️ ここで誤った値が通ると **イメージを作り直さないと** 直せない。実行時に
    #         変える方法がない。だから空かどうかだけでなく形まで見る。
    if [ -z "$REACT_APP_API_URL" ]; then
        warn "REACT_APP_API_URL 이 비어 있습니다 — 번들의 API 주소가 undefined 가 됩니다"
        warn "  $FRONTEND_ENV 에 적거나(cp ops/frontend.env.example ops/frontend.env), export REACT_APP_API_URL=… 로 넘기세요"
        warn "  (export 없이 따로 친 변수는 스크립트에 넘어가지 않습니다)"
    else
        case "$REACT_APP_API_URL" in
            https://*|http://*) ;;
            *) die "REACT_APP_API_URL 이 http:// 또는 https:// 로 시작하지 않습니다:
    $REACT_APP_API_URL" ;;
        esac
        # 마크다운 링크·따옴표·공백이 섞여 들어오는 사고가 실제로 있었다:
        #   https:[www.example.com](https://www.example.com)
        # 이대로 번들에 박히면 프론트엔드가 API 를 전혀 못 부른다.
        # [JA] Markdown のリンク・引用符・空白が混ざって入ってくる事故が実際にあった:
        #        https:[www.example.com](https://www.example.com)
        #      このままバンドルに埋め込まれると、フロントエンドは API をまったく呼べない。
        case "$REACT_APP_API_URL" in
            *'['*|*']'*|*'('*|*')'*|*' '*|*'"'*|*"'"*)
                die "REACT_APP_API_URL 에 괄호·공백·따옴표가 있습니다 — 붙여넣기 사고로 보입니다:
    $REACT_APP_API_URL

    마크다운 링크를 그대로 복사하면 이렇게 됩니다. 주소만 넣으세요:
        https://shop.example.com" ;;
        esac
        # 경로가 붙으면 프론트엔드가 `${URL}/user-service/...` 로 이어 붙여 이중 경로가 된다.
        # [JA] パスが付いているとフロントエンドが `${URL}/user-service/...` とつなげて二重パスになる。
        case "${REACT_APP_API_URL#https://}" in
            */*) warn "REACT_APP_API_URL 에 경로가 붙어 있습니다: $REACT_APP_API_URL"
                 warn "  프론트엔드가 뒤에 /user-service/... 를 붙이므로 보통 호스트까지만 넣습니다" ;;
        esac
        info "API 주소: $REACT_APP_API_URL"
    fi

    # ⚠️ PayPal 클라이언트 id 도 번들에 박힌다. 비어 있으면 SDK 프로바이더가 client-id
    #    파라미터를 아예 빼고 스크립트를 부르고, PayPal 이 400 을 돌려준 뒤 브라우저에서
    #    이렇게 터진다:
    #
    #        SDK Validation error: 'Expected client-id to be passed'
    #
    #    빌드는 성공하고 다른 화면도 멀쩡하다 — **결제 화면만** 깨지고, 그것도 사람이
    #    체크아웃까지 눌러 봐야 안다. REACT_APP_API_URL 과 같은 부류의 사고인데 여기만
    #    경고가 없었다.
    # [JA] ⚠️ PayPal のクライアント id もバンドルに埋め込まれる。空だと SDK プロバイダーが client-id
    #         パラメータをまるごと外してスクリプトを呼び、PayPal が 400 を返した後ブラウザで
    #         こう落ちる:
    #
    #             SDK Validation error: 'Expected client-id to be passed'
    #
    #         ビルドは成功し、他の画面も正常だ — **決済画面だけ** が壊れ、それも人が
    #         チェックアウトまで押してみないと分からない。REACT_APP_API_URL と同じ類の事故なのに、ここだけ
    #         警告がなかった。
    if [ -z "$REACT_APP_PAYPAL_CLIENT_ID" ]; then
        warn "REACT_APP_PAYPAL_CLIENT_ID 가 비어 있습니다 — 결제 화면이 SDK 로딩에서 깨집니다"
        warn "  $FRONTEND_ENV 에 적거나 export REACT_APP_PAYPAL_CLIENT_ID=… 로 넘기세요"
        warn "  (샌드박스 키도 됩니다. 결제를 안 쓸 계획이면 무시해도 되지만, 체크아웃은 열리지 않습니다)"
    else
        case "$REACT_APP_PAYPAL_CLIENT_ID" in
            *' '*|*'"'*|*"'"*|*'['*|*']'*)
                die "REACT_APP_PAYPAL_CLIENT_ID 에 공백·따옴표·괄호가 있습니다 — 붙여넣기 사고로 보입니다" ;;
        esac
        # 값 자체는 찍지 않는다. 길이와 앞자리만 남겨 "넘어오긴 했는지"를 확인할 수 있게 한다.
        # [JA] 値そのものは出さない。長さと先頭だけを残し、「渡ってきてはいるか」を確認できるようにする。
        info "PayPal client id: ${REACT_APP_PAYPAL_CLIENT_ID:0:6}… (${#REACT_APP_PAYPAL_CLIENT_ID}자)"
    fi

    # 포트원은 선택이다. 비어 있으면 결제수단 목록에서 포트원을 누를 때 "설정되지 않았다"
    # 안내가 뜨고 나머지는 그대로 동작한다 — 그래서 warn 이 아니라 info 다.
    # [JA] ポートワンは任意である。空なら決済手段一覧でポートワンを押したときに「設定されていない」
    #      案内が出て、残りはそのまま動く — だから warn ではなく info である。
    if [ -n "$REACT_APP_PORTONE_STORE_ID" ]; then
        case "$REACT_APP_PORTONE_STORE_ID" in
            store-*) ;;
            *) warn "REACT_APP_PORTONE_STORE_ID 가 store- 로 시작하지 않습니다 — V2 Store ID 가 맞는지 확인하세요" ;;
        esac
        [ -n "$REACT_APP_PORTONE_CHANNEL_KEY" ] \
            || warn "REACT_APP_PORTONE_STORE_ID 는 있는데 REACT_APP_PORTONE_CHANNEL_KEY 가 없습니다 — 결제창이 열리지 않습니다"
        info "포트원 V2: store=${REACT_APP_PORTONE_STORE_ID:0:12}… channel=${REACT_APP_PORTONE_CHANNEL_KEY:0:16}… store-currency=$REACT_APP_STORE_CURRENCY"
        [ -z "$REACT_APP_PORTONE_IMP_CODE" ] || info "  (V1 식별코드도 있지만 V2 가 우선합니다)"
    elif [ -z "$REACT_APP_PORTONE_IMP_CODE" ]; then
        info "REACT_APP_PORTONE_IMP_CODE / STORE_ID 없음 — 포트원 결제는 화면에서 비활성"
    else
        case "$REACT_APP_PORTONE_IMP_CODE" in
            imp*) ;;
            *) warn "REACT_APP_PORTONE_IMP_CODE 가 imp 로 시작하지 않습니다 — 가맹점 식별코드가 맞는지 확인하세요" ;;
        esac
        info "포트원 식별코드: ${REACT_APP_PORTONE_IMP_CODE:0:5}… (${#REACT_APP_PORTONE_IMP_CODE}자), PG=$REACT_APP_PORTONE_PG"
    fi

    npm0=$(now)
    (
        cd frontend
        [ -d node_modules ] || run_build "npm ci" npm ci --silent
        # env 로 감싼다 — run_build 는 명령을 받으므로 `VAR=x cmd` 접두사를 못 쓴다.
        # [JA] env で包む — run_build はコマンドを受け取るので `VAR=x cmd` の接頭辞は使えない。
        run_build "프론트엔드 번들 빌드" env \
            REACT_APP_API_URL="$REACT_APP_API_URL" \
            REACT_APP_PAYPAL_CLIENT_ID="$REACT_APP_PAYPAL_CLIENT_ID" \
            REACT_APP_PORTONE_IMP_CODE="$REACT_APP_PORTONE_IMP_CODE" \
            REACT_APP_PORTONE_PG="$REACT_APP_PORTONE_PG" \
            REACT_APP_PORTONE_STORE_ID="$REACT_APP_PORTONE_STORE_ID" \
            REACT_APP_PORTONE_CHANNEL_KEY="$REACT_APP_PORTONE_CHANNEL_KEY" \
            REACT_APP_PORTONE_STORE_EMAIL="$REACT_APP_PORTONE_STORE_EMAIL" \
            REACT_APP_STORE_CURRENCY="$REACT_APP_STORE_CURRENCY" \
            REACT_APP_PROFILE=production \
            PUBLIC_URL="" \
            NODE_ENV=production \
            npm run build --silent
    )

    # 빈 dist 를 감싸면 **200 을 주는 빈 사이트**가 나온다. 가장 알아채기 어려운 실패다.
    # [JA] 空の dist を包むと **200 を返す空のサイト** ができる。最も気づきにくい失敗である。
    [ -f frontend/dist/index.html ] || die "frontend/dist/index.html 이 없습니다 — 번들 빌드가 조용히 실패했습니다"
    record "3/3 프론트 번들 (npm)" "$npm0"
    info "dist 생성 완료 (정적 파일 — 아키텍처 무관)  $(( $(now) - npm0 ))s"

    img0=$(now)
    run_build "프론트엔드 이미지 빌드" \
        docker buildx build $BUILDER \
        --platform "$PLATFORM" \
        -f ops/Dockerfile.frontend-runtime \
        -t "$REGISTRY/ecommerce-msa/frontend:$TAG" \
        $OUTPUT \
        frontend

    record "3/3 프론트 이미지 (docker)" "$img0"
    info "frontend:$TAG"
fi

# ---------------------------------------------------------------- 마무리
# [JA] 仕上げ

log "완료"
info "이미지 접두사: $REGISTRY/ecommerce-msa/"

# 어디에 시간이 갔는지. 총합만 보면 매번 같은 자리를 잘못 짚게 된다(위 '시간 측정' 주석).
# [JA] どこに時間がかかったか。合計だけを見ると毎回同じ所を見誤る(上の「時間計測」のコメント)。
printf '\n  \033[1;36m소요 시간\033[0m\n'
printf '%s' "$TIMINGS" | while IFS='|' read -r secs label; do
    [ -n "$label" ] || continue
    printf '    %5ss  %s\n' "$secs" "$label"
done
printf '    %5ss  \033[1m합계\033[0m\n' "$(( $(now) - BUILD_STARTED_AT ))"

if [ "$OUTPUT" = "--load" ]; then
    warn "로컬 도커에만 있습니다. 클러스터에 올리려면 --push 를 쓰세요"
fi

cat <<EOF

  다음 단계 — **ops/28-deploy.sh 하나로** 배포한다(환경은 인자다):

    ./ops/28-deploy.sh prod $TAG
    ./ops/28-deploy.sh dev  $TAG      # 같은 스크립트, 같은 경로. 환경만 다르다

  28 이 하는 일: 레지스트리에 이미지가 실제로 있는지 확인 → 오버레이에 태그 주입(커밋된
  파일은 건드리지 않는다) → 롤아웃 대기 → ops/06 스모크 → 실패하면 **직전 버전으로 자동 복귀**.

  손으로 하려면(권장하지 않는다 — 배포 순서와 스모크 게이트를 직접 지켜야 한다):

    kubectl apply -k k8s-overlays/prod
    kubectl apply -f k8s/70-autoscaling.yaml

  ⚠️ k8s/ 에 kustomization.yaml 이 생겨서 \`kubectl apply -f k8s/\` 는 더 이상 쓸 수 없다.
     개별 파일 적용은 그대로 된다.
     (이 줄의 백틱은 반드시 이스케이프한다. 이 heredoc 은 \$TAG 를 치환하려고 따옴표
      없이 열었기 때문에 \`...\` 도 **명령 치환**이다 — 예전에는 이 안내문이 빌드마다
      실제로 kubectl apply -f k8s/ 를 실행해 HPA 를 되살리고 이미지를 1.0.0 으로
      되돌렸다. ConfigurationGuardrailTest B-47 이 이 실수를 막는다.)

  ⚠️ 배포 순서: user-service 를 gateway-server 보다 **먼저** 올려야 합니다.
     (gateway 가 uid 클레임 없는 토큰을 거부하므로 — docs/27 참조)
EOF
