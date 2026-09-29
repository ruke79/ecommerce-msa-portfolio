# 품질 하드닝 라운드 (테스트 · OpenAPI · 오토스케일 · 프론트 빌드 검증)

[日本語](10-quality-hardening.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 이전 기록.** Testcontainers · NetworkPolicy · HPA 는 이후 도입했습니다([17](17-testing.ko.md) · [57](57-remaining-priorities.ko.md) · [21](21-horizontal-scaling.ko.md)).

> 작성일: 2026-07-27 · 배경: MSA 최종 점검에서 확인된 공백(G11~G13, G18) 중 당시 검증 환경에서 안전하게 처리 가능한 항목의 적용 기록

## 1. 핵심 경로 단위 테스트 (G11 일부 해소)

이번 작업에서 수정한 버그들이 다시 돌아오지 못하도록 **회귀 테스트를 고정**했다.

| 테스트 | 지키는 것 |
|---|---|
| `catalog-service` `ProductServiceUnitTest` | ① **다중 항목 주문의 전 항목 재고/판매량 반영**(CAT-13 — 기존엔 첫 항목만 처리) ② null 요청 무해성 ③ **캐시 무효화 계약**: `productUpdated`/`addProduct`에 `@CacheEvict(search_cache…, allEntries)`가 자기호출 문제를 피해 외부 진입점에 붙어 있는지(CAT-2/12) |
| `catalog-service` `SearchServiceUnitTest` | ① `page=0` 기본 요청이 500 없이 offset 0/limit 20으로 클램프(CAT-3) ② `pageSize=99999` → 상한 100(deep pagination DoS 방지) ③ category **이름** 입력 시 NumberFormatException 없이 이름 경로(CAT-4) ④ 숫자면 id 경로 |
| `gateway-server` `CustomUserKeyResolverTest` | 레이트리미터 버킷 키가 user→XFF→소켓IP→anonymous 순으로 폴백하며 **어떤 경우에도 null이 아님**(기존 NPE 버그) |

여전히 부족한 것(정직하게): cart/order/pay의 사가·아웃박스 경로, 프론트 401 인터셉터는 미커버. 통합 테스트(Testcontainers)와 계약 테스트는 로드맵(G14).

## 2. OpenAPI 도입 (G13 해소)

- MVC 서비스 6개(user/catalog/cart/order/pay/coupon)에 `springdoc-openapi-starter-webmvc-ui:2.8.5` 추가 → 각 서비스에서 `/v3/api-docs`, `/swagger-ui.html` 제공.
- **chat-service의 springfox 3.0 제거**: Spring Boot 3와 호환되지 않는 사장 라이브러리였고, Boot 2.6용 우회 핵(`springfoxHandlerProviderBeanPostProcessor` 리플렉션 BeanPostProcessor)까지 함께 있었다. springdoc으로 교체하고 해당 Config 클래스 삭제.
- 참고: 게이트웨이(WebFlux) 통합 문서 집계는 별도 작업(로드맵). API 버저닝(G12)은 URL 계약 변경이라 이번 범위에서 제외.

## 3. k8s 오토스케일/무중단 (G18 일부 해소)

`k8s/70-autoscaling.yaml`:
- **HPA**(CPU 70%, 1→3): gateway + 6개 백엔드 + frontend. k3s 내장 metrics-server 사용.
- **의도적 제외**: chat-service(인메모리 STOMP 브로커 — 스케일아웃 시 메시지 파편화, docs/03 F-7), eureka(peer-awareness 선행 필요).
- **PDB**(`maxUnavailable: 1`): 전 서비스. 레플리카 1일 땐 드레인을 막지 않고, HPA로 늘어난 뒤부터 최소 가용성 보장이 실질 동작.
- NetworkPolicy는 여전히 로드맵.

## 4. 프론트엔드 프로덕션 빌드 검증

- `npm install` 후 `npm run build`(webpack prod) **성공** — 이전 라운드의 프론트 리팩토링(tokenUtil 제거, axios 래퍼 재작성, TokenRefresher 401 큐 수정, 의존성 제거)이 번들 수준에서 검증됨.
- `package-lock.json`을 `package.json` 변경(save-dev/toast 제거)과 **동기화**하여 커밋(기존엔 어긋나 `npm ci` 실패 상태였음).
- 경고: `main.bundle.js` 1.92MiB — 코드 스플리팅 여지(로드맵).

## 검증 요약

- 신규 테스트 3클래스 **11케이스 전부 Gradle 실행 통과** (catalog 7 + gateway 4, failures 0).
- springfox→springdoc 교체 포함 전 서비스 `compileJava` + catalog `compileTestJava` **BUILD SUCCESSFUL**.
- 프론트 `npm run build`(webpack production) **성공**, `package-lock.json` 동기화.
- `70-autoscaling.yaml` 17개 리소스(HPA 8 + PDB 9) 파싱 검증.

### 함께 발견/수정한 것

- **레거시 테스트 4파일이 컴파일조차 불가능한 상태**였음이 드러남(존재하지 않는 `repository.*` 패키지의 와일드카드 import — 패키지 재구성 때 방치된 것). 죽은 import 2줄을 제거해 컴파일은 복구했다.
- 단, 레거시 `@SpringBootTest`류(컨텍스트 로드에 실제 DB/ES/Kafka 필요)는 당시 검증 환경에서 **실행은 불가**하며, CI에서도 인프라 없이 실패한다 → Testcontainers 전환이 필요(로드맵). 신규 테스트는 순수 단위 테스트라 어디서든 돈다.
