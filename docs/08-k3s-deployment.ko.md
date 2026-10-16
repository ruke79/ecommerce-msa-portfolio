# k3s 배포 매니페스트 추가

[日本語](08-k3s-deployment.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 이전 기록.** 첫 매니페스트를 만든 기록입니다. 지금 배포는 kustomize 오버레이 · Cloudflare Tunnel · `ops/28` 입니다([31](31-kustomize-oci-tunnel.ko.md) · [33](33-deploy-runbook.ko.md) · [36](36-deployment-pipeline.ko.md)).

> 작성일: 2026-07-24 · 관련 산출물: [`k8s/`](../k8s/README.ko.md)

기존 `docker-compose-core.yml` / `docker-compose-services-1.yml` / `docker-compose-services-2.yml`은
k3s(Kubernetes)에서 직접 실행할 수 없습니다. 이 문서는 그 간극을 메우기 위해 추가한
순수 Kubernetes YAML 매니페스트(`k8s/`)에 대한 요약이며, 상세 사용법은 [`k8s/README.md`](../k8s/README.ko.md)에 있습니다.

## 범위 결정

사용자와 협의해 다음으로 범위를 정했습니다:
- **형식**: Helm/Kustomize가 아닌 **순수 K8s YAML 매니페스트** (바로 `kubectl apply -f`로 배포 가능, 별도 도구 불필요).
- **대상**: 애플리케이션 계층만(eureka/gateway/7개 백엔드 서비스/frontend). Kafka/PostgreSQL/Redis/MongoDB/
  Elasticsearch 같은 상태 저장 인프라는 이미 클러스터 또는 외부에 존재한다고 가정하고, 접속 정보만
  ConfigMap/Secret으로 주입합니다.

## 핵심 설계

- 모든 서비스가 `application-local.yml`을 그대로 두고 **OS 환경변수(Spring relaxed binding)만으로**
  설정을 주입받도록 이미 구성되어 있다는 점을 활용해, **애플리케이션 코드/이미지를 전혀 수정하지 않고**
  배포 가능하게 만들었습니다. (기존 docker-compose의 `environment:` 블록과 동일한 메커니즘을 k8s
  ConfigMap/Secret으로 옮긴 것)
- 앞선 작업에서 백엔드에 추가한 liveness/readiness actuator 프로브([`docs/02`](02-improvements.ko.md))를
  그대로 k8s의 `livenessProbe`/`readinessProbe`에 연결해, 문서화한 개선과 배포 매니페스트가 실제로
  맞물리도록 했습니다.
- 게이트웨이는 자체적으로 mTLS를 종료하므로 표준 Ingress(HTTP 프록시가 기본 동작) 대신
  `Service type: LoadBalancer`(k3s 내장 ServiceLB)로 직접 노출. frontend만 Traefik Ingress로 노출.
  > **2026-10-14 변경:** 게이트웨이 Service 는 `ClusterIP` 입니다. 운영은 Cloudflare 터널로 들어오고(`--disable servicelb`),
  > LoadBalancer 는 외부 IP 없이도 nodePort 를 받아 모든 노드의 그 포트를 열었습니다(B-85). 직접 붙을 때는
  > `kubectl -n ecommerce port-forward svc/gateway-server 8000:8000`.

## 조사 중 발견해 반영한 기존 이슈

매니페스트를 작성하며 애플리케이션 설정을 검토하다가, 이번 배포 작업의 범위를 넘어서는(코드 수정이
필요한) 기존 결함 몇 가지를 추가로 발견했습니다. 코드는 건드리지 않고 k8s 환경변수로 우회했으며,
근본 수정이 필요한 항목은 `k8s/README.md`의 "알려진 이슈" 표에 정리했습니다:

- `feign.catalog-url` / `feign.cart-url` / `feign.user-url`이 어떤 yml/compose에도 정의돼 있지 않아
  cart/order/coupon/chat/user-service가 현재 상태로는 기동 시 플레이스홀더 해석 실패로 죽는
  잠재적 문제 — ConfigMap에 클러스터 DNS 주소로 채워 해결.
- chat-service의 datasource가 `paydb`를 가리키고 hibernate dialect도 MySQL로 되어 있는 복붙 오류 —
  환경변수로 `chatdb` + PostgreSQL dialect로 바로잡음.
- catalog-service의 ES TLS 트러스트스토어 경로에 공백이 섞여 있어 classpath 리소스로 해석되지 않는
  문제(`docs/06-catalog-search-issues.md`와 연관) — 첫 배포는 ES를 http로 우회 접속.

## 다음 단계

`k8s/README.md`의 "향후 개선 방향"(HPA/PDB/NetworkPolicy/인프라의 클러스터 내 편입/Ingress TLS
passthrough/Secret 관리 도구화) 참고.
