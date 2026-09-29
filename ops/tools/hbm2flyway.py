#!/usr/bin/env python3
"""Hibernate 가 생성한 DDL 스크립트를 **멱등한** Flyway 베이스라인 마이그레이션으로 바꾼다.

왜 필요한가
-----------
`ddl-auto: update` 를 버리고 `validate` 로 가려면, Flyway 가 **엔티티와 정확히 일치하는**
스키마를 만들 수 있어야 한다. 그 DDL 을 손으로 옮겨 적으면 반드시 어긋나므로
(컬럼 하나만 틀려도 기동 시 validate 실패), Hibernate 자신에게 받아 적게 한다:

    ./gradlew -PexportSchema -PwithContainers :order-service:test --tests '*ApplicationTests*'
    ./ops/tools/hbm2flyway.py build/schema/order-service.sql \\
        -o order-service/src/main/resources/db/migration/V2__baseline_schema.sql

그런데 Hibernate 의 산출물은 그대로 쓸 수 없다. 이 저장소의 DB 는 두 가지 상태로 존재한다:

  ① 빈 DB (새 환경·Testcontainers) — 마이그레이션이 전부 만들어야 한다
  ② 이미 `ddl-auto: update` 가 만들어 둔 DB (운영·개발 서버) — 전부 이미 있다

②에 `create table` 을 그냥 던지면 'relation already exists' 로 기동이 죽는다. 그래서 모든
문장을 **이미 있으면 넘어가는** 형태로 바꾼다. 그러면 한 파일이 양쪽 모두에서 올바르다.

변환 규칙
---------
  create schema S              → create schema if not exists S
  create sequence S ...        → create sequence if not exists S ...
  create table T (...)         → create table if not exists T (...)
                                 + 컬럼마다 alter table T add column if not exists
  create index I on T (...)    → create index if not exists I on T (...)
  alter table T add constraint → pg_constraint 로 이름 존재를 확인하는 DO 블록

`add column` 을 덧붙이는 이유: ②의 DB 가 엔티티보다 **뒤처져 있을** 수 있다. create table
은 이미 있는 테이블을 건드리지 않으므로, 컬럼이 빠진 채로 남으면 validate 가 실패한다.
이때 컬럼은 반드시 **nullable 로** 추가한다 — 기존 행이 있는 테이블에 NOT NULL 컬럼은
기본값 없이 붙일 수 없기 때문이다. Hibernate 의 validate 는 널 허용 여부를 보지 않으므로
이것으로 충분하다(테이블/컬럼 존재와 타입만 본다).
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

# ─────────────────────────────────────────────────────────────────────────────
# 파싱
# ─────────────────────────────────────────────────────────────────────────────


def statements(text: str) -> list[str]:
    """세미콜론으로 문장을 나눈다. Hibernate 산출물에는 문자열 리터럴이 없어 단순 분리로 충분하다."""
    out = []
    for raw in text.split(";"):
        s = re.sub(r"\s+", " ", raw.strip())
        if s:
            out.append(s)
    return out


def split_table_body(body: str) -> list[str]:
    """create table 본문을 최상위 콤마로 자른다 (numeric(38,2) 같은 괄호 안 콤마는 무시)."""
    parts, depth, cur = [], 0, []
    for ch in body:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(cur).strip())
            cur = []
        else:
            cur.append(ch)
    if "".join(cur).strip():
        parts.append("".join(cur).strip())
    return parts


# 본문에서 컬럼이 아니라 제약인 줄
CONSTRAINT_LEAD = re.compile(
    r"^(primary\s+key|unique|foreign\s+key|constraint|check|exclude)\b", re.I
)

# serial 계열은 add column 으로 붙일 때 시퀀스를 새로 만들어 버리므로 기본 정수형으로 낮춘다.
SERIAL_TO_PLAIN = {
    "bigserial": "bigint",
    "serial": "integer",
    "smallserial": "smallint",
}


def column_for_add(defn: str) -> tuple[str, str] | None:
    """컬럼 정의를 (이름, add column 에 쓸 타입) 으로. 제약 줄이면 None."""
    if CONSTRAINT_LEAD.match(defn):
        return None
    m = re.match(r'^("?[\w$]+"?)\s+(.*)$', defn)
    if not m:
        return None
    name, rest = m.group(1), m.group(2)

    # nullable 로만 붙인다 (기존 행이 있는 테이블 때문에). 기본값·생성열도 떼어낸다.
    rest = re.sub(r"\bnot\s+null\b", "", rest, flags=re.I)
    rest = re.sub(r"\bdefault\s+.*$", "", rest, flags=re.I)
    rest = re.sub(r"\bgenerated\s+.*$", "", rest, flags=re.I)
    rest = re.sub(r"\bunique\b", "", rest, flags=re.I)
    rest = re.sub(r"\bprimary\s+key\b", "", rest, flags=re.I)
    rest = re.sub(r"\s+", " ", rest).strip()
    if not rest:
        return None

    head = rest.split("(")[0].split()[0].lower()
    if head in SERIAL_TO_PLAIN:
        rest = SERIAL_TO_PLAIN[head] + rest[len(head):]
    return name, rest


# ─────────────────────────────────────────────────────────────────────────────
# 변환
# ─────────────────────────────────────────────────────────────────────────────


def guarded_constraint(stmt: str) -> str | None:
    """alter table ... add constraint NAME ... → 이름이 없을 때만 실행하는 DO 블록."""
    m = re.match(
        r"^alter\s+table\s+(?:if\s+exists\s+)?(\S+)\s+add\s+constraint\s+(\S+)\s+(.*)$",
        stmt,
        re.I,
    )
    if not m:
        return None
    table, name, rest = m.group(1), m.group(2), m.group(3)
    # 테이블이 아직 없을 수도 있다(다른 마이그레이션이 만드는 경우). 그때는 조용히 넘어간다.
    return (
        "DO $$\nBEGIN\n"
        f"    IF to_regclass('{table}') IS NOT NULL\n"
        f"       AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = '{name.lower()}') THEN\n"
        f"        ALTER TABLE {table} ADD CONSTRAINT {name} {rest};\n"
        "    END IF;\n"
        "END $$;"
    )


def convert(text: str, service: str) -> str:
    out: list[str] = []
    add_columns: list[str] = []
    counts = {"schema": 0, "sequence": 0, "table": 0, "index": 0, "constraint": 0, "other": 0}

    for stmt in statements(text):
        low = stmt.lower()

        if low.startswith("create schema"):
            name = stmt.split()[-1]
            out.append(f"CREATE SCHEMA IF NOT EXISTS {name};")
            counts["schema"] += 1

        elif low.startswith("create sequence"):
            out.append(re.sub(r"^create\s+sequence\s+", "CREATE SEQUENCE IF NOT EXISTS ",
                              stmt, flags=re.I) + ";")
            counts["sequence"] += 1

        elif low.startswith("create table"):
            m = re.match(r"^create\s+table\s+(\S+)\s*\((.*)\)$", stmt, re.I | re.S)
            if not m:
                sys.exit(f"ERROR: create table 을 해석하지 못했습니다:\n{stmt[:200]}")
            table, body = m.group(1), m.group(2)
            defns = split_table_body(body)
            pretty = ",\n".join(f"    {d}" for d in defns)
            out.append(f"CREATE TABLE IF NOT EXISTS {table} (\n{pretty}\n);")
            counts["table"] += 1
            for defn in defns:
                col = column_for_add(defn)
                if col:
                    add_columns.append(
                        f"ALTER TABLE {table} ADD COLUMN IF NOT EXISTS {col[0]} {col[1]};")

        elif low.startswith("create index") or low.startswith("create unique index"):
            out.append(re.sub(r"^create\s+(unique\s+)?index\s+",
                              lambda mm: f"CREATE {(mm.group(1) or '').upper()}INDEX IF NOT EXISTS ",
                              stmt, flags=re.I) + ";")
            counts["index"] += 1

        elif low.startswith("alter table"):
            guarded = guarded_constraint(stmt)
            if guarded:
                out.append(guarded)
                counts["constraint"] += 1
            else:
                out.append(f"-- 변환하지 못한 문장 (검토 필요): {stmt};")
                counts["other"] += 1

        else:
            out.append(f"-- 변환하지 못한 문장 (검토 필요): {stmt};")
            counts["other"] += 1

    header = f"""-- {service} 스키마 베이스라인 — Flyway 가 스키마를 소유한다 (docs/20).
--
-- ⚠️ 손으로 쓰지 말 것. Hibernate 가 엔티티에서 생성한 DDL 을 기계 변환한 것이다:
--     ./gradlew -PexportSchema -PwithContainers :{service}:test --tests '*ApplicationTests*'
--     ./ops/tools/hbm2flyway.py build/schema/{service}.sql -o <이 파일>
--
-- 왜 베이스라인이 필요한가: 예전에는 `ddl-auto: update` 가 스키마를 만들었다. 그래서
--   · 스키마 변경이 이력에 남지 않고 (무엇이 언제 왜 바뀌었는지 아무도 모른다)
--   · 운영 DB 에 예고 없는 DDL 이 나가며
--   · 제약을 **지우지는 않아** 잘못된 제약이 영원히 남는다 (V4 가 그 뒤처리였다)
-- 이제 Flyway 가 소유하고 Hibernate 는 `validate` 로 검증만 한다.
--
-- 모든 문장이 멱등이다. 빈 DB 에서는 전부 만들고, `ddl-auto` 가 이미 만들어 둔 DB 에서는
-- 전부 건너뛴다. 그래서 기존 환경도 이 마이그레이션을 그대로 통과한다.
--
-- 파일 끝의 `add column if not exists` 묶음은 기존 DB 가 엔티티보다 **뒤처진** 경우를 메운다.
-- "그건 ddl-auto 랑 뭐가 다르냐"에 대한 답: ddl-auto 는 매 기동마다 그때의 코드에서 DDL 을
-- 새로 유추해 **검토 없이** 실행한다. 이건 커밋된 파일에 적힌 고정된 문장 목록이고, 리뷰를
-- 거치며, Flyway 가 기록하므로 **딱 한 번만** 실행된다. 뒤에 스키마를 또 바꾸려면 새 마이그
-- 레이션을 써야 한다. 컬럼은 반드시 널 허용으로 추가한다 — 기존 행이 있는 테이블에 기본값
-- 없는 NOT NULL 은 붙지 않기 때문이고, validate 는 널 여부가 아니라 존재와 타입만 본다.
--
-- 스키마 {counts['schema']} · 시퀀스 {counts['sequence']} · 테이블 {counts['table']} · 인덱스 {counts['index']} · 제약 {counts['constraint']}
"""

    body = "\n\n".join(out)
    if add_columns:
        body += (
            "\n\n-- ── 기존 DB 가 엔티티보다 뒤처진 경우를 메운다 ──────────────────────────\n"
            "-- 빈 DB 에서는 위 create table 이 이미 만들었으므로 전부 무동작이다.\n\n"
            + "\n".join(add_columns)
        )

    unresolved = counts["other"]
    if unresolved:
        print(f"  ⚠️  변환하지 못한 문장 {unresolved}건 — 출력 파일에서 '검토 필요' 를 확인하세요",
              file=sys.stderr)
    print(f"  {service}: 스키마 {counts['schema']}, 시퀀스 {counts['sequence']}, "
          f"테이블 {counts['table']}, 인덱스 {counts['index']}, 제약 {counts['constraint']}, "
          f"add column {len(add_columns)}")
    return header + "\n" + body + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input", type=pathlib.Path, help="Hibernate 가 생성한 DDL 스크립트")
    ap.add_argument("-o", "--out", type=pathlib.Path, required=True, help="출력 마이그레이션 파일")
    ap.add_argument("--service", help="헤더에 적을 서비스 이름 (기본: 입력 파일명)")
    args = ap.parse_args()

    if not args.input.exists():
        sys.exit(f"ERROR: {args.input} 가 없습니다. 먼저 -PexportSchema 로 뽑으세요.")
    service = args.service or args.input.stem
    sql = convert(args.input.read_text(encoding="utf-8"), service)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(sql, encoding="utf-8")
    print(f"  → {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
