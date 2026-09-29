-- 결제 전 재고 예약 (docs/59).
--
-- 예전에는 재고가 **결제 뒤에** 줄었다. 마지막 1개를 두 사람이 동시에 결제하면 둘 다 결제는 성공하고, 뒤의 차감이
-- 모자라 자동 환불로 보상했다(docs/53 §7). 돈은 돌아가지만 손님은 "샀는데 취소됐다" 를 겪는다.
--
-- 이제 주문을 만들 때 수량을 **잡아 둔다.** 결제가 확정되면(product-update 의 차감) 예약을 확정(COMMITTED)으로 바꾸고,
-- 주문이 취소되면 풀고(RELEASED), 아무도 오지 않으면 만료 잡이 푼다. 남은 수량은
--
--     product_size.quantity − (이 옵션의 HELD 이고 아직 안 끝난 예약의 합)
--
-- 이다. 예약은 수량을 **옮기지 않는다** — quantity 는 여전히 창고의 실물이고, 예약은 "이만큼은 누가 사는 중" 이라는 표시다.
-- 그래서 만료·취소는 행 상태만 바꾸면 되고, 되돌릴 차감이 없다.
--
-- 한 주문의 같은 옵션은 한 행이다(유니크). 재시도는 같은 행의 만료를 늘린다.

CREATE TABLE IF NOT EXISTS product.stock_reservation (
    reservation_id bigserial    PRIMARY KEY,
    order_id       bigint       NOT NULL,
    product_id     bigint       NOT NULL,
    color_id       bigint       NOT NULL,
    size           varchar(255) NOT NULL,
    qty            integer      NOT NULL,
    status         varchar(16)  NOT NULL DEFAULT 'HELD',
    expires_at     timestamptz  NOT NULL,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_stock_reservation_qty CHECK (qty > 0),
    CONSTRAINT ck_stock_reservation_status CHECK (status IN ('HELD', 'COMMITTED', 'RELEASED')),
    CONSTRAINT uk_stock_reservation_line UNIQUE (order_id, product_id, color_id, size)
);

-- 남은 수량 계산: 이 옵션의 살아 있는 예약 합.
CREATE INDEX IF NOT EXISTS idx_stock_reservation_held_option
    ON product.stock_reservation (product_id, color_id, size) WHERE status = 'HELD';

-- 만료 잡.
CREATE INDEX IF NOT EXISTS idx_stock_reservation_held_expiry
    ON product.stock_reservation (expires_at) WHERE status = 'HELD';
