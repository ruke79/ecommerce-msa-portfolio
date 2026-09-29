# 인증서 · 키스토어 관리

[日本語](18-certificates.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 일부 낡음.** 클라이언트 인증서는 이제 서비스별입니다(CN = 서비스 이름, `k8s/96`, cert-manager 자동 갱신 — [docs/57 §16·§18](57-remaining-priorities.ko.md)). 서버 인증서 `tls-keystore` 는 아직 공유입니다.

> 작성일: 2026-07-28
> 관련: [ops/07-issue-certificates.sh](../ops/07-issue-certificates.sh) · [백업/DR](15-backup-and-dr.ko.md) · [시크릿/HA](13-secrets-dbha-es-search.ko.md)

## 0. 무엇이 문제였나

이 저장소의 모든 서비스는 HTTPS 로 뜨고, Kafka·Redis·MongoDB·PostgreSQL 에 TLS 로 붙는다.
그런데 그 인증서를 **어떻게 만드는지 아무 데도 적혀 있지 않았다.** 결과:

| 증상 | 실제 코드 |
|---|---|
| 새로 클론하면 어떤 서비스도 못 뜬다 | `${LOCAL_KEY_STORE_PATH}` 를 채울 방법이 문서화되지 않음 |
| 설정에 원저자 PC 경로가 박혀 있다 | `file:E:/github/amazon-clone-msa/.../local_truststore.jks` — 7개 서비스 |
| mTLS 를 켜면 죽는다 | 자바가 스토어 형식을 `"JKS"` 로 하드코딩, 경로는 `.p12` — 19개 클래스 |
| 게이트웨이가 TLS 검증을 안 한다 | `useInsecureTrustManager: true` |
| 만료되면 아무도 모른다 | 갱신 절차·감시 없음 |

[docs/15 §0](15-backup-and-dr.ko.md) 이 "인증서/키스토어 = ❌ 저장소 밖 수동 관리 = 🔴" 로 남겨 둔
항목이 바로 이것이다. 아래 넷을 고쳤다.

## 1. 파일 위치는 환경변수로만 받는다

각 서비스의 `application-local.yml` 에 있던 `kafka:` 블록을 전부 제거했다. 그 블록은
존재하지 않는 윈도우 경로를 가리킨 채 `protocol: "SSL"` 을 켜 두고 있었다 — 로컬 브로커가
PLAINTEXT 라 조용히 넘어갔을 뿐, SSL 브로커에 붙는 순간 죽는 구성이었다.

이제 Kafka 접속 설정은 `common/src/main/resources/application-common.yml` 한 곳이 소유한다:

```yaml
kafka:
  url: ${KAFKA_URL:localhost:29092}
  security:
    protocol: ${KAFKA_SECURITY_PROTOCOL:PLAINTEXT}
  ssl:
    trust-store-location: ${KAFKA_SSL_TRUST_STORE_LOCATION:}
    trust-store-password: ${KAFKA_SSL_TRUST_STORE_PASSWORD:}
    key-store-location:   ${KAFKA_SSL_KEY_STORE_LOCATION:}
    key-store-password:   ${KAFKA_SSL_KEY_STORE_PASSWORD:}
    key-store-type:       ${KAFKA_SSL_KEY_STORE_TYPE:PKCS12}
    trust-store-type:     ${KAFKA_SSL_TRUST_STORE_TYPE:JKS}
```

환경변수 이름은 `k8s/10-configmap.yaml` 이 이미 쓰고 있던 `KAFKA_SSL_*` 로 통일했다.
(공유 설정은 `KAFKA_TRUST_STORE_*` 라는 다른 이름을 보고 있어서, 매니페스트가 주입하는 값이
어디에도 닿지 않는 상태였다.)

### `file:` 접두어 — 어디에 필요하고 어디에 필요 없는가

같은 경로 문자열인데 소비하는 쪽마다 해석이 다르다. 이걸 몰라서 기동이 죽는 일이 실제로 있었다.

| 설정 | 접두어 | 이유 |
|---|---|---|
| `server.ssl.key-store` | 선택 | Spring Boot 가 평범한 파일 경로도 처리한다 |
| `eureka.client.tls.key-store` / `trust-store` | **`file:` 필수** | 스프링 `Resource` 로 해석 → 접두어 없으면 ServletContext 상대 경로 |
| `spring.cloud.gateway.server.webflux.httpclient.ssl.trust-store` | **`file:` 필수** | 위와 같음 |
| `spring.data.redis.ssl.*` | **접두어 금지** | `RedisCacheConfig` 가 코드에서 `"file:" +` 를 직접 붙인다 |
| `kafka.ssl.*-location` | 무관 | 자바 쪽 `checkPath()` 가 `file:` 를 벗겨 낸다 |

접두어가 없으면 파일이 멀쩡히 있어도 이렇게 죽는다:

```
KeyStore not exists: ServletContext resource [/home/.../certs/truststore.p12]
```

그래서 **환경변수 값은 접두어 없는 순수 경로**로 두고, `file:` 가 필요한 두 곳(eureka-server,
gateway-server 의 `eureka.client.tls`)만 yml 에서 `file:${...}` 로 붙인다.

## 2. 스토어 형식도 주입받는다

19개 Kafka 설정 클래스가 이렇게 돼 있었다:

```java
.put(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG, "JKS")        // ← 하드코딩
.put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, checkPath(keyStoreLocation))   // ← .p12 파일
```

PKCS12 파일을 JKS 라고 선언한 것이다. `security.protocol` 을 `SSL` 로 바꾸는 순간
`java.io.IOException: Invalid keystore format` 으로 전 서비스가 기동 불가가 된다.
로컬 브로커가 PLAINTEXT 였기 때문에 한 번도 밟히지 않았을 뿐이다.

형식도 프로퍼티로 뺐고, 기본값은 `ops/07` 이 만들어 주는 파일 형식과 일치시켰다.
`ConfigurationGuardrailTest` 가 하드코딩 재발을 막는다.

## 3. 게이트웨이 TLS 검증을 켰다

```yaml
useInsecureTrustManager: ${GATEWAY_INSECURE_TRUST_MANAGER:false}   # 예전엔 true 고정
```

`true` 는 다운스트림 인증서 검증을 통째로 끈다 — 결제 트래픽이 지나가는 구간에서 MITM 이
성립한다. 같은 블록에 `trust-store` 를 써 놨지만 `useInsecureTrustManager: true` 가 그것을
무시하므로 아무 효과가 없었다.

기본값을 검증하는 쪽으로 바꿨다. 모든 서비스 인증서가 같은 CA 로 서명돼 있고 SAN 에 서비스
이름이 들어 있으면 그대로 동작한다 — `ops/07` 이 정확히 그런 인증서를 만든다. 외부 인증서로
넘어가는 과도기에만 `GATEWAY_INSECURE_TRUST_MANAGER=true` 로 임시 우회한다.

## 4. 발급과 주입 — `ops/07-issue-certificates.sh`

```bash
./ops/07-issue-certificates.sh                 # 개발용: 로컬 CA + 서비스 인증서
set -a; source ./certs/certs.env; set +a       # 주입
./gradlew :gateway-server:bootRun
```

### 산출물

| 파일 | 형식 | 쓰는 곳 |
|---|---|---|
| `ca.crt` / `ca.key` | PEM | 로컬 CA (dev 모드) · `LOCAL_CACERT_PATH` |
| `tls.crt` / `tls.key` | PEM | Redis SSL, ES 트러스트 번들 · `LOCAL_CRT_PATH` / `LOCAL_KEY_PATH` |
| `keystore.p12` | PKCS12 | `server.ssl.key-store` · `LOCAL_KEY_STORE_PATH` |
| `truststore.p12` | PKCS12 | `LOCAL_TRUST_STORE_PATH` |
| `truststore.jks` | JKS | 레거시 소비자 |
| `kafka-keystore.p12` | PKCS12 | `KAFKA_SSL_KEY_STORE_LOCATION` |
| `kafka-truststore.jks` | JKS | `KAFKA_SSL_TRUST_STORE_LOCATION` |
| `certs.env` | sh | 위 전부를 export |
| `k8s-tls-keystore.secret.yaml` | k8s Secret | `kubectl apply -f` |

### 모드

| 모드 | 용도 |
|---|---|
| `MODE=dev` (기본) | 로컬 CA 를 만들고 그 CA 로 서비스 인증서 발급. 오프라인·무료·즉시 |
| `MODE=letsencrypt` | certbot(ACME)으로 실제 도메인 인증서 무료 발급 → 자바 스토어로 변환 |
| `MODE=import` | 이미 가진 인증서/키를 스토어로 변환만 |
| `MODE=verify` | 기존 산출물 검증만 (cron 으로 만료 감시) |

```bash
MODE=letsencrypt LE_DOMAIN=api.example.com LE_EMAIL=ops@example.com \
  ./ops/07-issue-certificates.sh
```

### 비밀번호가 하나인 이유

키스토어·키·트러스트스토어 비밀번호는 **같아야 한다**. 선택이 아니라 기존 설정의 제약이다:

- `gateway-server/application-local.yml`: `trust-store-password: ${LOCAL_KEY_STORE_PASSWORD}`
- 각 서비스: `key-password: ${LOCAL_KEY_PASSWORD}`
- `RedisCacheConfig`: `storePassword: ${LOCAL_KEY_PASSWORD}`

이미 두 이름이 섞여 쓰이고 있어, 값을 다르게 두면 반드시 어딘가가 깨진다.
스크립트는 비밀번호를 자동 생성해 `certs.env`(권한 600)에만 기록하고 화면에는 찍지 않는다.
재실행 시에는 기존 `certs.env` 의 값을 재사용해 배포된 Secret 과 어긋나지 않게 한다.

### SAN — 여기 없는 이름으로 붙으면 핸드셰이크가 깨진다

기본 SAN 은 `localhost`·`127.0.0.1`·`::1`·`host.docker.internal`, 컴포즈 네트워크의 서비스
이름 12종, 인프라 이름 5종, 그리고 `*.ecommerce.svc.cluster.local` 을 포함한다.
`SANS=` 로 교체할 수 있다.

인증서에는 `serverAuth` 와 `clientAuth` 를 **둘 다** 넣는다. 서비스들이 서로에게 클라이언트로도
붙기 때문이다(Kafka mTLS, Feign, Eureka peer). `serverAuth` 만 넣으면 mTLS 에서 상대가 거부한다.

### 자체 검증

스크립트는 발급 후 6가지를 검사하고, 하나라도 실패하면 exit 1 한다:

1. 체인 검증 (`openssl verify`)
2. 인증서와 개인키가 같은 쌍인가 (공개키 해시 비교)
3. SAN 존재 및 `localhost` 포함 — SAN 없는 인증서는 자바 17 이 거부한다
4. 만료일 (기본 30일 전 경고)
5. **자바가 실제로 스토어를 여는가** (`keytool -list` 5종) — 서비스가 기동 중 겪을 경로를 미리 밟는다
6. 개인키 파일 권한 600

**이 검증들이 장식이 아님도 확인했다.** 유효기간 10일짜리 인증서를 발급하니 `WARN: 30일 이내에
만료됩니다` 가 떴고, 키스토어 파일을 일부러 망가뜨리니 `FAIL keystore.p12 (PKCS12) 를 열 수
없습니다` 와 함께 exit 1 로 끝났다. 실패하지 않는 검사는 검사가 아니다.

### 실제로 확인한 것

`ops/07` 로 발급한 인증서만으로 eureka-server 를 띄워 확인했다:

```
$ curl --cacert ./certs/ca.crt https://localhost:8761/actuator/health
{"status":"UP"}

$ openssl s_client -connect localhost:8761 -CAfile ./certs/ca.crt
subject=CN = localhost, O = ecommerce-msa
issuer=O = ecommerce-msa, CN = ecommerce-msa local development CA
Verify return code: 0 (ok)

$ curl https://localhost:8761/actuator/health          # CA 없이
curl: (60) SSL certificate problem: self-signed certificate in certificate chain
```

## 5. 갱신

Let's Encrypt 인증서는 90일마다 갱신된다. 갱신은 PEM 만 바꾸므로 **키스토어를 다시 만들지
않으면 서비스는 옛 인증서를 계속 쓴다.** 이 간극이 실제 장애의 단골 원인이다.

`MODE=letsencrypt` 는 `certs/renew-deploy-hook.sh` 를 함께 생성한다:

```bash
sudo ln -sf "$PWD/certs/renew-deploy-hook.sh" \
     /etc/letsencrypt/renewal-hooks/deploy/rebuild-java-stores.sh
```

훅은 갱신된 PEM 으로 스토어를 다시 만들고, kubectl 이 있으면 Secret 을 apply 한 뒤
`rollout restart` 까지 한다. 자바는 기동 시 키스토어를 한 번만 읽으므로 재기동이 필요하다.

만료 감시는 cron 에 검증 모드를 걸면 된다:

```cron
0 6 * * * cd /srv/ecommerce && MODE=verify WARN_DAYS=30 ./ops/07-issue-certificates.sh
```

## 6. 절대 커밋하지 말 것

`certs/` 전체가 `.gitignore` 대상이다. 스크립트도 출력 디렉터리가 git 추적 대상이면
`FORCE=1` 없이는 실행을 거부한다.

개인키가 git 이력에 들어가면 [ops/01](../ops/01-rotate-db-password.sh) 의 DB 비밀번호와
똑같은 일이 벌어진다 — **파일을 지워도 이력에는 남고**, 유일한 해결책은 전량 재발급이다.

## 6.1 같은 부류의 나머지 — 코드에 박힌 배포 정보

인증서 경로를 정리하다 보니 같은 성격의 것들이 더 있었다. 전부 "이 코드는 한 사람의 한 환경에서만
돌아간다"는 뜻이다.

| 위치 | 있던 것 | 바꾼 것 |
|---|---|---|
| `RegistrationListener` | 가입 확인 메일 링크에 특정 배포 호스트명 하드코딩 | `${app.public-url:https://localhost:8000}` |
| `*-outbox-connector-postgresql.json` | `"database.hostname": "<공개 호스트>"` | Connect 파일 config provider 참조(`db-host`) |

가입 확인 링크가 특히 나빴다. 어느 환경에서 실행하든 링크가 그 한 호스트를 가리켰으므로,
로컬이나 스테이징에서 가입한 사용자는 **계정을 활성화할 방법이 없었다.** 그 호스트가 내려가면
운영에서도 마찬가지다.

커넥터의 호스트명은 비밀번호만큼 치명적이지는 않다. 다만 저장소를 읽는 누구에게나
"이 주소의 5435 포트에 DB 가 있다"고 알려 주는 정보이므로, 공짜로 정찰 비용을 낮춰 줄 이유가 없다.
**이미 공개된 이력은 되돌릴 수 없으니**, 그 호스트의 방화벽 점검은 여전히 필요하다
([docs/13 §1](13-secrets-dbha-es-search.ko.md)).

## 7. 남은 것

- **HSM/KMS 연동** — 개인키가 여전히 디스크에 평문으로 있다. 운영에서는 cert-manager +
  Vault PKI 또는 클라우드 KMS 로 옮기는 것이 정석이다. 이 스크립트는 그 전 단계를 메운다.
- **인증서 로테이션 자동화(k8s)** — cert-manager 를 도입하면 `Certificate` 리소스 선언만으로
  발급·갱신·Secret 반영이 자동화된다. 현재는 훅 + `rollout restart` 로 대체한다.
- **개별 서비스 인증서** — 지금은 모든 서비스가 SAN 이 넉넉한 **하나의** 인증서를 공유한다.
  운영에서는 서비스마다 별도 신원을 주는 편이 낫다(`SANS=` 로 서비스별 발급 가능). 서비스 간 mTLS(docs/57 §16)가 이 인증서를
  클라이언트 인증서로 쓰기 시작했으므로, 서비스별로 나누면 `internal-auth.allowed-subjects` 로 "어느 서비스인가" 까지 가를 수 있다.
