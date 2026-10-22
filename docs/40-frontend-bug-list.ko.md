# 40. 프론트엔드 결함 목록 — 우선순위순

[日本語](40-frontend-bug-list.md) | **한국어**

> 🕰️ **상태(2026-09-27) — 이전 기록.** P0~P2 는 모두 고쳤습니다(진행표). 남은 것은 chat 라우트의 헤더 순서(§백엔드로 넘길 것)입니다.

2026-09-14, `frontend/src` 전체(약 18,000줄)를 훑은 결과다. 배포된 사이트가 처음으로
끝에서 끝까지 살아난 직후라, **인프라·백엔드에 가려 보이지 않던 화면 문제**가 이제 그대로
드러난다. 위에서부터 고치면 된다.

기준은 하나다: **사용자가 겪는 결과**. 코드 냄새가 아니라 "이걸 누르면 무슨 일이 나는가"로
순서를 매겼다. 각 항목에 파일과 줄, 그리고 고칠 방향을 한 줄로 적었다.

이미 고쳐진 것(SSE 재연결, PayPal 이중 캡처, 주문 중복 제출, 저장소 배너 3상태 등)은
빼고, **지금 남아 있는 것**만 적었다.

---

## 진행 상황 (2026-09-15 갱신)

| 항목 | 상태 | 커밋 |
|---|---|---|
| 0-a 아이콘 404 | ✅ 완료 | `1e1638c` |
| 0-b 지운 물건이 되살아남 | ✅ 완료(2단계 — 서버 기준 장바구니 포함) | `1e1638c` |
| 1 `/forgot` 404 | ✅ 완료 | `1e1638c` |
| 2 결제수단 없이 주문 | ✅ 완료 | `1e1638c` |
| 3 `/profile/orders` 크래시 | ✅ 완료 | `1e1638c` |
| 4 401 하나에 전역 로그아웃 | ✅ 완료 — ErrorFallback 도 함께 다시 씀 | `2d49a44` |
| 5 키 입력마다 API | ✅ 완료 | `b919e30` |
| 6 Paid 표시가 거꾸로 | ✅ 완료 | `f653e80` |
| 7 화면의 `undefined` | ✅ 완료 — `util/userDisplay` + `Avatar` 로 완화한 뒤, 프로필 GET 엔드포인트가 생기면서 실제 이름·사진이 채워진다 | `f653e80`, `a5ad644` |
| 8 장바구니 선택이 옆으로 옮겨감 | ✅ 완료 | `f653e80` |
| 9 쿠폰 입력창에서 글자가 사라짐 | ✅ 해소 — 입력칸을 `CouponPicker` 로 교체하며 Formik/ShippingInput 조합 자체가 사라졌다 | `9dbf3c5` |
| 10 금액을 클라이언트가 정함 | ✅ 완료 — `OrderRequest` 에서 금액 필드를 **삭제**했다. 총액은 장바구니, 할인액은 coupon-service 견적에서 온다 | |
| 11 모바일 헤더 "Sign in" | ✅ 완료 | `f653e80` |
| 12 페이지 제목 | ✅ 완료 | `f653e80` |
| 13 로그인 후 항상 홈 | ✅ 완료 | `f653e80` |
| 14~18 (P2) | ✅ 완료 — 소스맵·진단 로그·console.log·오타·죽은 링크·구조 | |
| 19 배포가 반영 안 됨 (캐시) | ✅ 완료 — 번들에 내용 해시 + `/static/` | 아래 §19 |
| 20 홈 이미지가 화면 밖으로 | ✅ 완료 — 이미지 크기 제약 + `slidesPerView="auto"` | 아래 §20 |
| 22 검색과 상품 화면의 가격이 다름 · 통화 표기 제각각 | ✅ 완료 — 할인가를 한 함수로, 통화는 Intl 로 | 아래 §22 |

---

## 19. 🔴 프론트엔드 배포가 사용자에게 반영되지 않는다 ✅

**"배포했는데 화면이 그대로다" 의 원인.** 이 항목은 홈 화면 하나가 아니라 **모든 프론트
변경**에 해당한다.

- `webpack.config.base.js` 는 `filename: "[name].bundle.js"` — 번들 이름이 **언제나**
  `main.bundle.js` 다. 내용이 바뀌어도 경로가 같다.
- `nginx.conf` 의 캐시 규칙은 CRA 처럼 **해시 붙은 파일이 `/static/` 에 있다고 전제**한다
  (그 주석에 그렇게 적혀 있다). 실제 산출물은 루트의 `main.bundle.js` 라 그 규칙에
  **하나도 걸리지 않았고**, `Cache-Control` 헤더가 아예 붙지 않았다.
- 헤더가 없으면 브라우저는 *휴리스틱 캐싱*을 한다 — 보통 `Last-Modified` 로부터 지난
  시간의 10% 를 제 마음대로 캐시한다. `index.html` 은 `no-cache` 라 새로 받아오지만,
  그 안의 `<script src="/main.bundle.js">` 는 **이름이 그대로**라 브라우저는 캐시에 있는
  옛 번들을 쓴다.

증상이 하나뿐인 것이 이 버그의 성질이다. 개발자도구에는 `200 OK (from disk cache)` 로
보이고 서버는 새 파일을 잘 서빙하고 있어서, 어디가 잘못됐는지 드러나지 않는다.

**고침** — `webpack.config.prod.js` 의 output 에 내용 해시를 넣고 `/static/` 아래로
내보낸다. 이름에 해시가 들어가면 내용이 바뀔 때마다 경로가 바뀌어 캐시가 낄 자리가 없고,
nginx 의 기존 `immutable` 규칙이 **의도대로** 걸린다. `HtmlWebPackPlugin` 이 script 태그를
새 이름으로 다시 쓰므로 수동 작업은 없다.

운영 빌드로 확인했다: `dist/static/main.<해시>.bundle.js` 생성, `index.html` 이 그 경로를
가리킴, `location ~ \.map$` 는 정규식이라 prefix 보다 우선하므로 소스맵 404 차단은 유지.
파일명을 하드코딩한 곳은 없다(Dockerfile·ops·k8s 무참조).

⚠️ **이번 배포까지는** 옛 `main.bundle.js` 가 캐시에 남아 있을 수 있다. 확인할 때
하드 리프레시(Ctrl/Cmd+Shift+R)를 쓸 것. 다음 배포부터는 구조적으로 사라진다.

---

## 20. 홈 상품 이미지가 화면 밖으로 튀어나온다 ✅ (사용자 보고)

- `components/home/ProductSwiper.jsx` — `<img className="w-auto h-auto object-cover">`.
  `w-auto h-auto` 는 **"원본 크기로 그려라"** 라는 뜻이다. 1200×1600 상품 사진이 220px
  상자 안에서 **1200px 폭으로** 렌더된다. 상자에 `overflow-hidden` 도 없어 사방으로
  흘러넘쳤다.
- `object-cover` 는 **죽은 선언**이었다. `object-fit` 은 요소의 상자가 원본 비율과 다를
  때만 의미가 있는데, `w-auto h-auto` 면 상자 = 원본이라 맞출 것이 없다. 설정이 있으니
  괜찮아 보이는 것이 이 버그가 오래 남은 이유다.
- **왜 "순간"인가** — 로드 전에는 크기가 0 에 가까워 얌전하다가, 로드되는 순간 원본
  크기로 튀어오르고, 그 다음 Swiper 가 레이아웃을 다시 잡으며 정리된다.
- 폭이 세 군데에서 서로 달랐다: 카드 `w-[215px]`, 내부 래퍼 `w-52`(208px), 이미지 상자
  `w-[220px]` — **안쪽이 바깥보다 넓었다.**
- `HomeProductSwiper` 는 `slidesPerView: 5` 였다. Swiper 는 슬라이드 폭을 컨테이너의
  1/5 로 계산하는데 카드는 고정 215px 이라, 1024px 화면이면 슬라이드 ≈190px vs 카드
  215px → **카드가 25px 씩 넘쳐 겹쳤다.**
- `pages/home/GridCategory.jsx` 도 같은 부류다 — `h-[420px] object-cover` 로 **폭이
  제한되지 않았다.**

**고침** — 상자가 크기를 정하고(`w-full h-[300px]` + `overflow-hidden`) 이미지는 그 안에
맞춘다(`max-w-full max-h-full object-contain`). Swiper 는 `slidesPerView="auto"` 로 바꿔
**내용이 폭을 정하게** 한다 — 고정 폭 카드에 맞는 관용구이고 어느 화면 폭에서도 넘치지
않는다. 화면 밖 이미지는 `loading="lazy"` 로 미룬다(상자 높이가 고정이라 늦게 와도
레이아웃이 밀리지 않는다).

`object-contain` 을 고른 이유는 상품 사진을 **자르지 않기 위해서**다. `cover` 는 상자를
채우는 대신 가장자리를 잘라내는데, 옷·신발 사진에서는 잘린 부분이 정작 봐야 할 부분이다.

> ⚠️ **홈에 대해서는 21번에서 뒤집혔다.** 자르지 않으면 대신 **상자가 빈다.** 홈 격자·홈
> 카드는 썸네일이라 꽉 찬 쪽이 낫다는 판단으로 `cover` 로 바꿨고, 상세 화면은 `contain`
> 그대로다. 아래 가드 설명도 21번 기준으로 갱신됐다.

가드는 `ProductSwiper.test.jsx` — 이미지가 상자를 **넘지도 비우지도** 않는 것과
`w-auto`/`h-auto` 의 **부재**를 함께 고정한다.

---

## 21. 홈 가운데 격자에 빈 공백이 크게 남는다 ✅ (사용자 보고)

20번을 고치면서 `GridCategory` 는 **넘치지는 않게** 됐지만 이번엔 반대로 **비었다.**
원인이 셋이고, 셋 다 "칸과 내용의 비율이 안 맞으면 남는 자리를 그냥 비워 둔다" 이다.

- **① 한 줄짜리 격자에 420px 타일.** `grid-cols-1` + `h-[420px]` 인데 서버가 내려 주는
  개수는 `content.home_section.item_limit = 4` 다. 카드 하나가 세로로 **1700px** 넘게
  자란다. 그런데 카테고리마다 상품 수가 다르다 — 1개뿐인 카테고리 카드는 420px 인데
  같은 줄의 다른 카드가 1700px 이므로, 격자가 행 높이를 맞추면서 **그 카드 아래로
  1200px 넘는 흰 공백**이 생겼다. 이름이 "격자"인데 한 열이었던 것이 화근이다.
  (원래 코드는 `slice(0, gridCols*gridCols)` 로 2×2 를 전제했는데, 서버 그룹핑으로
  옮기면서 열 수만 1 로 남았다.)
- **② `object-contain` 레터박스.** 상자 비율과 사진 비율이 다른 만큼 상자가 빈다.
  가로로 긴 사진을 세로로 긴 칸에 넣으면 절반이 흰 띠가 된다.
- **③ 마지막 줄의 빈 칸.** `md:grid-cols-4` 는 **열 개수를 고정**한다. 섹션 개수는
  데이터가 정하는데(시드는 GRID 5개) 5 ÷ 4 = 마지막 줄에 카드 하나 + **빈 칸 셋**이다.
  `grid-flow-row-dense` 가 있었지만 아무 일도 하지 않았다 — 그것은 뒤에 오는 **작은**
  항목을 앞의 구멍에 끼우는 규칙이고, 모든 카드가 1칸이면 메울 구멍이 없다.

**고침**

| 곳 | 전 | 후 |
|---|---|---|
| `GridCategory` 타일 | `h-[420px] w-full object-contain` | `aspect-square` 상자 + `absolute inset-0 h-full w-full object-cover` |
| `GridCategory` 내부 열 | `grid-cols-1` | `grid-cols-2` (상품이 1개면 `grid-cols-1`) |
| `HomeSections` 격자 | `grid md:grid-cols-4 grid-flow-row-dense` | `flex flex-wrap` + 항목 `flex-1 basis-[220px] min-w-0` |

정사각 타일 + `cover` 는 **상자가 절대 비지 않는다.** flex 줄바꿈은 **열 개수를 내용이
정하게** 해서, 섹션이 몇 개든 각 줄이 남는 폭을 나눠 가진다 — 구멍이 생길 수 없다.

### 카드 스와이퍼도 같은 이유로 비어 있었다

`ProductSwiper` 는 `w-full h-[300px]` 상자에 `max-w-full max-h-full object-contain`
이었다. 넘치지는 않지만 같은 ②번이다 — 215×300 상자(비율 0.72)에 정사각 사진을 넣으면
**위아래로 85px 씩 흰 띠**가 생기고, 카드가 줄지어 있으니 그 띠도 줄지어 보인다.

`aspect-[4/5]` 상자 + `absolute inset-0 h-full w-full object-cover` 로 바꿨다.
**높이를 픽셀로 박지 않아도 레이아웃은 그대로 안정적이다** — 지연 로딩과 짝을 이루는
조건은 "높이가 픽셀로 고정"이 아니라 "이미지가 오기 전에 높이를 알 수 있다" 이고,
비율 + 폭이면 알 수 있다. `ProductSwiper.test.jsx` 의 고정 높이 가드도 비율 가드로 바꿨다.

> **왜 격자는 정사각이고 카드는 4:5 인가.** 잘려 나가는 양은 상자 비율이 사진 비율에서
> **얼마나 먼가**로 정해진다. 상품 사진은 대개 세로가 길다(3:4 = 0.75, 4:5 = 0.8).
> 정사각(1.0)은 그만큼 더 잘라낸다. 격자 타일은 작은 썸네일이라 가지런함이 더 값지고,
> 카드는 상품을 보는 자리라 덜 자르는 쪽을 골랐다.

> 20번에서 `contain` 을 고른 이유(자르지 않는다)는 **상세 화면에서 여전히 유효하다.**
> 홈은 썸네일이라 꽉 찬 쪽이 낫다는 판단으로 뒤집었고, 상세 화면은 그대로 `contain` 이다.
> 같은 규칙을 화면 전체에 일괄 적용한 것이 20번 수정의 과녁 이탈이었다.

가드는 `GridCategory.test.jsx`(8건)·`HomeSections.test.jsx`(4건)·`ProductSwiper.test.jsx`
(갱신). 핵심은 `object-cover`·`aspect-*` 의 **존재**와 `object-contain`·`h-[…px]`·
`grid-cols-N` 의 **부재**를 함께 고정하는 것. 되돌려 실패하는 것을 확인했다.

덤으로 `__mocks__/swiperMock.js` 의 prop 제거 목록이 빠져 있어(`centeredSlides`,
`slidesPerGroup`, `navigation`, `breakpoints`, `speed`) React 경고가 테스트 출력을 덮고
있었다. `forwardRef` 도 아니어서 ref 경고까지 났다. 둘 다 정리했다.

---

## 22. 🔴 검색 화면과 상품 화면의 가격이 다르다 · 통화 표기가 제각각이다 ✅ (사용자 확인 요청)

### 무엇이 달랐나

서버는 검색 결과와 상품 상세에 **같은 값**을 보낸다 — 사이즈의 정가와, 상시 할인·기획전 할인 중 큰 쪽의 할인율
(`EventPricing.apply`). 어긋난 것은 **할인을 어디서 적용하느냐**였다.

| 화면 | 예전 | 결과(정가 $100, 30% 할인) |
|---|---|---|
| 검색·홈·기획전의 목록 카드(`ProductCard`) | 사이즈 **정가**를 `USD100$` 로 | "$100" + "-30%" 배지 |
| 상품 화면(`pages/Product.jsx`) | `discounted(정가, 할인율)` | "$70" |
| 장바구니·결제(서버 `ProductService.discounted`) | 할인가 | $70 |
| 가격 정렬(`browse.jsx`) | **정가** | $70 짜리가 $80 짜리 뒤에 |
| 가격 필터(서버 `findProductIDBySizeAndPriceAndColor`) | **정가** | "$80 이하" 에서 빠지고 "$90 이상" 에 나옴 |

그리고 🟡 1센트: 상품 화면의 `discounted` 는 부동소수 `p × (1 − d/100)` 에 `toFixed(2)` 였고, 서버는 정수
퍼센트·BigDecimal·HALF_UP 이다. $1.15 의 50% 가 화면은 **$0.57**, 청구는 **$0.58** 이었다.

### 고친 것

- **할인가를 한 곳에서** — `util/pricing` 의 `discounted` 를 센트 정수·HALF_UP 으로(서버와 같은 값), `skuPriceRange`
  를 목록 카드·상품 화면·가격 정렬이 함께 쓴다. 카드는 할인가(범위)와 줄 그은 정가를 보여 준다.
- **가격 필터도 보이는 가격으로** — 서버가 정가로 걸렀다. 기획전 할인은 메모리의 맵이라 SQL 이 모른다. 그래서 SQL 은
  언제나 참인 조건(할인가 ≤ 정가 이므로 "정가 ≥ 하한")으로 후보를 줄이고, `SearchService.skuFilterIds` 가 후보마다
  `ProductService.discounted(정가, effectiveDiscount)` 로 정확히 판정한다.
  → 2026-10-19 판정을 SQL 안으로 옮겼다(`SearchService.skuFilter` — EXISTS + 기획전 규칙의 CASE, 같은 식 · 같은 "높은 쪽" 규칙). 후보를 앱으로
  읽던 방식은 상품 100만에서 catalog 를 힙 부족으로 쓰러뜨렸다(docs/83 §5).
- **통화 표기는 Intl 로** — 화면마다 `59.98$` · `USD59.98 $` · `$59.98` 로 손으로 붙이던 것을 `useMoney()` 하나로. 상점
  통화(빌드 인자 `REACT_APP_STORE_CURRENCY`)를 앱 언어의 로캘로 그린다 — 한국어 `US$59.98`, 일본어 `$59.98`. 청구 통화
  (포트원 KRW 등)는 두 번째 인자로 준다. 결제·환불처럼 i18n Provider 밖에서 테스트되는 화면도 쓸 수 있게 언어가 없으면
  기본 로캘로 그린다(`useCurrentLang`). 배송비 문구(`shipping.fee`)에서 `$` 를 빼고 금액을 표기된 채로 넣는다.
- 덤으로 찾은 것:
  - 🐛 장바구니 요약의 금액이 `toFixed` **문자열**이라 `shippingFee === 0` 이 늘 거짓 — 무료 배송이 "배송비 0.00$" 로 보였다.
  - 🐛 상품 화면 배송 안내의 재고 표시가 `quantity > 1` 이라 **마지막 1개가 "품절"** 로 보였다.
  - 🐛 목록 카드 링크가 가격이 여럿이면 `&size=<색상 번호>` 를 붙여 상세가 엉뚱한 사이즈를 고른 채 열렸다.
  - 🌏 번역되지 않은 "pieces Available" · "Select a Color:" · "Coupon applied" · "New Price", 결제 화면의 한국어 고정
    쿠폰 문구를 사전으로.

### 가드

`pricing.test`(서버와 같은 반올림 — $1.15 의 50% = 0.58 등, 할인가 범위), `ProductCard.test`(할인가·줄 그은 정가·범위,
`USD…$` 가 없다), `browse.test`(보이는 가격으로 정렬), `SearchServiceUnitTest`(할인·기획전 할인을 반영한 가격 필터, 가격
없는 필터는 예전 질의), `Summary`·`Checkout`·`ShippingInfo`·`Total` 테스트(Intl 표기, 문자열 "0.00" 도 무료, 마지막 1개는
재고 있음). 새 SQL 은 PG16 에서 확인했다.

⚠️ 남은 것: 목록 카드는 첫 색상(sku)이 아니라 **마우스를 올린 색상**의 가격을 보여 주고, 가격 정렬은 **첫 색상** 기준이다
(예전과 같다). 색상마다 할인이 다르면 정렬 순서와 카드 가격이 어긋날 수 있다.

---

## P0 — 기능이 안 된다

### 0-a. 아이콘이 깨진다 — 운영 빌드가 `public/assets` 를 복사하지 않는다 (사용자 보고)

- `frontend/webpack.config.prod.js:30-40` — `CopyPlugin({ from: "public", globOptions:
  { ignore: ["**/assets/**"] }, to: "public" })`. **`assets/` 는 통째로 빼고**, 나머지는
  `dist/public/` 밑으로 넣는다(루트가 아니라).
- 왜 뺐나: `webpack.config.base.js:62` 의 asset generator 가 **import 한** 이미지를
  `dist/assets/images/[name].[ext]` 로 내보내므로, 같은 경로를 복사하면 겹친다고 본 것이다.
- 그런데 **문자열 경로로만** 쓰는 이미지는 import 가 없어 번들러가 만들지 않고, Copy 도
  건너뛴다 → 운영에서 404:
  - `components/order/Payment.jsx:196`, `components/checkout/Payment.jsx:40` —
    `/assets/images/{paypal,credit_card,cash}.png` → **결제수단 아이콘 전부**
  - `components/browse/brandsFilter/BrandsFilter.jsx:34` — `/assets/images/<brand>.png` →
    **/browse 의 브랜드 로고 전부**
- 개발에서는 안 보인 이유: `webpack.config.dev.js` 에는 CopyPlugin 이 없고 dev-server 가
  `public/` 을 정적으로 서빙한다. "로컬에선 되는데 배포하면 깨진다"의 전형.
- 고침: `to: "."` 로 루트에 복사하고 `assets/` 를 제외하지 않는다. 번들러가 내보내는
  파일과 이름이 겹치는 것은 **같은 파일**이라 덮어써도 무해하다 — 다만 확실히 하려면
  런타임 참조 이미지를 `public/assets/images/` 가 아니라 `public/img/` 처럼 **다른 경로**로
  옮기고 참조도 바꾼다. `index.html` 은 제외(`ignore: ["**/index.html"]` — HtmlWebPackPlugin 이
  만든다).
- 부수: `public/index.html` 이 font-awesome 를 **두 CDN 에서 두 버전**(5.8.2 + 5.7.0) 로
  불러온다. 채팅 화면의 아이콘 4개(`fas fa-*`)가 여기에 걸려 있다. 나머지 아이콘은 전부
  heroicons/react-icons 라 CDN 과 무관하다. 채팅 4개를 react-icons 로 바꾸고 CDN 링크
  둘을 지우면 외부 의존이 없어진다.

### 0-b. 장바구니에서 지운 물건이 되살아난다 (사용자 보고)

- `components/cart/Product.jsx:56-73` — `removeItemCart` 는 **redux 에서만** 지운다. 서버
  호출은 `:59-68` 에 주석 처리돼 있다. `updateQty`(`:26-54`)도 마찬가지로 로컬만 바꾼다.
- `pages/cart.jsx:27-35` — 장바구니 화면에 들어올 때 **redux 가 비어 있으면 서버 사본으로
  채운다**(`cart.cartItems.length === 0 && cartData.length > 0 → updateCart(cartData)`).
- 그래서: 다 지우고 나가면 redux 는 비고 → 다시 들어오면 서버에 남은 옛 사본이 그대로
  돌아온다. 서버 사본은 `savecart`(체크아웃 누를 때만) 로 쓰이므로 **마지막 체크아웃 시점의
  스냅샷**이다. 그걸 장바구니로 되살리는 게 이 버그다.
- 한 가지 더: `redux/CartSlice.js:27` — `updateCart` 안의 `state.status = UPDATED` 가 주석
  처리돼 있다. 살아 있었다면 "사용자가 손댄 장바구니"를 구분할 수 있었다.
- cart-service 에는 항목 삭제 엔드포인트가 **없다**(`CartController.java:63` 에 주석으로만).
- 고침 — 두 단계:
  1. **지금**: `updateCart` 가 `status = UPDATED` 를 설정하게 하고, `cart.jsx` 는
     `status === EMPTY` 일 때만 서버에서 채운다. 사용자가 비운 장바구니(UPDATED)는
     되살리지 않는다. redux 는 persist 되므로 새로고침에도 유지된다.
  2. **제대로**: 서버가 장바구니의 진실이어야 한다. 항목 삭제·수량 변경 엔드포인트를
     cart-service 에 만들고 `removeItemCart`/`updateQty` 가 호출한다. 그래야 다른 기기에서
     같은 장바구니가 보인다. 이건 백엔드 작업이 같이 필요하다.
- ⚠️ `useFetchCart`/`useFetchCheckOut` 의 queryKey 에 `cart.status` 가 들어 있어, 1번을
  하면 상태가 바뀔 때마다 서버 장바구니를 다시 읽는다. 무해하지만 알고는 있을 것.

#### 0-b 2단계 — 서버가 장바구니의 기준 (완료)

| 무엇 | 어디 |
|---|---|
| 담기·수량·삭제 엔드포인트 — `PUT/PATCH/DELETE /api/cart/items`(신원은 `User-Id`, 줄은 상품·색상·사이즈), 응답은 지금 장바구니 전체 | `cart-service` `CartController`·`CartService.putItem/setItemQty/removeItem` |
| 체크아웃(`savecart`)은 장바구니를 **바꾸지 않고** 고른 줄에 `selected` 표시만 — 총액·`/checkout`(order·coupon 이 읽는다)은 고른 줄로만 | `V3__cart_product_selected.sql`(추가만, 기본값 true) |
| catalog 가 모르는 줄(삭제·판매 중지)은 응답에서만 빼고 경고 로그 — 예전에는 장바구니 전체가 예외라, 그 줄을 빼는 요청까지 롤백됐다 | `CartService.loadCart` |
| 색상은 SKU 가 정한다(본문의 색상을 믿지 않는다) | `CartService.createCartProduct` |
| 화면: 로그인했으면 담기(`Infos`)·수량·삭제(`cart/Product`)가 서버를 부르고 응답으로 바꾼다. 실패하면 화면은 그대로 + 이유 | `redux/CartSlice.syncedFromServer` |
| 장바구니에 **주인(ownerId)** — 로그인 때 `CartSync` 가 주인 없는 줄(로그인 전에 담은 것·옛 상태)을 서버에 올리고 서버 것을 받는다. 다른 사람의 것은 버린다. 로그아웃하면 사본을 지운다 | `components/cart/CartSync.jsx` |
| 장바구니 화면은 내 장바구니면 들어올 때마다 서버 것으로 — 다른 기기의 변경이 보인다 | `pages/cart.jsx` |

- 🐛 함께 고친 것: 같은 브라우저에서 다른 사람이 로그인하면 앞 사람의 장바구니가 보였고, 체크아웃하면 그 사람의 서버
  장바구니·주문으로 들어갔다(redux-persist 가 cart 를 주인 없이 저장).
- 배포: cart-service(Flyway V3 — 컬럼 추가만) + frontend. 순서 무관 — 옛 화면은 `savecart` 만 쓰고, 새 서버의 `savecart` 는
  고른 줄 표시로 동작이 같다(고르지 않은 줄이 지워지지 않는 것만 다르다).


### 1. "비밀번호를 잊으셨나요?" 가 오류 페이지로 간다

- `pages/user/SignInPage.jsx:314` — `<Link to="/forgot">`
- `App.jsx` — `/forgot` 라우트가 **없다**. `pages/user/ForgotPage.jsx`(134줄)는 만들어져
  있는데 어디에도 연결돼 있지 않다.
- 결과: 링크를 누르면 라우터 오류 화면. 비밀번호를 잊은 사용자는 계정을 되찾을 길이 없다.
- 고침: `App.jsx` 의 `RootPage` 자식에 `{ path: 'forgot', element: <ForgotPage /> }` 한 줄.
  (백엔드 `/user-service/api/auth/public/forgot` 이 실제로 있는지도 같이 확인.)

### 2. 결제수단을 안 골라도 주문이 생성된다

- `pages/checkout.jsx:28` — `useState(user.defaultPaymentMethod)`. JWT 에서 만든 `user` 에는
  그 필드가 없다(`util/jwtUser.js` — email/userId/roles 뿐). 초기값이 **`undefined`**.
- `components/checkout/Summary.jsx:92` — 가드가 `paymentMethod === ""` 다. `undefined` 는
  통과한다.
- 결과: 결제수단을 고르지 않고 "Place Order" 를 누르면 `paymentMethod: undefined` 로 주문이
  만들어진다. 주문 화면(`order/Payment.jsx`)은 `payment.id == order.paymentMethod` 로 아이콘을
  고르므로 아무것도 안 그려지고, PayPal 버튼만 덩그러니 남는다.
- 고침: 초기값을 `""` 로 두고, 가드를 `!paymentMethod` 로. `pages/profile/payment.jsx:15-16` 도
  같은 초기값을 쓴다.

### 3. `/profile/orders` 에 직접 들어가면 크래시

- `pages/profile/orders.jsx:107` — `searchParams.get('q').split("__")[1]`
- 쿼리 `?q=` 없이 들어오면 `get('q')` 가 `null` → `TypeError` → 라우터 오류 화면.
  사이드바 링크는 q 를 붙이지만, 주소창 입력·북마크·새로고침(주소가 `/profile/orders` 로
  정규화된 경우)은 붙지 않는다.
- 고침: `searchParams.get('q')?.split("__")[1] ?? "all"` 처럼 기본 필터를 둔다.

### 4. 부수적인 요청 하나가 앱 전체를 로그아웃시킨다

- `util/TokenRefresher.js:158` — 401 을 받은 요청이 리프레시 후 재시도해도 401 이면
  `logout()`. **어떤 요청이든** 그렇다.
- 어제 `/admin/product` 가 로그인 화면으로 튕긴 게 정확히 이것이었다(배너의 storage-health
  하나가 세션을 날렸다). 원인 로그(`reportForcedLogout`)는 붙였지만 동작은 그대로다.
- 같은 파일 `:137` `case 500: navRef.current("/")` — **어떤 500 이든 홈으로** 보낸다. 사용자는 자기가
  무엇을 하다 튕겼는지 모른다. `case 503` 도 로그아웃까지 한다.
- 고침 방향: 로그아웃은 **토큰 갱신 자체가 실패했을 때만**(리프레시 엔드포인트가 401/204).
  개별 요청의 401 은 그 요청의 실패로 돌려준다(호출부가 처리). 500 은 이동하지 않고
  호출부로 던진다 — ErrorBoundary 가 받는다.
- ⚠️ 이건 인터셉터 하나를 고치는 일이 아니라 **인증 실패의 의미를 다시 정하는 일**이다.
  고치고 나면 "권한 없는 API 를 부른 화면"이 어떻게 보이는지 화면마다 확인해야 한다.

### 5. 상품 등록 화면이 키를 누를 때마다 API 를 부른다

- `pages/admin/Index.jsx:113-131` — `useEffect(..., [product.category, product.name])`.
  **상품 이름을 한 글자 칠 때마다** `loadCategories` 와 `getSubs` 가 다시 나간다.
  게이트웨이 레이트리미터가 burst 40 · 초당 20 이라, 빠르게 치면 429 가 나고
  429 는 어디서도 처리되지 않는다.
- 같은 파일 세 개의 `useEffect` 안 `async` 함수(`getProducts`, `getParentData`, `getSubs`)
  전부 **catch 가 없다.** 실패는 콘솔의 "처리되지 않은 거절"로만 남고 화면은 그대로다.
- `getParentData` 는 `setProduct({ ...product, ... })` 로 **오래된 클로저의 product** 를
  펼친다 — 응답이 오는 사이 입력한 값이 덮인다.
- 고침: 의존성에서 `product.name` 을 뺀다. `loadCategories` 는 마운트 때 한 번. `setProduct`
  는 함수형(`prev => ({...prev, ...})`)으로. 셋 다 `try/catch` + 화면 표시.

---

## P1 — 틀린 것을 보여주거나, 조용히 잘못 동작한다

### 6. 주문 목록의 "Paid" 표시가 거꾸로다

- `pages/profile/orders.jsx:80` — `orderStatus === "UNPAID" ? <초록 체크> : <빨간 X>`
- **미결제**가 초록 체크, 결제 완료가 빨간 X 로 보인다.
- 고침: 조건을 뒤집거나, 결제 완료 상태 집합(`PAID`, `COMPLETED` …)으로 판정.

### 7. 화면 곳곳에 `undefined` 가 찍힌다 — JWT 사용자 객체에 없는 필드

`util/jwtUser.js` 의 `toUser()` 는 `{ email, userId, roles }` 만 만든다. 그런데:

| 파일 | 접근 | 결과 |
|---|---|---|
| `pages/profile/orders.jsx:36` | `user.name` | 제목 "undefined's Orders" |
| `pages/profile/address.jsx:22` | `user.userName` | "undefined's Address" |
| `pages/profile/payment.jsx:38` | `user.username` | "undefined's Address" (복붙 — 제목도 틀림) |
| `components/order/UserInfo.jsx:10-17` | `user.image`, `user.name` | 깨진 이미지 + 빈 이름 |
| `pages/checkout.jsx:28` | `user.defaultPaymentMethod` | P0-2 |
| `pages/header/Account.jsx:45` | `user ? user.email : "sign in"` | `user` 초기값이 `{}` 라 truthy → "Hello, undefined" |

- 고침: 둘 중 하나. (a) 로그인 직후 `/user-service/api/user/profile` 로 프로필을 받아
  `updateUser` 로 채운다(`AuthContext.js` 에 주석 처리된 `fetchUser` 가 그 자리다).
  (b) 화면이 `user.email` 만 쓰도록 바꾼다. (a) 가 맞다 — 프로필 이미지·이름은 결국 필요하다.

### 8. 장바구니에서 항목을 지우면 선택이 옆 항목으로 옮겨간다

- `components/cart/CartPage.jsx:118` — `cart.cartItems.map((product, i) => <Product key={i} …>)`
- 자식 `Product` 가 선택 상태를 들고 있는데 키가 인덱스라, 가운데 항목을 지우면 React 가
  뒤 항목에 앞 항목의 상태를 붙인다. "1번을 지웠는데 2번이 체크돼 있다."
- 고침: `key={product._uid}` (Infos.jsx 가 이미 `_uid` 를 만든다).

### 9. 쿠폰 입력창에서 글자가 사라진다

- `components/checkout/Summary.jsx:141` — `<Formik enableReinitialize initialValues={{ coupon }}>`
  + `<ShippingInput onChange={(e) => setCoupon(e.target.value)}>`
- 값의 출처가 둘(페이지 state + Formik). `enableReinitialize` 가 키 입력마다 폼을 초기화한다.
  `pages/profile/security.jsx` 의 주석이 **정확히 이 버그**를 설명하며 고쳐 놓았는데, 여기는
  그대로다.
- 고침: security.jsx 와 같은 방식 — Formik 이 값을 소유하고 `onSubmit(values)` 에서 읽는다.

### 10. 금액을 클라이언트가 정한다 ✅

**확인 결과: 서버는 재계산하지 않고 있었다.** `OrderDataService.createOrder` 가
`request.getTotal()` 을 그대로 `Order.total` 에 저장했다.

PayPal 검증이 있으니 괜찮다고 볼 수 없었다. 그 검증은 **결제액과 DB 의 주문 총액이
같은지**만 본다(`persisitPaypalPayment`). 총액 자체가 브라우저에서 왔으므로
`total: 1` 을 보내고 1달러를 결제하면 **검증은 정상 통과했다.** 자물쇠는 튼튼한데
열쇠를 손님이 깎고 있었다.

같은 문제가 쿠폰 쪽에도 남아 있었다. 제약 **없는** 쿠폰은 `CouponApplyRequest.totalPrice`
를 그대로 할인 기준으로 썼다 — "내 장바구니는 100만원이다" 라고 주장하면 10% 쿠폰이
10만원을 깎아 줬고, 그 금액이 다시 주문 총액으로 들어갔다.

**고친 방식**

- `OrderRequest` 에서 `total`·`totalBeforeDiscount` 를 **삭제했다.** 읽지 않는다고 주석만
  달면 다음 사람이 "여기 total 이 있네" 하고 다시 쓴다. 없애는 것이 유일하게 확실하다
- 총액은 **cart-service 가 카탈로그 가격으로 계산해 저장한 값**을 쓴다
  (`CartDto.cartTotal`). 주문 생성은 이미 그 장바구니를 받아 오고 있었다
- 할인액은 coupon-service 에 **다시 물어본다**(`POST /api/coupon/internal/quote`).
  장바구니에 저장된 `totalAfterDiscount` 를 쓰지 않는 이유는 그것이 Kafka 로 비동기
  갱신되기 때문이다 — 쿠폰을 적용하고 바로 주문하면 아직 도착하지 않았을 수 있다
- 🔴 **견적은 쿠폰을 소모하지 않는다.** 주문 생성이 쿠폰을 "사용" 처리하면 결제 실패나
  이탈 때 쓰지도 않은 쿠폰이 사라진다. 사용 처리는 원래대로 적용 시점에만 일어난다
- 쿠폰을 확인하지 못하면 **주문을 거절한다.** 할인 없이 진행하면 사용자는 화면에서 본
  금액과 다른 금액을 결제하게 된다 — 조용히 더 받는 것보다 "지금은 안 된다"가 낫다
- `Summary.jsx` 도 금액을 보내지 않는다. 서버가 버리긴 하지만, 화면이 계속 보내고 있으면
  다음 사람이 서버에 필드를 되살린다
- `Payment.jsx` 의 `order.total` 은 그대로 둔다 — 그 값은 이제 **서버가 계산해 내려준**
  주문 총액이고, 결제 후 서버가 실제 결제액과 다시 대조한다

**🔒 인가** — `/internal/quote` 는 `/api/auth/user/id` 와 같은 규칙으로 막는다
(`User-Role` 헤더가 있으면 외부 요청 → 관리자만). 없으면 로그인한 아무나 **남의 이메일**로
"그 사람이 이 쿠폰을 가졌는지, 얼마가 깎이는지"를 물어볼 수 있다 — `/applycoupon` 에서
고친 BOLA 가 그대로 되살아난다.

**⚠️ 남은 것 — 금액이 정수다.** `Order.total` 이 `int` 라 소수점이 잘린다. $59.98 은 59 로
저장되고, 프론트가 PayPal 에 보내는 금액도 같은 59 라 검증은 통과하지만 실제로는 98센트를
덜 받는다. 여기서 고치지 않은 이유는 `Order`·`OrderProduct`·`OrderDto`·결제 아웃박스·
프론트까지 함께 `BigDecimal` 로 옮겨야 하기 때문이다. 이번 변경은 **누가 금액을
정하는가**만 바로잡는다. 버림을 쓴 것도 그래서다 — 반올림하면 어떤 주문은 장바구니에
표시된 금액보다 비싸진다. `OrderPricingTest` 가 이 동작을 숨기지 않고 고정해 둔다.

> ✅ **고쳤다** — order-service V7 이 네 금액 컬럼을 `numeric(19,2)` 로 옮겼고 `Order`·
> `OrderPricing.Amounts`·`OrderDto` 가 `BigDecimal` 이다. 위 테스트는 이제 반대("센트가
> 살아남는다")를 고정한다. 프론트의 `{total}$` 도 `formatMoney(total, STORE_CURRENCY)` 로
> 바꿨다(`Total.jsx`·`orders.jsx`·`Refunds.jsx`). 자세한 것은 docs/49 §6.

### 11. 모바일 헤더는 로그인해도 "Sign in"

- `pages/header/Account.jsx:35` — `md:hidden` 블록이 인증 상태를 보지 않는다.
- 같은 파일 `:143` — 비로그인 상태에서 장바구니 아이콘을 누르면 **아무 일도 없다**
  (`token ? navigate("/cart") : {}`). 로그인 화면으로 보내야 한다.

### 12. 페이지 제목이 안 바뀐다

- `pages/header/Header.jsx:33-37` — `<title>`·`<meta>` 를 `<header>` 안(body)에 그린다.
  React 18 은 이걸 head 로 올리지 않는다(19 부터). `public/index.html` 에는 `<title>` 자체가
  없어서 탭 제목이 빈 문자열/URL 이다.
- 고침: `react-helmet-async` 또는 `useEffect(() => { document.title = title }, [title])`.

### 13. 로그인 후 항상 홈으로 간다

- `pages/user/SignInPage.jsx:178` — `navigate('/')`.
- `/checkout` 에서 세션이 끊겨 로그인하러 갔다 오면 다시 처음부터다. `ProtectedRoute` 가
  `state: { from }` 을 넘기고 로그인 성공 시 거기로 돌아가야 한다.

---

## P2 — 지금은 안 보이지만 곧 문제가 되는 것

### 14. 운영 빌드가 소스맵을 그대로 내보낸다 / 진단 로그를 전부 지운다 ✅

- `devtool: "source-map"` → **`hidden-source-map`**. 맵은 만들되 번들에
  `//# sourceMappingURL=` 주석을 넣지 않는다. 맵 자체를 없애면(`false`) 나중에
  스택트레이스를 풀 방법이 사라진다.
- 🔴 **그것만으로는 부족하다.** 파일은 여전히 `dist/` 에 있으므로 `/main.bundle.js.map` 을
  직접 찍으면 그대로 받아진다. nginx 가 `location ~ \.map$` 을 404 로 막는다. 두 겹이다.
- `drop_console: true` → **제거.** 그건 `console.error`·`console.warn` 까지 전부 지운다 —
  운영에서 클라이언트 진단이 0 이 된다. 실제로 지워지던 것들: `[auth] 강제 로그아웃 —
  원인 요청: …`, ErrorBoundary 의 컴포넌트 스택, axios 인터셉터의 실패 사유.
  대신 `pure_funcs: ["console.log", "console.debug", "console.trace"]` 로 **소음만** 지운다.
- 부수로 `output: { clean: true }` 를 켰다. 없으면 예전 빌드의 해시 붙은 청크가 계속
  쌓여 이미지에 들어간다 — 실제로 `dist/` 에 날짜가 다른 청크가 섞여 있었다.
- 빌드해서 확인: 번들에 `console.log` 0개, `console.error` 살아 있음,
  `//# sourceMappingURL=` 트레일러 없음, 청크 타임스탬프 전부 동일.

### 15. 남아 있는 `console.log` 22곳 ✅

살아 있는 호출은 **0개**가 됐다(남은 것은 전부 주석). 단순 삭제가 아니라 셋으로 나눴다:

- **지웠다** — 렌더마다 찍히거나 아무 정보가 없던 것:
  `MultipleSelect`(field), `ProductPage`(reviews[0]), `Reviews`(리뷰 본문 전체 —
  사용자가 쓴 글이다), `ProductImages`("err")
- **`console.error` 로 올렸다** — 실패인데 운영에서 지워지고 있던 것:
  `CreateSubCategory`, `CreateCategory`, `util/api` 의 queryCache onError,
  `browse.jsx` 의 loader catch (여기가 `null` 을 돌려주면 화면은 "상품이 없습니다" 를
  그린다 — 왜 비었는지가 이 한 줄에만 남는다)
- **`console.debug` 로 낮췄다** — 개발용 시드 스크립트 `loadFake.js`

`components/profile/sidebar/Item.jsx` 는 문제가 둘이었다. JSX 안에서
`{console.log(link)}` 를 호출했고, `map` 이 만드는 **가장 바깥** 요소인 `<>` 에 key 가
없었다(key 는 안쪽 `<li>` 에 붙어 있었다). 프래그먼트가 `<li>` 하나만 감싸고 있어 존재
이유도 없었으므로 `React.Fragment key={...}` 로 바꾸고 안쪽 key 는 뺐다.

### 16. 사용자에게 보이는 오타 ✅

`charactes`·`atleast`·`Faild`·`successed`·`Confirm our New` — 전부 **한국어로 옮겼다.**
철자만 고치면 한국어 화면에 영어 안내가 계속 섞인 채로 남는다. 로그인 실패 문구를
한국어로 바꾼 것과 같은 방향이다.

🐛 옮기다 **다른 버그를 찾았다.** `AddShipping` 의 `address1` 안내가 전부 "Address 2" 였다 —
기본 주소를 비우면 상세 주소가 잘못됐다는 말이 떴다. 바로 위 `phoneNumber` 가 "First Name
..." 을 쓰던 것과 똑같은 복사 실수다.

id 값 `passowrd` 도 고쳤다. 참조하는 `htmlFor` 가 없어 무해했지만, 붙이려는 순간 틀린다.

### 17. 죽어 있는 링크·컴포넌트 ✅

- **`BreadCrumb`** — `<Link href=…>` 였다. react-router 의 Link 는 `to` 를 받으므로
  렌더는 되고 클릭만 안 됐다. `to` 로만 고쳤다면 이번엔 404 로 갔을 것이다 —
  가리키던 `/category/<slug>` 라우트가 이 앱에 없다(`/category` 는 관리자 화면이다).
  실제 경로인 `/browse?category=<id>` 로 바꿨다(햄버거 메뉴와 같은 주소).
  하위 카테고리는 **일부러 링크가 아니다** — 하위 카테고리로 거르는 경로가 아직 없다.
  `// have error … i will fix this` 자리에 그 사정을 적었다.
- **`Maintenance`** — 이 화면은 **네트워크가 끊겼을 때** 뜬다. 그런데 svgrepo.com 의 SVG 를
  런타임에 `<img src>` 로 가져오고 있었다. 정작 필요한 순간에 깨진 이미지가 뜨고, 외부
  도메인에 우리 사용자의 방문이 기록된다. 인라인 SVG 로 바꿔 요청을 0 으로 만들었다.
  `<a href="#"><button>` 중첩(유효하지 않은 HTML)도 풀었고, 갈 곳 없는 "Contact Us" 대신
  지금 실제로 도움이 되는 **다시 시도**를 뒀다. 문구도 한국어로.
- **font-awesome 이중 로드** — 두 CDN 에서 두 버전을 받고 있었다. 뒤에 온 5.7.0 이 앞의
  5.8.2 를 덮어쓰므로 **실제로 쓰이는 것은 옛 버전**이고 최신을 받은 요청은 통째로 낭비였다.
  하나만 남겼다(이미 쓰이던 5.8.2 — 아이콘 이름이 바뀔 여지를 만들지 않으려고 올리지 않았다).
  ⚠️ `integrity`(SRI)는 붙이지 않았다. 해시는 실제 파일에서 계산해야 하는데 이 작업
  환경에서는 CDN 에 나갈 수 없었다. **추측한 해시를 넣으면 브라우저가 스타일시트를 통째로
  거부해 채팅 아이콘이 전부 사라진다** — 틀린 자물쇠가 없는 자물쇠보다 나쁘다. 계산 방법은
  `public/index.html` 주석에 적어 뒀다.

### 18. 구조 ✅

- **`createBrowserRouter` 를 모듈 최상위로.** 이 함수는 히스토리 구독·로더 상태·스크롤
  복원 위치를 들고 있는 살아 있는 객체를 만든다. 렌더마다 새로 만들면 그 상태가 통째로
  버려진다. `AppRouter` 에 prop 도 state 도 없어 실제로는 **증상 없이 잠복해 있었다** —
  위에 Provider 가 하나만 늘어도 터진다.
- **`pages/checkout.jsx` 의 엉뚱한 import.** `import { isPending } from "@reduxjs/toolkit"` —
  이 파일이 쓰는 것은 react-query 의 `isPendingCheckOut` 이고, redux-toolkit 의 `isPending`
  은 async thunk 액션 매처다. 누가 그냥 `isPending` 을 쓰면 **언제나 참이 아닌 함수 객체**를
  조건으로 검사하게 된다. 안 쓰는 import 7개와 함께 걷어냈다.
- **`ShippingPage` 의 `return;`** — undefined 를 그리면 React 는 아무것도 렌더하지 않는다.
  배송지를 불러오는 동안 결제 화면 위쪽이 빈 공간이었고, 사용자는 로딩 중인지 주소가
  없는 건지 알 수 없었다. "배송지를 불러오는 중…" 으로 바꿨다.
- **ESLint 를 CI 에서 켰다 — 래칫으로.** 지금 `no-unused-vars` 를 error 로 올리면 경고가
  198개라 CI 가 첫날부터 빨갛고, 그러면 사람들은 규칙을 고치는 대신 **규칙을 끈다.**
  반대로 경고만 찍고 통과시키면 그건 장식이다. `npm run lint` 가 `--max-warnings 198` 로
  현재 개수를 고정하므로 **새로 생긴 경고 하나면 CI 가 빨개진다.** 기존 198개는 고칠 때마다
  그 숫자를 내려서 잠근다 — 올라가지 않는다. (래칫이 실제로 작동하는지 경고를 하나
  일부러 추가해 확인했다: 199 > 198 에서 멈춘다.)
- **`nginx.conf` 의 죽은 프록시 블록** — 지우지 않고 **그렇다고 적었다.** cloudflared 가
  `/*-service/` 를 게이트웨이로 직접 보내므로 이 블록에는 닿지 않지만, cloudflared 없이
  nginx 하나로 띄우는 구성에서는 그대로 쓰인다. 적어 두지 않으면 다음 사람이 "SSE 는
  nginx 가 프록시한다"고 믿고 SSE 가 끊길 때 여기부터 뒤진다.


---

### 19. 채팅방을 만들고 첫 메시지를 보내면 화면이 죽는다 ✅

사용자 보고: *"채팅방 만들고 채팅 입력하니깐 `Cannot read properties of undefined
(reading 'trim')` … 화면을 그리는 중 오류가 발생했습니다 화면으로만 계속 연결돼."*

`ChatContent.jsx` 가 날짜 구분선을 이렇게 만들었다:

```js
const splitDate = createdAt.toLocaleDateString().split('.');
return `${splitDate[0].trim()}년 ${splitDate[1].trim()}월 ${splitDate[2].trim()}일 ${day}`;
```

`toLocaleDateString()` 은 **브라우저 언어**대로 찍는다. 점으로 나뉘는 것은 `ko-KR`
("2026. 9. 18.") 뿐이다 — `en-US` 는 "9/18/2026", `ja-JP` 는 "2026/9/18". 점이 없으니
`split('.')` 은 원소 하나짜리 배열이고 `splitDate[1]` 은 undefined 다. 그 뒤의 `.trim()` 이
바로 그 오류다.

**왜 "첫 메시지" 인가.** 구분선은 *이전 메시지와 날짜가 다를 때* 만든다. 첫 메시지는
이전이 없으니 반드시 만든다. 그래서 방을 만들고 첫 줄을 치는 순간이 정확한 재현 조건이고,
한국어 브라우저에서만 안 보이던 버그다. 일본어 화면을 붙인 뒤(docs/45) 드러났다.

두 겹이 더 있었다:

- **시각도 잘라 만들었다.** `toLocaleTimeString()` 의 끝 세 글자를 떼어 초를 지웠는데
  ("오후 3:24:05" → "오후 3:24"), 영어("3:24:05 PM")에서는 그 세 글자가 " PM" 이라
  초는 남고 오전/오후가 사라진다.
- **서버가 시각 없는 메시지를 보낼 수 있었다.** `ChatService.sendMessage` 가
  `if (member != null)` 안에서만 id·`createdAt` 을 붙였다. 회원 복제 행이 없는 계정
  (docs/48 — 메일 인증을 끈 가입)은 **id 없이, 시각 없이** 메시지가 나갔다.

문자열을 쪼개지 않고 `Intl` 에 형식을 **요청**한다 — 한국어 "2026년 9월 18일 금요일",
일본어 "2026年9月18日金曜日", 영어 "Friday, September 18, 2026" 이 같은 코드에서 나온다.
같은 날 판정도 로케일 문자열이 아니라 `getFullYear/getMonth/getDate` 로 한다.
`createdAt` 이 없으면 시각·날짜 없이 말풍선만 그린다. 서버는 발신자가 누구든 id·시각을
붙이고, 닉네임만 회원 행에서 오되 없으면 이메일 로컬 파트로 대신한다.

가드: `ChatContent.test.jsx`(10 — 옛 코드로 되돌려 6개 실패 확인) ·
`ChatServiceSendMessageTest`(4).

## 백엔드에 넘길 것 (이 목록에서 발견됐지만 프론트 문제가 아님)

- **10번** — 주문/결제 금액을 서버가 재계산하는가.
- `gateway-server/application-local.yml` `chat-service-api` — `RemoveRequestHeader=Cookie` 가
  `JwtAuthorization` **앞**에 있다. 같은 파일의 다른 라우트 주석이 "반드시 뒤" 라고 못 박아
  둔 순서와 반대다. 지금은 브라우저가 헤더로 토큰을 보내 무해하지만, 쿠키 경로를 쓰는 순간
  401 이 난다.
- `chat-service-api` 의 `uri: lb:ws://` — REST 라우트가 ws 스킴이다. 동작한다면 우연이다. → ✅ 2026-10-16 `lb://` 로. 동작한 것은 우연이었다 — Eureka 인스턴스가 https 를 알려 ws 가 무시됐다(알리지 않는 디스커버리에서는 GET 400 · POST 405, ChatRouteTlsTest · RouteMatchingTest).

---

## 손대지 않아도 되는 것 (확인했음)

읽다가 "이거 버그 아닌가" 싶었지만 아닌 것들. 다시 의심하지 않도록 적어 둔다.

- `ChatPage.jsx` 가 STOMP CONNECT 에 `Authorization: <raw token>` (Bearer 없이)을 보낸다 —
  chat-service `StompHandler` 가 raw 토큰을 그대로 검증한다. 일관성은 없지만 동작한다.
- `SocialLogin.jsx` 의 `window.location.href = "/user-service/oauth2/…"` — API_BASE 없이
  상대 경로지만 same-origin 이라 cloudflared 가 게이트웨이로 보낸다.
- `Payment.jsx`(order) 의 `useReducer` 초기 `loading: true` — 읽는 곳이 없어 무해.
- `AuthContext` 가 토큰을 메모리에만 두는 것 — **의도된 설계**다(XSS 대비). localStorage
  로 옮기려는 시도는 하지 말 것.

---

## 순서 제안

1. **0-a·0-b·1·2·3** — 각각 몇 줄. 오늘 안에 끝난다. 사용자가 바로 부딪히는 것들이다.
   0-a 는 설정 한 줄이고 결과를 `ls dist/assets/images` 로 바로 확인할 수 있다.
2. **6·8·9·11** — 한 파일씩. 반나절.
3. **7** — `fetchUser` 를 살리면 여섯 곳이 한 번에 풀린다. 다만 프로필 API 응답 모양을 먼저 봐야 한다.
4. **4** — 가장 큰 변경. 인증 실패의 의미를 정하는 일이라 따로 시간을 잡는다. 이걸 먼저
   건드리면 나머지를 테스트할 때 로그아웃이 튀어 방해된다.
5. **5·13·14** — 그다음.
6. 나머지는 눈에 띌 때.
