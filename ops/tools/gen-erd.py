#!/usr/bin/env python3
"""Flyway 마이그레이션에서 **실제 스키마의 ERD** 를 만들어 docs/69 에 써 넣는다(Mermaid).

왜 필요한가
-----------
README 의 ERD 는 GitHub 에 올린 **이미지 첨부**(user-attachments) 한 장이었다 — 저장소 밖에 있어 깨져도
고칠 방법이 없었고, 2024년 그림이라 그 뒤의 스키마(TSID · 아웃박스 · 재고 예약 · 사이즈 단위 SKU)가 없었다.
이제 ERD 는 마이그레이션을 **실제로 적용한 DB** 에서 뽑는다. 손으로 그리지 않으므로 어긋나지 않는다.

    python3 ops/tools/gen-erd.py            # docs/69-erd.md · docs/69-erd.ko.md 의 표시 구간을 새로 쓴다
    python3 ops/tools/gen-erd.py --check    # 문서가 스키마와 다르면 1 로 끝난다(CI 용)

하는 일
-------
  1. postgres:16-alpine 컨테이너를 띄운다(Docker 필요, 끝나면 지운다).
  2. 서비스마다 DB 를 만들고 V*.sql 을 버전 순서대로 적용한다.
  3. pg_catalog 에서 표 · 컬럼 · PK/FK/UK · FK 관계를 읽어 Mermaid erDiagram 으로 만든다.
     - 월 파티션(_YYYY_MM · _default)과 Spring Batch 메타 표는 뺀다.
     - 공통 감사 컬럼(created_at/by · updated_at/by · version)은 뺀다 — 키가 아니면.
  4. 문서의 <!-- erd:KEY --> ... <!-- /erd:KEY --> 구간만 바꾼다. 개요(서비스 사이의 논리 참조)는 손으로 관리한다.
"""
import json
import pathlib
import re
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[2]
DOCS = [ROOT / "docs/69-erd.md", ROOT / "docs/69-erd.ko.md"]
CONTAINER = "gen-erd-pg"

# (문서 구간 키, 서비스, DB, 스키마들)
SPEC = [
    ("user", "user-service", "userdb", ["users"]),
    ("catalog-product", "catalog-service", "catalogdb", ["product"]),
    ("catalog-content", "catalog-service", "catalogdb", ["content"]),
    ("cart", "cart-service", "cartdb", ["cart"]),
    ("order", "order-service", "orderdb", ["orders"]),
    ("pay", "pay-service", "paydb", ["pay"]),
    ("coupon", "coupon-service", "coupondb", ["coupon"]),
]
TYPES = {"character varying": "varchar", "timestamp without time zone": "timestamp",
         "timestamp with time zone": "timestamptz", "integer": "int", "double precision": "double",
         "character": "char", "USER-DEFINED": "enum", "ARRAY": "array"}
PARTITION = re.compile(r"(_\d{4}_\d{2}|_default)$")
AUDIT = {"created_at", "created_by", "updated_at", "updated_by", "version"}
KEY_ORDER = ["PK", "FK", "UK"]


def sh(*args, stdin=None):
    return subprocess.run(args, input=stdin, capture_output=True, text=True, check=True).stdout


def psql(db, sql):
    out = sh("docker", "exec", CONTAINER, "psql", "-U", "postgres", "-d", db, "-At", "-F", "\t", "-c", sql)
    return [line.split("\t") for line in out.splitlines() if line]


def migrate():
    subprocess.run(["docker", "rm", "-f", CONTAINER], capture_output=True)
    sh("docker", "run", "-d", "--name", CONTAINER, "-e", "POSTGRES_PASSWORD=erd", "postgres:16-alpine")
    for _ in range(60):
        if subprocess.run(["docker", "exec", CONTAINER, "pg_isready", "-U", "postgres"], capture_output=True).returncode == 0:
            break
        time.sleep(1)
    time.sleep(2)
    done = set()
    for _, service, db, _ in SPEC:
        if db in done:
            continue
        done.add(db)
        sh("docker", "exec", CONTAINER, "psql", "-U", "postgres", "-q", "-c", f"create database {db}")
        files = sorted((ROOT / service / "src/main/resources/db/migration").glob("V*.sql"),
                       key=lambda p: [int(x) for x in re.findall(r"\d+", p.name.split("__")[0])])
        for f in files:
            r = subprocess.run(["docker", "exec", "-i", CONTAINER, "psql", "-U", "postgres", "-d", db, "-q"],
                               input=f.read_text(), capture_output=True, text=True)
            if "ERROR" in r.stderr and "seed_" not in r.stderr:   # 데이터 보정 스크립트의 임시 표 오류는 스키마와 무관
                print(f"경고: {service}/{f.name}: {r.stderr.strip().splitlines()[0]}", file=sys.stderr)


def diagram(db, schemas):
    names = ",".join(f"'{s}'" for s in schemas)
    tables = [(s, t) for s, t in psql(db, f"""select c.relnamespace::regnamespace::text, c.relname from pg_class c
        where c.relkind in ('r','p') and not c.relispartition
          and c.relnamespace::regnamespace::text in ({names}) order by 1, 2""") if not PARTITION.search(t)]
    known = set(tables)
    lines, rels = ["erDiagram"], set()
    for s, t in tables:
        keys = {}
        for col, kind in psql(db, f"""select a.attname, con.contype from pg_constraint con
                join pg_attribute a on a.attrelid = con.conrelid and a.attnum = any(con.conkey)
                where con.conrelid = '{s}.{t}'::regclass and con.contype in ('p','f','u')"""):
            keys.setdefault(col, set()).add({"p": "PK", "f": "FK", "u": "UK"}[kind])
        cols = psql(db, f"""select column_name, data_type from information_schema.columns
                where table_schema = '{s}' and table_name = '{t}' order by ordinal_position""")
        rank = lambda c: min([KEY_ORDER.index(k) for k in keys.get(c, [])] or [3])
        lines.append(f"    {t} {{")
        for col, dt in sorted((c for c in cols if c[0] not in AUDIT or keys.get(c[0])), key=lambda c: rank(c[0])):
            k = ", ".join(sorted(keys.get(col, []), key=KEY_ORDER.index))
            lines.append(f"        {TYPES.get(dt, dt.replace(' ', '_'))} {col}{' ' + k if k else ''}")
        lines.append("    }")
        for col, ps, pt, unique, notnull in psql(db, f"""select a.attname, pc.relnamespace::regnamespace::text, pc.relname,
                exists(select 1 from pg_constraint u where u.conrelid = con.conrelid and u.contype in ('p','u')
                       and u.conkey = con.conkey), a.attnotnull
                from pg_constraint con join pg_attribute a on a.attrelid = con.conrelid and a.attnum = con.conkey[1]
                join pg_class pc on pc.oid = con.confrelid
                where con.conrelid = '{s}.{t}'::regclass and con.contype = 'f'"""):
            if (ps, pt) in known:
                left = "||" if notnull == "t" else "|o"
                right = "o|" if unique == "t" else "o{"
                rels.add(f'    {pt} {left}--{right} {t} : "{col}"')
    return "\n".join(lines + sorted(rels))


def main():
    check = "--check" in sys.argv
    try:
        migrate()
        blocks = {key: diagram(db, schemas) for key, _, db, schemas in SPEC}
    finally:
        subprocess.run(["docker", "rm", "-f", CONTAINER], capture_output=True)
    changed = False
    for doc in DOCS:
        text = doc.read_text()
        new = text
        for key, block in blocks.items():
            pattern = re.compile(rf"(<!-- erd:{re.escape(key)} -->\n)```mermaid\n.*?\n```(\n<!-- /erd:{re.escape(key)} -->)", re.S)
            if not pattern.search(new):
                sys.exit(f"{doc.name}: <!-- erd:{key} --> 구간이 없다")
            new = pattern.sub(lambda m: m.group(1) + "```mermaid\n" + block + "\n```" + m.group(2), new)
        if new != text:
            changed = True
            if not check:
                doc.write_text(new)
                print(f"갱신: {doc.relative_to(ROOT)}")
    if check and changed:
        sys.exit("ERD 가 스키마와 다르다 — python3 ops/tools/gen-erd.py 로 다시 만든다")
    if not changed:
        print("ERD 가 스키마와 같다")


if __name__ == "__main__":
    main()
