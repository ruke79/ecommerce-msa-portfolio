#!/usr/bin/env python3
"""부하 테스트용 주문 이력 대량 생성기 — 데모 사용자(ops/48)마다 지난 주문을 쌓는다(docs/83 §2.4).

왜: order 시나리오(docs/83 §2)는 주문 표가 비어 있는 채로 쟀다 — 주문 · 주문 줄 · 배송지가 수백만 줄이 된 뒤의 주문 생성 ·
상세 · 내 주문 목록은 재지 않았다(상품 쪽은 ops/44 로 1,000만까지 쟀다). 이 생성기는 그 이력만 만든다 — 결제 · 배송 · 쿠폰 ·
재고 · 읽기 모델(saga-projection)에는 아무것도 쓰지 않는다. 지난 주문이라 아웃박스 이벤트도 없다.

무엇을 만드나(주문 하나당):

  orders.order_address   배송지 사본 하나 — 주문이 가리킨다(V25: orders.order_address_id, 옛 열 order_id 는 비운다)
  orders.orders          끝난 주문 — 상태는 구매 확정 · 배송 완료 · 취소 · 환불 · 결제 완료(입금 대기 · 결제 전은 만들지 않는다:
                         입금 대기 한도(3건)와 재고 예약에 걸린다)
  orders.order_product   줄 1 ~ 3개 — 상품 id 는 ops/44 의 생성 범위(--product-offset, --products)에서

외부 라이브러리가 없다. **psql 스크립트를 stdout 으로 낸다** — ops/54-bulk-orders.sh 가 PostgreSQL 파드의 psql 로 흘려 넣는다.

규칙:

  · id      주문 · 배송지는 예약 범위 [--id-offset, …) 의 같은 번호, 주문 줄은 주문 id × 4 + 줄 번호(1 ~ 3)의 범위(--line-offset).
            앱의 TSID(약 10^18)와 겹치지 않는다. purge 는 이 범위 **그리고** tracking_id 'demo-' 인 행만 지운다.
  · 손님    customer_id = --user-offset + 사용자 번호(ops/48 의 demo<번호>@demo.invalid 와 같은 번호).
  · 날짜    2년 안에 흩되 손님마다 주문 id 가 커지는 순서로 — 내 주문 목록(주문 id 내림차순)이 시간 순과 같다.
  · 다시 실행해도 같다 — 스테이징 표에 COPY 한 뒤 ON CONFLICT DO NOTHING. 묶음마다 시드가 고정이라 같은 번호는 같은 주문이다.

사용(보통은 ops/54-bulk-orders.sh 가 부른다):

    ./ops/tools/generate-demo-orders.py --users 100000 --orders-per-user 20 --chunk-index 0 > chunk0.sql
    ./ops/tools/generate-demo-orders.py --users 100000 --orders-per-user 20 --estimate
    ./ops/tools/generate-demo-orders.py --users 100000 --orders-per-user 20 --purge

[JA] 負荷テスト用の注文履歴の大量生成器 — デモユーザーごとに過去の注文を積む。決済 · 配送 · クーポン · 在庫 · 読みモデルには何も書かない。
psql スクリプトを stdout に出し、ops/54 が PostgreSQL Pod の psql に流し込む。
"""
from __future__ import annotations

import argparse
import random
import sys
from datetime import datetime, timedelta, timezone
from decimal import Decimal

DEFAULT_ORDER_OFFSET = 8_000_000_000
DEFAULT_USER_OFFSET = 7_000_000_000   # generate-demo-users.py 의 DEFAULT_OFFSET
DEFAULT_PRODUCT_OFFSET = 1_000_000    # generate-catalog-data.py · ops/44 의 기본 ID_OFFSET
TSID_FLOOR = 1 << 40
LINES_MAX = 3
MAX_AGE_DAYS = 730
# 실측(PG16, 주문 20만 · 줄 40만 — 2026-10-20 로컬 컨테이너, 209MB): 주문 + 배송지 + 줄(평균 2) + 인덱스. estimate 가 쓴다.
BYTES_PER_ORDER = 1100

# 끝난 주문의 상태 — (상태, 비율). 결제 전(NOT_PROCESSED) · 입금 대기는 만들지 않는다.
STATUSES = [("COMPLETED", 0.62), ("DELIVERED", 0.12), ("CANCELED", 0.12), ("REFUNDED", 0.06),
            ("PAID", 0.04), ("PROCESSING", 0.02), ("DISPATCHED", 0.02)]
PAID_STATES = {"PAID", "PROCESSING", "DISPATCHED", "DELIVERED", "COMPLETED", "REFUNDED"}

CITIES = [("서울특별시", "강남구", "06"), ("서울특별시", "마포구", "04"), ("부산광역시", "해운대구", "48"),
          ("경기도", "성남시 분당구", "13"), ("東京都", "渋谷区", "150"), ("大阪府", "大阪市北区", "530")]

ADDR_COLS = ("order_address_id, firstname, lastname, phone_number, address1, address2, city, state, zip_code, country, "
             "created_at, updated_at, version, created_by")
ORDER_COLS = ("order_id, customer_id, tracking_id, order_status, payment_method, coupon_applied, shipping_price, "
              "total_before_discount, total, order_address_id, created_at, updated_at, paid_created_at, delivered_created_at, "
              "completed_at, auto_completed, refunded_at, refunded_amount, depositor_name, version, created_by")
LINE_COLS = "order_product_id, order_id, product_id, color_id, name, size, image, qty, price, created_at, updated_at, version, created_by"


def csv_field(v) -> str:
    """COPY … (FORMAT csv) 한 칸 — None 은 빈 칸(NULL), 문자열은 따옴표로."""
    if v is None:
        return ""
    if isinstance(v, bool):
        return "t" if v else "f"
    if isinstance(v, (int, Decimal)):
        return str(v)
    if isinstance(v, datetime):
        return v.isoformat(sep=" ", timespec="seconds")
    return '"' + str(v).replace('"', '""') + '"'


def csv_line(values) -> str:
    return ",".join(csv_field(v) for v in values)


def check_args(a) -> None:
    if a.users <= 0 or a.orders_per_user <= 0:
        sys.exit("--users · --orders-per-user 는 1 이상")
    if a.chunk <= 0:
        sys.exit("--chunk 는 1 이상")
    total = a.users * a.orders_per_user
    a.total = total
    for name, lo, hi in (("--id-offset", a.id_offset, a.id_offset + total),
                         ("--line-offset", a.line_offset, a.line_offset + (total + 1) * (LINES_MAX + 1)),
                         ("--user-offset", a.user_offset, a.user_offset + a.users)):
        if lo <= 0 or hi >= TSID_FLOOR:
            sys.exit(f"{name} 범위가 앱의 TSID 와 겹칠 수 있다: [{lo}, {hi}) — {TSID_FLOOR} 아래여야 한다")
    if a.products <= 0:
        sys.exit("--products 는 1 이상(ops/44 로 넣은 상품 수)")


def chunk_range(a) -> tuple[int, int]:
    """이 묶음의 사용자 번호 [start, end)."""
    start = a.chunk_index * a.chunk
    if start >= a.users:
        sys.exit(f"--chunk-index {a.chunk_index} 는 범위 밖이다(묶음 수 {(a.users + a.chunk - 1) // a.chunk})")
    return start, min(start + a.chunk, a.users)


def pick_status(rng: random.Random) -> str:
    x, acc = rng.random(), 0.0
    for s, w in STATUSES:
        acc += w
        if x < acc:
            return s
    return STATUSES[0][0]


def money(x: float) -> Decimal:
    return Decimal(f"{x:.2f}")


def build_chunk(a, now: datetime):
    """이 묶음의 (배송지, 주문, 줄) 행 — 순수 함수(시험이 돌려 본다)."""
    start, end = chunk_range(a)
    rng = random.Random(a.seed * 1_000_003 + a.chunk_index)
    addrs, orders, lines = [], [], []
    for u in range(start, end):
        customer = a.user_offset + u
        # 손님마다 날짜를 정렬해 주문 id 와 같은 순서로
        ages = sorted((rng.uniform(1, MAX_AGE_DAYS) for _ in range(a.orders_per_user)), reverse=True)
        for j, age in enumerate(ages):
            g = u * a.orders_per_user + j
            oid = a.id_offset + g
            created = (now - timedelta(days=age)).replace(microsecond=0)
            status = pick_status(rng)
            state, city, zip_prefix = rng.choice(CITIES)
            addrs.append([oid, "데모", f"손님{u}", f"010-{rng.randint(1000, 9999)}-{rng.randint(1000, 9999)}",
                          f"{city} 데모로 {rng.randint(1, 400)}", f"{rng.randint(1, 30)}층", city, state,
                          f"{zip_prefix}{rng.randint(100, 999)}", "KR", created, created, 0, "demo-generator"])
            subtotal = Decimal("0")
            for n in range(1, rng.randint(1, LINES_MAX) + 1):
                pid = a.product_offset + rng.randint(1, a.products)
                qty = rng.randint(1, 3)
                price = money(rng.uniform(5, 300))
                subtotal += price * qty
                lines.append([a.line_offset + (g + 1) * (LINES_MAX + 1) + n, oid, pid, None, f"데모 상품 {pid}", "", "",
                              qty, price, created, created, 0, "demo-generator"])
            shipping = Decimal("0.00") if subtotal >= 50 else Decimal("5.00")
            total = subtotal + shipping
            paid = created + timedelta(minutes=rng.randint(1, 600)) if status in PAID_STATES else None
            delivered = paid + timedelta(days=rng.randint(1, 5)) if status in ("DELIVERED", "COMPLETED") else None
            completed = delivered + timedelta(days=rng.randint(1, 7)) if status == "COMPLETED" else None
            refunded = paid + timedelta(days=rng.randint(1, 10)) if status == "REFUNDED" else None
            updated = completed or refunded or delivered or paid or created
            orders.append([oid, customer, f"demo-{oid}", status, "cash", "", shipping, subtotal, total, oid,
                           created, updated, paid, delivered, completed, bool(completed and rng.random() < 0.7),
                           refunded, total if refunded else Decimal("0"), "데모" if paid else None, 0, "demo-generator"])
    return addrs, orders, lines


def emit_chunk(a, out) -> None:
    now = datetime.now(timezone.utc).replace(tzinfo=None)
    addrs, orders, lines = build_chunk(a, now)
    start, end = chunk_range(a)
    w = out.write
    w(f"-- generate-demo-orders: 묶음 {a.chunk_index} — 손님 {a.user_offset + start} … {a.user_offset + end - 1}, 주문 {len(orders)}\n")
    w("\\set ON_ERROR_STOP on\nBEGIN;\nSET LOCAL synchronous_commit = off;\n")
    w("CREATE TEMP TABLE demo_addr_stage (LIKE orders.order_address INCLUDING DEFAULTS) ON COMMIT DROP;\n")
    w("CREATE TEMP TABLE demo_order_stage (LIKE orders.orders INCLUDING DEFAULTS) ON COMMIT DROP;\n")
    w("CREATE TEMP TABLE demo_line_stage (LIKE orders.order_product INCLUDING DEFAULTS) ON COMMIT DROP;\n")
    for table, cols, rows in (("demo_addr_stage", ADDR_COLS, addrs), ("demo_order_stage", ORDER_COLS, orders),
                              ("demo_line_stage", LINE_COLS, lines)):
        w(f"COPY {table} ({cols}) FROM stdin WITH (FORMAT csv);\n")
        for r in rows:
            w(csv_line(r) + "\n")
        w("\\.\n")
    # 배송지가 먼저(주문이 가리킨다 — V25), 주문, 줄. 이미 있는 주문(다시 실행)은 건너뛰고 그 줄도 넣지 않는다.
    w(f"INSERT INTO orders.order_address ({ADDR_COLS}) SELECT {ADDR_COLS} FROM demo_addr_stage ON CONFLICT DO NOTHING;\n")
    w(f"INSERT INTO orders.orders ({ORDER_COLS}) SELECT {ORDER_COLS} FROM demo_order_stage ON CONFLICT DO NOTHING;\n")
    w(f"INSERT INTO orders.order_product ({LINE_COLS})\n"
      f"SELECT {', '.join('l.' + c.strip() for c in LINE_COLS.split(','))} FROM demo_line_stage l\n"
      "  JOIN orders.orders o ON o.order_id = l.order_id AND o.tracking_id LIKE 'demo-%'\nON CONFLICT DO NOTHING;\n")
    w("COMMIT;\n")


def emit_purge(a, out) -> None:
    """생성 범위만 — 묶음마다 커밋(긴 트랜잭션 하나로 WAL 이 폭증하지 않게). 줄 → 주문 → 배송지(주문이 배송지를 가리킨다)."""
    lo, hi = a.id_offset, a.id_offset + a.total - 1
    step = 200_000
    w = out.write
    w(f"-- generate-demo-orders: 삭제 — 주문 {lo} … {hi} 중 tracking_id 'demo-' 인 것만\n\\set ON_ERROR_STOP on\n")
    for b in range(lo, hi + 1, step):
        e = min(hi, b + step - 1)
        cond = f"order_id BETWEEN {b} AND {e} AND tracking_id LIKE 'demo-%'"
        w("BEGIN;\n")
        w(f"CREATE TEMP TABLE demo_purge ON COMMIT DROP AS SELECT order_id, order_address_id FROM orders.orders WHERE {cond};\n")
        w("DELETE FROM orders.order_product WHERE order_id IN (SELECT order_id FROM demo_purge);\n")
        w("DELETE FROM orders.order_refund WHERE order_id IN (SELECT order_id FROM demo_purge);\n")
        w("DELETE FROM orders.orders WHERE order_id IN (SELECT order_id FROM demo_purge);\n")
        w("DELETE FROM orders.order_address WHERE order_address_id IN (SELECT order_address_id FROM demo_purge);\n")
        w(f"COMMIT;\n\\echo '  주문 {b} … {e} 지움'\n")
    w("ANALYZE orders.orders;\nANALYZE orders.order_product;\nANALYZE orders.order_address;\n")


def estimate(a) -> None:
    chunks = (a.users + a.chunk - 1) // a.chunk
    print(f"손님 {a.users:,} 명 × {a.orders_per_user} = 주문 {a.total:,} 개(줄 약 {a.total * 2:,}) · 묶음 {chunks} 개(묶음당 손님 {a.chunk:,})")
    print(f"주문 id {a.id_offset} … {a.id_offset + a.total - 1} · 손님 id {a.user_offset} … · 상품 id {a.product_offset + 1} … {a.product_offset + a.products}")
    print(f"용량(실측 {BYTES_PER_ORDER} B/주문, 줄 · 배송지 · 인덱스 포함) ≈ {a.total * BYTES_PER_ORDER / 1e9:.2f} GB — WAL 은 적재 중 잠깐 그만큼 더")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--users", type=int, required=True, help="주문을 쌓을 데모 사용자 수(ops/48 의 USERS 이하)")
    ap.add_argument("--orders-per-user", type=int, default=20, help="손님당 주문 수 (기본 20)")
    ap.add_argument("--user-offset", type=int, default=DEFAULT_USER_OFFSET, help=f"데모 사용자 id 의 시작 (기본 {DEFAULT_USER_OFFSET})")
    ap.add_argument("--id-offset", type=int, default=DEFAULT_ORDER_OFFSET, help=f"주문 · 배송지 id 의 시작 (기본 {DEFAULT_ORDER_OFFSET})")
    ap.add_argument("--line-offset", type=int, default=DEFAULT_ORDER_OFFSET * 4, help="주문 줄 id 의 시작 (기본 주문 시작 × 4)")
    ap.add_argument("--product-offset", type=int, default=DEFAULT_PRODUCT_OFFSET, help="상품 id 의 시작(ops/44 의 ID_OFFSET)")
    ap.add_argument("--products", type=int, default=1_000_000, help="상품 수(ops/44 의 PRODUCTS) — 줄의 상품은 이 범위에서")
    ap.add_argument("--chunk", type=int, default=1000, help="한 트랜잭션의 손님 수 (기본 1000)")
    ap.add_argument("--chunk-index", type=int, help="이 묶음의 SQL 을 낸다(0 부터)")
    ap.add_argument("--seed", type=int, default=83, help="난수 시드 — 같은 번호는 언제나 같은 주문")
    ap.add_argument("--estimate", action="store_true", help="행 수 · 용량만")
    ap.add_argument("--purge", action="store_true", help="orders DB 의 삭제 SQL")
    a = ap.parse_args()
    check_args(a)
    if a.estimate:
        estimate(a)
    elif a.purge:
        emit_purge(a, sys.stdout)
    elif a.chunk_index is not None:
        emit_chunk(a, sys.stdout)
    else:
        sys.exit("--chunk-index · --estimate · --purge 중 하나")


if __name__ == "__main__":
    main()
