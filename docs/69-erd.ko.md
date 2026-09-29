# 69. ERD — 실제 스키마

[日本語](69-erd.md) | **한국어**

> 작성일: 2026-09-28 · 상태: ✅ · **서비스별 그림은 마이그레이션에서 자동 생성**(`ops/tools/gen-erd.py`)
>
> 관련: [55 무중단 전환](55-zero-downtime-migrations.ko.md) · [65 사이즈 단위 SKU](65-size-level-sku.ko.md) · [59 결제 전 재고 예약](59-stock-reservation.ko.md)

## 0. 왜 다시 그렸나

README 의 ERD 는 GitHub 에 올린 **이미지 첨부 한 장**(user-attachments)이었다.

- **저장소 밖에 있다** — 첨부가 사라지거나 볼 권한이 어긋나면 깨지고, 저장소에서는 고칠 방법이 없다.
- **2024년 그림이다** — 그 뒤의 스키마(TSID · 아웃박스 월 파티션 · 재고 예약 · 사이즈 단위 SKU · 환불 · 무통장)가 없다.

이제 서비스별 그림은 Flyway 마이그레이션을 **실제로 적용한 PostgreSQL** 에서 뽑는다(손으로 그리지 않는다). GitHub 가 Mermaid 를 그대로 그린다.

```bash
python3 ops/tools/gen-erd.py          # 아래 서비스별 구간을 새로 쓴다(Docker 필요)
python3 ops/tools/gen-erd.py --check  # 문서가 스키마와 다르면 실패
```

**읽는 법** — 실선은 같은 DB 안의 **FK**, 점선은 서비스 사이의 **논리 참조**(DB 가 달라 FK 가 없다 — 정합성은 사가 · 아웃박스 · 대사 배치가 맞춘다).
공통 감사 컬럼(`created_at/by` · `updated_at/by` · `version`), 아웃박스 월 파티션, Spring Batch 메타 표는 생략했다.

## 1. 개요 — 서비스 사이의 참조(손으로 관리)

```mermaid
erDiagram
    USERS {
        bigint user_id PK
        varchar email UK
    }
    REVIEW {
        bigint review_id PK
        bigint user_id FK
        bigint product_id
    }
    WISHLIST {
        bigint wish_item_id PK
        bigint user_id FK
        bigint product_id
    }
    PRODUCT {
        bigint product_id PK
        bigint category_id FK
    }
    PRODUCT_SKU {
        bigint skuproduct_id PK
        bigint product_id FK
        bigint color_id FK
    }
    PRODUCT_SIZE {
        bigint size_id PK
        bigint skuproduct_id FK
        varchar sku_code
        int quantity
    }
    STOCK_RESERVATION {
        bigint reservation_id PK
        bigint order_id
        bigint product_id
        int qty
    }
    CART {
        bigint cart_id PK
        bigint user_id
    }
    CART_PRODUCT {
        bigint cartproduct_id PK
        bigint cart_id FK
        bigint product_id
        varchar sku_code
    }
    ORDERS {
        bigint order_id PK
        bigint customer_id
        varchar order_status
    }
    ORDER_PRODUCT {
        bigint order_product_id PK
        bigint order_id FK
        bigint product_id
        varchar sku_code
    }
    PAYMENT {
        bigint payment_id PK
        bigint order_id UK
        bigint customer_id
    }
    USERS ||--o{ REVIEW : "user_id"
    USERS ||--o{ WISHLIST : "user_id"
    PRODUCT ||--o{ PRODUCT_SKU : "product_id"
    PRODUCT_SKU ||--o{ PRODUCT_SIZE : "skuproduct_id"
    CART ||--o{ CART_PRODUCT : "cart_id"
    ORDERS ||--o{ ORDER_PRODUCT : "order_id"
    USERS ||..o{ CART : "user_id"
    USERS ||..o{ ORDERS : "customer_id"
    USERS ||..o{ PAYMENT : "customer_id"
    PRODUCT ||..o{ REVIEW : "product_id"
    PRODUCT ||..o{ WISHLIST : "product_id"
    PRODUCT ||..o{ CART_PRODUCT : "product_id"
    PRODUCT ||..o{ ORDER_PRODUCT : "product_id"
    PRODUCT_SIZE ||..o{ ORDER_PRODUCT : "sku_code"
    PRODUCT ||..o{ STOCK_RESERVATION : "product_id"
    ORDERS ||..o{ STOCK_RESERVATION : "order_id"
    ORDERS ||..o| PAYMENT : "order_id"
```

- 서비스마다 DB 가 따로다(database-per-service). 다른 서비스의 행을 FK 로 묶지 않고 **id 만** 들고 있다.
- `order_product.sku_code` 는 catalog 의 `product_size.sku_code` 를 주문 시점에 복사한 값이다 — 원본이 바뀌어도 주문 기록은 그대로다(docs/65).
- `stock_reservation` 은 catalog DB 에 있지만 order 의 `order_id` 를 든다 — 결제 전 재고를 잡아 두는 표다(docs/59).

## 2. 서비스별(자동 생성)

### 2.1 user-service (`users`)

회원 · 역할 · 배송지 · 리뷰 · 위시리스트 · 알려진 기기 · 아웃박스.

<!-- erd:user -->
```mermaid
erDiagram
    known_device {
        bigint device_id PK
        bigint user_id UK
        varchar token_hash UK
        varchar label
        varchar last_ip
        timestamp first_seen_at
        timestamp last_seen_at
    }
    notification {
        bigint id PK
        boolean is_read
        varchar email
        varchar message
        varchar session_id
    }
    outbox_event {
        varchar id PK
        timestamp created_at PK
        varchar aggregate_id
        varchar aggregate_type
        varchar event_type
        jsonb payload
        varchar outbox_status
        varchar trace_context
    }
    password_reset_token {
        bigint id PK
        bigint user_id FK, UK
        varchar token UK
        boolean used
        timestamptz expiry_date
    }
    review {
        bigint review_id PK
        bigint rstyle_id FK, UK
        bigint user_id FK
        real rating
        bigint product_id
        varchar fit
        varchar review
        varchar size
        array images
    }
    review_like {
        bigint like_id PK
        bigint review_id FK, UK
        bigint user_id FK, UK
    }
    review_style {
        bigint rstyle_id PK
        varchar color
        varchar image
    }
    roles {
        bigint role_id PK
        varchar role_name
    }
    shipping_address {
        bigint shipping_address_id PK
        bigint user_id FK
        boolean active
        varchar address1
        varchar address2
        varchar city
        varchar country
        varchar firstname
        varchar lastname
        varchar phone_number
        varchar state
        varchar zip_code
    }
    users {
        bigint user_id PK
        bigint role_id FK
        varchar username UK
        varchar email UK
        date account_expiry_date
        boolean account_non_expired
        boolean account_non_locked
        date credentials_expiry_date
        boolean credentials_non_expired
        boolean email_verified
        boolean enabled
        boolean is_two_factor_enabled
        varchar name
        varchar password
        varchar default_payment_method
        varchar image
        varchar sign_up_method
        varchar two_factor_secret
        int failed_login_attempts
        timestamp lock_expires_at
        timestamp withdrawal_requested_at
        timestamp withdrawn_at
        varchar withdrawal_reason
        varchar tier
        timestamp tier_computed_at
        bigint lifetime_order_count
        bigint recent_paid_amount
        timestamp password_changed_at
    }
    verfication_token {
        bigint id PK
        bigint user_id FK, UK
        timestamp expiry_date
        varchar token
    }
    wishlist {
        bigint wish_item_id PK
        bigint user_id FK
        bigint product_id
        varchar style
    }
    review ||--o{ review_like : "review_id"
    review_style ||--o| review : "rstyle_id"
    roles |o--o{ users : "role_id"
    users ||--o{ review : "user_id"
    users ||--o{ review_like : "user_id"
    users ||--o{ shipping_address : "user_id"
    users ||--o{ wishlist : "user_id"
    users ||--o| password_reset_token : "user_id"
    users ||--o| verfication_token : "user_id"
```
<!-- /erd:user -->

### 2.2 catalog-service — 상품 (`product`)

카테고리 · 상품 · 색상(SKU) · 사이즈(재고 · sku_code) · 재고 예약 · 부족 기록 · 멱등 처리.

<!-- erd:catalog-product -->
```mermaid
erDiagram
    category {
        bigint category_id PK
        timestamp createdat
        timestamp updatedat
        varchar category_name
        varchar createdby
        varchar slug
        varchar updatedby
    }
    processed_event {
        varchar event_id PK
        timestamp processed_at
    }
    product {
        bigint product_id PK
        bigint category_id FK
        real rating
        numeric shipping
        timestamp createdat
        timestamp updatedat
        varchar description
        varchar brand
        varchar createdby
        varchar name
        varchar refundpolicy
        varchar slug
        varchar updatedby
        varchar status
    }
    product_color {
        bigint color_id PK
        timestamp createdat
        timestamp updatedat
        varchar color
        varchar colorimage
        varchar createdby
        varchar updatedby
    }
    product_details {
        bigint pdetail_id PK
        bigint product_id FK
        timestamp createdat
        timestamp updatedat
        varchar createdby
        varchar name
        varchar updatedby
        varchar value
    }
    product_qa {
        bigint qa_id PK
        bigint product_id FK
        timestamp createdat
        timestamp updatedat
        varchar answer
        varchar createdby
        varchar question
        varchar updatedby
    }
    product_size {
        bigint size_id PK
        bigint skuproduct_id FK
        numeric price
        int quantity
        timestamp createdat
        timestamp updatedat
        varchar createdby
        varchar size
        varchar updatedby
        varchar sku_code
    }
    product_sku {
        bigint skuproduct_id PK
        bigint color_id FK
        bigint product_id FK
        int discount
        int sold
        timestamp createdat
        timestamp updatedat
        varchar createdby
        varchar sku
        varchar updatedby
        array images
    }
    product_subcategory {
        bigint proc_subcategory_id PK
        bigint product_id FK
        bigint subcategory_id FK
    }
    stock_reservation {
        bigint reservation_id PK
        bigint order_id UK
        bigint product_id UK
        bigint color_id UK
        varchar size UK
        int qty
        varchar status
        timestamptz expires_at
    }
    stock_shortage {
        varchar event_id PK
        bigint order_id
        text payload
        timestamp published_at
    }
    subcategory {
        bigint subcategory_id PK
        bigint category_id FK
        timestamp createdat
        timestamp updatedat
        varchar createdby
        varchar slug
        varchar subcategory_name
        varchar updatedby
    }
    category ||--o{ product : "category_id"
    category ||--o{ subcategory : "category_id"
    product |o--o{ product_subcategory : "product_id"
    product ||--o{ product_details : "product_id"
    product ||--o{ product_qa : "product_id"
    product ||--o{ product_sku : "product_id"
    product_color |o--o{ product_sku : "color_id"
    product_sku |o--o{ product_size : "skuproduct_id"
    subcategory |o--o{ product_subcategory : "subcategory_id"
```
<!-- /erd:catalog-product -->

### 2.3 catalog-service — 콘텐츠 (`content`)

배너 · 기획전 · 홈 섹션 · 공지. 상품과는 id 로만 잇는다.

<!-- erd:catalog-content -->
```mermaid
erDiagram
    banner {
        bigint banner_id PK
        varchar title
        varchar image_url
        varchar link_url
        int sort_order
        boolean published
        timestamp starts_at
        timestamp ends_at
    }
    event {
        bigint event_id PK
        varchar slug UK
        varchar title
        varchar description
        varchar banner_image_url
        varchar link_url
        varchar status
        timestamp starts_at
        timestamp ends_at
        varchar audience
        varchar coupon_name
        int coupon_discount
        int coupon_valid_days
    }
    event_category {
        bigint event_id PK
        bigint category_id PK
        int discount_rate
    }
    event_product {
        bigint event_id PK
        bigint product_id PK
        int discount_rate
    }
    home_section {
        bigint section_id PK
        varchar title
        varchar kind
        varchar category_name
        varchar subcategory_name
        varchar layout
        int item_limit
        int sort_order
        boolean published
        timestamp starts_at
        timestamp ends_at
    }
    notice {
        bigint notice_id PK
        varchar title
        text body
        boolean pinned
        boolean published
        timestamp published_at
    }
```
<!-- /erd:catalog-content -->

### 2.4 cart-service (`cart`)

<!-- erd:cart -->
```mermaid
erDiagram
    cart {
        bigint cart_id PK
        numeric cart_total
        numeric total_after_discount
        bigint user_id
    }
    cart_product {
        bigint cartproduct_id PK
        bigint cart_id FK
        numeric price
        int qty
        numeric shipping
        int style
        bigint color_id
        bigint product_id
        varchar _uid
        varchar image
        varchar name
        varchar size
        boolean selected
        varchar sku_code
    }
    outbox_event {
        varchar id PK
        timestamp created_at PK
        varchar aggregate_id
        varchar aggregate_type
        varchar event_type
        jsonb payload
        varchar outbox_status
    }
    cart |o--o{ cart_product : "cart_id"
```
<!-- /erd:cart -->

### 2.5 order-service (`orders`)

주문 · 배송지 · 품목(환불 · 반품 · 부족 수량) · 환불 기록 · 결제 아웃박스.

<!-- erd:order -->
```mermaid
erDiagram
    order_address {
        bigint order_address_id PK
        bigint order_id FK, UK
        varchar address1
        varchar address2
        varchar city
        varchar country
        varchar firstname
        varchar lastname
        varchar phone_number
        varchar state
        varchar zip_code
    }
    order_product {
        bigint order_product_id PK
        bigint order_id FK
        numeric price
        int qty
        bigint color_id
        bigint product_id
        varchar image
        varchar name
        varchar size
        int refunded_qty
        int returned_qty
        int restocked_qty
        int discarded_qty
        int short_qty
        varchar sku_code
    }
    order_refund {
        bigint order_refund_id PK
        bigint order_id FK
        varchar refund_ref
        numeric amount
        numeric charged_amount
        varchar currency
        varchar reason
        text items
    }
    orders {
        bigint order_id PK
        bigint order_address_id FK
        varchar tracking_id UK
        numeric shipping_price
        numeric tax_price
        numeric total
        numeric total_before_discount
        bigint customer_id
        timestamp delivered_created_at
        timestamp paid_created_at
        varchar coupon_applied
        varchar order_status
        varchar payment_method
        varchar payment_provider
        varchar provider_payment_id
        varchar refund_id
        timestamp refunded_at
        varchar currency
        numeric charged_amount
        numeric fx_rate
        boolean awaiting_return
        timestamp return_received_at
        timestamp stock_restored_at
        numeric refunded_amount
        numeric refunded_charged_amount
        timestamp deposit_deadline
        varchar depositor_name
        varchar vbank_bank
        varchar vbank_account
        varchar vbank_holder
        varchar pending_payment_ref
        timestamp completed_at
        boolean auto_completed
        varchar shortage_event_id
        timestamp shortage_resolved_at
        timestamp stock_hold_until
    }
    payment_outbox_event {
        varchar id PK
        timestamp created_at PK
        varchar aggregate_id
        varchar aggregate_type
        varchar event_type
        jsonb payload
        varchar outbox_status
        varchar trace_context
    }
    order_address |o--o{ orders : "order_address_id"
    orders ||--o{ order_product : "order_id"
    orders ||--o{ order_refund : "order_id"
    orders ||--o| order_address : "order_id"
```
<!-- /erd:order -->

### 2.6 pay-service (`pay`)

<!-- erd:pay -->
```mermaid
erDiagram
    order_outbox_event {
        varchar id PK
        timestamp created_at PK
        varchar aggregate_id
        varchar aggregate_type
        varchar event_type
        jsonb payload
        varchar outbox_status
        varchar trace_context
    }
    payment {
        bigint payment_id PK
        bigint order_id UK
        varchar tracking_id UK
        numeric amounts
        timestamp cancel_createdat
        bigint customer_id
        timestamp payment_created_at
        varchar payment_status
        varchar payment_type
        varchar payment_provider
        varchar provider_payment_id
    }
```
<!-- /erd:pay -->

### 2.7 coupon-service (`coupon`)

<!-- erd:coupon -->
```mermaid
erDiagram
    coupon {
        bigint coupon_id PK
        bigint user_id UK
        varchar name UK
        int discount
        timestamp end_date
        timestamp start_date
        varchar status
        varchar scope
    }
    coupon_target {
        bigint coupon_target_id PK
        bigint coupon_id UK
        varchar target_type UK
        varchar target_value UK
    }
    processed_event {
        varchar event_id PK
        timestamp processed_at
    }
```
<!-- /erd:coupon -->

