# 43. 데이터 로딩 전략 점검 — 프로필 화면 · 홈 화면 · 전역

[日本語](43-loading-strategy-review.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 이전 기록.** 17건 모두 고쳤습니다(맨 위 진행표). 아래 "요약" 표의 "미착수" 는 작성 당시입니다.

조사 범위: 프론트엔드 전체의 데이터 로딩 경로(라우터 loader, React Query, useEffect fetch)와
그에 대응하는 백엔드 엔드포인트. 2026-09 기준.

이 문서는 **확인한 것만** 적는다. 확인하지 못한 것은 "미확인"이라고 명시한다.

---

## 진행 상황 (2026-09-15)

| 항목 | 상태 | 커밋 |
|---|---|---|
| A1 `/profile` 본문 없음 | ✅ 완료 | `a5ad644` |
| A2 프로필 이미지 변경 불가 | ✅ 완료 — GET/PUT/POST/DELETE 추가 | `a5ad644` |
| A3 사이드바의 `user.image`/`username` | ✅ 완료 — Layout 이 프로필을 받아 다섯 탭에 전달 | `f653e80`, `a5ad644` |
| B1 `findByActive` 전역 조회 | ✅ 완료 — 안전하지 않은 메서드를 제거해 되돌리면 컴파일이 안 된다 | `0ed948e` |
| B2 주소 삭제 IDOR | ✅ 완료 | `0ed948e` |
| B3 개인정보 복합 인덱스 | ✅ 완료 — V7 에서 DROP | `0ed948e` |
| B4 `@Valid` 없음 | ✅ 완료 | `0ed948e` |
| B5 builder+deepCopy 중복 등 | ✅ 완료 — 매핑을 `ShippingAddress.from` 하나로 모으고 가드 테스트 | |
| C1 격자가 캐러셀을 덮음 | ✅ 완료 — 겹침을 배너 높이에 비례시킴 | `8d8e469` |
| C2 배너 로드 순서 | ✅ 완료 — loader 로 이동 + 높이 예약 | `8d8e469` |
| D1 커서 페이징을 프론트가 안 부름 | ✅ 완료 (상품·주문 두 화면) | `2956937` |
| D2 관리자 상품 목록 전체 로드 | ✅ 완료 — 관리자 전용 경로로 이동까지 | `2956937` |
| D3 주문 목록 무제한 | ✅ 완료 — N+1 과 null 섞임도 함께 | `2956937` |
| D4 검색 응답마다 카테고리 전체 | ✅ 완료 — 첫 페이지에서만 받는다 | 이 커밋 |
| D5 로딩 상태 표시 | ✅ 완료 — 전역 진행 막대 + 필터 중 흐림 + 스켈레톤 | |
| E1 햄버거 메뉴 하드코딩 | ✅ 완료 | `7ed471e` |
| E2 메뉴의 정적 "Hello, sign in" | ✅ 완료 | `7ed471e` |
| E3 Amazon 상표 | ✅ 완료 — 가드 테스트 포함 | `7ed471e` |

---

## 요약

| # | 증상 / 위험 | 심각도 | 상태 |
|---|---|---|---|
| A1 | `/profile` 본문에 아무것도 없다 — 만들어진 적이 없다 | 높음(기능 없음) | 미착수 |
| A2 | 프로필 이미지 변경 기능이 프론트·백 양쪽에 없다 | 중간 | 미착수 |
| A3 | 사이드바가 `user.image` / `user.username` 을 읽는데 둘 다 존재하지 않는 필드 | 높음 | 미착수 |
| B1 | **`findByActive(true)` 가 전 테이블 조회다 — 남의 기본배송지를 끈다** | **치명** | 미착수 |
| B2 | **주소 삭제에 소유권 검사가 없다 (IDOR)** | **치명** | 미착수 |
| B3 | 이름+주소 전체에 걸린, 아무도 쓰지 않는 복합 인덱스 | 중간(개인정보) | 미착수 |
| B4 | `@Valid` 없음 → 검증이 Hibernate flush 시점으로 밀린다 | 중간 | 미착수 |
| C1 | 홈: 상품 격자가 캐러셀을 덮는다 (고정 음수 마진 + 유동 높이) | 중간(UI) | 미착수 |
| C2 | 홈: 배너를 마운트 후에 받아 와 이미지가 두 번 바뀐다 → 레이아웃 점프 | 중간(UI) | 미착수 |
| D1 | **백엔드에 커서 페이징이 이미 있는데 프론트가 한 곳도 부르지 않는다** | 높음 | 미착수 |
| D2 | 관리자 상품 목록이 전 카탈로그를 한 번에 받는다 | 높음 | 미착수 |
| D3 | `GET /order/orders` 가 주문 전체를 무제한으로 돌려준다 | 높음 | 미착수 |
| D4 | `/api/search` 응답마다 카테고리·서브카테고리 전체가 실려 온다 | 중간 | 미착수 |
| D5 | 로딩 상태 표시가 거의 없다 — 라우터 loader 가 화면 전환을 통째로 막는다 | 중간 | 미착수 |
| E1 | 햄버거 메뉴가 100% 하드코딩 — 실제 카테고리를 한 건도 읽지 않는다 | 높음 | 미착수 |
| E2 | 메뉴에 `Hello, sign in` 이 정적 문자열로 박혀 있다 (로그인해도 그대로) | 중간 | 미착수 |
| E3 | **공개 배포된 사이트에 Amazon 로고·상표·약관 문구가 그대로 남아 있다** | **법적 위험** | 미착수 |

`/browse` 무한 스크롤은 이 조사 도중 별도로 처리했다(커밋 `0c30f23`).

---

## A. 프로필 화면

### A1. `/profile` 본문은 제목뿐이다

`frontend/src/pages/profile/profile.jsx`:

```jsx
<Layout user={user} tab={0} title={`${user.email}'s Profile`}>
    <div className="text-center">
        <h2 className="text-4xl font-bold mb-6">My Profile</h2>
    </div>
</Layout>
```

본문에 `<h2>` 하나뿐이다. **버그가 아니라 만들어진 적이 없다.** loader 는 `tab` 값을
계산해 돌려주지만 컴포넌트에서 쓰는 줄이 주석 처리돼 있다.

같은 `Layout` 을 쓰는 다른 탭(`address` · `orders` · `payment` · `security`)은 내용이 있다.
`/profile` 만 껍데기다.

### A2 / A3. 프로필 이미지

`components/profile/sidebar/Sidebar.jsx:9`:

```jsx
<img src={data.image} alt={data.username} ... />
```

`data` 는 `useAuthContext().user` 이고, 그 값은 `util/jwtUser.js` 의 `toUser()` 가 만든다:

```js
export function toUser(decodedToken) {
  return {
    email: decodedToken?.sub,
    userId: decodedToken?.uid ?? null,
    roles: normalizeRoles(decodedToken),
  };
}
```

**`image` 도 `username` 도 없다.** 그래서

- `<img src={undefined}>` → React 가 `src` 속성을 아예 빼고 렌더 → 깨진 이미지 자리
- `<span>{data.username}</span>` → 빈 칸

사용자 이름이 사라지고 아바타가 깨져 보이는 원인이 이것이다. 아이콘 404 와는 무관하다.

백엔드 쪽:

- `User.image` 컬럼과 `UserProfileDto.image` 는 **있다**
- 그런데 `UserController` 의 엔드포인트는 `/address` · `/save_address` · `/select/{id}` ·
  `/delete/{id}` · `/payment` · `/changepm` · `/update-password` 가 전부다
- **프로필을 통째로 돌려주는 GET 이 없고, 이미지를 바꾸는 엔드포인트도 없다**
- `user-service` 에 `ImageService` 는 있지만 리뷰 이미지용이다

즉 "프로필 이미지를 변경할 수 없다"는 것은 UI 가 빠진 게 아니라 **경로 자체가 없다**.

### 고치려면

1. `GET /api/user/profile` 추가 → `UserProfileDto.toDto(user, roles)` (이미 있다)
2. 프론트에 `useFetchProfile()` 훅 추가, `Layout` 에 JWT 가 아니라 이 응답을 넘긴다
   — JWT 는 신원 증명이지 프로필 저장소가 아니다. 이름을 바꿔도 토큰은 그대로다
3. `Sidebar` 에 이미지가 없을 때의 폴백(이니셜 원형 등) 추가 — 값이 없는 상태는 정상이다
4. 이미지 변경은 `POST /api/user/profile/image` (multipart) → `ImageService` 재사용

---

## B. 주소 저장 — 실무 관점

`user-service/.../service/AddressService.java` 를 읽은 결과. 두 가지는 **지금 당장 고쳐야 한다.**

### B1. 🔴 `findByActive(true)` 는 사용자 범위가 없다

`ShippingAddressRepository`:

```java
Optional<ShippingAddress> findByActive(boolean active);
```

`selectShipAddress()` 안에서:

```java
ShippingAddress currActiveAddress = shippingAddressRepository.findByActive(true).orElse(null);
if (currActiveAddress != null && !Objects.equals(currActiveAddress.getShippingAddressId(), ...)) {
    currActiveAddress.setActive(false);
    shippingAddressRepository.save(currActiveAddress);
}
```

이 쿼리는 `where active = true` 다. **`user_id` 조건이 없다.** 결과:

1. 사용자가 둘 이상 기본배송지를 갖는 순간, `Optional` 로 선언된 쿼리가 두 행을 만나
   Spring Data 가 `IncorrectResultSizeDataAccessException` 을 던진다 →
   컨트롤러의 `catch (RuntimeException)` 에 걸려 **400 "배송지를 선택할 수 없습니다"**.
   지금 사용자가 사실상 한 명이라 드러나지 않았을 뿐, 두 번째 사용자가 배송지를 고르는
   순간 재현된다.
2. 예외가 나기 전이라도 — 즉 전체에 active 가 딱 하나일 때 — 그 하나가 **다른 사람 것**이면
   남의 기본배송지를 꺼 버린다.

고치는 방법: `Optional<ShippingAddress> findByUser_UserIdAndActiveTrue(Long userId)`.
DB 쪽에도 부분 유니크 인덱스를 거는 게 맞다:

```sql
CREATE UNIQUE INDEX uq_shipping_address_active_per_user
    ON users.shipping_address (user_id) WHERE active;
```

(새 Flyway 버전 파일로. 이미 적용된 마이그레이션은 절대 수정하지 않는다 — 체크섬이 깨진다.)

### B2. 🔴 주소 삭제에 소유권 검사가 없다

```java
public void deleteShippingAddress(String username, String addressId) {
    Optional<User> user = userRepository.findByUsername(username);
    if (!user.isPresent()) return;

    Optional<ShippingAddress> deleteAddress = shippingAddressRepository.findById(Long.parseLong(addressId));
    if (deleteAddress.isPresent()) {
        user.get().getShippingAddresses().remove(deleteAddress.get());
        shippingAddressRepository.delete(deleteAddress.get());   // ← 남의 것이어도 지운다
    }
}
```

`user` 를 조회는 하지만 **그 주소가 이 사용자 것인지 확인하지 않는다.**
`remove(...)` 는 남의 것이면 아무 일도 하지 않고 조용히 지나가고, 그 다음 줄의 `delete()` 가
그냥 실행된다. 로그인한 아무나 주소 id 하나만 알면 남의 배송지를 지울 수 있다(IDOR).

TSID 라 id 추측이 어렵다는 건 완화 요인이지 대책이 아니다. `selectShipAddress` 는 (방식은
비효율적이지만) 사용자 주소 목록을 순회하며 확인하고 있다 — 삭제만 빠졌다.

고치는 방법:

```java
ShippingAddress target = shippingAddressRepository.findById(Long.parseLong(addressId))
        .filter(a -> Objects.equals(a.getUser().getUserId(), user.get().getUserId()))
        .orElseThrow(() -> new AccessDeniedException("본인의 배송지가 아닙니다."));
```

`selectShipAddress` 도 `findByUser_UserIdAndShippingAddressId(...)` 한 번으로 줄이는 게 맞다.

### B3. 이름 + 주소 전체에 걸린 복합 인덱스

`ShippingAddress`:

```java
indexes = { @Index(columnList = "firstname, lastname, address1, city, state, zipCode",
                   name = "idx_shipping_address") }
```

이 테이블에 실제로 나가는 쿼리는 `findByUser_UserId`, `findById`, `findByActive` 세 개뿐이다.
**이 인덱스를 타는 쿼리가 하나도 없다.** 대신:

- 쓰기마다 6개 컬럼짜리 인덱스를 갱신한다
- 개인정보(실명 + 전체 주소)를 정렬된 형태로 한 번 더 복제해 둔다. DB 덤프가 유출되면
  "이 사람 주소 찾기"가 인덱스 스캔 한 번이 된다

실무에서는 **지우는 게 맞다.** 정말 필요한 인덱스는 `user_id` 다(`@ManyToOne` 의 FK 에
자동으로 생기지 않는다 — PostgreSQL 은 FK 에 인덱스를 만들지 않는다).

### B4. `@Valid` 가 없다

```java
ResponseEntity<?> saveShippingAddress(@RequestBody AddressRequest request, ...)
```

`AddressRequest` · `AddressDto` 에는 제약이 하나도 없고, `@NotBlank` / `@Size` 는 **엔티티**에
붙어 있다. 그래서 검증이 컨트롤러 진입 시점이 아니라 **Hibernate flush 시점**으로 밀린다.
결과적으로 400 은 나가지만 메시지가 Hibernate 의 `ConstraintViolationException` 덤프다.
DTO 에 제약을 옮기고 `@Valid` 를 붙이는 게 맞다.

### B5. 그밖에 ✅

**① builder + deepCopy 중복** — `ShippingAddress.builder()...build()` 로 전 필드를 채운
**직후** `deepCopyShippingAddress()` 가 같은 필드를 다시 덮었다. 단순 중복으로 보이지만
함정이 있다: 나중에 **빌더에만** 필드를 추가하면 deepCopy 가 그것을 `null` 로 덮는다.
컴파일도 되고, 그 필드를 보지 않는 테스트는 전부 통과한다 — 새 주소 항목 하나가 조용히
사라지는 모양이다.

둘 중 하나를 지우는 것으로는 부족했다. 지우는 쪽을 잘못 고르면 다음 사람이 다시 짝을
맞춰 놓는다. 매핑을 **`ShippingAddress.from(AddressDto)` 하나**로 모으고, 리플렉션으로
"엔티티 필드 중 from 이 옮기지 않는 것"을 찾는 테스트를 붙였다. 필드가 늘어났는데 매핑을
안 고치면 거기서 먼저 막힌다. (실제로 `zipCode` 매핑을 지워 보고 테스트가 잡는 것을
확인했다.)

**② 불필요한 `save`** — `userRepository.save(user); // prevent java.stackoverflow`.
주석의 설명은 **사실이 아니다.** `user` 는 바로 위에서 `findByUsername` 으로 읽은 영속
상태 엔티티이고 이 클래스는 `@Transactional` 이라, 변경은 커밋 시점에 더티 체킹으로
반영된다. save 는 아무 일도 하지 않으면서 쿼리만 한 번 더 유발한다. 스택오버플로와는
관계가 없다 — 그건 양방향 연관을 직렬화할 때 나는 증상이고 `@JsonBackReference` 가 이미
막고 있다.

**③ 로그의 개인정보** — ⚠️ **이 문서의 원래 지적은 부정확했다.** "username 이 이메일이다"
라고 적혀 있었는데, 확인해 보니 그렇지 않다: 폼 가입은 사용자가 고른 이름
(`UserServiceImpl:411`), 소셜 가입은 `provider_providerId`
(`CustomOAuth2UserService:120`) 다. 이메일은 별도 컬럼이다.

그래도 고쳤다. 이메일이 아닐 뿐 **그 사람을 특정하는 값**이고, 배송지 저장·조회는 자주
일어나 INFO 한 줄이 그대로 쌓인다. 보안 알림·기기 인식 경로가 `userId` 만 남기는 것과
기준을 맞췄다. 사용자를 못 찾았을 때 username 을 남기던 `log.warn` 도 지웠다 — 그러면
"그 계정이 있는지"가 로그로 새어 나간다.

### B6. 문제 **없는** 것

주문이 배송지를 **스냅샷으로 복사**한다(`OrderDataService:198-211` → `OrderAddress`).
그래서 주소를 나중에 수정하거나 삭제해도 과거 주문의 배송지가 바뀌지 않는다.
이건 실무적으로 정확한 설계다. (반대로 FK 로 참조했다면 주소 삭제가 주문 이력을 망가뜨린다.)

---

## C. 홈 화면

### C1. 상품 격자가 캐러셀을 덮는다

`pages/home/HomeSections.jsx:38`:

```jsx
<div className="relative grid md:grid-cols-4 ... -mt-16 sm:-mt-32 md:-mt-48 lg:-mt-80 z-10 ...">
```

상단 GRID 섹션을 **고정 픽셀 음수 마진**으로 끌어올려 캐러셀 위에 겹친다(`lg:-mt-80` = -20rem
= -320px). 의도는 아마존식 "배너 하단에 상품 격자가 걸치는" 레이아웃이다.

문제는 **캐러셀 높이가 유동**이라는 점이다. 배너는 `<img>` 를 폭 100% 로 그리므로 높이는
이미지 종횡비 × 뷰포트 폭으로 결정된다. 음수 마진은 고정, 캐러셀 높이는 가변 →
브레이크포인트와 실제 이미지 비율이 어긋나는 구간에서 격자가 **배너 한가운데까지** 올라온다.

고치는 방법 (셋 중 하나):

- 캐러셀 컨테이너에 `aspect-ratio` 를 고정하고 음수 마진을 그 비율에 맞춰 계산
- 음수 마진 대신 캐러셀을 `position: relative`, 격자를 `position: absolute; bottom: 0` 로
  배너 **안쪽**에 배치 (겹침 폭이 배너 높이에 자동으로 따라간다)
- 겹치기를 포기하고 `mt-0` — 가장 단순하고 깨질 여지가 없다

### C2. 배너가 마운트 후에 바뀌어 레이아웃이 점프한다

`components/home/CarouselContainer.jsx`:

```js
const [banners, setBanners] = useState(FALLBACK);   // 번들에 박힌 이미지 5장

useEffect(() => {
    const loaded = await getBanners();              // 마운트 **후에** 원격 배너를 받는다
    if (...) setBanners(loaded);
}, []);
```

한편 상품 섹션은 **라우터 loader** 가 가져온다. 라우터는 loader 가 끝날 때까지 화면 전환을
막으므로, 첫 페인트 시점에 `home.sections` 는 **이미 있다.**

그래서 순서가 이렇게 된다:

1. loader 완료 → 페이지 렌더. 상품 이미지가 즉시 요청된다
2. 캐러셀은 번들 폴백 이미지로 그려진다 (번들이라 빠르다)
3. `getBanners()` 응답 도착 → **이미지가 원격 URL 로 통째로 교체** → 아직 로드 안 된 이미지라
   높이가 0 으로 무너졌다가 다시 커진다 → 아래 전체가 위아래로 튄다

"상품 이미지들이 움직이는 이미지보다 먼저 로드돼서 부자연스럽다"의 정확한 메커니즘이다.
폴백을 두는 판단 자체는 옳다(첫 화면이 비면 사이트가 고장 난 것처럼 보인다). 문제는
**교체가 레이아웃 높이를 바꾼다**는 것.

고치는 방법:

1. **배너를 홈 loader 로 옮긴다** — `Promise.allSettled([getHome(), getBanners()])`.
   그러면 첫 페인트에 최종 이미지가 들어가고 교체 자체가 없어진다. 실패하면 폴백을 쓴다
2. 배너 `<img>` 에 `width`/`height` 또는 컨테이너에 `aspect-ratio` 를 주어 **높이를 예약**한다.
   이것만으로도 교체 시 점프가 사라진다
3. 첫 장에 `fetchpriority="high"` + `loading="eager"`, 나머지에 `loading="lazy"`.
   지금은 상품 이미지와 배너가 같은 우선순위로 경쟁한다

---

## D. 전역 로딩 전략 — "한 번에 다 받는다"

사용자 지적이 정확하다. **점진적 로딩이 적용된 화면이 (방금 고친 `/browse` 를 빼면) 없다.**

### D1. 🔴 백엔드에 커서 페이징이 이미 있는데 프론트가 부르지 않는다

백엔드에 있는 것:

| 메서드 / 엔드포인트 | 위치 |
|---|---|
| `getOrdersByCustomerIdWithCursor(customerId, cursorId, size)` | `OrderService:116`, `OrderDataService:138`, `OrderCacheService:108` |
| `getOrdersByCustomerIdWithPagination(customerId, page, size)` | `OrderService:102` 외 2곳 |
| `GET /api/product/products/page?category=&cursorId=&pageSize=` | `ProductController:101` |
| `CursorPagenation<T>` 유틸 | `catalog-service/util/CursorPagenation.java` |

프론트에서 이것들을 부르는 코드: **0건** (`grep -rn "cursorId\|products/page" frontend/src` → 없음).

`useInfiniteScroll` 이 정의만 있고 아무도 안 쓰던 것과 **똑같은 패턴**이다. 점진적 로딩
기반은 세 계층(Service / DataService / CacheService)에 걸쳐 다 만들어 놓고 화면에 연결하지
않았다. 지금은 죽은 코드이고, 언제든 "있으니까 되겠지"로 오해될 수 있다.

### D2. 🔴 관리자 상품 목록이 전 카탈로그를 한 번에 받는다

`frontend/src/pages/admin/ProductList.jsx:19`:

```js
getRequest("/catalog-service/api/product/products")
```

→ `ProductController:84` → `productService.warmUpProductCaches(20)` →

```java
@Cacheable(value = "products", key = "'warmup'")
public List<ProductDto> warmUpProductCaches(int pageSize) {
    List<Category> allCategories = categoryRepository.findAll();
    for (Category category : allCategories) {
        for (Subcategory subcategory : subCategoryRepository.findByCategory(category)) { ... }
    }
}
```

이름은 "캐시 워밍"인데 화면 목록 조회에 쓰이고, **전 카테고리 × 전 서브카테고리를 돌아
모든 상품을 한 배열로** 돌려준다. 게다가 `@Cacheable(key = "'all'")` / `"'warmup'"` 이라
**카탈로그 전체가 Redis 키 하나**에 들어간다.

지금 상품이 250개라 견디고 있다. 규모가 커지면:

- 응답 JSON 이 수 MB → 게이트웨이 타임아웃, 브라우저 메모리
- Redis 키 하나가 수십 MB → `maxmemory` 정책에 따라 다른 키를 밀어내거나 이 키가 evict 되어
  매번 전 카탈로그를 다시 조회
- `ProductService:153` 의 `getProducts()` 도 같은 `findAll()` 이다

`/product/products/page` 가 이미 있으니 **화면을 그쪽으로 옮기면 된다.**

### D3. 🔴 주문 목록이 무제한이다

`GET /order-service/api/order/orders?filter=` → `getOrders(email, filter)` →
페이징 인자가 없다. 주문이 쌓이는 만큼 응답이 커진다. `/profile/orders` 화면은 그걸
그대로 다 그린다.

바로 옆에 `getOrdersByCustomerIdWithCursor` 가 있는데 쓰이지 않는다(D1).

### D4. 검색 응답마다 카테고리 전체가 실려 온다

`SearchService:149-150`:

```java
List<CategoryDto> categoryDtos = categoryRepository.findAll()...
List<SubCategoryDto> subCategoryDtos = subCategoryRepository.findAll()...
```

`/api/search` 는 **매 페이지 요청마다** 카테고리·서브카테고리 전체를 함께 내려준다.
무한 스크롤을 붙인 지금은 이게 더 눈에 띈다 — 2페이지, 3페이지를 받을 때도 똑같은
필터 메뉴 데이터가 반복해서 온다(프론트는 1페이지 것만 쓰고 나머지는 버린다).

필터 메뉴는 `GET /api/search/filters` 처럼 분리해 별도로 캐시하는 게 맞다.

### D5. 로딩 상태가 거의 없다 ✅

**🔴 핵심은 "덮지 않는다"였다.** 이 앱에는 이미 전체화면 오버레이(`DotLoaderSpinner`)가
있는데 호출부 **대부분에서 주석 처리돼 있었다.** 그럴 만한 이유가 있다 — 화면을 덮으면
읽던 내용이 사라지고 짧은 전환에도 깜빡인다. 그래서 다들 꺼 버렸고, 결과적으로 **아무
표시도 없는 상태**가 남았다. 같은 오버레이를 다시 켜는 것은 답이 아니다.

덮지 않는 세 가지를 넣었다.

**① 전역 진행 막대 (`NavigationProgress`)** — `Root.jsx` 에 있던
`{/* {navigation.state === 'loading' && <p>Loading...</p>} */}` 자리다. 라우터가 loader 를
기다리는 동안 위쪽에 얇은 막대를 그린다. 데이터 라우터는 로더가 끝나야 **URL 도 바꾸지
않으므로**, 이게 없으면 `/browse` 에서 필터를 눌러도 클릭이 먹었는지조차 알 수 없다.

⏱️ **200ms 를 기다렸다 보여 준다.** 캐시된 전환은 수십 ms 에 끝나고, 그때 막대를 띄우면
깜빡임만 남아 오히려 화면이 불안해 보인다 — 없는 것보다 나쁘다. `submitting` 도 함께
본다(form 제출도 사용자에겐 같은 기다림이다). `role="status"` + `aria-live` 로
스크린리더에도 알린다.

**② 필터 변경 중 목록 흐리게** — `/browse` 에서 필터를 바꾸는 동안 화면에 남아 있는
목록은 **바뀌기 전 결과**다. 지우거나 스켈레톤으로 바꾸지 않는다(읽던 것이 사라지고
레이아웃이 튄다). 흐리게 하고 클릭만 막으면 "곧 바뀔 값"이라는 뜻이 전달되면서 내용은
남는다. `aria-busy` 도 함께 세운다.

**③ 스켈레톤 (`Skeleton` · `ProductGridSkeleton`)** — 한 곳도 없던 것을 넣었다.
스켈레톤의 요점은 기다림을 감추는 게 아니라 **자리를 잡아 두는** 것이다. 실제 내용과 같은
크기를 차지하므로 데이터가 도착해도 아무것도 움직이지 않는다. 보여 줄 이전 결과가 없는
**첫 진입에만** 쓴다.

**접근성** — 두 애니메이션 모두 `prefers-reduced-motion: reduce` 에서 멈춘다. 움직임에
민감한 사용자에게 무한 반복 애니메이션은 불편을 넘어 증상을 유발할 수 있고, 정지한 회색
블록으로도 뜻은 충분히 전달된다.

### D6. 문제 **없는** 것

- 관리자 화면들은 `React.lazy` 로 코드 스플리팅돼 있다(`App.jsx:38-46`). 일반 사용자가
  관리자 번들을 받지 않는다 — 이건 제대로 돼 있다
- `GET /api/products/info?ids=...` 는 `MAX_BATCH_IDS = 100` 으로 상한이 있다

---

## E. 햄버거 메뉴 (`pages/header/MenuSidebar.jsx`)

### E1. 카테고리 목록이 전부 하드코딩이다

파일 101줄 전체에 **데이터를 읽는 코드가 한 줄도 없다.** `useSelector(selectMenu)` 로
열림/닫힘 상태만 읽고, 목록은 JSX 에 박혀 있다:

```jsx
<h3>Shop By Department</h3>
<ul className="border-b pb-2">
    <li className="group">Electronics <ChevronRightIcon /></li>
    <li className="group flex">Computers <ChevronRightIcon /></li>
    <li className="group">Smart Home <ChevronRightIcon /></li>
    <li className="group">Arts & Crafts <ChevronRightIcon /></li>
</ul>
```

`<li>` 에 `onClick` 도 `<Link>` 도 없다 — **클릭해도 아무 일도 일어나지 않는다.**
실제 카테고리(`books`, `fashion` 등, `product.category` 10건)와는 아무 관계가 없다.

쓸 수 있는 엔드포인트는 이미 있다:

- `GET /catalog-service/api/category/categories` (`CategoryController:116`)
- `GET /catalog-service/api/category/product/allsubcategories` (`CategoryController:151`)
- `GET /catalog-service/api/category/product/subcategories?category=` (`CategoryController:177`)

그리고 각 항목은 `/browse?category=<id>` 로 연결하면 된다 — `/browse` 의 `categoryHandler`
가 이미 그 파라미터를 처리한다.

메뉴는 홈에서 바로 열리므로 **라우터 loader 가 아니라 React Query 로** 가져오는 게 맞다
(`staleTime` 을 길게 — 카테고리는 거의 안 바뀐다). 메뉴를 처음 열 때 가져오면
(`enabled: menuOpen`) 첫 화면 로딩에 부담을 주지 않는다.

### E2. `Hello, sign in` 이 정적 문자열이다

`MenuSidebar.jsx:27`:

```jsx
<b className="text-xl font-bold ml-3">Hello, sign in</b>
```

로그인 여부를 보지 않는다. 로그인해도 계속 "sign in" 이라고 나오고, 링크도 없다.

헤더의 `Account.jsx:47` 은 **제대로 돼 있다**:

```jsx
<p className="text-xs text-slate-300">Hello, {user ? user?.email : "sign in"}</p>
```

즉 같은 화면 안에서 헤더는 로그인 상태를 반영하고 메뉴는 반영하지 않는다.
`useAuthContext()` 를 똑같이 쓰면 된다.

### E3. ⚠️ Amazon 상표·로고·약관 문구가 공개 사이트에 그대로 남아 있다

이 프로젝트는 아마존 클론 템플릿에서 출발했고, **브랜드 문자열이 교체되지 않은 채
`www.ysyproject.com` 에 배포돼 있다.** 확인된 것:

| 위치 | 내용 |
|---|---|
| `pages/user/SignInPage.jsx:6,196` | `assets/images/amazon-dark.png` 로고를 로그인 화면에 표시 |
| `pages/user/RegisterPage.jsx:5,131` | 같은 로고 |
| `pages/user/ForgotPage.jsx:4,75` | 같은 로고 |
| `SignInPage.jsx:289`, `RegisterPage.jsx:211` | "By continuing, you agree to **Amazon's Conditions of Use and Privacy Notice**." |
| `SignInPage.jsx:341,347` | "New to Amazon?" / "Create your Amazon account" |
| `pages/Footer.jsx:4` | `amazon-logo.png` |
| `pages/Footer.jsx:24-46` | "About Amazon", "Amazon Devices", "Amazon Science", "Sell products on Amazon", "Amazon Payment Products" … |
| `MenuSidebar.jsx:37,41,81` | "Amazon Music", "Amazon Appstore", "Amazon Live" |
| `components/product/ShippingInfo.jsx:59` | 판매자/배송사 이름이 "Amazon" |

포트폴리오용 로컬 데모라면 흔한 일이지만, 지금은 **공개 도메인에 떠 있고 PayPal 실결제가
연결돼 있다.** 특히 문제가 되는 조합은:

- 남의 **로고를 계정 생성·로그인 화면에** 쓰는 것 (출처를 오인하게 만든다)
- **"Amazon 의 이용약관에 동의한다"** 고 적어 둔 것 — 사실이 아닌 약관 고지다.
  실제로 적용되는 약관이 무엇인지 사용자가 알 수 없다
- 결제 화면까지 이어지는 흐름 안에 있다는 것

기술적 결함은 아니지만 **배포 상태에서는 위 목록 중 가장 먼저 처리할 항목**이다.
로고 3장 교체 + 문자열 치환이면 되는 작업이고, 코드 구조를 바꿀 필요는 없다.

---

## 권장 순서

1. **E3** — 공개 배포 중이다. 코드 작업이 아니라 로고·문자열 교체이고 가장 빨리 끝난다
2. **B1, B2** — 데이터 정합성과 인가 문제다. 사용자가 둘 이상이 되는 즉시 터진다
3. **C2** (배너를 loader 로 + 높이 예약) — 작고, 첫인상에 직접 영향
4. **E1, E2** (메뉴를 실제 카테고리로 + 로그인 상태 반영) — 엔드포인트가 이미 있다
5. **D2** (관리자 상품 목록을 `/products/page` 로) — 엔드포인트가 이미 있다
6. **D3 + D1** (주문 목록 커서 페이징) — 서비스 계층이 이미 있다. 컨트롤러와 화면만
7. **A1~A3** (프로필 화면) — 새 엔드포인트가 필요하다. 위보다 규모가 크다
8. **C1** (겹침 레이아웃), **D4**, **D5**, **B3~B5**
