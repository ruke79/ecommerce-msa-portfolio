#!/usr/bin/env python3
"""부하 테스트용 카탈로그 데이터 생성기.

저장소의 시드 CSV(`catalog-service/src/main/resources/csv/`)를 **템플릿으로 읽어**
임의 규모로 증식한다. 값을 새로 지어내지 않고 실제 데이터의 어휘·분포를 재사용하므로,
브랜드명 길이·설명문 크기·색상 수·SKU 당 사이즈 개수 같은 특성이 보존된다.
이게 중요한 이유: 인덱스 선택도와 직렬화 비용은 **값의 분포**에 좌우되는데,
"product-00001" 같은 합성 문자열로는 그 분포가 재현되지 않는다.

참조 무결성을 유지한다:
    category → subcategory → product → product_sku → product_size
                                   └→ product_subcategory
    product_sku.color_id → product_color

사용:
    # CSV 로 출력 (Spring Batch 잡이 그대로 소비 가능)
    ./ops/tools/generate-catalog-data.py --products 50000 --out /tmp/bigcsv

    # PostgreSQL 로 직접 적재 (100k+ 는 이쪽이 현실적 — §속도 참고)
    ./ops/tools/generate-catalog-data.py --products 100000 \\
        --copy "postgresql://product:pw@localhost:5435/productdb?sslmode=require"

    # 기존 데이터에 이어 붙이기 (id 충돌 방지)
    ./ops/tools/generate-catalog-data.py --products 10000 --id-offset 1000000 ...

속도: Spring Batch 잡은 JPA 를 거치므로 10만 상품이면 수십 분이 걸린다.
`--copy` 는 PostgreSQL COPY 로 밀어 넣어 같은 양을 수십 초에 끝낸다.
**적재 경로 자체를 검증**하려면 CSV + 배치 잡을, **부하용 데이터가 필요할 뿐**이면
`--copy` 를 쓸 것.
"""
from __future__ import annotations

import argparse
import csv
import io
import os
import random
import sys
from datetime import date, timedelta
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SEED = REPO / "catalog-service/src/main/resources/csv"

# 시드에서 관찰된 분포. 하드코딩이 아니라 아래 load_seeds() 가 실측으로 덮어쓴다.
DEFAULTS = {"skus_per_product": 2, "sizes_per_sku": 3, "subcats_per_product": 2}


def read_csv(name: str) -> tuple[list[str], list[dict]]:
    path = SEED / name
    if not path.exists() or path.stat().st_size == 0:
        return [], []
    with path.open(encoding="utf-8", newline="") as fh:
        reader = csv.DictReader(fh)
        rows = list(reader)
        return (reader.fieldnames or []), rows


def load_seeds() -> dict:
    """시드 CSV 를 읽고 실제 분포를 계산한다."""
    seeds = {}
    for name in ("categories", "subcategories", "products", "product_colors",
                 "product_skus", "product_sizes", "product_subcategories"):
        header, rows = read_csv(f"{name}.csv")
        seeds[name] = {"header": header, "rows": rows}
        if not rows:
            print(f"  ! {name}.csv 가 비어 있음 — 이 테이블은 생성하지 않는다", file=sys.stderr)

    products = seeds["products"]["rows"]
    skus = seeds["product_skus"]["rows"]
    sizes = seeds["product_sizes"]["rows"]
    psub = seeds["product_subcategories"]["rows"]

    ratios = dict(DEFAULTS)
    if products and skus:
        ratios["skus_per_product"] = max(1, round(len(skus) / len(products)))
    if skus and sizes:
        ratios["sizes_per_sku"] = max(1, round(len(sizes) / len(skus)))
    if products and psub:
        ratios["subcats_per_product"] = max(1, round(len(psub) / len(products)))
    seeds["ratios"] = ratios
    return seeds


def rand_date(rng: random.Random, span_days: int = 730) -> str:
    # created_at 은 파티션 키가 되는 컬럼과 같은 성격이다 — 한 날짜에 몰리면
    # 파티션 프루닝 효과를 측정할 수 없으므로 넓게 퍼뜨린다(docs/09).
    return (date.today() - timedelta(days=rng.randrange(span_days))).isoformat()


def generate(seeds: dict, n_products: int, offset: int, seed: int):
    """시드 행을 순환·조합해 n_products 규모의 데이터셋을 만든다."""
    rng = random.Random(seed)
    r = seeds["ratios"]

    src_products = seeds["products"]["rows"]
    src_skus = seeds["product_skus"]["rows"]
    src_sizes = seeds["product_sizes"]["rows"]
    src_colors = seeds["product_colors"]["rows"]
    src_subcats = seeds["subcategories"]["rows"]
    if not src_products:
        sys.exit("ERROR: products.csv 가 비어 있어 생성할 수 없습니다")

    categories = seeds["categories"]["rows"]
    color_ids = [c["color_id"] for c in src_colors] or ["1"]
    subcat_by_cat: dict[str, list[str]] = {}
    for sc in src_subcats:
        subcat_by_cat.setdefault(sc["category_id"], []).append(sc["subcategory_id"])

    # 색상은 **공유 어휘**다 — 시드의 색상 행을 여러 SKU 가 재사용한다.
    # 이게 가능해진 것은 V4 마이그레이션이 product_sku.color_id 의 UNIQUE 제약을 푼 뒤부터다.
    # 그 전에는 @OneToOne 매핑 때문에 SKU 수가 색상 수(509)로 상한이 걸려 있었다(docs/19 §5.1).
    # 공유 모델이 현실적이기도 하다: 실제 카탈로그는 색상 수십 개를 수천 SKU 가 나눠 쓴다.
    out = {"products": [], "product_skus": [], "product_sizes": [],
           "product_subcategories": []}

    sku_id = offset + 1
    size_id = offset + 1
    psub_id = offset + 1

    for i in range(n_products):
        tpl = src_products[i % len(src_products)]
        pid = offset + i + 1
        cat = tpl["category_id"] if categories else "1"

        # 이름은 템플릿을 재사용하되 접미사로 유일성을 준다 — 어휘 분포는 보존하면서
        # 인덱스 카디널리티는 실제처럼 높게 유지한다.
        out["products"].append({
            "product_id": pid,
            "name": f'{tpl["name"]} #{pid}',
            "description": tpl["description"],
            "brand": tpl["brand"],
            "slug": f'{tpl["slug"]}-{pid}',
            "category_id": cat,
            "refundpolicy": tpl["refund_policy"],
            "rating": tpl["rating"],
            "shipping": tpl["shipping"],
            "created_at": rand_date(rng),
        })

        # 서브카테고리 연결 (해당 카테고리의 것만 — 참조 무결성)
        pool = subcat_by_cat.get(cat) or [s["subcategory_id"] for s in src_subcats]
        if pool:
            for sc in rng.sample(pool, min(r["subcats_per_product"], len(pool))):
                out["product_subcategories"].append(
                    {"proc_subcategory_id": psub_id, "product_id": pid, "subcategory_id": sc})
                psub_id += 1

        # SKU — 한 상품 안에서는 색상이 겹치지 않게 표본추출하고(같은 색 SKU 두 개는
        # 의미가 없다), 상품 간에는 자유롭게 공유한다.
        #
        # 코드 형식은 운영 규칙과 동일하다: <카테고리 접두사 3자>-<id 6자리>
        # (ProductService.skuPrefix / V7 참조). 예전 형식(브랜드-카테고리-id)은 쉼표·
        # 공백이 섞였고, 시드 원본에서는 id 없이 만들어져 130종이 중복이기까지 했다.
        # skuproduct_id 를 번호로 쓰므로 몇 배로 증식해도 유일성이 보장된다.
        cat_name = next((c0["category_name"] for c0 in categories
                         if c0["category_id"] == cat), "") if categories else ""
        cat_alnum = "".join(ch for ch in cat_name if ch.isascii() and ch.isalnum()).upper()
        sku_prefix = cat_alnum[:3] if len(cat_alnum) >= 3 else "PRD"
        n_sku = r["skus_per_product"]
        for c in rng.sample(color_ids, min(n_sku, len(color_ids))):
            stpl = src_skus[(sku_id + i) % len(src_skus)] if src_skus else {}
            out["product_skus"].append({
                "skuproduct_id": sku_id,
                "sku": f"{sku_prefix}-{sku_id:06d}",
                "images": stpl.get("images", ""),
                "discount": stpl.get("discount", "0"),
                "sold": rng.randrange(0, 500),
                "color_id": c,
                "product_id": pid,
                "created_at": rand_date(rng),
            })
            for _ in range(r["sizes_per_sku"]):
                ztpl = src_sizes[size_id % len(src_sizes)] if src_sizes else {}
                out["product_sizes"].append({
                    "size_id": size_id,
                    "size": ztpl.get("size", ""),
                    "quantity": rng.randrange(0, 200),
                    "price": ztpl.get("price", "10.00"),
                    "skuproduct_id": sku_id,
                })
                size_id += 1
            sku_id += 1

    return out


def write_csvs(seeds: dict, data: dict, outdir: Path):
    outdir.mkdir(parents=True, exist_ok=True)
    # 변경 없이 그대로 복사되는 참조 테이블
    for name in ("categories", "subcategories", "product_colors"):
        header, rows = seeds[name]["header"], seeds[name]["rows"]
        if not header:
            continue
        with (outdir / f"{name}.csv").open("w", encoding="utf-8", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=header)
            w.writeheader()
            w.writerows(rows)

    for name, rows in data.items():
        if not rows:
            continue
        with (outdir / f"{name}.csv").open("w", encoding="utf-8", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
            w.writeheader()
            w.writerows(rows)
        print(f"  {name}.csv  {len(rows):,}행")

    # 배치 잡이 읽지만 시드가 비어 있는 파일들 — 헤더만 만들어 잡이 깨지지 않게 한다
    for name, cols in (("product_details", ["pdetail_id", "name", "value", "product_id"]),
                       ("product_qas", ["qa_id", "question", "answer", "product_id"])):
        f = outdir / f"{name}.csv"
        if not f.exists():
            f.write_text(",".join(cols) + "\n", encoding="utf-8")


def copy_load(data: dict, seeds: dict, dsn: str):
    """PostgreSQL COPY 로 직접 적재. 대량에서 배치 잡보다 수십 배 빠르다."""
    try:
        import psycopg2                                  # noqa: F401
    except ImportError:
        sys.exit("ERROR: --copy 에는 psycopg2 가 필요합니다  (pip install psycopg2-binary)")
    import psycopg2

    # ⚠️ DB 컬럼명은 CSV 헤더와 다르다. CSV 는 배치 잡이 DTO 를 거쳐 매핑하지만
    # COPY 는 테이블에 직접 넣으므로 **엔티티가 만든 실제 컬럼명**을 써야 한다:
    #   refund_policy → refundpolicy, color_image → colorimage,
    #   product_subcategories.id → proc_subcategory_id
    # 또 모든 테이블이 BaseEntity 를 상속해 createdat/updatedat/version 감사 컬럼을 갖는다.
    # version 은 낙관적 잠금용이라 NULL 이면 이후 UPDATE 가 실패한다 — 반드시 0 을 넣는다.
    # (컬럼명은 실측으로 확인했다. 스키마가 바뀌면 psql \\d product.product 로 재확인할 것.)
    def pg_array(value: str) -> str:
        """콤마로 이어진 문자열을 PostgreSQL 배열 리터럴로 바꾼다.

        product_sku.images 는 text[] 컬럼인데 CSV 에는 "a.jpg,b.jpg" 로 들어 있다.
        COPY 는 그 값을 배열로 파싱하려다 'malformed array literal' 로 실패한다 —
        배치 잡은 DTO 에서 split 해 넘기므로 이 차이가 드러나지 않았다(실측).
        백슬래시(윈도우 경로)는 배열 리터럴 안에서 이스케이프가 필요하다.
        """
        if not value:
            return "{}"
        items = []
        for part in value.split(","):
            part = part.strip().replace("\\", "\\\\").replace('"', '\\"')
            items.append(f'"{part}"')
        return "{" + ",".join(items) + "}"

    def audited(rows, array_cols=()):
        for r in rows:
            r.setdefault("version", 0)
            r["createdat"] = r.pop("created_at", None) or ""
            for c in array_cols:
                r[c] = pg_array(r.get(c, ""))
        return rows

    tables = [
        ("product.product",
         ["product_id", "name", "description", "brand", "slug", "category_id",
          "refundpolicy", "rating", "shipping", "createdat", "version"],
         audited(data["products"])),
        ("product.product_sku",
         ["skuproduct_id", "sku", "images", "discount", "sold", "color_id",
          "product_id", "createdat", "version"],
         audited(data["product_skus"], ("images",))),
        ("product.product_size",
         ["size_id", "size", "quantity", "price", "skuproduct_id", "version"],
         audited(data["product_sizes"])),
        ("product.product_subcategory",
         ["proc_subcategory_id", "product_id", "subcategory_id", "version"],
         audited(data["product_subcategories"])),
    ]

    conn = psycopg2.connect(dsn)
    conn.autocommit = False
    try:
        with conn.cursor() as cur:
            for table, cols, rows in tables:
                if not rows:
                    continue
                buf = io.StringIO()
                w = csv.writer(buf)
                for row in rows:
                    w.writerow([row.get(c, "") for c in cols])
                buf.seek(0)
                try:
                    cur.copy_expert(
                        f"COPY {table} ({','.join(cols)}) FROM STDIN WITH CSV", buf)
                    print(f"  {table}  {len(rows):,}행")
                except psycopg2.Error as e:
                    conn.rollback()
                    sys.exit(f"ERROR: {table} 적재 실패 — {e}\n"
                             f"  컬럼명이 실제 스키마와 다를 수 있습니다. "
                             f"psql 에서 \\d {table} 로 확인하세요.")
        conn.commit()
        print("  커밋 완료")
        # 통계를 갱신하지 않으면 플래너가 옛 카디널리티로 계획을 세워
        # 인덱스가 있어도 Seq Scan 을 고른다 — 대량 적재 후 반드시 필요하다.
        conn.autocommit = True
        with conn.cursor() as cur:
            cur.execute("ANALYZE product.product")
            cur.execute("ANALYZE product.product_sku")
            cur.execute("ANALYZE product.product_size")
        print("  ANALYZE 완료 (플래너 통계 갱신)")
    finally:
        conn.close()


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--products", type=int, default=10000, help="생성할 상품 수 (기본 10000)")
    ap.add_argument("--out", type=Path, help="CSV 출력 디렉터리")
    ap.add_argument("--copy", metavar="DSN", help="PostgreSQL DSN — COPY 로 직접 적재")
    ap.add_argument("--id-offset", type=int, default=1_000_000,
                    help="id 시작값. 기존 데이터와 충돌하지 않게 크게 잡는다 (기본 1000000)")
    ap.add_argument("--seed", type=int, default=42, help="난수 시드 — 재현 가능한 데이터셋")
    args = ap.parse_args()

    if not args.out and not args.copy:
        ap.error("--out 또는 --copy 중 하나는 필요합니다")

    print(f">>> 시드 읽기: {SEED}")
    seeds = load_seeds()
    r = seeds["ratios"]
    print(f"    실측 분포 — 상품당 SKU {r['skus_per_product']}, "
          f"SKU당 사이즈 {r['sizes_per_sku']}, 상품당 서브카테고리 {r['subcats_per_product']}")

    print(f">>> 생성: 상품 {args.products:,}개 (id {args.id_offset + 1} 부터)")
    data = generate(seeds, args.products, args.id_offset, args.seed)
    total = sum(len(v) for v in data.values())
    print(f"    총 {total:,}행")

    if args.out:
        print(f">>> CSV 출력: {args.out}")
        write_csvs(seeds, data, args.out)
    if args.copy:
        print(">>> PostgreSQL 적재")
        copy_load(data, seeds, args.copy)

    print("\n>>> 다음 단계")
    if args.out:
        print(f"    배치 잡으로 적재하려면 클래스패스의 csv/ 를 {args.out} 로 교체하거나,")
        print(f"    BATCH_CSV_LOCATION={args.out} 로 지정 (catalog-service).")
    print("    적재 후 부하 측정:  ./ops/14-load-test.sh")
    print("    ⚠️ 대량 적재 후에는 ES 재색인이 필요하다:  ./ops/03-enable-es-search.sh")


if __name__ == "__main__":
    main()
