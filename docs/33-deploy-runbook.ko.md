# 33. 배포 런북 — 맥 · 리눅스 · OCI Ampere 1대

[日本語](33-deploy-runbook.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 일부 낡음.** 프론트엔드는 CRA 가 아니라 webpack 5 입니다(`REACT_APP_*` 이름은 그대로). 평소 배포는 CI → 승인 → `ops/28` 입니다([docs/36 §6](36-deployment-pipeline.ko.md)).

docs/31(오버레이·부트스트랩·터널)과 docs/32(인프라 외부화)가 **무엇을 왜 만들었는지**를
설명한다면, 이 문서는 **그것들을 어떤 순서로 실행하는가**만 다룬다.

머신이 셋이라는 전제로 쓴다. 각 단계 앞에 어디서 실행하는지를 적었다.

---

## 0. 머신별 역할

| | 역할 | 왜 그 머신인가 |
|---|---|---|
| **맥** | 조종석 — 코드, 시크릿 보관, `kubectl`, Cloudflare 터널 생성 | 터널 자격증명이 `~/.cloudflared` 에 생긴다. 시크릿이 서버에 남지 않는 편이 낫다 |
| **리눅스** | 배포 전 검증 — `ops/13`(k3s 실클러스터), `ops/12`(전체 스택), `ops/14`(부하) | **`ops/13` 은 리눅스 전용이다** (privileged 컨테이너를 쓴다). 맥에서는 돌지 않는다 |
| **Ampere 1대** | 운영 + **arm64 이미지 빌드** | Ampere 가 arm64 라 그 위에서 빌드하면 에뮬레이션이 0이다 |

맥이 Apple Silicon 이면 arm64 이미지를 맥에서 만들 수도 있다. 그래도 인스턴스 위에서
빌드하는 편이 단순하다 — 레지스트리를 거치지 않아도 되고, 빌드 캐시가 배포 대상에 남는다.

---

## 1. 시작 전에 정할 것 — 순서 제약 둘

### ① 도메인을 먼저 정한다

프론트엔드는 Create React App 이라 `REACT_APP_*` 이 **빌드 시점에 번들에 박힌다.**
런타임 환경변수로 못 바꾼다. 도메인을 안 정한 채 이미지를 만들면 **전부 다시 빌드**해야 한다.

### ② 인스턴스는 1대로, 받을 수 있는 만큼 전부

2대로 쪼개도 같은 한도를 나눠 쓸 뿐이고, 2노드 k3s 는 etcd 쿼럼 관점에서 1노드보다 나쁘다.

**한도는 테넌시마다 다르다.** 2026-06-15 부로 Always Free Ampere 한도가 4 OCPU/24GB →
2 OCPU/12GB 로 축소됐다고 공지 없이 문서만 바뀌었지만, **실제로는 4 OCPU/24GB 가 그대로
잡히는 계정이 있다.** 문서를 믿지 말고 인스턴스에서 확인한다:

```bash
nproc && free -g
```

받은 크기에 따라 **쓰는 오버레이가 갈린다.** 이게 이 문서 전체에서 가장 중요한 분기다.

| 받은 크기 | 오버레이 | 요청 합계 | 비고 |
|---|---|---:|---|
| **4 OCPU / 24GB** | `k8s-overlays/external` | 약 **1,950m (49%)** | CPU 요청을 100m 으로 낮춘 뒤의 값. 낮추기 전에는 실측 3,980m(99%)이었다 — docs/32 §2 |
| **2 OCPU / 12GB** | `k8s-overlays/oci` | 1,000m / 3,520Mi | ES 를 빼고 요청·힙을 조인 값. 전부 1레플리카 |

⚠️ CPU 요청은 `k8s-overlays/external/patch-java-requests.yaml` 이 정한다. **`kubectl patch`
로 임시로 바꾸지 말 것** — 다음 `kubectl apply -k` 에 덮여 99% 로 되돌아가고, 그러면 파드가
`Insufficient cpu` 로 스케줄되지 못한다(실제로 겪었다). 바꿔야 하면 그 파일에서 바꾼다.

둘 다 **`k8s/70-autoscaling.yaml` 은 적용하지 않는다.** prod 의 HPA 는 min=2 라
어느 쪽에서도 CPU 를 넘긴다(5,300m). 계산은 docs/32 §2.

⚠️ 4 OCPU 를 받았더라도 `oci` 오버레이를 써도 된다 — 더 조인 값이라 그냥 여유가 남는다.
반대는 안 된다.

### 계정 준비 (맥, 브라우저)

| | 비고 |
|---|---|
| Neon (또는 Aiven) | ⚠️ **OCI 인스턴스와 같은 리전.** 지연이 30ms 가 되면 쿼리 20개짜리 요청이 600ms 가 된다 |
| MongoDB Atlas M0 | chat-service 용 |
| ~~Upstash Redis~~ | **만들지 않는다.** Redis 는 클러스터 안에 남기기로 했다 — docs/32 §6 |
| Cloudflare | 도메인 네임서버를 Cloudflare 로 이전해 둘 것 |
| GHCR | `write:packages` 권한의 PAT |

---

## 2. 【맥】 관리형 PostgreSQL 확보

```bash
brew install postgresql@16 kubectl kustomize cloudflared
```

Neon 콘솔 → **Connection Details** 에서 접속 문자열을 가져온다. 세 가지를 반드시 확인한다.

| 확인 | 이유 |
|---|---|
| Role 이 **`neondb_owner`** | 콘솔에서 만든 역할만 `neon_superuser` 멤버라 `CREATE DATABASE` 가 된다. SQL 로 만든 역할(`product`)은 평범한 역할이라 3단계에서 막힌다 |
| **Connection pooling 토글 OFF** | 풀러(`-pooler`)는 PgBouncer transaction 모드라 `CREATE DATABASE` 를 트랜잭션으로 감싼다 — 실행 불가다 |
| `channel_binding` 제거 | libpq 전용 옵션이다. 나중에 JDBC URL 에 들어가면 안 된다 |

```bash
# ⚠️ 따옴표를 **직접 타이핑**할 것. 웹에서 복사하면 ' 가 둥근따옴표(‘)로 바뀌어 값에 섞인다.
#    그러면 libpq 가 URI 가 아니라 키=값 문자열로 읽어 엉뚱한 오류를 낸다.
export ADMIN_URL="postgresql://neondb_owner@ep-xxxx.<region>.aws.neon.tech/neondb?sslmode=require"

printf '관리자 비밀번호: '; read -rs PGPASSWORD; echo; export PGPASSWORD

psql -X "$ADMIN_URL" -c 'select current_user'      # neondb_owner 여야 한다
```

```bash
ops/25-provision-external-postgres.sh --check      # ← 여기서 막히면 그 DB 로는 배포할 수 없다
ops/25-provision-external-postgres.sh              # 역할 1 + 데이터베이스 10개
```

`--check` 는 SKIP LOCKED · jsonb · IDENTITY · 선언적 파티셔닝 · 스키마 생성 · `CREATE DATABASE`
여섯 가지를 **실제로 실행해 본다**. 카탈로그를 읽어 추측하지 않는다(docs/32 §9).

### ⚠️ 비밀번호가 둘이다

| | 누구 것 | 어디에 쓰이나 |
|---|---|---|
| `PGPASSWORD` | **neondb_owner** | 프로비저닝하는 동안만. **클러스터에 넣지 않는다** |
| 스크립트가 묻는 값 | **product** | `k8s/20-secret.yaml` 의 `LOCAL_DB_PASSWORD` 가 된다 |

애플리케이션은 `product` 로만 접속한다.

---

## 2b. 【맥】 MongoDB Atlas 클러스터 만들기

**chat-service 하나만 쓴다.** 컬렉션은 `chat`(메시지)과 `auto_sequence`(채팅방 번호
시퀀스) 둘이고, **미리 만들 필요가 없다** — 첫 쓰기에서 생긴다. 인덱스도 코드에 선언된
것이 없다. 그래서 할 일은 클러스터·사용자·네트워크 셋뿐이다.

### ① 클러스터 (M0 Free)

1. <https://cloud.mongodb.com> 가입 → **Create a cluster** → **M0 Free**
2. Provider·Region: ⚠️ **OCI 인스턴스와 같은 지역**을 고른다. 도쿄면 AWS `ap-northeast-1`.
   지연이 30ms 를 넘으면 채팅 메시지 저장이 눈에 띄게 느려진다
3. 이름은 기본값(`Cluster0`)으로 둔다 — 접속 문자열에만 쓰인다

M0 의 벽: **저장 용량 512MB**, 백업 없음, 유휴 시 자동 일시정지. 개인 프로젝트에는
충분하지만 **백업이 없다**는 점은 알고 있어야 한다(`ops/10-backup-mongo.sh` 로 직접 뜬다).

### ② 데이터베이스 사용자

Database Access → **Add New Database User** → Password 인증.

| | 값 |
|---|---|
| 사용자명 | 예: `chat` |
| 권한 | **Specific Privileges** → `readWrite` on `chat` |

⚠️ "Read and write to any database" 는 고르지 않는다. 이 프로젝트가 쓰는 DB 는 `chat`
하나뿐이라 넓힐 이유가 없다.

⚠️ **비밀번호에 특수문자를 쓰면 접속 문자열에서 퍼센트 인코딩해야 한다**(`@` → `%40`,
`/` → `%2F`, `:` → `%3A`). 인코딩을 빠뜨리면 URI 파싱이 엉뚱한 곳에서 끊겨 "호스트를 못
찾는다"는 오류가 나는데, 원인이 비밀번호라는 게 메시지에 안 보인다. **영숫자만 쓰는
편이 간단하다.**

### ③ 네트워크 접근

Network Access → **Add IP Address** → **Ampere 공인 IP/32**.

⚠️ `0.0.0.0/0`(Allow Access from Anywhere)을 고르지 않는다. 그러면 비밀번호가 유일한
방어선이 된다. OCI 인스턴스의 공인 IP 는 재부팅해도 유지되므로 한 번만 넣으면 된다.

### ④ 접속 문자열

Connect → **Drivers** → Java → 문자열 복사:

```
mongodb+srv://chat:<PASSWORD>@cluster0.xxxxx.mongodb.net/?retryWrites=true&w=majority
```

이 값이 `k8s/20-secret.yaml` 의 `SPRING_DATA_MONGODB_CLIENT` 이 된다(§7).

> ⚠️ **데이터베이스 이름은 이 URI 가 정하지 않는다.** chat-service 는
> `MongoTemplate(mongoClient(), mongoProperties.getName())` 로 만들어지고, 그
> `name` 은 `spring.data.mongodb.name` 이다(값: `chat`). URI 끝에 `/chat` 을 붙여도
> **무시된다** — 붙여도 해롭진 않지만, 빠뜨렸다고 걱정할 필요도 없다.
>
> 반대로 Atlas 쪽 DB 이름을 `chat` 이 아닌 것으로 만들었다면 URI 가 아니라
> `SPRING_DATA_MONGODB_NAME` 을 바꿔야 한다. URI 만 고치면 조용히 엉뚱한 DB 를 쓴다.

> ⚠️ **인증서 경로를 채우지 않는다.** `mongodb+srv` 는 TLS 가 기본이고 Atlas 인증서는
> 공개 CA 로 발급된다. `external` 오버레이가 `SPRING_DATA_MONGODB_CACERT` ·
> `SPRING_DATA_MONGODB_KEYSTORE` 를 **빈 값으로 덮어쓰는 이유가 이것**이다 — 비어 있으면
> `MongoSslConfig` 가 JVM 기본 신뢰 저장소를 쓴다. 사설 CA 경로가 남아 있으면 그
> 트러스트스토어로 검증하다 핸드셰이크가 실패한다(docs/32 §4).

### ⑤ 붙는지 확인

Secret 에 넣기 전에 맥에서 먼저 확인한다. 여기서 막히면 클러스터에서도 막힌다:

```bash
brew install mongosh
mongosh "mongodb+srv://chat@cluster0.xxxxx.mongodb.net/" --apiVersion 1
# 비밀번호는 프롬프트로 입력된다 — 명령줄에 넣으면 셸 히스토리에 남는다
```

```javascript
use chat
db.smoke.insertOne({ ok: 1 })
db.smoke.find()
db.smoke.drop()
```

| 증상 | 원인 |
|---|---|
| `getaddrinfo ENOTFOUND` / SRV 조회 실패 | 클러스터 이름 오타이거나 DNS 가 SRV 를 못 물어본다 |
| `bad auth : authentication failed` | 사용자명·비밀번호. 특수문자를 인코딩했는지 확인 |
| 연결이 그냥 멈춘다 | **Network Access 에 IP 가 없다.** 맥에서 테스트하려면 맥 IP 도 잠깐 넣어야 한다 |
| `not authorized on chat` | 사용자 권한이 다른 DB 로 걸려 있다 |

⚠️ 맥 IP 를 넣어 테스트했다면 **끝나고 지운다.** 남겨 두면 그만큼 열려 있는 것이다.

(Upstash 는 만들지 않는다 — Redis 는 클러스터에 남긴다, docs/32 §6)

---

## 3. 【리눅스】 배포 전 검증

건너뛰면 Ampere 에서 디버깅하게 된다. 여기서 잡히는 것이 거기서는 훨씬 비싸다.

```bash
git clone <repo> && cd ecommerce-msa
./gradlew test -PwithContainers        # 268 tests — 12개 서비스가 실제 인프라로 기동까지
./ops/13-k3s-deploy.sh                 # k3s 실클러스터 + 매니페스트 전체 + 스모크
```

`ops/13` 은 클러스터 **안** 인프라로 돈다. 그리고 그게 운영 구성과도 같다 —
**Redis 는 클러스터에 남기기로 했다**(docs/32 §6). 관리형으로 옮기고 싶어지면 그때
스택을 살려 두고 cart-service 하나만 붙여 보면 된다:

```bash
KEEP=1 ./ops/13-k3s-deploy.sh
# 장바구니 담기(Redisson 락 경로)를 눌러 본다. ERR unknown command 나 pub/sub
# 타임아웃이 나오면 그 provider 는 못 쓴다.
```

---

## 4. 【OCI】 인스턴스 생성 — 여기서 가장 오래 막힌다

- Ampere A1 · **받을 수 있는 최대**(aarch64). 테넌시에 따라 4 OCPU/24GB 또는 2 OCPU/12GB — §1 ②
- 이미지: **Oracle Linux 9 (aarch64)** 권장. Ubuntu 도 그대로 된다 — 아래 참조
- 부트볼륨 **100GB 이상**. 기본 47GB 는 이미지가 쌓이면 kubelet 이 파드를 evict 하기 시작한다
- Security List 인그레스: **22번만.** 가능하면 본인 IP 로 제한

80/443 은 열지 않는다. Cloudflare Tunnel 을 쓰는 이유가 그것이다.

### 접속 경로 — 보안 리스트에 뭘 열어야 하나

**대개 아무것도 열 필요가 없다.** OCI 는 VCN 을 만들 때 기본 보안 리스트에
`0.0.0.0/0 → TCP 22` 인그레스를 넣어 준다. 마법사로 만들었다면 SSH 는 그대로 된다.

| 포트 | 여는가 | 이유 |
|---|---|---|
| 22 | **이미 열려 있다** | 기본 보안 리스트에 있다. 본인 IP 로 좁히는 것만 하면 된다 |
| 80 / 443 | **열지 않는다** | Cloudflare Tunnel 이 **밖으로** 나가서 연결을 맺는다. 인바운드가 필요 없다 |
| 6443 | **열지 않는다** | `kubectl` 은 SSH 터널로 붙인다(§8). 열면 k8s API 가 인터넷에 노출된다 |

그래서 SSH 가 안 될 때 범인은 보통 보안 리스트가 아니다. 셋 중 하나다.

| | 무엇 | 증상 |
|---|---|---|
| ① | **프라이빗 서브넷**을 골랐다 | 공인 IP 자체가 없다. VCN 마법사는 퍼블릭·프라이빗을 **둘 다** 만든다 |
| ② | 라우트 테이블에 **IGW 가 없다**(NAT 만 있다) | 공인 IP 는 붙는데 밖에서 들어올 길이 없다 → **timeout** |
| ③ | 인스턴스 **안**의 방화벽 | OCI 밖의 문제. `ops/23` 이 다룬다 |

증상으로 OCI 쪽과 호스트 쪽을 가를 수 있다. 이게 가장 빠른 판정이다:

- **timeout** → 패킷이 조용히 버려졌다. ①②·보안 리스트
- **Connection refused** → 패킷은 닿았다. ③ 이거나 `sshd` 가 안 떠 있다

①②와 보안 리스트는 **인스턴스를 만들기 전에** 확인할 수 있다:

```bash
ops/26-oci-launch-retry.sh --list-subnets      # public=true 인 것을 고른다
export OCI_SUBNET_OCID=ocid1.subnet.oc1...
ops/26-oci-launch-retry.sh --check-network
```

퍼블릭 여부 · IGW 라우트 · 22번 허용을 보고, **덤으로 22번 외에 인터넷으로 열린 규칙을
찾아 경고한다**(6443 이 열려 있으면 k8s API 노출이다). ICMP type 3 code 4 는 Path MTU
Discovery 용이라 경고하지 않는다 — 그건 있어야 하는 규칙이다.

> ⚠️ NSG 는 서브넷이 아니라 VNIC 에 붙어서 이 점검에 안 보인다. OCI 는 보안 리스트와
> NSG 를 **합집합**으로 적용하므로 보안 리스트에 22가 있으면 통과다. NSG 로만 허용해 둔
> 구성이라면 "22 없음" 경고가 오탐이다.

③ 은 인스턴스 위에서 본다 — `sudo bash ops/23-oci-ampere-bootstrap.sh --check` (§5).

### 어떤 리눅스를 고를 것인가

도쿄 리전에서 A1 이미지 목록에 Ubuntu 가 안 잡히는 경우가 있다. RHEL 계열 셋 중
**Oracle Linux 9** 를 권한다. 안에서 돌아가는 것은 셋 다 사실상 같고, **OCI 안에서만**
차이가 난다.

| | Oracle Linux 9 | Rocky Linux 9 | CentOS |
|---|---|---|---|
| OCI 이미지 | **플랫폼 이미지** — 콘솔 목록에 항상 있다 | 마켓플레이스 이미지 — OCID 를 따로 찾아 `ops/26` 에 넣어야 한다 | 플랫폼 이미지 **없음** |
| aarch64 A1 | 공식 지원 (OL7/8/9) | 되지만 OCI 가 검증하지 않는다 | — |
| 수명 | 2032, 무상 업데이트 | 2032 | **CentOS Linux 7 은 2024-06 EOL**. 남은 Stream 은 RHEL 의 *상류*라 롤링 |
| cloud-init·oci-utils | 내장 | 직접 | — |

- **CentOS 는 지금 선택지가 아니다.** CentOS Linux 는 끝났고, 남은 Stream 은 롤링이다.
- **Rocky 는 기술적으로 동등하다.** 갈리는 건 OCI 에서 플랫폼 이미지가 아니라는 점 하나뿐인데,
  지금은 인스턴스 생성 자체가 용량으로 막히는 상황이라 단계를 하나 더 만들 이유가 없다.
- ⚠️ **OL8 이 아니라 OL9.** OL8 은 cgroup v1 이 기본이라 k3s 의 리소스 제한이 헐거워진다.
- ⚠️ 이미지를 바꿔도 `Out of host capacity` 는 **해결되지 않는다.** 용량은 이미지와 무관하다.

Ubuntu 에서 넘어오면 실제로 달라지는 것은 넷이다. `ops/23` 이 전부 처리한다:

| | Ubuntu | Oracle Linux 9 |
|---|---|---|
| 기본 SSH 사용자 | `ubuntu` | **`opc`** |
| 패키지 | `apt` | `dnf` |
| 방화벽 | iptables + netfilter-persistent | **firewalld** (파드·서비스 CIDR 을 trusted 존에) |
| SELinux | 없음 | **enforcing** — `container-selinux` + `k3s-selinux` 가 필요하다 |

마지막 줄이 가장 조용히 물리는 항목이다. 정책 없이 k3s 를 올리면 **설치는 성공하는데**
컨테이너 생성이 거부되고, 로그에는 `permission denied` 만 남아 방화벽 문제처럼 보인다.
`ops/23` 은 끄는 대신 정책을 넣고, 설치 뒤 `rpm -q k3s-selinux` 로 실제로 들어갔는지 확인한다.

### ⚠️ `Out of host capacity`

도쿄·서울 같은 인기 리전에서는 콘솔로 만들려 하면 거의 항상 이걸 만난다. 설정 오류가
아니라 **그 순간 호스트에 A1 코어가 비어 있지 않다**는 뜻이다. 그래서 다음은 전부 소용없다:

| 흔한 오해 | 실제 |
|---|---|
| 이미지를 바꾼다 (Ubuntu ↔ Oracle Linux ↔ Rocky) | 용량은 **이미지와 무관**하다. 이미지 선택은 위 절의 이유로 하는 것이지 용량 때문이 아니다 |
| 다른 리전을 고른다 | ⚠️ **Always Free 는 홈 리전에서만** 된다. 홈 리전은 나중에 바꿀 수 없다 |
| 다른 AD/FD 를 고른다 | 도쿄·오사카·서울은 **AD 가 하나뿐**이다. FD 지정은 범위만 좁힌다 |
| shape 를 바꾼다 | A1 이 아니면 Always Free 가 아니다 |

통하는 것은 둘뿐이다.

**① 잡힐 때까지 재시도한다.** 용량은 수시로 잠깐씩 열린다.

```bash
ops/26-oci-launch-retry.sh --list-subnets    # 필요한 OCID 를 찾는다
ops/26-oci-launch-retry.sh --list-images
export OCI_COMPARTMENT_OCID=... OCI_SUBNET_OCID=... OCI_IMAGE_OCID=...
ops/26-oci-launch-retry.sh --check
tmux new -s oci 'ops/26-oci-launch-retry.sh'   # 며칠 두어도 된다
```

이 스크립트는 **재시도할 실패와 하지 말아야 할 실패를 가른다.** `Out of host capacity` 는
계속 두드리고, `LimitExceeded`(이미 A1 을 쓰고 있다)나 인증 오류는 즉시 멈춘다 —
영원히 안 될 요청을 며칠씩 두드리지 않기 위해서다.

> **AD 를 바꾸면 되지 않나?** 생성 **요청**에서 AD 를 고르는 것은 가능하다(이미 만든
> 인스턴스를 옮기는 것은 불가능하다 — AD 는 생성 시 확정된다). 스크립트도 AD 가 여럿이면
> 번갈아 시도한다.
>
> ⚠️ 그런데 **도쿄·오사카·서울은 AD 가 하나뿐이다.** 3개를 가진 곳은 애슈번·피닉스·
> 프랑크푸르트·런던 정도다. 단일 AD 리전에서는 이 순회가 아무 효과가 없고, 스크립트가
> 실행 시 그렇게 경고한다. 폴트 도메인(FD)도 지정하지 않는다 — 지정하면 OCI 가 찾을
> 범위만 좁아져 오히려 불리하다.

**② Pay As You Go 로 업그레이드한다.** Always Free 리소스는 그대로 $0 이고, A1 할당
성공률이 크게 올라간다는 보고가 많다. 카드가 필요하고, 프리티어 한도(**2 OCPU/12GB**,
부트볼륨 합계 200GB)를 넘기면 과금되므로 그 선은 지켜야 한다.

⚠️ 한도 축소 이후 PAYG 계정도 영향을 받는지에 대해 오라클 지원의 답변이 엇갈린다는
보고가 있다. 문서에는 "모든 테넌시"라고 적혀 있다. 카드를 등록하기 전에 청구 알림을
반드시 걸어 둘 것.

### 그래도 안 잡히면

한도가 반토막 난 지금도 2 OCPU/12GB 를 무료로 주는 곳은 사실상 OCI 뿐이다.
`k8s-overlays/oci` 기준 요구는 **약 1,000m CPU / 3.5Gi 요청**(실사용 7GB 안팎)이므로
Hetzner ARM `CAX21`(4 vCPU / 8GB, 월 €4 정도)이면 들어간다 — 무료 한도가 줄면서
유료 대안과의 격차가 오히려 좁아졌다.

---

## 5. 【Ampere】 k3s 부트스트랩

```bash
# 기본 사용자는 이미지마다 다르다: Oracle Linux 는 opc, Ubuntu 는 ubuntu
scp ops/23-oci-ampere-bootstrap.sh opc@<IP>:~/
ssh opc@<IP>
sudo bash 23-oci-ampere-bootstrap.sh --check     # 진단 먼저
sudo bash 23-oci-ampere-bootstrap.sh
```

OCI 이미지는 **인스턴스 안에 입력 차단 규칙을 미리 넣어 둔다**(Ubuntu 는 iptables,
Oracle Linux 는 firewalld). 그게 파드 네트워크(CNI)까지 막는데, 증상이 "파드는 Running
인데 통신 불가 / CoreDNS CrashLoopBackOff / `kubectl exec` 타임아웃"이라 방화벽으로
보이지 않는다. 스크립트가 규칙을 **지우지 않고** 파드·서비스 CIDR 만 허용한다.

Oracle Linux 에서는 **SELinux 도 여기서 처리한다** — enforcing 을 끄지 않고
`container-selinux`·`k3s-selinux` 정책을 넣는다(§4 참조).

Elasticsearch 가 클러스터에 남으므로 `vm.max_map_count=262144` 도 여기서 올린다.

> ⚠️ `iptables` 명령이 없는 최소 설치 이미지에서 이 검사는 예전에 **"차단 규칙 없음"으로
> 거짓 통과**했다. 지금은 미확인이라고 말한다 — 없는 것과 못 본 것은 다르다.

---

## 6. 【Ampere】 arm64 이미지 빌드

### GHCR 로그인

`GHCR_PAT` 는 **직접 발급해 넣어야 하는 값**이다. 어디서 자동으로 오지 않는다.

GitHub → Settings → Developer settings → Personal access tokens → **Tokens (classic)**
→ Generate new token (classic) → 스코프 **`write:packages`**

| | |
|---|---|
| ⚠️ Fine-grained token | **안 된다.** GHCR 를 제대로 지원하지 않아 push 에서 막힌다 |
| ⚠️ 값이 보이는 시점 | 생성 직후 **한 번뿐**이다. 놓치면 재발급 |
| `-u` 자리 | GitHub 사용자명. 이메일이 아니다. 아래 `<USER>` 와 같은 값 |

```bash
git clone <repo> && cd ecommerce-msa

# 한 줄로 export 하면 셸 히스토리에 평문으로 남는다. 에코 없이 받는다.
printf 'GHCR PAT: '; read -rs GHCR_PAT; echo; export GHCR_PAT
echo "${#GHCR_PAT}"        # 길이만 확인 — 값은 절대 찍지 않는다

echo "$GHCR_PAT" | docker login ghcr.io -u <USER> --password-stdin
```

> `password is empty` 가 나오면 원인은 둘 중 하나다: 변수가 비었거나, 파이프 왼쪽 명령이
> 실패해 stdin 이 비었거나(`echo` 오타 등). **둘 다 같은 메시지**라서 변수부터 확인해야
> 한다 — 위의 `${#GHCR_PAT}` 가 그 확인이다.

#### `Login Succeeded` 인데 push 가 거부될 때

```
denied: permission_denied: The token provided does not match expected scopes.
```

**로그인 실패가 아니다.** 빌드 로그에 `[auth] ... pull,push token for ghcr.io  DONE` 이
찍혔다면 인증은 통과한 것이고, 거부된 것은 **토큰의 스코프**다. 다시 로그인해도 안 고쳐진다.

토큰 값을 노출하지 않고 스코프만 확인한다:

```bash
curl -sI -H "Authorization: Bearer $GHCR_PAT" https://api.github.com/user | grep -i x-oauth-scopes
```

| 결과 | 뜻 | 할 일 |
|---|---|---|
| `x-oauth-scopes: ... write:packages ...` | 스코프는 맞다 | 조직 SSO 나 패키지 소유자 불일치를 본다 |
| 목록에 `write:packages` 가 없다 | classic 토큰인데 스코프 미체크 | 토큰 설정에서 체크하고 저장 |
| **헤더가 아예 안 나온다** | **fine-grained 토큰**이다 | classic 으로 새로 발급 |

토큰을 고친 뒤에는 **반드시 다시 로그인한다.** 도커가 자격증명을 캐시하므로 그냥 두면
옛 토큰을 계속 쓴다:

```bash
docker logout ghcr.io
echo "$GHCR_PAT" | docker login ghcr.io -u <USER> --password-stdin
```

### 빌드

```bash
REACT_APP_API_URL=https://shop.example.com \
REACT_APP_PAYPAL_CLIENT_ID=<paypal-client-id> \
  ./ops/19-build-images.sh --registry ghcr.io/<USER> --push
```

**권장 — 파일로 한 번만 적어 둔다.** `ops/19`(그리고 그것을 부르는 `ops/33`)는 `ops/frontend.env` 를 자동으로 읽는다
(gitignore — 환경마다 다르다). 환경 변수로 준 값이 이긴다.

```bash
cp ops/frontend.env.example ops/frontend.env   # REACT_APP_API_URL·REACT_APP_PAYPAL_CLIENT_ID·(포트원) 을 채운다
```

> ⚠️ 셸에서 `REACT_APP_API_URL=…` 을 **한 줄로 따로** 치면 그 셸의 변수일 뿐 스크립트에 넘어가지 않는다(export 가 없으면
> 자식 프로세스는 못 본다). `echo` 로는 보이니 "설정했는데 비어 있다고 한다" 가 된다. 파일이면 그런 일이 없다.
> 빌드 로그의 `✓ 프론트 설정 파일에서 읽음 … REACT_APP_API_URL REACT_APP_PAYPAL_CLIENT_ID` 로 확인한다.
> 여기에는 **공개 값만** 둔다 — PayPal Secret·Stripe·포트원 비밀은 `k8s/20-secret.yaml` 에만(B-51).
>
> 빈 값으로 frontend 를 이미 배포했다면 같은 태그로는 다시 못 올린다(노드가 캐시된 이미지를 쓴다) — 새 커밋 뒤
> `./ops/33-deploy-some.sh frontend`.

> **`REACT_APP_API_URL` 에 무엇을 넣나 — 프론트엔드와 같은 주소다.** 경로도 포트도
> 붙이지 않는다. 프론트엔드가 `${REACT_APP_API_URL}/user-service/api/...` 처럼
> **서비스 접두사를 직접 붙이고**, 터널이 그 경로를 게이트웨이로 보낸다
> (`k8s-overlays/prod/cloudflared.yaml` 의 첫 ingress 규칙).
>
> 호스트를 하나로 두는 것이 의도적이다 — 프론트엔드와 API 가 **동일 출처**라 CORS 설정이
> 필요 없고, 무료 Universal SSL 의 "서브도메인 한 단계" 제약도 안 걸린다.
>
> ⚠️ 이 값은 **번들에 박힌다.** 도메인이 정해지기 전에 빌드하면 프론트엔드를 통째로
> 다시 만들어야 한다(§1 ①).
>
> ⚠️ **사이트는 이 주소 하나로만 열려야 한다.** 터널은 호스트명을 가리지 않아 `https://<도메인>`
> (www 없음)으로 들어와도 화면이 뜨는데, 그러면 모든 API 호출이 www 로 가는 교차 출처가 되어
> 게이트웨이가 403 으로 거절한다(Firefox: `Cross-Origin Request Blocked … token/refresh`).
> 운영 번들은 그때 같은 경로로 이 주소에 다시 연다(`frontend/src/util/canonicalOrigin.js`). 더
> 깔끔한 것은 Cloudflare 에서 그리기 전에 옮기는 것이다 — 대시보드 → Rules → Redirect Rules →
> "Redirect from root to WWW" 템플릿(301, 경로·쿼리 유지).

⚠️ GHCR 경로는 **소문자만** 받는다. 사용자명에 대문자가 있으면 `--registry` 에는 소문자로 넣는다.

### ⚠️ 같은 태그를 재사용하면 노드가 새 이미지를 받지 않는다

매니페스트에 `imagePullPolicy` 가 없고 태그가 `latest` 가 아니므로 기본값은
**`IfNotPresent`** 다. 즉 **같은 태그를 덮어써 push 해도 노드는 캐시된 옛 이미지를 계속
쓴다.** kubelet 은 이렇게만 찍는다:

```
Normal  Pulled  Container image "ghcr.io/USER/ecommerce-msa/frontend:1.0.0" already present on machine
```

"고쳐서 올렸는데 왜 그대로지"가 여기서 나온다. 실제로 이 배포에서 겪었다.

**이 함정은 이제 기본값이 막는다.** `ops/19` 의 기본 태그가 **현재 커밋의 SHA 12자**라
같은 태그가 두 번 나올 수 없다(docs/36 §3.2). 손으로 `--tag` 를 주지 않는 한 안전하다.
`--tag` 로 사람이 고른 값을 쓰면 옛 함정이 그대로 돌아온다.

두 번째 배포부터는 이미지를 만든 다음 **`ops/28-deploy.sh`** 로 올린다 — 태그 주입,
배포 순서, 스모크 게이트, 실패 시 자동 복귀가 전부 그 안에 있다(docs/36 §7):

```bash
./ops/19-build-images.sh --registry ghcr.io/<USER> --push       # 태그 = 커밋 SHA
./ops/28-deploy.sh prod $(git rev-parse --short=12 HEAD)
```

`--only` 는 **따옴표로 묶어 여러 개**를 줄 수 있고 frontend 도 섞인다 —
`--only "delivery-service frontend"`. `common` 은 서비스가 아니라 라이브러리라 적지 않는다.
Gradle 이 의존 모듈을 알아서 함께 만든다.

프론트엔드만 고쳤더라도 `--only frontend` 로 그 이미지만 만들고 나머지는 **같은 커밋
SHA 로 이미 레지스트리에 있어야** 한다. 없으면 `ops/28` 이 클러스터를 건드리기 전에
"이미지 N 개를 찾지 못했습니다"로 멈춘다 — 절반만 새 버전인 상태가 생기지 않게 하려는
것이다.

같은 태그를 고집한다면 노드 캐시를 직접 지워야 한다 — 롤백이 안 되고 무엇이 도는지
불명확해지므로 권하지 않는다:

```bash
sudo k3s crictl rmi ghcr.io/<USER>/ecommerce-msa/frontend:1.0.0
kubectl -n ecommerce rollout restart deploy/frontend
```

`ops/19` 는 push 전에 그 태그가 레지스트리에 이미 있으면 경고한다.

⚠️ 처음 push 한 패키지는 **private 으로 생긴다.** 그대로 두면 k3s 가 이미지를 못 당기고
`ImagePullBackOff` 가 난다. 둘 중 하나를 고른다.

**① 패키지를 public 으로 바꾼다 (간단하다).** GitHub → 프로필 → Packages → 각 패키지 →
Package settings → Change visibility. 서비스가 12개라 12번 해야 한다.

**② private 을 유지하고 pull secret 을 넣는다.** 이 저장소의 매니페스트에는
`imagePullSecrets` 가 **없으므로**, Secret 만 만들어서는 붙지 않는다. Deployment 12개를
고치는 대신 네임스페이스의 기본 ServiceAccount 에 붙이면 한 번으로 끝난다:

```bash
kubectl -n ecommerce create secret docker-registry ghcr \
  --docker-server=ghcr.io --docker-username=<USER> --docker-password="$GHCR_PAT"

kubectl -n ecommerce patch serviceaccount default \
  -p '{"imagePullSecrets":[{"name":"ghcr"}]}'

# 이미 실패 중인 파드는 스스로 회복하지 않는다. 굴려 준다.
kubectl -n ecommerce rollout restart deploy
```

⚠️ ②를 고르면 **PAT 만료가 배포 장애가 된다.** classic PAT 에 만료를 걸어 두었다면
그날 이미지 pull 이 조용히 실패한다. 개인 프로젝트라면 ①이 낫다.

`--platform` 을 주지 않는 것이 핵심이다. 호스트가 arm64 라 그대로 네이티브다.

> ⚠️ `REACT_APP_*` 는 **공개 번들에 박힌다.** 비밀을 넣으면 안 된다. PayPal *client id* 는
> 공개 값이지만 secret 은 아니다.

---

## 7. 【Ampere】 인증서 + 시크릿

```bash
./ops/07-issue-certificates.sh                       # 클러스터 내부용 사설 CA 세트
kubectl apply -f k8s/00-namespace.yaml
kubectl apply -f certs/k8s-tls-keystore.secret.yaml  # tls-keystore Secret

cp k8s/20-secret.example.yaml k8s/20-secret.yaml     # .gitignore 대상 — 커밋되지 않는다
```

`k8s/20-secret.yaml` 에 채울 값:

| 키 | 값 |
|---|---|
| `LOCAL_DB_PASSWORD` | §2 에서 스크립트에 입력한 **product** 비밀번호. **넣기 전에 psql 로 확인한다** — 아래 |
| `SPRING_DATA_REDIS_PASSWORD` | 관리형 Redis 를 쓸 때만. 클러스터 안 Redis 면 비워 둔다(LOCAL_DB_PASSWORD 로 폴백) |
| `SPRING_DATA_MONGODB_CLIENT` | §2b 에서 복사한 Atlas 문자열. `mongodb+srv://chat:PW@cluster0.xxx.mongodb.net/?retryWrites=true&w=majority` — 비밀번호에 특수문자가 있으면 **퍼센트 인코딩**. DB 이름은 이 URI 가 아니라 `spring.data.mongodb.name` 이 정한다(§2b) |
| `LOCAL_KEY_STORE_PASSWORD` · `LOCAL_KEY_PASSWORD` · `KAFKA_SSL_*_PASSWORD` | **손으로 옮기지 말 것.** `certs/certs.env` 에서 바로 넣는다 — 아래 |
| `JWT_SECRET_KEY` · `SMS_CODE_PEPPER` | `openssl rand -hex 32` 로 **각각 따로** — 아래 참조 |
| AWS · PayPal · GitHub OAuth | **새로 발급한 것.** git 이력에 유출됐던 값이 아니라(docs/30) |

> **Cloudinary 는 필요 없다.** 프론트엔드에서 외부 스토리지로 직접 올리던 코드는 제거했다
> — 이미지는 catalog-service 가 `MultipartFile` 로 받아 **S3** 에 올린다(`ImageService` ·
> `AmazonS3Config`). 그래서 계정도, 키도, `REACT_APP_CLOUDINARY_*` 도 필요 없다.
>
> ⚠️ 단 **이력에 유출된 옛 키는 그대로 유효하다.** 코드를 지운 것과 키를 폐기한 것은
> 다르다 — 폐기는 여전히 해야 한다(docs/30).

### `LOCAL_DB_PASSWORD` 는 넣기 전에 확인한다

이 값만은 파일에서 가져올 수 없다 — `ops/25` 가 프롬프트로 받고 **어디에도 저장하지
않기 때문**이다(저장하면 그게 유출 경로가 된다). 그래서 손으로 옮겨야 하는 유일한
비밀번호이고, 틀리면 **10개 서비스가 전부** Flyway 단계에서 죽는다.

클러스터에 넣기 전에 psql 로 먼저 확인한다. 여기서 통과해야 거기서도 된다.

⚠️ **이 절은 【Ampere】인데 psql 은 §2【맥】에서 설치했다.** 인스턴스에는 없다. 둘 중 하나:

```bash
# ① Ampere 에 설치 — Ubuntu 24.04 는 postgresql-client → 16 이다
sudo apt-get update && sudo apt-get install -y postgresql-client
#   Oracle Linux 9:  sudo dnf install -y postgresql

# ② 또는 맥에서 확인한다. Neon 은 어디서든 붙으므로 결과는 같다.
#    ⚠️ Neon 에 IP 제한을 걸어 두었다면 맥 IP 를 잠깐 추가하고, 끝나면 지운다.
```

`postgresql-client-common` 만 있고 실제 클라이언트가 없으면 이렇게 나온다 — 설치가 안 된
것이지 접속이 실패한 것이 아니다:

```
Warning: No existing cluster is suitable as a default target.
Error: You must install at least one postgresql-client-<version> package
```

호스트는 **이미 적용한 ConfigMap 에서 읽는다.** `$ADMIN_URL` 은 §2【맥】의 셸 변수라
Ampere 에는 없고, 무엇보다 이쪽이 **앱이 실제로 쓰는 값**이라 확인으로도 더 정확하다:

```bash
HOST=$(kubectl -n ecommerce get cm ecommerce-config -o jsonpath='{.data.POSTGRES_HOST}')
echo "HOST=[$HOST]"          # 비어 있으면 [] 로 보인다 — 그러면 여기서 멈춘다

printf 'product 비밀번호: '; read -rs APP_DB_PASSWORD; echo

PGPASSWORD="$APP_DB_PASSWORD" psql -X \
  "postgresql://product@$HOST/userdb?sslmode=require" -c 'select current_user'
```

> ⚠️ **`$HOST` 가 비면 접속 실패가 아니라 엉뚱한 곳으로 간다.** 호스트가 없는 URI 를
> libpq 는 **로컬 유닉스 소켓**으로 해석한다:
>
> ```
> psql: error: connection to server on socket "/var/run/postgresql/.s.PGSQL.5432" failed:
>       No such file or directory
> ```
>
> Neon 이 거부한 것처럼 보이지만 Neon 까지 가지도 않았다. 변수가 비었는지부터 본다 —
> 위에서 값을 대괄호로 감싸 찍는 이유가 이것이다.

기억나지 않으면 맞춰 보지 말고 재설정한다. 이때만 **관리자 자격증명이 필요하다**(§2).
Ampere 에는 없으므로 다시 넣거나 맥에서 한다 — 따옴표는 **직접 타이핑할 것**(§2 의
둥근따옴표 함정):

```bash
export ADMIN_URL="postgresql://neondb_owner@$HOST/neondb?sslmode=require"
printf '관리자(neondb_owner) 비밀번호: '; read -rs PGPASSWORD; echo; export PGPASSWORD

psql -X "$ADMIN_URL" -c "ALTER ROLE product WITH PASSWORD '$APP_DB_PASSWORD'"
```

확인됐으면 Secret 에 넣는다:

```bash
kubectl -n ecommerce patch secret ecommerce-secrets --type merge \
  -p "{\"stringData\":{\"LOCAL_DB_PASSWORD\":\"$APP_DB_PASSWORD\"}}"
unset APP_DB_PASSWORD PGPASSWORD
```

> ⚠️ **콘솔 URI 의 비밀번호는 퍼센트 인코딩되어 있다.** `%40` 은 `@` 다. 그 값을 그대로
> 넣으면 인증에 실패한다. **평문**이 필요하다 — Neon 은 Roles → Reset password 로 평문을
> 한 번 보여 준다.
>
> 실제로 이 배포에서 URI 가 `product:%E2%80%98…%E2%80%99@` 로 나왔다. 디코딩하면
> **`‘…’`** — 복사할 때 편집기가 넣은 **둥근따옴표가 값의 일부**가 되어 있었다.
> `read -rs` 는 화면에 아무것도 보여 주지 않으므로 눈으로 잡을 수 없고 길이 검사도
> 통과한다. 지금은 `ops/25` 가 이 문자들을 거부한다.
>
> **틀렸을 때의 증상:** `Unable to obtain connection from database:` +
> `password authentication failed for user 'product'` (SQL State **28P01**).
>
> ⚠️ 이 오류가 났다면 **호스트·TLS·역할은 전부 맞은 것이다.** 거기까지 도달해야 인증을
> 시도한다. 엉뚱하게 `POSTGRES_HOST` 나 `sslmode` 를 다시 뒤지지 말 것.

### 스토어 비밀번호 넷은 `certs.env` 에서 넣는다

`ops/07` 은 **하나의 `STORE_PASSWORD` 로 다섯 개 스토어를 전부** 만든다. 그래서 아래 네
키는 값이 같아야 하고, 하나라도 어긋나면 그 스토어를 여는 서비스가 기동에서 죽는다.
손으로 옮기면 반드시 언젠가 틀리므로 파일에서 바로 넣는다:

```bash
set -a; . certs/certs.env; set +a
echo "길이: ${#LOCAL_KEY_STORE_PASSWORD}"     # 24 여야 한다 (값은 찍지 않는다)

kubectl -n ecommerce patch secret ecommerce-secrets --type merge -p "{\"stringData\":{
  \"LOCAL_KEY_STORE_PASSWORD\":\"$LOCAL_KEY_STORE_PASSWORD\",
  \"LOCAL_KEY_PASSWORD\":\"$LOCAL_KEY_PASSWORD\",
  \"KAFKA_SSL_KEY_STORE_PASSWORD\":\"$KAFKA_SSL_KEY_STORE_PASSWORD\",
  \"KAFKA_SSL_TRUST_STORE_PASSWORD\":\"$KAFKA_SSL_TRUST_STORE_PASSWORD\"}}"

kubectl -n ecommerce rollout restart deploy
```

> **틀렸을 때의 증상.** 파드가 `CrashLoopBackOff` 로 돌고 로그 맨 아래가 이렇다:
>
> ```
> Caused by: java.io.IOException: keystore password was incorrect
> Caused by: java.security.UnrecoverableKeyException: failed to decrypt safe contents entry:
>            javax.crypto.BadPaddingException: Given final block not properly padded
> ```
>
> **파일은 제대로 마운트된 것이다** — 못 찾았다면 `KeyStore not exists` 가 났을 것이다.
> 순수하게 비밀번호 불일치다.
>
> ⚠️ `ops/07` 을 **다시 돌렸다면** `certs/k8s-tls-keystore.secret.yaml` 도 함께 다시
> 적용해야 한다. 스토어 파일과 비밀번호는 한 세트라, 한쪽만 갱신하면 같은 오류가 난다:
>
> ```bash
> kubectl apply -f certs/k8s-tls-keystore.secret.yaml
> ```

### `JWT_SECRET_KEY` · `SMS_CODE_PEPPER` 만들기

```bash
openssl rand -hex 32     # JWT_SECRET_KEY
openssl rand -hex 32     # SMS_CODE_PEPPER  ← 다시 돌린다. 같은 값을 쓰지 않는다
```

**왜 hex 이고 왜 32인가.**

코드는 이 문자열을 **디코딩하지 않고 UTF-8 바이트 그대로** 키로 쓴다:

```java
new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), Jwts.SIG.HS256.key()...)
```

HS256 은 **256비트(32바이트) 이상**을 요구한다. `openssl rand -hex 32` 는 64자를 내놓고
전부 ASCII 라 정확히 64바이트다 — 넉넉하다.

| | |
|---|---|
| ⚠️ **Base64 로 만들지 말 것** | 코드가 Base64 디코딩을 하지 않는다. `openssl rand -base64 32` 는 44자(44바이트)라 우연히 통과하지만, `+` `/` `=` 가 YAML·셸·환경변수를 거치며 사고를 낸다. hex 는 그런 문자가 없다 |
| ⚠️ **32바이트 미만이면 기동 실패** | jjwt 가 `WeakKeyException` 을 던진다. 조용히 약한 키로 도는 것보다 낫다 |
| ⚠️ **둘은 다른 값** | 서명 키와 해시 pepper 는 용도가 다르다. 하나가 새면 둘 다 새는 상황을 만들지 않는다 |
| ⚠️ **gateway-server 와 user-service 가 같은 `JWT_SECRET_KEY`** | 한쪽만 바꾸면 발급한 토큰을 다른 쪽이 거부한다. Secret 하나를 공유하므로 자동으로 맞는다 |
| ⚠️ **바꾸면 기존 토큰이 전부 무효** | 로그인한 사용자가 전원 재로그인해야 한다(docs/30) |
| pepper 를 바꾸면 | 진행 중인 인증번호가 무효가 된다. 코드는 5분짜리라 실질적으로 무해하다 |

> **`k8s/20-secret.example.yaml` 의 플레이스홀더는 일부러 짧다.** 예전 값
> (`REPLACE_ME_with_a_long_random_string`, 36자)은 32자를 넘어서 **채우지 않아도 그냥
> 동작했다** — 저장소에 공개된 키로 토큰을 서명하는 상태로 뜬다. 지금은 채우지 않으면
> 기동 자체가 실패한다.

```bash
kubectl apply -f k8s/20-secret.yaml
```

### 메일 발송과 인증 게이트 — 순서가 중요하다

기본 상태에서 메일은 **로그에만 찍히고 나가지 않는다**(`MAIL_PROVIDER=logging`).
Secret 에 `MAIL_*` 네 값을 채우는 것만으로는 아무 일도 일어나지 않는다 — local
오버레이의 `patch-mail.yaml` 이 스위치다(Gmail 앱 비밀번호 등 주의사항은 그 파일 주석).

그리고 **가입 계정은 기본으로 즉시 활성화된다.** 메일 인증이 게이트가 되려면
`APP_REQUIRE_EMAIL_VERIFICATION=true` 가 필요한데, ⚠️ **반드시 이 순서로**:

1. `patch-mail.yaml` 로 SMTP 를 켠다 (`MAIL_FROM` = SMTP 계정 주소)
2. 가입 한 번으로 **메일이 실제로 오는지** 확인한다
3. 그 다음에야 user-service 에 `APP_REQUIRE_EMAIL_VERIFICATION=true` 를 준다

순서를 바꾸면 — 메일이 안 나가는 상태에서 게이트만 켜면 — **가입은 되는데 아무도
로그인할 수 없다.** 잠긴 계정의 비상 탈출구:
`UPDATE users.users SET enabled = true WHERE email = '...';`

### 소셜 로그인 (선택) — Google · GitHub · Facebook · 카카오 · LINE

자격증명을 넣은 프로바이더만 켜진다. 하나도 안 넣으면 로그인 화면에 버튼 자체가
없다(서버의 `/api/auth/public/oauth2/providers` 가 빈 목록을 준다).

**프로바이더 콘솔에서 앱을 만들 때, redirect URI 는 정확히 이 형태다:**

```
https://<도메인>/user-service/login/oauth2/code/google      ← google | github | facebook | kakao | line
```

⚠️ 한 글자라도 다르면 `redirect_uri_mismatch` 로 동의 화면에서 튕긴다. 흔한 실수:
`www.` 유무 불일치, 마지막 슬래시, http/https.

| 프로바이더 | 발급처 | 주의 |
|---|---|---|
| Google | Cloud Console → API 및 서비스 → 사용자 인증 정보 → OAuth 클라이언트 ID(웹) | 동의 화면 구성 필요. 테스트 모드면 테스트 사용자만 로그인된다 |
| GitHub | Settings → Developer settings → OAuth Apps | callback URL 은 하나만 등록된다 |
| Facebook | Meta for Developers → 앱 → Facebook 로그인 | 개발 모드면 앱 역할이 있는 계정만 로그인된다 |
| 카카오 | Kakao Developers → 내 애플리케이션 → 앱 만들기. **client-id = REST API 키**, 카카오 로그인 활성화 + Redirect URI 등록, [보안] Client Secret 발급 후 **"사용함"** | 동의항목에서 **카카오계정(이메일)** 을 켜야 한다 — 이메일 동의항목은 비즈 앱(개인 개발자도 전환 가능)이어야 쓸 수 있다. 이메일이 없으면 로그인이 `email_required` 로 거절된다 |
| LINE | LINE Developers → Provider → **LINE Login 채널**. client-id = 채널 ID, client-secret = 채널 시크릿. Callback URL 등록 | **OpenID Connect → 이메일 주소 권한(Email address permission)을 신청해 승인**받아야 한다 — 이메일은 ID 토큰에만 실린다. 채널이 Developing 상태면 채널 관리자·테스터만 로그인된다(Published 로 전환) |

받은 값을 Secret 에 넣고(`k8s/20-secret.yaml`, 키 이름은 `20-secret.example.yaml` 참조)
user-service 를 재시작한다:

```bash
kubectl apply -f k8s/20-secret.yaml
kubectl -n ecommerce rollout restart deploy/user-service
```

동작 방식(코드를 고칠 때 알아야 할 것):

- 버튼 → `/user-service/oauth2/authorization/{provider}` **전체 페이지 이동** →
  동의 화면 → 콜백에서 **정상 로그인과 같은 REFRESH 쿠키**를 심고 홈으로 리다이렉트 →
  SPA 가 새로 뜨며 `/api/token/refresh` 로 세션을 복구한다. 액세스 토큰을 URL 로
  넘기지 않는다
- 계정 연결은 **검증된 이메일** 기준이다. 같은 이메일의 기존 계정이 있으면 그 계정으로
  로그인된다(역할 포함 — 관리자가 소셜로 들어와도 관리자다). 미검증 이메일은 거절한다 —
  믿으면 남의 이메일로 소셜 계정을 만들어 로컬 계정을 탈취할 수 있다
- **2FA 를 켠 계정은 소셜 로그인 뒤에도 2FA 를 거친다** — 성공 핸들러가 세션 대신 5분짜리 보류 토큰(httpOnly
  `PENDING_2FA`, 별도 키 서명)을 심고 `/signin?oauth2fa=1` 로 보낸다. 코드가 맞아야 REFRESH 가 나온다(소셜 버튼이
  2FA 우회로가 되지 않게)
- GitHub 은 이메일 비공개 계정이 많아 `/user/emails` 폴백이 있다(`user:email` scope)
- **구글·LINE 은 OIDC 경로**(`openid` scope)로 처리된다 — 계정 연결은 `CustomOidcUserService` 가 OAuth2 경로와 같은
  `CustomOAuth2UserService.resolve` 로 한다. LINE 의 ID 토큰은 **ES256** 이라 알고리즘을 등록마다 고른다
  (`SecurityConfig#idTokenAlgorithm`). 🐛 이 연결이 없던 때는 구글 로그인이 켜는 순간부터 실패했다(ID 토큰 검증
  라이브러리 `spring-security-oauth2-jose` 도 빠져 있었다)
- 실패는 이유 코드를 들고 로그인 화면으로 돌아온다(`?error=email_required|unverified_email|access_denied`,
  `OAuth2FailureHandler`). 그 밖의 실패는 `oauth_failed` 하나 — 프로바이더의 오류 설명은 URL 에 싣지 않는다
- 카카오는 `is_email_valid`·`is_email_verified` 가 둘 다 true 여야 기존 계정에 연결한다(재사용·미인증 주소로
  남의 계정을 가져가지 못하게)

---

## 8. 【Ampere】 엔드포인트 + 배포

### 남는 인프라만 띄운다

Kafka 와 Elasticsearch 는 클러스터에 남는다(docs/32 §3). 나머지 셋은 지운다.

⚠️ **`90-dev-infra.yaml` 은 Secret 을 참조만 하고 만들지 않는다.** 파일 헤더에 전제로
적혀 있지만(`redis/redis-auth` 등) 그것을 만드는 것은 `ops/13`(개발용 스크립트)뿐이라,
이 런북대로만 따라오면 **redis 파드가 `CreateContainerConfigError` 에서 영영 멈춘다.**

여기서 남기는 셋 중 Secret 이 필요한 것은 **redis 하나**다(Kafka·ES 는 없어도 뜬다).
비밀번호는 앱이 보내는 값과 같아야 한다 — `spring.data.redis.password` 가
`${LOCAL_DB_PASSWORD}` 이므로 **DB 비밀번호와 동일**하다:

```bash
PW=$(kubectl -n ecommerce get secret ecommerce-secrets \
       -o jsonpath='{.data.LOCAL_DB_PASSWORD}' | base64 -d)
echo "길이=${#PW}"                        # 0 이면 §7 을 먼저 끝낸다

kubectl -n redis create secret generic redis-auth --from-literal=password="$PW" \
  --dry-run=client -o yaml | kubectl apply -f -
unset PW
```

```bash
kubectl apply -f k8s/90-dev-infra.yaml     # 낱개 파일이라 오버레이가 참조하지 못한다
kubectl -n postgres delete deploy postgres
kubectl -n mongodb  delete deploy mongodb
# ⚠️ redis 는 지우지 않는다 — 클러스터에 남긴다(docs/32 §6)

# 실제로 떴는지 확인한다. Service 만 있고 파드가 없으면 앱이 Connection refused 로 죽는다
kubectl -n redis rollout status deploy/redis --timeout=120s
kubectl -n redis get endpoints redis       # <none> 이면 아직이다

# Elasticsearch(200m/768Mi)는 받은 크기에 따라 갈린다
kubectl -n elasticsearch delete deploy elasticsearch   # ← 2 OCPU 라면 지운다
```

**4 OCPU 라면 Elasticsearch 를 남겨도 된다.** 한국어 형태소 검색(nori)을 실제로 확인하고
싶다면 남기는 편이 낫다 — `SEARCH_ENGINE=postgres` 기본값으로는 그 경로를 밟지 못한다.
2 OCPU 라면 지운다(`oci` 오버레이가 `SEARCH_ENGINE: postgres` 를 못박는 이유다).

### 값 채우기

| 파일 | 바꿀 것 |
|---|---|
| `k8s-overlays/external/patch-external-endpoints.yaml` | `POSTGRES_HOST` → Neon 호스트명. **호스트명만** — 아래 참조 |
| `k8s-overlays/prod/kustomization.yaml` | `REPLACE_ME_OWNER` → GHCR 사용자명 |
| `k8s/10-configmap.yaml` | `APP_PUBLIC_URL` → 실제 도메인 (회원가입 확인 메일 링크에 쓰인다) |
| `k8s/60-ingress.yaml` | `host: shop.example.com` → 실제 도메인 |

> ⚠️ **위 네 파일은 전부 git 이 추적한다.** 그런데 채우는 값(도메인·Neon 호스트·GHCR
> 사용자명)은 커밋하지 않는 편이 낫다 — 비밀은 아니지만 공격 표면을 넓힌다. 즉 **추적되는
> 파일에 커밋하면 안 되는 값을 넣은 상태**로 운영하게 된다. 두 가지가 따라온다:
>
> - `git pull` 이 충돌하거나 로컬 수정을 덮을 수 있다 → **`git pull --autostash`** 로 받는다
> - `git add -A` 하면 **그대로 커밋된다** → 인스턴스에서는 커밋하지 않는 것이 가장 안전하다.
>   해야 한다면 `git add <파일>` 로 개별 지정하고 `git diff --cached` 를 먼저 본다
>
> `git update-index --skip-worktree <파일>` 로 아예 감출 수도 있지만, 그러면 **상류에서
> 그 파일이 바뀔 때 pull 이 실패한다**(메시지가 원인을 잘 알려 주지 않는다). 이 저장소는
> 아직 그 파일들을 고치는 중이므로 지금은 `--autostash` 쪽을 권한다.

#### `POSTGRES_HOST` 는 어떻게 확인하나

**§2 에서 쓴 `ADMIN_URL` 에 이미 들어 있다.** 콘솔에서 눈으로 옮겨 적지 말고 뽑아낸다:

```bash
echo "$ADMIN_URL" | sed -E 's|.*@([^/:?]+).*|\1|'
# → ep-<이름>-<ID>.<리전>.aws.neon.tech   ← 실제 값이 나온다. 이 예시를 복사하지 말 것
```

(셸을 새로 열었다면 Neon 콘솔 → 프로젝트 → **Connection Details** 에서 접속 문자열을
다시 가져와 같은 방법으로 뽑는다.)

⚠️ **호스트명만** 넣는다. 다음은 전부 들어가면 안 된다:

| 넣지 말 것 | 이유 |
|---|---|
| `postgresql://` | 베이스가 `jdbc:postgresql://` 를 이미 붙인다 |
| `사용자:비밀번호@` | 자격증명은 Secret 으로 간다. ConfigMap 은 커밋된다 |
| `:5432` | `POSTGRES_PORT` 가 따로 있다 |
| `/neondb` | 각 서비스가 자기 DB 이름을 붙인다(`userdb`·`productdb` …) |
| `?sslmode=require` | 베이스 URL 에 이미 있다 |
| `&channel_binding=require` | **libpq 전용 옵션이다.** JDBC 는 모르는 파라미터로 보고 실패한다 |

⚠️ **`-pooler` 가 붙은 주소를 쓰지 않는다.** PgBouncer transaction 모드가 서버 사이드
프리페어드 스테이트먼트와 충돌해 `prepared statement "S_1" already exists` 가 산발적으로
난다(docs/32 §9, 미해결로 남긴 항목). 확인:

```bash
case "$(echo "$ADMIN_URL" | sed -E 's|.*@([^/:?]+).*|\1|')" in
  *-pooler.*) echo "⚠️ 풀러 주소다 — 직결 주소를 쓸 것" ;;
  *) echo "직결 주소 ✓" ;;
esac
```

> **MongoDB 는 여기서 채울 것이 없다.** Atlas 접속은 전적으로 Secret 의
> `SPRING_DATA_MONGODB_CLIENT`(§7) 하나로 결정된다. 예전에는 이 파일에
> `MONGODB_HOST: REPLACE_ME.mongodb.net` 이 있었지만 **그 키를 읽는 곳이 없어** 제거했다.

**채운 값이 실재하는지 확인한다 — 단, DNS 로는 안 된다.**

⚠️ **`getent hosts`·`dig` 는 Neon 에서 아무것도 검증하지 못한다.** 와일드카드 DNS 라
`*.ap-southeast-1.aws.neon.tech` 아래 **아무 이름이나 해석된다**(실측: `ep-obviously-
not-real-zzz999.…` 도 해석됨). 실제 라우팅은 TLS **SNI** 로 하므로, 엔드포인트가
존재하는지는 **연결해 봐야** 알 수 있다.

```bash
H=$(grep -oP '^\s*POSTGRES_HOST:\s*"\K[^"]+' k8s-overlays/external/patch-external-endpoints.yaml)
echo "HOST=[$H]"
printf 'product 비밀번호: '; read -rs PW; echo

PGPASSWORD="$PW" psql -X "postgresql://product@$H/userdb?sslmode=require" -c 'select 1'
unset PW
```

| 결과 | 뜻 |
|---|---|
| `1` 이 나온다 | ✅ 호스트·비밀번호·DB 전부 맞다 |
| 프록시가 연결을 거부/종료한다 | **엔드포인트가 없다** — 오타이거나 자리표시자다 |
| `password authentication failed` (28P01) | 엔드포인트는 맞다. 비밀번호만 틀리다 |
| `database "userdb" does not exist` | 엔드포인트는 살아 있는데 프로비저닝되지 않았다 |

세 오류가 **서로 다른 층**을 가리킨다는 점이 중요하다. 28P01 이 나왔다면 호스트를 다시
뒤질 이유가 없다.

> ⚠️ **문서의 예시 호스트를 그대로 복사하지 말 것.** 실제로 그런 사고가 났다 — 예시가
> 진짜처럼 생겨서(`ep-<단어>-<단어>-<hex>.<리전>.aws.neon.tech`) 눈에 띄지 않았다.
> 그래서 예시를 `ep-<이름>-<ID>.<리전>` 꼴로 바꿨다. 진짜 값은 **반드시 콘솔이나
> `ADMIN_URL` 에서** 가져온다.
>
> ⚠️ `-pooler` 를 지울 때 **점까지 같이 지우지 않도록** 주의한다.
> `ep-xxx-pooler.c-3.…` → `ep-xxx.c-3.…` 이다. `ep-xxxc3.…` 이 되면 DNS 가 실패한다.

**채웠는지 apply 전에 확인한다.** 안 채우고 apply 하면 파드가 `InvalidImageName` 으로
멈추는데, 그 메시지는 **어느 값이 비었는지 알려 주지 않는다**:

```bash
kustomize build k8s-overlays/external | grep -n REPLACE_ME
```

아무것도 안 나오면 통과다. `REPLACE_ME_TUNNEL_ID` 하나만 남는 것은 정상이다 —
그건 `ops/24` 가 클러스터 안에서 치환한다(§9).

> **왜 `InvalidImageName` 인가.** `REPLACE_ME_OWNER` 에는 **대문자가 있다.** 컨테이너
> 이미지 경로는 소문자만 허용하므로 kubelet 이 이름을 파싱조차 못 한다. "이미지를 못
> 찾았다"(`ImagePullBackOff`)와는 다른 상태다 — 레지스트리에 가 보지도 않았다는 뜻이고,
> 그래서 로그인이나 패키지 공개 여부를 아무리 확인해도 원인이 안 나온다.
>
> ```bash
> sed -i 's/REPLACE_ME_OWNER/<GHCR 사용자명 소문자>/g' k8s-overlays/prod/kustomization.yaml
> ```

### 백업 CronJob — external 에서는 절반만 쓴다

`k8s/85-backup-cronjobs.yaml` 은 오버레이에 없고 따로 적용하는 파일이다. **external
구성에서는 둘의 처지가 정반대**라 그냥 적용하면 안 된다:

| CronJob | external 에서 | 이유 |
|---|---|---|
| `postgres-backup` | **쓰지 않는다** | `pg_basebackup` 은 replication 프로토콜로 붙는데 Neon 은 그 접속을 열어 주지 않는다. 백업·PITR 은 provider 책임이 된다(docs/32 §5) |
| `restore-drill` | **쓰지 않는다** | 위가 남긴 백업을 되돌려 보는 훈련이다. 복원할 것이 없다 |
| `mongo-backup` | **필요하다** | **Atlas M0 는 백업이 없다**(§2b). 여기서 안 뜨면 백업이 아예 없는 상태다 |

`postgres-backup` 과 `restore-drill` 은 매니페스트에 **`suspend: true` 로 들어 있다** —
external 에서는 그대로 두면 된다. 클러스터 안 PostgreSQL 을 쓴다면 `ACTION=check
./ops/08-backup-postgres.sh` 를 통과시킨 뒤 직접 켠다.

`mongo-backup` 만 자격증명을 넣어 살린다:

⚠️ **`read` 는 반드시 따로 한 줄로 실행한다.** 여러 줄을 한꺼번에 붙여넣으면 셸이
**다음 줄을 값으로 읽어** 가고, 화면에 안 보이므로 눈치챌 수 없다. Secret 은 `created` 로
멀쩡히 만들어지고 백업만 조용히 실패한다 — 실배포에서 겪었다.

```bash
read -rs MONGO_URI          # ← 이 줄만 실행하고 엔터, 프롬프트에 URI 입력
```

값이 들어간 뒤 나머지를 실행한다:

```bash
kubectl -n ecommerce create secret generic backup-credentials \
  --from-literal=MONGO_URI="$MONGO_URI" \
  --dry-run=client -o yaml | kubectl apply -f -
unset MONGO_URI

# 값을 보지 않고 확인 — 스킴과 길이만 본다
kubectl -n ecommerce get secret backup-credentials \
  -o jsonpath='{.data.MONGO_URI}' | base64 -d | cut -c1-14; echo

# 스케줄을 기다리지 말고 지금 한 번 돌려 본다 — "떠 있다"와 "덤프가 나온다"는 다르다
kubectl -n ecommerce create job --from=cronjob/mongo-backup mongo-backup-verify
kubectl -n ecommerce logs job/mongo-backup-verify --follow
```

⚠️ **`backup-credentials` 는 매니페스트가 만들지 않는다.** 없으면 파드가 컨테이너를
만들기 전에 `CreateContainerConfigError` 로 멈추고 **로그가 없다** — `redis-auth` 와
똑같은 함정이다. 실배포에서 두 잡이 31시간 동안 그 상태로 방치됐다.

이미 실패한 잡이 남아 있으면 지운다:

```bash
kubectl -n ecommerce delete job -l job-name --field-selector status.successful=0 2>/dev/null
kubectl -n ecommerce get jobs
```

### 여러분의 값은 `local` 오버레이에 둔다 — 추적 파일에 적지 않는다

여기까지의 오버레이(`prod` → `external`)는 누구나 그대로 쓴다. 마지막 한 겹에만
**이 환경에만 있는 값**이 들어간다: GHCR 소유자, 이미지 태그, Neon 호스트, 공개 도메인.

⚠️ 그 값들을 `prod/kustomization.yaml`·`patch-external-endpoints.yaml`·`10-configmap.yaml`
같은 **추적 대상 파일에 직접 적지 말 것.** 실배포에서 이렇게 됐다:

- `git pull` 이 매번 충돌한다 — 값을 채운 줄과 저장소가 고친 줄이 같은 줄이다
- 충돌 마커가 남으면 kustomize 가 파일 전체를 거부한다
  (`trouble configuring builtin PatchTransformer`, `ScannerError`)
- 되돌리면 실제 호스트가 사라져 `28P01`·`InvalidImageName` 으로 죽는다
- 무엇보다 **호스트명과 레지스트리 소유자가 커밋될 뻔했다**

```bash
cp -r k8s-overlays/local.example k8s-overlays/local     # local/ 은 gitignore 된다
$EDITOR k8s-overlays/local/kustomization.yaml           # REPLACE_ME_OWNER, 태그
$EDITOR k8s-overlays/local/patch-local-endpoints.yaml   # POSTGRES_HOST, APP_PUBLIC_URL

# ⚠️ 적용 전 확인. REPLACE_ME_TUNNEL_ID **한 줄만** 남아야 한다 —
#    그건 ops/24 가 클러스터에서 치환하므로 여기서 채우지 않는다
kubectl kustomize k8s-overlays/local | grep REPLACE_ME

kubectl apply -k k8s-overlays/local
```

2 OCPU 인스턴스라면 `local/kustomization.yaml` 의 `resources` 를 `../oci` 로 바꾼다.

비밀은 여기 두지 않는다 — 비밀번호·접속 문자열은 `k8s/20-secret.yaml`(역시 gitignore)이
담당한다. 이 오버레이에 들어가는 것은 **비밀이 아닌 식별자**뿐이다.

### 배포 — 받은 크기에 따라 오버레이가 갈린다

```bash
nproc && free -g          # 먼저 확인한다. §1 ②

# 4 OCPU / 24GB
kubectl apply -k k8s-overlays/external

# 2 OCPU / 12GB
kubectl apply -k k8s-overlays/oci
```

`oci` 는 `external` 을 상속해 리소스만 더 조인 것이므로, 4 OCPU 에서 `oci` 를 써도 된다
(여유가 남을 뿐이다). 반대는 안 된다.

> **최초 배포에서만 이렇게 한다.** 두 번째부터는 `kubectl apply -k` 를 직접 치지 않고
> **`./ops/28-deploy.sh <오버레이> <버전>`** 을 쓴다. 같은 apply 를 하되 앞뒤로
> ① 레지스트리에 그 버전이 실제로 있는지 확인, ② user-service→gateway 순서 준수,
> ③ 스모크 게이트, ④ 실패 시 직전 버전으로 자동 복귀가 붙는다(docs/36).
> 위 명령을 손으로 치면 그 넷을 사람이 기억해야 한다.
>
> 🔴 그리고 다섯 번째가 있다. **오버레이의 `images:` 에는 손으로 적어 둔 `newTag` 가
> 박혀 있다.** 배포가 끝난 뒤 그 파일을 그대로 apply 하면 전 서비스가 그 태그로
> **되돌아간다** — 실패도 롤백도 아니고, 적힌 대로 적용된 것이다. 실제로 그렇게 몇 주
> 전 프론트엔드가 돌아왔고, 원인을 찾는 데 오래 걸렸다(증상이 "배포 문제"로 보인다).
> ops/28 은 배포할 버전으로 태그를 덮어쓰므로 이 사고가 구조적으로 없다.
>
> **설정만 바꿀 때**(ConfigMap·Secret·Ingress)도 같다. 이미지는 그대로 두고 설정만
> 반영하는 전용 경로가 있다:
>
> ```bash
> ./ops/28-deploy.sh local --config-only                          # 적용만
> ./ops/28-deploy.sh local --config-only --restart delivery-service   # 값을 읽히려면 재시작
> ```
>
> ConfigMap 은 해시 접미사가 없어 바뀌어도 이미 떠 있는 파드가 읽지 않는다. `envFrom` 은
> **기동 시점**에 한 번 읽는다 — 그래서 `--restart` 가 필요하다.

### 파드가 이미지를 못 받아올 때

`ImagePullBackOff` 는 **이름은 맞는데 못 받아왔다**는 뜻이다(이름이 틀린 경우는 위의
`InvalidImageName`). 원인이 둘이라 **실제 메시지를 봐야 한다** — 파드 상태만으로는 구분되지 않는다:

```bash
kubectl -n ecommerce get events --sort-by=.lastTimestamp | grep -i "failed to pull" | tail -3
```

전형적인 메시지는 이것이다:

```
failed to fetch anonymous token: unexpected status from GET request to
https://ghcr.io/token?scope=repository%3A<user>%2F…%3Apull&service=ghcr.io: 403 Forbidden
```

⚠️ **이 403 은 두 가지를 뜻할 수 있다.** GHCR 은 패키지 존재 여부를 노출하지 않으려고
**private 인 경우와 아예 없는 경우에 똑같이 403** 을 돌려준다. 메시지만으로는 못 가른다.

가르는 방법은 **인증된 상태로 물어보는 것**이다:

```bash
echo "$GHCR_PAT" | docker login ghcr.io -u <USER> --password-stdin
docker manifest inspect ghcr.io/<USER>/ecommerce-msa/order-service:1.0.0 >/dev/null \
  && echo "존재한다 → private 이 원인" \
  || echo "없다 → 아직 push 되지 않았다"
```

| 결과 | 할 일 |
|---|---|
| **없다** | §6 을 **전부** 돌린다. `--only <서비스>` 로 하나만 테스트했다면 나머지는 존재하지 않는다 — 첫 배포에서 가장 흔하다 |
| **존재한다(private)** | 패키지를 public 으로 바꾸거나 pull secret 을 넣는다 (§6) |

GitHub → 프로필 → Packages 에서 눈으로도 확인된다. `ops/19` 는 서비스 12개 + frontend =
**13개**를 만든다(`config-server` 는 배포 매니페스트에 없지만 이미지는 만들어진다 —
올라가 있어도 정상이다).

배포 후 실제 여유를 확인한다 — **계산과 노드의 현실은 실제로 달랐다**(추정 3,350m vs
실측 3,980m). 매니페스트만 더하면 k3s 내장 파드와 cloudflared 레플리카가 빠진다:

```bash
kubectl describe node | sed -n '/Allocated resources/,/Events/p'
```

읽는 법:

| 줄 | 뜻 | 걱정할 값 |
|---|---|---|
| `cpu Requests` | **스케줄러의 예약 장부.** 실제 사용량이 아니다 | **95% 넘으면 롤링 업데이트가 막힌다** |
| `cpu Limits` (555% 등) | 상한의 합. 노드 용량을 넘어도 **정상**이다 | 그 자체로는 문제 아님 |
| `memory Requests` | 같은 의미의 메모리 예약 | 90% 넘으면 파드가 스케줄되지 않는다 |

**요청이 99% 라도 CPU 가 바쁜 것은 아니다.** Spring Boot 서비스는 유휴 시 10~50m 을 쓴다.
실제 사용량은 `kubectl top nodes` / `kubectl top pods -A` 로 본다(metrics-server 필요).

> ⚠️ **뜨지 못한 파드도 요청을 차지한다.** `ImagePullBackOff` · `CrashLoopBackOff` 는
> **이미 노드에 배정된** 상태다 — 스케줄러가 자리를 잡아 준 뒤 컨테이너가 실패한 것이라
> 예약은 그대로 남는다. (`Pending` 은 배정 자체가 안 된 것이라 차지하지 않는다.)
>
> 여기서 이중으로 샌다. 이미지를 못 받으면 새 파드가 Ready 가 되지 못하고, 그러면
> **옛 ReplicaSet 이 정리되지 않는다.** 한 서비스가 250m 이 아니라 500m 을 잡는다:
>
> ```bash
> kubectl -n ecommerce get rs | awk '$2>0'      # DESIRED>0 인 ReplicaSet 이 둘이면 롤아웃 미완료
> ```
>
> 즉 **요청 사용률이 높은 것이 원인이 아니라 결과일 수 있다.** 리소스를 조정하기 전에
> 파드가 전부 Running 인지부터 본다 — 이미지가 붙는 순간 옛 ReplicaSet 이 사라지며
> 저절로 내려간다.

⚠️ 4 OCPU + `external` 조합의 실측이 **3,980m (99%)** 다. 파드는 다 뜨지만 여유가 20m 라
`kubectl rollout restart` 가 `Pending` 에서 멈춘다. 여유를 만드는 방법은 docs/32 §2.

### ⚠️ `k8s/70-autoscaling.yaml` 은 적용하지 않는다

prod 오버레이가 Deployment 에서 `replicas` 를 지웠으므로(HPA 소유), HPA 를 적용하지 않으면
쿠버네티스 기본값인 **1레플리카**가 된다. 그게 두 구성 모두에서 원하는 값이다.
적용하면 `minReplicas=2` × 11개 서비스로 5,300m 이 되어 **4 OCPU 에서도** 모자란다.

### 레플리카 2 로 올리고 싶다면

레플리카 1은 이 저장소가 레플리카 2에서 잡아 온 동시성 버그를 재현하지 못한다
(docs/31 §3). 핵심 두어 개만 올려 그 경로를 밟아 보고 싶다면 **순서가 있다.**

**① 먼저 CPU 요청을 낮춘다.** 실측 여유가 20m 라(위 참조) 그냥 올리면 새 파드가
`Pending` 에서 끝난다. 방법은 docs/32 §2.

**② `kubectl scale` 이 아니라 HPA 를 고친다.** HPA 를 적용해 두었다면 `kubectl scale` 로
올린 값은 **HPA 가 다시 1로 내린다**(CPU 가 목표치 70% 아래면 안정화 창 5분 뒤 축소).
바꿀 곳은 HPA 의 `minReplicas` 다:

```bash
# 상품 서비스의 이름은 catalog-service 다 — product-service 라는 것은 없다
kubectl -n ecommerce patch hpa order-service   -p '{"spec":{"minReplicas":2}}'
kubectl -n ecommerce patch hpa catalog-service -p '{"spec":{"minReplicas":2}}'
```

HPA 를 아예 적용하지 않았다면 `kubectl scale deploy ...` 로 충분하다.

> 서비스 이름은 디렉터리 이름과 같다: `user` `catalog` `cart` `order` `pay` `coupon`
> `chat` `notification` `delivery` + `-service`, 그리고 `eureka-server` `gateway-server`.

2 OCPU 에서는 여지가 없다. 그쪽은 `ops/12-staging-rehearsal.sh` 로 리눅스에서 확인한다.

`oci` 오버레이가 추가로 조이는 것(docs/32 §2):

| | external | oci |
|---|---|---|
| CPU 요청 | 250m | **60m** |
| 메모리 요청 | 384~512Mi | **256Mi** |
| CPU 상한 | 1000m | **800m** |
| 메모리 상한 | 768~1024Mi | **512Mi** |
| JVM 힙 | 상한의 1/4 (암묵) | **`-XX:MaxRAMPercentage=60` 명시** |
| 톰캣 스레드 | 200 | **50** |
| cloudflared | 2 레플리카 | **1** |
| Elasticsearch | 띄운다 | **띄우지 않는다** (`SEARCH_ENGINE=postgres` 기본) |

⚠️ **레플리카 1에서는 이 저장소가 겪은 동시성 버그가 재현되지 않는다**(docs/31 §3).
그건 `ops/12-staging-rehearsal.sh` 로 따로 확인한다.

```bash
kubectl -n ecommerce get pods -w
```

### DLT 토픽 + 컴팩션 상태 토픽

전부 Ready 가 된 뒤에 만든다.

`ops/02-create-dlt-topics.sh` 가 실행 위치를 스스로 찾는다 — kubectl → docker → 로컬 PATH
순으로 시도하고, 명령 이름도 `kafka-topics`(Confluent)와 `kafka-topics.sh`(Apache) 둘 다 본다.

```bash
ops/02-create-dlt-topics.sh --check     # 무엇을 쓸지 진단만
ops/02-create-dlt-topics.sh             # DLT 9종 + order-saga-state(컴팩션)
```

(⚠️ 이 스크립트는 예전에 `kafka-topics.sh` 를 PATH·`KAFKA_BIN`·docker 중 하나에서만 찾아
**k8s 에서는 동작하지 않았다.** 이 런북을 쓰다 발견해서 고쳤다.)

**`order-saga-state` 는 순서가 중요하다.** saga-projection(Kafka Streams)의 출력 토픽이고
`cleanup.policy=compact` 여야 하는데, Streams 는 싱크 토픽을 만들지 않는다 — 내부 토픽
(변경 로그·리파티션)만 스스로 만든다. **앱이 먼저 뜨면 브로커가 `delete` 정책으로 자동
생성해 버리고**, 그 뒤에 이 스크립트를 돌려도 `--if-not-exists` 는 "이미 있음"으로 조용히
넘어간다. 증상은 `retention.ms`(기본 7일)가 지나서야 나온다 — 과거 주문의 사가 상태가
사라지고, 프로젝션을 재구축하면 그 구간이 빈다.

그래서 이 스크립트는 존재 여부만 보지 않고 **`cleanup.policy` 를 실제로 읽어 다르면 고친다**
(`kafka-configs --alter`). 이미 지워진 과거 상태는 돌아오지 않으므로, 가능하면 **앱을 띄우기
전에** 돌리는 편이 낫다. 고치기를 원하지 않으면 `FIX_CONFIGS=0` 으로 경고만 받는다.

```bash
ops/02-create-dlt-topics.sh --check          # 진단만
FIX_CONFIGS=0 ops/02-create-dlt-topics.sh    # 기존 토픽은 건드리지 않고 경고만
SKIP_STATE=1  ops/02-create-dlt-topics.sh    # DLT 만
```

배포 후 확인은 `ops/06-smoke-test.sh` 의 6번 항목이 한다 — 존재가 아니라 **컴팩션인지**를 본다.

---

## 9. 【맥】 Cloudflare Tunnel

### 사전 준비 — 가입만으로는 아무것도 안 된다

**가장 오래 걸리는 항목은 코드가 아니라 네임서버 전파다.** 레지스트라에서 네임서버를
Cloudflare 것으로 바꾸고 존이 활성화될 때까지 몇 분에서 하루가 걸린다. 그 전에는
`cloudflared tunnel login` 이 도메인을 **목록에 보여 주지도 않는다.** 그래서 이것부터 한다 —
§1 에서 도메인을 먼저 정하라고 한 이유가 여기와 프론트엔드 빌드 둘이다.

```bash
ops/24-cloudflare-tunnel.sh --preflight --hostname shop.example.com
```

확인 가능한 것만 확인하고, 나머지는 목록으로 준다:

| 확인한다 | 어떻게 |
|---|---|
| `cloudflared` 설치 | `command -v` |
| 로그인 여부 | `~/.cloudflared/cert.pem` 존재 — **내용은 찍지 않는다** |
| **네임서버가 Cloudflare 인가** | `dig NS` 로 존 정점을 찾아 확인. `co.kr` 같은 2단 TLD 도 라벨을 벗겨 가며 처리한다 |
| 호스트명이 이미 응답하는가 | 기존 레코드가 있으면 터널 CNAME 을 만들지 못한다 |

`dig` 가 없으면 **통과가 아니라 "미확인"이라고 말한다.**

#### 대시보드에서 사람이 봐야 하는 것

스크립트가 읽을 수 없는 영역이라 통과했다고 말하지 않는다.

| 위치 | 무엇 | 틀리면 |
|---|---|---|
| SSL/TLS → Overview | 암호화 모드가 **Off 가 아닐 것**(Full 이면 충분) | 엣지가 TLS 를 끊는다 |
| SSL/TLS → Edge Certificates | Universal SSL 활성(존 활성화 후 15분쯤), Always Use HTTPS 켬 | 인증서 오류 |
| Network | **WebSockets 켬** | **채팅만** 조용히 안 된다. 나머지는 멀쩡해서 원인 찾기가 어렵다 |
| Caching | 기본값 유지 | "Cache Everything" 규칙을 만들면 `/api/*` 가 캐시되어 **남의 장바구니가 보인다** |

⚠️ 무료 인증서는 `example.com` 과 `*.example.com` **한 단계까지**만 덮는다.
`a.b.example.com` 은 유료 인증서가 필요하니 호스트명을 한 단계로 둔다.

⚠️ 무료 플랜 업로드 본문 100MB 제한은 설정으로 못 넘는다. 넘으면 엣지가 413 을 돌려주고
**애플리케이션 로그에는 아무것도 남지 않는다**(§11 의 증상 표 참조).

### 터널 만들기

6443 을 열지 않았으므로 SSH 터널로 `kubectl` 을 붙인다.

```bash
scp opc@<IP>:/etc/rancher/k3s/k3s.yaml ~/.kube/oci-config
ssh -N -L 6443:127.0.0.1:6443 opc@<IP> &     # 이 터널을 살려 둔 채로
export KUBECONFIG=~/.kube/oci-config
kubectl get nodes                                # 확인
```

k3s 의 kubeconfig 는 서버 주소가 `127.0.0.1:6443` 이므로 SSH 터널과 그대로 맞는다.

```bash
cloudflared tunnel login                         # 브라우저에서 도메인 선택
ops/24-cloudflare-tunnel.sh --hostname shop.example.com
ops/24-cloudflare-tunnel.sh --status
```

> **순서 주의:** `ops/24` 는 §8 **뒤에** 돌려야 한다. `cloudflared-config` ConfigMap 이 먼저
> 있어야 터널 ID 를 치환할 수 있다. 순서가 바뀌면 경고를 내고 다시 실행하라고 알려 준다.

터널 자격증명 Secret 은 **절대 커밋하지 않는다.**

---

## 10. 확인하고 잠그기

```bash
curl -I https://shop.example.com                          # 200 이어야 한다
GATEWAY=https://shop.example.com ./ops/06-smoke-test.sh
./ops/27-check-secret-drift.sh                            # 자격증명이 아직 통하는가
```

`ops/27` 은 `ops/06` 이 **구조적으로 볼 수 없는 것**을 본다. 스모크는 지금 떠 있는 파드에
요청을 보내는데, 그 파드들은 **기동할 때 읽은 자격증명을 들고 있다.** Secret 이나 DB 쪽
비밀번호가 그 뒤에 바뀌었어도 전부 초록불이고, **다음 롤아웃에서 한꺼번에 죽는다.**
이번 배포에서 5시간 된 파드는 Running 인데 새로 뜨는 파드만 CrashLoopBackOff 였다.

값을 비교하지 않고 **클러스터 안에서 실제로 로그인해 본다** — 비밀번호가 로컬 셸에
나오지 않고, 서비스가 받는 값과 정확히 같은 값을 시험하게 된다.

**터널 경유일 때 `ops/06` 이 무엇을 볼 수 있고 없는지 알고 봐야 한다.** 공개 호스트명
하나로 프론트엔드와 API 를 같이 받으므로, **게이트웨이로 라우팅되지 않는 경로는 전부
프론트엔드로 흘러가고 SPA 는 어떤 경로에든 index.html 을 200 으로 돌려준다.**

| 항목 | 터널 경유에서 | 왜 |
|---|---|---|
| §1 개별 서비스 readiness | ⏭ 자동 생략 | 개별 포트가 클러스터 밖으로 안 나온다. `$GATEWAY` 가 원격이면 스크립트가 알아서 끈다(`SKIP_DIRECT`) |
| §2 `gateway /actuator/health` | ⏭ 생략 | actuator 는 **일부러 공개하지 않는다.** 클러스터 안에서 본다(아래) |
| §2 `/fallback` | ⏭ 생략 | Resilience4j 가 게이트웨이 **내부에서** `forward:/fallback` 한다. 외부에서 안 닿는 게 정상 |
| §3 레이트리미터 429 | ✅ 실측 | `/catalog-service/...` 는 터널 규칙에 맞아 게이트웨이까지 간다 — **게이트웨이가 살아 있다는 진짜 증거는 이것이다** |
| §7 카탈로그 조회 | ✅ 실측 | 같은 이유 |

⚠️ 예전에는 §2 가 상태 코드만 봐서 `✅ gateway health 200` 으로 **통과**했다. 게이트웨이는
쳐다보지도 않고 프론트엔드의 HTML 을 받은 것이었다. 지금은 본문을 보고 누가 대답했는지
가린 뒤 생략으로 처리한다.

게이트웨이 헬스는 클러스터 안에서 본다:

```bash
kubectl -n ecommerce exec deploy/gateway-server -- \
  curl -sk https://localhost:8000/actuator/health
```

스모크가 통과했다면 그다음은 **사람이 눌러 보는 차례**다 — `ops/06` 은 헬스·레이트리미터·
Eureka·DLT·카탈로그 조회까지만 보고 **주문·결제는 보지 않는다.**
순회 경로는 [docs/34 인수 체크리스트](34-acceptance-checklist.ko.md).

그다음 반드시:

- **Neon · Atlas 콘솔에서 접근 IP 를 Ampere 공인 IP 로 제한.** 무료 티어라 제한이
  불가능하다면, 그건 비밀번호가 유일한 방어선이라는 뜻이다
- OCI Security List 의 22번도 본인 IP 로 제한
- `ops/20-scan-history-secrets.sh --remote` 로 한 번 더 확인

---

## 11. 자주 막히는 곳

### 리눅스가 WSL 이라면 — 먼저 읽을 것

**`/usr/bin/env: ‘bash\r’: No such file or directory`**

Windows 의 git 은 `core.autocrlf=true` 가 기본이라 체크아웃할 때 LF 를 CRLF 로 바꾼다.
그러면 셔뱅이 `#!/usr/bin/env bash\r` 가 되고 커널이 `bash\r` 라는 프로그램을 찾다 실패한다.
증상이 스크립트 내용과 무관해서 원인을 찾기 어렵고, **한 파일을 고쳐도 그것이 호출하는 다음
스크립트에서 똑같이 막힌다**(`ops/13` → `ops/07` 에서 실측).

`.gitattributes` 가 `*.sh` 를 `eol=lf` 로 고정하지만, 그 규칙이 생기기 **전에** 클론했다면
이미 CRLF 로 펼쳐져 있다. 다시 펼친다:

```bash
git rm --cached -r . >/dev/null && git reset --hard        # .gitattributes 기준으로 재정규화
git ls-files -z | xargs -0 grep -lIU $'\r' || echo "CR 없음"
```

⚠️ **`/mnt/c`, `/mnt/d` 가 아니라 WSL 네이티브 파일시스템에 클론할 것.**

```bash
cd ~ && git clone <repo> && cd ecommerce-msa
```

Windows 드라이브(DrvFs)에서는 (1) Windows 쪽 git 이 CRLF 로 다시 덮어쓸 수 있고,
(2) 실행 권한이 제대로 유지되지 않으며, (3) Gradle 빌드가 몇 배 느리다.
`ops/13` 은 docker 를 privileged 로 띄우므로 네이티브 파일시스템이 안전하다.

빌드가 이 실수를 잡는다 — 가드레일 **B-36** 이 `*.sh` 안의 CR 을 찾으면 실패한다.

### 접속·프로비저닝

| 증상 | 원인 |
|---|---|
| `invalid connection option "‘postgresql://…sslmode"` | 값 맨 앞에 둥근따옴표가 섞였다. URI 로 시작하지 않으면 libpq 가 키=값으로 읽는다. 따옴표를 직접 타이핑할 것 |
| `must be able to SET ROLE "product"` | 관리자 계정이 슈퍼유저가 아니다. `ops/25` 가 멤버십을 받아 처리한다(최신 버전) |
| `permission denied to create database` | `ADMIN_URL` 이 `product` 를 가리킨다. `neondb_owner` 로 바꾼다 |
| 3단계에서만 실패 / `cannot run inside a transaction block` | 풀러(`-pooler`) 주소다. 직결 주소를 쓴다 |
| `must be owner of schema probe_ecommerce_msa` | 이전 실행의 잔여물. 소유자 계정에서 `DROP SCHEMA … CASCADE` |
| `connection to server on socket "/var/run/postgresql/..." failed` | **원격지에 간 적이 없다.** URI 의 호스트가 비어 libpq 가 로컬 소켓으로 폴백한 것이다 — 셸 변수(`$HOST`·`$ADMIN_URL`)가 비었는지 본다 |
| `You must install at least one postgresql-client-<version> package` | psql 미설치. `apt-get install -y postgresql-client` / `dnf install -y postgresql` |

### 배포

| 증상 | 원인 |
|---|---|
| 파드가 `Pending` · `Insufficient cpu` | 노드의 CPU **요청**이 찼다. `kubectl patch` 로 낮춘 값은 `apply -k` 에 덮이므로 `patch-java-requests.yaml` 에서 바꾼다. 그 밖에 자리를 먹는 것들: cloudflared 2레플리카(§9 전이면 0으로), 되살아난 postgres·mongodb, HPA 가 만든 여분 레플리카, 롤아웃이 끝나지 않아 남은 옛 ReplicaSet |
| 이미지를 고쳐 push 했는데 **그대로다** · `already present on machine` | `imagePullPolicy` 가 `IfNotPresent` 라 같은 태그는 다시 받지 않는다. **태그를 올린다** — §6 |
| **frontend** 만 `CreateContainerConfigError` (`non-numeric user`) | 이미지의 `USER` 가 이름이면 kubelet 이 `runAsNonRoot` 를 **판정할 수 없어** 거부한다. `Dockerfile.frontend-runtime` 을 `USER 1000` 으로 고쳤다(Java 이미지는 원래 숫자였다) — frontend 만 다시 빌드·push 하면 된다 |
| **backup CronJob** 이 `CreateContainerConfigError` | `backup-credentials` Secret 이 없다. ⚠️ **external 구성에서 postgres-backup 은 아예 쓸 수 없다** — `pg_basebackup` 은 Neon 에 통하지 않는다(백업은 provider 책임). mongo-backup 은 반대로 **필요하다**(Atlas M0 는 백업이 없다). §8 참조 |
| redis 파드가 **`CreateContainerConfigError`** | `redis-auth` Secret 이 없다. `90-dev-infra.yaml` 은 참조만 하고 만들지 않는다 — §8 의 생성 명령. 컨테이너를 만들기 **전에** 실패하므로 로그도 없다 |
| `CrashLoopBackOff` + **`RedisConnectionException` · `Connection refused ...:6379`** | **redis Deployment 가 없다.** PG·Mongo 와 함께 지우기 쉽다 — §8 참조. Redis 를 쓰는 서비스가 **7개**(cart·catalog·chat·notification·order·pay·user)라 절반 이상이 죽는다. ⚠️ Service 는 남아 있어 **이름 해석과 ClusterIP 는 정상**이라 DNS 문제로 보이지 않는다: `kubectl -n redis get endpoints redis` 가 `<none>` 이면 확정 |
| 회원가입이 **400 `Error: Role is not found.`** | `users.roles` 테이블이 **비어 있다.** 역할을 심던 코드는 `SecurityConfig` 에서 주석 처리돼 있었고 마이그레이션도 넣지 않아, **깨끗한 DB 에서는 아무도 가입할 수 없었다** — 첫 사용자가 없으니 관리자도 만들 수 없다. `V3__seed_roles.sql` 이 ROLE_USER·ROLE_ADMIN 을 심는다. 이미 뜬 DB 라면 user-service 를 재시작해 Flyway 를 돌린다: `kubectl -n ecommerce rollout restart deploy/user-service` |
| `CrashLoopBackOff` + **`password authentication failed for user 'product'`** (28P01) | `LOCAL_DB_PASSWORD` 가 `ops/25` 에 입력한 **product** 비밀번호와 다르다. ⚠️ 여기까지 왔다면 호스트·TLS·역할은 전부 맞은 것이다 — 비밀번호만 고치면 된다. §7 의 psql 확인 절차 |
| `CrashLoopBackOff` + **`keystore password was incorrect`** | `LOCAL_KEY_STORE_PASSWORD` 가 `certs/certs.env` 의 값과 다르다. 파일 마운트는 정상이다(못 찾았으면 `KeyStore not exists` 였다). §7 의 patch 명령으로 넷을 한꺼번에 맞춘다 |
| `CrashLoopBackOff` + **`KeyStore not exists`** | 이쪽이 파일 문제다. `tls-keystore` Secret 이 없거나 파일 8개가 다 안 들어갔다 — `ops/07` 후 `kubectl apply -f certs/k8s-tls-keystore.secret.yaml` |
| 파드가 **`InvalidImageName`** | 이미지 이름이 문법에 안 맞는다 — 레지스트리에 가 보지도 않았다. 대개 `REPLACE_ME_OWNER` 가 그대로 남은 것이다(**대문자**라 경로로 쓸 수 없다). 로그인·패키지 공개 여부를 아무리 봐도 원인이 안 나온다. §8 의 `grep REPLACE_ME` |
| 파드가 **`ImagePullBackOff`** + `anonymous token … 403` | 이름은 맞는데 못 받아 왔다. ⚠️ GHCR 은 **private 인 경우와 아예 없는 경우에 같은 403** 을 준다 — `docker manifest inspect` 로 갈라야 한다(§8) |
| 프론트가 옛 API 주소로 간다 | `REACT_APP_API_URL` 은 **빌드 시점** 값이다. §6 부터 다시 |
| 콘솔에 **`Cross-Origin Request Blocked`** + 403 (`token/refresh` 등) | 페이지가 `REACT_APP_API_URL` 과 **다른 주소**(www 없음·http)로 열렸다. 같은 출처라면 브라우저는 CORS 검사를 하지 않는다. 주소창 확인 → §6 의 루트→www 리다이렉트 |
| cloudflared 파드가 안 뜨고 `Rule #N is matching the hostname ''` | ingress 규칙 중 **catch-all 이 마지막이 아니다.** cloudflared 는 그러면 기동을 거부한다. 규칙을 고쳤으면 `cloudflared --config <파일> tunnel ingress validate` 로 먼저 확인할 것 |
| `curl` 이 **522** — 그런데 cloudflared 파드는 `1/1 Running` | ⚠️ **파드가 Ready 인 것은 "엣지에 붙었다"는 뜻일 뿐 사용자가 닿는다는 뜻이 아니다.** 커넥터가 멀쩡한데 522 면 **DNS 레코드가 이 터널을 안 가리킨다.** 도메인을 Cloudflare 로 옮길 때 이전 네임서버의 **A 레코드가 통째로 임포트**되고, 그게 남아 있으면 `cloudflared tunnel route dns` 가 실패해 CNAME 이 안 생긴다. Cloudflare 는 그 죽은 IP 로 붙으려다 522 를 낸다. 대시보드 → DNS → Records 에서 `CNAME → <tunnel-id>.cfargotunnel.com (Proxied)` 인지 확인하고, 아니면 `cloudflared tunnel route dns --overwrite-dns <터널> <호스트>`. ⚠️ 프록시된 레코드는 `dig` 로 Cloudflare IP 만 보여서 **밖에서는 구분되지 않는다** — 대시보드를 봐야 한다 |
| `curl` 이 **530** | DNS 는 터널을 가리키는데 커넥터가 하나도 없다. `kubectl -n ecommerce get deploy cloudflared` 가 `0/0` 이면 그것이다 |
| 터널은 붙었는데 **404** | Ingress `host` 불일치. 기본값이 `shop.example.com` 이던 시절 이걸 교체하지 않으면 **배포는 전부 성공한 것처럼 보이고** 브라우저에서만 404 가 났다. 지금은 `k8s/60-ingress.yaml` 에 `host` 를 아예 두지 않는다(모든 호스트 매칭). 옛 값이 클러스터에 남아 있다면: `kubectl -n ecommerce patch ingress ecommerce-frontend --type=json -p='[{"op":"remove","path":"/spec/rules/0/host"}]'`. 클러스터 안에서 바로 확인하려면 `kubectl -n ecommerce run curltest --rm -it --restart=Never --image=curlimages/curl -- curl -sS -m 10 -o /dev/null -w 'HTTP %{http_code}\n' -H 'Host: <도메인>' http://traefik.kube-system.svc.cluster.local:80/` |
| 파드는 Running 인데 서로 통신 불가 | OCI 의 iptables REJECT. `ops/23` 을 돌리지 않았거나 재부팅 후 규칙이 날아갔다 |
| Elasticsearch 가 즉시 죽는다 | `vm.max_map_count`. `ops/23` 이 올린다 |
| 이미지 업로드 413 · **로그에 아무것도 없다** | Cloudflare 무료 플랜 업로드 100MB 제한. 엣지에서 잘려 애플리케이션까지 오지 않는다 |
| 산발적 `prepared statement "S_1" already exists` | 애플리케이션이 풀러 엔드포인트를 쓰고 있다. JDBC URL 에 `&prepareThreshold=0` 이 필요하다(docs/32 §9, 미해결로 남긴 항목) |
| 파드가 `Pending` 에서 안 넘어간다 | 자원 초과. HPA `minReplicas` 를 1로 내렸는지 확인(§8) |

---

## 12. 검증 상태 — 무엇을 믿고 무엇을 믿지 말 것

**§1~§8 은 실제 OCI Ampere(4 OCPU/24GB, Ubuntu 24.04, arm64)에서 끝까지 통과했다.**
그 과정에서 이 문서가 여러 번 틀렸고, 틀린 것들은 아래 §13 에 남겼다. §9 이후는 아직이다.

| 단계 | 상태 |
|---|---|
| §2 `ops/25` | ✅ PostgreSQL 16 컨테이너 + **실제 Neon**. 관리자·비관리자·CREATEDB 없음·잔여 스키마 네 상황 확인. 마이그레이션 26개 전량 적용 |
| §2 Neon 실접속 | ✅ **실측.** 역할·DB 10개 생성, 앱이 붙어 Flyway 통과 |
| §2b Atlas 실접속 | ✅ **실측.** 클러스터 생성 → 연결 확인까지 |
| §3 `gradlew test -PwithContainers` | ✅ 281 tests, 실패 0 |
| §3 `ops/13` | ✅ 리눅스에서 실행 확인(이전 작업) |
| §6 이미지 빌드·GHCR push | ✅ **실측.** Ampere 위 arm64 네이티브 빌드, 13개 push |
| §7 인증서·시크릿 | ✅ **실측.** `ops/07` 산출물로 12개 서비스 기동 |
| §8 배포 | ✅ **실측.** external 오버레이로 서비스 기동 확인 |
| §8 `kustomize build` | ✅ 29개 리소스, ConfigMap 병합 확인 |
| §4 `ops/26` | ⚠️ oci CLI 를 스텁으로 두고 **실패 분류 3종**을 확인. 실제 OCI 호출은 미검증(인스턴스는 콘솔로 만들었다) |
| §5 `ops/23` | ⚠️ **부분.** Ubuntu 경로만 해당하고, 이 배포에서 스크립트를 그대로 돌렸는지는 확인되지 않았다. RHEL 계열 분기는 여전히 미검증 |
| §9 `ops/24` 설정 | ✅ **실제 cloudflared 2026.7.3 으로 검증.** 세 오버레이의 ingress 규칙이 `tunnel ingress validate` 통과, 경로 라우팅(API→게이트웨이 / 나머지→traefik)도 `tunnel ingress rule` 로 확인 |
| §9 `ops/24` 실행 | ⚠️ **부분.** 터널 생성·DNS 라우트까지 진행했다. 엔드투엔드(공개 URL 응답)는 미완 |
| §10 `ops/06` 스모크 | ❌ **미검증.** §9 뒤 단계다 |
| §8 `ops/02` DLT 토픽 | ✅ 실제 Confluent Kafka 컨테이너에 9종 생성 확인. kube 분기는 명령 조립까지만 |

---

## 13. 실제 배포에서 이 문서가 틀렸던 것들

한 번의 실배포에서 나온 것이다. 같은 부류가 또 있을 수 있으니 패턴을 적어 둔다.

| 무엇이 틀렸나 | 왜 안 보였나 |
|---|---|
| `redis-auth` Secret 생성 단계가 없었다 | `90-dev-infra.yaml` 헤더에 전제로만 적혀 있고, 만드는 것은 `ops/13` 뿐이었다. redis 가 `CreateContainerConfigError` 로 멈춰도 **로그가 없다** |
| Redis 삭제의 피해를 "cart-service" 로 적었다 | 실제로는 **7개 서비스**가 Redis 를 쓴다 |
| 예시 호스트가 진짜처럼 생겼다 | `ep-solitary-mountain-a1b2c3.…` 를 그대로 복사해 넣어도 눈에 띄지 않았다 |
| DNS 로 호스트를 검증하라고 했다 | Neon 은 **와일드카드 DNS** 라 아무 이름이나 해석된다. 검증이 되지 않는 검사였다 |
| psql·`$ADMIN_URL` 이 있다고 가정했다 | 그건 §2【맥】의 상태다. §7 은 【Ampere】다 |
| 비밀번호의 둥근따옴표를 막지 않았다 | `read -rs` 는 화면에 아무것도 안 보여 준다. 길이 검사도 통과한다 |
| `POSTGRES_HOST` 를 손으로 채우게 했다 | 값을 채운 파일 넷이 전부 git 추적 대상이라 `git pull` 이 충돌을 냈고, 마커가 남아 kustomize 가 통째로 거부했다. 지금은 `k8s-overlays/local`(gitignore)이 받는다 |
| YAML 을 PyYAML 로 검증했다 | **PyYAML 은 중복 키를 조용히 허용한다** — 마지막 것만 남기고 넘어간다. `paths:` 가 두 번 들어간 Ingress 가 통과해서 커밋됐고, `kustomize build` 를 돌리고서야 드러났다(`mapping key "paths" already defined`). 매니페스트는 **실제로 쓰는 도구로** 검증해야 한다 |
| Ingress 의 `host` 기본값이 `shop.example.com` 이었다 | 교체를 잊어도 **배포가 전부 성공한 것처럼 보인다.** 파드는 Running, Ingress 도 정상, 터널도 붙어 있고, 실패는 브라우저의 404 로만 드러난다. 지금은 `host` 를 아예 두지 않는다 |
| `ops/24` 의 DNS 라우트가 실패를 삼켰다 | `route dns … >/dev/null 2>&1` 뒤에 *"이미 있거나 만들지 못했습니다"* 라는 **구분 불가능한** 문장을 흘리고 성공처럼 끝났다. 도메인 이전 때 임포트된 옛 A 레코드가 남아 있으면 CNAME 이 안 생기고, 그 결과가 522 다 |
| 522 를 "커넥터가 없다"로만 적었다 | 커넥터가 `1/1 Running` 인 채로 522 가 난다. **파드 Ready 는 "엣지에 붙었다"이지 "사용자가 닿는다"가 아니다** |
| `ops/06` 이 `✅ gateway health 200` 으로 통과했다 | 게이트웨이를 쳐다보지도 않았다. `/actuator/health` 는 터널 규칙에 안 맞아 프론트엔드로 갔고, **SPA 는 어떤 경로에든 index.html 을 200 으로 돌려준다.** 상태 코드만 보는 검사는 SPA 앞에서 전부 무의미하다 |
| §10 의 `ops/06` 명령에 `SKIP_DIRECT` 가 없었다 | `localhost:8080` 을 때려 **가짜 실패 7개**가 났고, 진짜 문제(위 행)가 그 사이에 묻혔다. 지금은 `$GATEWAY` 가 원격이면 자동으로 끈다 |
| `read -rs` 를 여러 줄 붙여넣기 블록 안에 넣었다 | 셸이 **붙여넣은 다음 줄을 값으로 읽어** 간다. 화면에 안 보이니 눈치챌 수 없고, Secret 은 `created` 로 멀쩡히 만들어진다. 이 문서가 그렇게 쓰라고 시켰다. `read` 는 반드시 **따로 한 줄로** 실행한다 |
| 빈 백업이 "완료"로 끝났다 | `mongodump` 는 대상 DB 가 비어도 성공하고 `gzip -t` 는 빈 아카이브도 통과시킨다. 116바이트짜리 덤프가 초록불이었다. 이 프로젝트는 URI 경로가 아니라 `spring.data.mongodb.name`(=`chat`)으로 DB 를 고르므로, URI 가 다른 DB 를 가리키면 **매일 성공하는 빈 백업**이 된다. 크기를 지표로 내보내고 `MongoBackupEmpty` 경보를 걸었다 |
| CronJob 이 내보내는 지표 이름이 경보와 달랐다 | 경보는 `ecommerce_mongo_backup_last_success_timestamp` 를, CronJob 은 `mongo_backup_last_success_timestamp_seconds` 를 썼다. **CronJob 경로에서는 백업 경보가 영원히 울리지 않는다.** 없는 지표에 대한 `time() - x` 는 조용히 아무것도 하지 않는다 — 경보가 안 울리는 것과 구분되지 않는다 |
| 백업 잡이 `restartPolicy: OnFailure` 였다 | 백오프 한도에 닿으면 job-controller 가 **파드를 지운다.** mongo-backup 이 14초 만에 실패했는데 원인을 볼 수 없었다. `Never` 는 실패한 파드를 남긴다. **백업이 왜 실패했는지는 백업이 도는 것만큼 중요하다** |
| 백업 CronJob 을 문서로만 정지시키게 했다 | `85-backup-cronjobs.yaml` 을 적용하면 `postgres-backup` 이 **즉시 켜진 상태**로 들어온다. Neon 에서는 절대 성공할 수 없는데도. 문서의 정지 명령을 건너뛰면 매일 실패한다. 지금은 매니페스트가 `suspend: true` 로 들어오고, 되는 것을 확인한 사람이 켠다 |

**반복되는 형태가 하나 있다.** "이름이 해석된다"가 "거기 뭔가 있다"를 뜻하지 않는다는 것 —
Neon 의 와일드카드 DNS, 파드 없는 Service 의 ClusterIP, 터널에 연결되지 않은 DNS 레코드,
전부 같은 착각을 만든다. 확인은 **연결해 보는 것**으로 해야 한다. 그래서 `ops/24` 는 이제
마지막에 공개 URL 을 실제로 한 번 받아오고, 돌아온 코드(404/522/530/…)별로 어디가
끊겼는지 알려 준다.

**두 번째 형태도 있다.** 층이 여럿인 경로에서는 **각 층이 저마다 "정상"이라고 보고한다.**
Cloudflare(DNS) → 터널 → traefik(Ingress) → frontend 넷 중 어디가 끊겨도 나머지 셋은
초록불이다. 층을 하나씩 건너뛰며 때려 보는 것이 유일하게 빠른 방법이다:
클러스터 안에서 traefik 직접 → 터널 통해 공개 URL → 브라우저.
