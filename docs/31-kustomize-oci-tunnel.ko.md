# 31. 환경 분리 · OCI Ampere · Cloudflare Tunnel

[日本語](31-kustomize-oci-tunnel.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 일부 낡음.** OCI 운영은 Cloudflare Tunnel 뒤에서 돌고 있습니다. 배포는 `ops/28` 과 `deploy-oci.yml` 입니다([docs/36](36-deployment-pipeline.ko.md)).

세 가지를 한 묶음으로 만들었다. 셋 다 "노트북에서만 돌던 것을 실제로 어딘가에 띄운다"는
같은 목표를 향한다.

1. **kustomize 오버레이** — dev/prod 차이를 매니페스트 복제 없이
2. **OCI Ampere k3s 부트스트랩** — arm64 무료 인스턴스 한 대에
   (⚠️ **2026-06-15 부로 무료 한도가 2 OCPU/12GB 로 반토막 났다.** prod 오버레이는
    물론 external 도 들어가지 않는다 — `k8s-overlays/oci` 를 쓴다. 계산은 docs/32 §2)
3. **Cloudflare Tunnel** — 인바운드 포트를 하나도 열지 않고 외부 노출

---

## 1. 왜 ALB + ACM 이 아니라 Cloudflare Tunnel 인가

요청은 "Cloudflare Tunnel **또는** ALB → Ingress + ACM"이었다. 배포 대상이 OCI Ampere 인
이상 선택지가 아니다.

| | ALB + ACM | OCI LB | Cloudflare Tunnel |
|---|---|---|---|
| OCI 인스턴스에 붙나 | ❌ AWS 전용 | ✅ | ✅ |
| 인바운드 포트 | 80/443 열어야 함 | 열어야 함 | **0개** |
| TLS | ACM (AWS 안에서만) | 직접 발급·갱신 | **Cloudflare 가 종단** |
| 무료 티어 | ALB 는 유료 | 10Mbps 제한 | 무료 |
| 공인 IP 변경 | 신경 써야 함 | 신경 써야 함 | 무관 |

ALB + ACM 은 **EKS 로 옮길 때** 의미가 생긴다. 그때는 AWS Load Balancer Controller 와
`ingressClassName: alb`, ACM ARN 어노테이션이 필요하고, 지금 만든 것과 겹치지 않는다.

터널의 대가도 분명하다:

- **Cloudflare 의존.** 엣지 장애가 곧 서비스 장애다
- **무료 플랜 업로드 100MB 제한.** 상품 이미지가 그보다 크면 413 이 나가는데,
  **애플리케이션 로그에는 아무것도 남지 않는다** — 엣지에서 잘리기 때문이다
- 클러스터 안으로는 평문 HTTP 로 들어온다(터널 구간 자체는 암호화되어 있다)

---

## 2. 오버레이 구조 — 그리고 왜 이렇게 생겼나

```
k8s/                        매니페스트 원본 + kustomization.yaml (베이스)
k8s-overlays/dev/           레플리카 1, 요청 축소, 로컬 이미지
k8s-overlays/prod/          replicas 삭제(HPA 소유), 레지스트리 이미지, cloudflared
k8s-overlays/external/      prod 상속 + 관리형 PG/Mongo 엔드포인트 (docs/32)
k8s-overlays/oci/           external 상속 + 2 OCPU/12GB 로 조이기 (docs/33 §4)
```

`k8s/base/` + `k8s/overlays/` 라는 표준 배치가 **아니다.** 두 번 막혔다.

**(1) kustomize 는 kustomization 디렉터리 밖의 낱개 파일을 참조하지 못한다.**

```
security; file '.../k8s/40-user-service.yaml' is not in or below '.../k8s/base'
```

그래서 베이스를 `k8s/base/` 에 두려면 매니페스트를 전부 옮겨야 한다. 그런데 이 저장소는
`ops/13-k3s-deploy.sh`·`k8s/README.md`·docs 등 **50곳 가까이가 `k8s/40-user-service.yaml`
같은 경로를 직접 참조**한다. 전부 고치는 비용이 오버레이를 옮기는 비용보다 크다.

**(2) 베이스를 `k8s/` 에 두면 오버레이가 그 안에 있을 수 없다.**

```
cycle detected: candidate root '.../k8s' contains visited root '.../k8s/overlays/dev'
```

베이스 루트가 오버레이를 포함하면 순환으로 본다. 그래서 오버레이를 `k8s-overlays/` 로 뺐다.
이름을 `k8s-` 로 시작하게 해서 정렬상 바로 옆에 오도록 했다.

### 대가 하나 — `kubectl apply -f k8s/` 가 안 된다

`k8s/kustomization.yaml` 이 생겼으므로 kubectl 이 그걸 리소스로 읽으려다 실패한다.
개별 파일 적용은 그대로 된다. `ops/13-k3s-deploy.sh` 는 개별 파일을 적용하므로 영향이 없고,
`ops/19` 의 안내 문구와 `k8s/README.md` 만 고쳤다.

### 오버레이가 담지 못한 것

`90-dev-infra.yaml`(dev 인프라), `70-autoscaling.yaml`(HPA·PDB), `85-backup-cronjobs.yaml`
은 **낱개 파일이라 오버레이에서 참조할 수 없다**(위 (1)과 같은 제약). 복제하면 반드시
원본과 어긋나므로 따로 적용한다:

```bash
# 개발
kubectl apply -f k8s/90-dev-infra.yaml
kubectl apply -k k8s-overlays/dev

# 운영
kubectl apply -k k8s-overlays/prod
kubectl apply -f k8s/70-autoscaling.yaml
kubectl apply -f k8s/85-backup-cronjobs.yaml
```

---

## 3. dev 와 prod 의 실제 차이

| | dev | prod |
|---|---|---|
| 레플리카 | 전부 1 | **필드 삭제** — HPA 가 소유 |
| CPU 요청 | 50m | 250m (베이스) |
| 메모리 요청 | 256Mi | 384~512Mi (베이스) |
| limits | **건드리지 않음** | 베이스 |
| 이미지 | `ecommerce-msa/*:local` | `ghcr.io/OWNER/ecommerce-msa/*:1.0.0` |
| imagePullPolicy | IfNotPresent | 베이스 |
| 외부 노출 | port-forward | Cloudflare Tunnel |

**요청 합계: dev 600m / 3.0Gi.** 24GB 노트북에서 인프라까지 함께 돌아간다.

### requests 만 낮추고 limits 는 그대로 두는 이유

흔한 실수가 둘을 같이 낮추는 것이다. `requests` 는 스케줄러가 **자리를 잡아 두는** 값이라
낮추면 더 많은 파드가 한 노드에 들어간다. `limits` 는 **실제로 쓸 수 있는 상한**이라
낮추면 JVM 이 힙을 잡다 OOMKilled 된다. 목적이 "다 뜨게 하는 것"이면 requests 만 낮추는
것이 맞다.

### prod 에서 replicas 를 삭제하는 이유

HPA 가 관리하는 Deployment 에 `replicas` 가 남아 있으면 **매 `kubectl apply` 마다
매니페스트 값으로 되돌아갔다가 HPA 가 다시 끌어올린다.** 트래픽이 몰려 6개로 늘어난 상태에서
배포하면 2로 떨어졌다가 다시 올라가고, 그 사이가 장애다.

`eureka-server`(피어 복제 미설정)와 `chat-service`(WebSocket 세션이 파드에 붙어 있다,
docs/21)는 HPA 가 없으므로 replicas 를 남겨 둔다.

### dev 를 1로 두면 못 잡는 버그가 있다

이 저장소가 레플리카 2에서 겪은 문제들 — 아웃박스 폴러가 레플리카마다 같은 이벤트를 발행,
SMS·메일 디스패처가 잠금 없이 같은 행을 두 번 집음(docs/21, docs/29) — 은
**dev 에서 절대 재현되지 않는다.** 동시성은 prod 오버레이나 `ops/12-staging-rehearsal.sh`
로 확인해야 한다.

---

## 4. OCI Ampere — 가장 많이 막히는 지점

`ops/23-oci-ampere-bootstrap.sh`.

**OCI 의 Ubuntu·Oracle Linux 이미지는 인스턴스 안에 iptables REJECT 규칙을 미리 넣어 둔다.**
22번 외 거의 모든 입력을 막는데, 이게 파드 네트워크(CNI)까지 막는다. 증상이 엉뚱하다:

- 파드는 `Running` 인데 서로 통신이 안 됨
- CoreDNS 가 `CrashLoopBackOff`
- `kubectl exec` 이 타임아웃

"k3s 가 이상하다"로 보이지만 원인은 방화벽이다. 스크립트는 REJECT 를 **걷어내지 않고**
파드/서비스 CIDR(`10.42.0.0/16`, `10.43.0.0/16`)만 명시적으로 허용한다 — 다 지우면
인스턴스가 그대로 노출된다. 그리고 `netfilter-persistent` 로 저장한다(안 하면 재부팅 후
"어제는 됐는데"가 된다).

그 밖에:

- `vm.max_map_count=262144` — 없으면 Elasticsearch 가 부트스트랩 체크에서 즉시 죽는다
- `--disable servicelb` — 터널로 들어오므로 LoadBalancer 가 필요 없다. 켜 두면 klipper-lb 가
  80/443 을 잡으려다 실패한다
- metrics-server 는 **끄지 않는다** — prod 오버레이의 HPA 가 쓴다
  (다만 2 OCPU 박스에서는 HPA 를 적용하지 않는다 — docs/33 §4)
- 이미지 GC 임계값을 70/50 으로 — 프리티어 부트볼륨이 작아 이미지가 쌓이면 kubelet 이
  파드를 evict 하기 시작한다

**Security List 는 스크립트가 손댈 수 없다**(콘솔 영역). 다만 터널을 쓰므로 **22번만
열려 있으면 된다.** 그게 이 조합의 핵심이다.

### arm64 이미지

`ops/19-build-images.sh` 가 이미 처리한다 — 별도 스크립트를 만들지 않았다.
**Ampere 인스턴스 위에서 그냥 돌리면 네이티브 arm64 라 에뮬레이션이 없다:**

```bash
ssh ubuntu@<Ampere-IP>
./ops/19-build-images.sh --registry ghcr.io/USER --push    # --platform 불필요
```

느려지는 조합은 **amd64 를 arm 에서, 또는 그 반대로** 만들 때뿐이고, 그마저도 jar 를 한 번만
빌드하도록 되어 있어 짧다(`ops/Dockerfile.runtime` 주석).

---

## 5. 검증한 것과 하지 않은 것

**검증함**

| | |
|---|---|
| `kustomize build k8s-overlays/dev` | 27개 리소스, Deployment 12개 전부 replicas=1, 요청 600m/3.0Gi |
| `kustomize build k8s-overlays/prod` | 29개 리소스, HPA 대상 10개에서 `replicas` 필드 삭제 확인, cloudflared 포함 |
| 셸 문법 | `bash -n` — ops/19, 23, 24 |
| 네임스페이스 | 두 오버레이 모두 `ecommerce` 로 통일 |

**검증하지 않음 (환경이 없다)**

- **OCI Ampere 인스턴스에서 `ops/23` 을 실제로 돌려 보지 않았다.** iptables 처리와 k3s
  설치 플래그는 문법과 문서 기준으로 작성했고, 실제 하드웨어에서의 동작은 미확인이다
- **Cloudflare 터널을 실제로 연결해 보지 않았다.** 계정·도메인이 필요하다
- `kubectl apply` 자체 — 클러스터가 없다. `kustomize build` 결과가 유효한 매니페스트인
  것까지만 확인했다

특히 `ops/23` 의 iptables 규칙은 **`--check` 로 먼저 진단만 돌려 보고** 적용하기를 권한다.

---

## 6. 순서

```bash
# ── OCI 인스턴스에서 ──
sudo bash ops/23-oci-ampere-bootstrap.sh --check    # 진단 먼저
sudo bash ops/23-oci-ampere-bootstrap.sh

./ops/19-build-images.sh --registry ghcr.io/USER --push   # 네이티브 arm64

kubectl apply -f k8s/20-secret.yaml                 # docs/13
kubectl apply -k k8s-overlays/prod
kubectl apply -f k8s/70-autoscaling.yaml

# ── 로컬에서 (kubectl 이 OCI 를 가리킨 상태) ──
ops/24-cloudflare-tunnel.sh --hostname shop.example.com
ops/24-cloudflare-tunnel.sh --status

# ── 마지막: 열어 둔 인바운드 포트 닫기 ──
# OCI 콘솔 → VCN → Security List → 80/443 삭제 (22만, 가능하면 본인 IP 로 제한)
```

`ops/24` 는 **`kubectl apply -k k8s-overlays/prod` 뒤에** 돌려야 한다. ConfigMap
(`cloudflared-config`)이 먼저 있어야 터널 ID 를 치환할 수 있다. 순서가 바뀌면 경고를 내고
다시 실행하라고 알려 준다.
