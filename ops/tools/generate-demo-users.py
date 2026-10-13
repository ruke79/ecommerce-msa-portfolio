#!/usr/bin/env python3
"""운영 데모 · 부하 테스트용 사용자 대량 생성기 — 실제로 로그인할 수 있는 계정을 만든다(docs/79).

무엇을 만드나(한 사람당):

  users.users              로그인 가능한 행 — enabled, BCrypt($2a$10$) 해시, ROLE_USER, 2FA 꺼짐
  users.shipping_address   기본 배송지 하나(선택, 기본 켬) — 바로 주문해 볼 수 있게
  users.outbox_event       user-created 이벤트(선택, 기본 켬) — 같은 트랜잭션에. 웰컴 쿠폰(coupon) · 채팅 회원(chat)
                           복제본이 이것으로 생긴다. 빼면 docs/47 의 사고(이벤트 없는 사용자)를 손으로 만드는 셈이다.

외부 라이브러리가 없다. **psql 스크립트를 stdout 으로 낸다** — ops/48-bulk-users.sh 가 PostgreSQL 파드의 psql 로 흘려 넣는다.
DB 비밀번호 · port-forward · psycopg2 가 필요 없고, 비밀번호 해시는 인자가 아니라 환경 변수(DEMO_PASSWORD_HASH)로 받는다.

규칙(앱과 맞춘 것 — docs/79 §2):

  · id      예약 범위 [--id-offset, --id-offset + --users). 앱의 TSID(약 10^18)와 겹치지 않는다. purge 는 이 범위만 지운다.
  · 이메일  demo<번호>@demo.invalid — 소문자(로그인 조회가 정확히 일치), .invalid(RFC 2606)라 메일이 어디에도 가지 않는다.
            purge 는 범위 **그리고** 이 도메인인 행만 지운다 — 범위를 잘못 줘도 실제 사용자는 지우지 않는다.
  · 가입일  8일 ~ 2년 전 사이에 흩는다. UserReplicationReconciler 는 매일 "최근 7일 가입자" 의 이벤트를 다시 적는다 —
            지금 시각으로 넣으면 다음 날 수십만 건이 한꺼번에 다시 나간다.
  · 아웃박스  STARTED 로 넣는다 — user-service 의 폴러가 보낸다(UserOutboxHelper 와 같다). created_at 은 지금 —
            월 파티션이 있는 범위.
  · 다시 실행해도 같다 — 스테이징 표에 COPY 한 뒤 ON CONFLICT DO NOTHING 으로 넣는다. 이벤트는 실제로 들어간
            사용자 것만 쓴다(이미 있던 사용자에게 두 번째 user-created 를 만들지 않는다).

사용(보통은 ops/48-bulk-users.sh 가 부른다):

    DEMO_PASSWORD_HASH='$2a$10$…' ./ops/tools/generate-demo-users.py --users 100000 --chunk-index 0 > chunk0.sql
    ./ops/tools/generate-demo-users.py --users 100000 --estimate
    ./ops/tools/generate-demo-users.py --users 100000 --purge          # users DB 용 삭제 SQL
    ./ops/tools/generate-demo-users.py --users 100000 --purge-replicas coupon|chat

[JA] 運用デモ・負荷テスト用のユーザー大量生成器 — 実際にログインできるアカウントを作る。psql スクリプトを stdout に出し、
ops/48 が PostgreSQL Pod の psql に流し込む。id は予約範囲、メールは demo<番号>@demo.invalid、加入日は 8 日〜2 年前に散らす。
"""
from __future__ import annotations

import argparse
import json
import os
import random
import re
import sys
import uuid
from datetime import datetime, timedelta, timezone

DOMAIN = "demo.invalid"
DEFAULT_OFFSET = 7_000_000_000
# 앱의 TSID 는 (2020 년 이후 밀리초 << 22) — 지금 약 10^18. 예약 범위는 그보다 한참 작아야 한다.
TSID_FLOOR = 1 << 40
# users.users 의 열 길이(V2) — username varchar(20), email varchar(50), name varchar(20)
USERNAME_MAX, EMAIL_MAX, NAME_MAX = 20, 50, 20
# UserReplicationReconciler 의 창(7일)보다 오래전이어야 한다 — 하루 여유를 더 둔다.
MIN_AGE_DAYS, MAX_AGE_DAYS = 8, 730
# 실측(로컬 PG16, 2만 명 — 2026-10-08): 사용자 + 인덱스 + 배송지 + 아웃박스 행 — docs/79 §3. estimate 가 쓴다.
BYTES_PER_USER = 1012

KO_FAMILY = ["김", "이", "박", "최", "정", "강", "조", "윤", "장", "임", "한", "오", "서", "신", "권", "황"]
KO_GIVEN = ["민준", "서연", "도윤", "하은", "시우", "지유", "주원", "서윤", "예준", "지민", "하준", "수아", "지호", "채원",
            "건우", "다은", "유준", "소윤", "현우", "예린"]
JA_FAMILY = ["佐藤", "鈴木", "高橋", "田中", "伊藤", "渡辺", "山本", "中村", "小林", "加藤", "吉田", "山田"]
JA_GIVEN = ["蓮", "陽翔", "湊", "結菜", "陽葵", "凛", "大和", "芽依", "悠真", "葵", "樹", "さくら"]
KO_CITIES = [("서울특별시", "강남구", "06"), ("서울특별시", "마포구", "04"), ("부산광역시", "해운대구", "48"),
             ("인천광역시", "연수구", "21"), ("대구광역시", "수성구", "42"), ("경기도", "성남시 분당구", "13"),
             ("경기도", "수원시 영통구", "16"), ("광주광역시", "서구", "61"), ("대전광역시", "유성구", "34")]
KO_ROADS = ["테헤란로", "월드컵북로", "해운대로", "컨벤시아대로", "달구벌대로", "판교역로", "광교중앙로", "상무대로", "대학로"]
JA_CITIES = [("東京都", "渋谷区", "150"), ("東京都", "新宿区", "160"), ("大阪府", "大阪市北区", "530"),
             ("神奈川県", "横浜市西区", "220"), ("愛知県", "名古屋市中村区", "450"), ("福岡県", "福岡市博多区", "812")]
JA_STREETS = ["道玄坂", "西新宿", "梅田", "みなとみらい", "名駅", "博多駅前"]

USER_COLS = ("user_id, username, email, password, name, enabled, email_verified, account_non_expired, account_non_locked, "
             "credentials_non_expired, is_two_factor_enabled, account_expiry_date, credentials_expiry_date, "
             "sign_up_method, created_at, version")
ADDR_COLS = ("shipping_address_id, user_id, active, firstname, lastname, phone_number, address1, address2, city, state, "
             "zip_code, country, created_at, version")


def csv_field(v) -> str:
    """COPY … (FORMAT csv) 한 칸. None 은 빈 칸(= NULL), 문자열은 따옴표로 감싸 쉼표 · 줄바꿈 · 따옴표에 안전하게."""
    if v is None:
        return ""
    if isinstance(v, bool):
        return "t" if v else "f"
    if isinstance(v, (int,)):
        return str(v)
    s = str(v)
    return '"' + s.replace('"', '""') + '"'


def csv_line(values) -> str:
    return ",".join(csv_field(v) for v in values)


def ident(n: int, width: int) -> str:
    return f"{n:0{width}d}"


def person(rng: random.Random, n: int, width: int) -> dict:
    """한 사람 — 한국 · 일본 7:3. 닉네임은 번호를 붙여 겹치지 않게(채팅 · 리뷰 화면에서 구분된다)."""
    tag = ident(n, width)
    if rng.random() < 0.7:
        family, given = rng.choice(KO_FAMILY), rng.choice(KO_GIVEN)
        city, district, zip_prefix = rng.choice(KO_CITIES)
        road = rng.choice(KO_ROADS)
        return {
            "nickname": f"{family}{given}{tag}"[:NAME_MAX],
            "firstname": given, "lastname": family, "country": "KR",
            "phone": f"010-{rng.randint(1000, 9999)}-{rng.randint(1000, 9999)}",
            "address1": f"{district} {road} {rng.randint(1, 400)}",
            "address2": f"{rng.randint(1, 30)}층 {rng.randint(1, 20)}호",
            "city": district, "state": city, "zip": f"{zip_prefix}{rng.randint(100, 999)}",
        }
    family, given = rng.choice(JA_FAMILY), rng.choice(JA_GIVEN)
    pref, city, zip_prefix = rng.choice(JA_CITIES)
    return {
        "nickname": f"{family}{given}{tag}"[:NAME_MAX],
        "firstname": given, "lastname": family, "country": "JP",
        "phone": f"090-{rng.randint(1000, 9999)}-{rng.randint(1000, 9999)}",
        "address1": f"{city}{rng.choice(JA_STREETS)}{rng.randint(1, 5)}-{rng.randint(1, 30)}-{rng.randint(1, 20)}",
        "address2": f"{rng.randint(101, 1505)}号室",
        "city": city, "state": pref, "zip": f"{zip_prefix}-{rng.randint(1000, 9999)}",
    }


def check_args(a) -> None:
    if a.users <= 0:
        sys.exit("--users 는 1 이상")
    if a.id_offset <= 0 or a.id_offset + a.users >= TSID_FLOOR:
        sys.exit(f"--id-offset 범위가 앱의 TSID 와 겹칠 수 있다: [{a.id_offset}, {a.id_offset + a.users}) — {TSID_FLOOR} 아래여야 한다")
    width = len(str(a.users - 1)) if a.users > 1 else 1
    width = max(width, 7)
    if len(f"demo{'9' * width}") > USERNAME_MAX or len(f"demo{'9' * width}@{DOMAIN}") > EMAIL_MAX:
        sys.exit("사용자 수가 너무 많아 username · email 길이 제한을 넘는다")
    a.width = width


def chunk_range(a) -> tuple[int, int]:
    start = a.chunk_index * a.chunk
    if start >= a.users:
        sys.exit(f"--chunk-index {a.chunk_index} 는 범위 밖이다(묶음 수 {(a.users + a.chunk - 1) // a.chunk})")
    return start, min(start + a.chunk, a.users)


def emit_chunk(a, out) -> None:
    pw_hash = os.environ.get("DEMO_PASSWORD_HASH", "")
    # 앱은 접두사 없는 BCryptPasswordEncoder(강도 10)다 — {bcrypt} 접두사나 다른 강도면 로그인이 안 된다.
    if not re.fullmatch(r"\$2[aby]\$10\$[./A-Za-z0-9]{53}", pw_hash):
        sys.exit("DEMO_PASSWORD_HASH 가 $2a$10$ BCrypt 해시가 아니다(앱의 BCryptPasswordEncoder 와 같아야 로그인된다)")
    start, end = chunk_range(a)
    # 묶음마다 시드를 고정 — 이어서 넣어도(resume) 같은 번호는 같은 사람이다.
    rng = random.Random(a.seed * 1_000_003 + a.chunk_index)
    now = datetime.now(timezone.utc).replace(tzinfo=None)
    expiry = (now + timedelta(days=365)).date().isoformat()

    w = out.write
    w(f"-- generate-demo-users: 묶음 {a.chunk_index} — 사용자 {a.id_offset + start} … {a.id_offset + end - 1}\n")
    w("\\set ON_ERROR_STOP on\nBEGIN;\n")
    w("SET LOCAL synchronous_commit = off;\n")
    # 같은 이름의 임시 표가 세션에 남아 있어도 되게 — 트랜잭션이 끝나면 사라진다.
    w(f"CREATE TEMP TABLE demo_user_stage (LIKE users.users INCLUDING DEFAULTS) ON COMMIT DROP;\n")
    w(f"CREATE TEMP TABLE demo_addr_stage (LIKE users.shipping_address INCLUDING DEFAULTS) ON COMMIT DROP;\n")
    w(f"COPY demo_user_stage ({USER_COLS}) FROM stdin WITH (FORMAT csv);\n")
    people = []
    for i in range(start, end):
        uid = a.id_offset + i
        tag = ident(i, a.width)
        p = person(rng, i, a.width)
        created = now - timedelta(days=rng.uniform(MIN_AGE_DAYS, MAX_AGE_DAYS))
        people.append((uid, p, created))
        w(csv_line([uid, f"demo{tag}", f"demo{tag}@{DOMAIN}", pw_hash, p["nickname"], True, True, True, True, True, False,
                    expiry, expiry, "email", created.isoformat(sep=" ", timespec="microseconds"), 0]) + "\n")
    w("\\.\n")
    # role_id 는 이름으로 찾는다 — 시드 id 가 DB 마다 다를 수 있다(V3 은 "없으면 넣기").
    w(f"INSERT INTO users.users ({USER_COLS}, role_id)\n"
      f"SELECT {USER_COLS}, (SELECT role_id FROM users.roles WHERE role_name = 'ROLE_USER')\n"
      f"  FROM demo_user_stage ON CONFLICT DO NOTHING;\n")
    if a.addresses:
        w(f"COPY demo_addr_stage ({ADDR_COLS}) FROM stdin WITH (FORMAT csv);\n")
        for uid, p, created in people:
            w(csv_line([uid, uid, True, p["firstname"], p["lastname"], p["phone"], p["address1"], p["address2"], p["city"],
                        p["state"], p["zip"], p["country"], created.isoformat(sep=" ", timespec="microseconds"), 0]) + "\n")
        w("\\.\n")
        # 실제로 들어간(이 범위 · 이 도메인) 사용자의 것만, 이미 배송지가 있는 사람은 건너뛴다(사람당 활성 주소 하나 — V7).
        w(f"INSERT INTO users.shipping_address ({ADDR_COLS})\n"
          f"SELECT s.* FROM (SELECT {ADDR_COLS} FROM demo_addr_stage) s\n"
          f"  JOIN users.users u ON u.user_id = s.user_id AND u.email LIKE '%@{DOMAIN}'\n"
          f" WHERE NOT EXISTS (SELECT 1 FROM users.shipping_address x WHERE x.user_id = s.user_id)\n"
          f"ON CONFLICT DO NOTHING;\n")
    if a.events:
        # UserOutboxHelper.recordUserCreated 와 같은 모양: {"userId","image","nickname"} — 이메일은 싣지 않는다(docs/48 §6-⑥).
        # 이 묶음에서 **새로** 들어간 사용자만 — 아웃박스에 같은 사용자의 user-created 가 이미 있으면 만들지 않는다.
        w("CREATE TEMP TABLE demo_event_stage (id varchar(255), aggregate_id varchar(255), payload jsonb) ON COMMIT DROP;\n")
        w("COPY demo_event_stage (id, aggregate_id, payload) FROM stdin WITH (FORMAT csv);\n")
        for uid, p, _ in people:
            payload = json.dumps({"userId": uid, "image": None, "nickname": p["nickname"]}, ensure_ascii=False)
            w(csv_line([str(uuid.UUID(int=rng.getrandbits(128), version=4)), str(uid), payload]) + "\n")
        w("\\.\n")
        w("INSERT INTO users.outbox_event (id, aggregate_id, aggregate_type, event_type, payload, created_at, outbox_status)\n"
          "SELECT e.id, e.aggregate_id, 'User', 'user-created', e.payload, now(), 'STARTED'\n"
          "  FROM demo_event_stage e\n"
          f"  JOIN users.users u ON u.user_id = e.aggregate_id::bigint AND u.email LIKE '%@{DOMAIN}'\n"
          " WHERE NOT EXISTS (SELECT 1 FROM users.outbox_event o\n"
          "                    WHERE o.aggregate_id = e.aggregate_id AND o.event_type = 'user-created');\n")
    w("COMMIT;\n")


def emit_purge(a, out) -> None:
    lo, hi = a.id_offset, a.id_offset + a.users - 1
    w = out.write
    w(f"-- generate-demo-users: 삭제 — 사용자 {lo} … {hi} 중 @{DOMAIN} 인 행만\n\\set ON_ERROR_STOP on\nBEGIN;\n")
    # users.users 를 가리키는 모든 외래 키를 카탈로그에서 찾아 아래부터 지운다 — 데모 중에 생긴 기기 · 알림 · 리뷰 · 위시리스트까지.
    # 표 목록을 여기 적어 두면 새 표가 생길 때 빠진다(그러면 FK 위반으로 purge 가 멈춘다). 파티션 자식은 부모 제약으로 충분하다.
    w("""CREATE FUNCTION pg_temp.demo_purge(target regclass, cond text) RETURNS bigint LANGUAGE plpgsql AS $f$
DECLARE fk record; n bigint;
BEGIN
  FOR fk IN SELECT c.conrelid::regclass AS child, a.attname AS child_col, pa.attname AS parent_col
              FROM pg_constraint c
              JOIN pg_attribute a  ON a.attrelid = c.conrelid  AND a.attnum = c.conkey[1]
              JOIN pg_attribute pa ON pa.attrelid = c.confrelid AND pa.attnum = c.confkey[1]
             WHERE c.contype = 'f' AND c.confrelid = target AND c.conrelid <> target
               AND c.conparentid = 0 AND array_length(c.conkey, 1) = 1
  LOOP
    PERFORM pg_temp.demo_purge(fk.child,
      format('%I IN (SELECT %I FROM %s WHERE %s)', fk.child_col, fk.parent_col, target, cond));
  END LOOP;
  EXECUTE format('DELETE FROM %s WHERE %s', target, cond);
  GET DIAGNOSTICS n = ROW_COUNT;
  RAISE NOTICE 'purge %: % 행', target, n;
  RETURN n;
END $f$;
""")
    # 지울 사람을 먼저 고정한다 — 사용자 행을 지운 뒤에는 "누가 데모였나" 를 다시 알 수 없다.
    w("CREATE TEMP TABLE demo_ids ON COMMIT DROP AS\n"
      f"SELECT user_id FROM users.users WHERE user_id BETWEEN {lo} AND {hi} AND email LIKE '%@{DOMAIN}';\n")
    # 아직 안 나간 user-created 는 같이 지운다 — 사용자가 없는데 이벤트만 나가면 쿠폰 · 채팅에 유령이 생긴다.
    w("DELETE FROM users.outbox_event WHERE aggregate_id IN (SELECT user_id::text FROM demo_ids)\n"
      "   AND outbox_status <> 'COMPLETED';\n")
    # 외래 키 없이 user_id 만 가진 표(known_device 등) — 카탈로그에서 찾는다. 그 표의 자식은 demo_purge 가 따라간다.
    w("""DO $d$
DECLARE t record;
BEGIN
  FOR t IN SELECT c.oid::regclass AS tbl
             FROM pg_class c JOIN pg_namespace ns ON ns.oid = c.relnamespace
             JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'user_id' AND NOT a.attisdropped
            WHERE ns.nspname = 'users' AND c.relkind IN ('r', 'p') AND NOT c.relispartition
              AND c.oid <> 'users.users'::regclass
  LOOP
    PERFORM pg_temp.demo_purge(t.tbl, 'user_id IN (SELECT user_id FROM demo_ids)');
  END LOOP;
END $d$;
""")
    w("SELECT pg_temp.demo_purge('users.users', 'user_id IN (SELECT user_id FROM demo_ids)') AS deleted_users;\n")
    w("COMMIT;\nANALYZE users.users;\nANALYZE users.shipping_address;\n")


def emit_purge_replicas(a, out) -> None:
    """다른 DB 의 복제본 — 이벤트로 생긴 것. id 범위만으로 지운다(그 DB 에는 이메일이 없다)."""
    lo, hi = a.id_offset, a.id_offset + a.users - 1
    w = out.write
    w("\\set ON_ERROR_STOP on\nBEGIN;\n")
    if a.purge_replicas == "coupon":
        # 웰컴 쿠폰(user-created 로 발급) — 쓰인 쿠폰(주문에 묶임)도 데모 것이라 같이 지운다. 처리 기록은 남겨도 해가 없다.
        w(f"DELETE FROM coupon.coupon WHERE user_id BETWEEN {lo} AND {hi};\n")
    else:
        w(f"DELETE FROM chat.member WHERE member_id BETWEEN {lo} AND {hi};\n")
    w("COMMIT;\n")


def estimate(a) -> None:
    chunks = (a.users + a.chunk - 1) // a.chunk
    gb = a.users * BYTES_PER_USER / 1e9
    print(f"사용자 {a.users:,} 명 · 묶음 {chunks} 개(묶음당 {a.chunk:,})")
    print(f"id {a.id_offset} … {a.id_offset + a.users - 1}  ·  이메일 demo{'0' * a.width}@{DOMAIN} …")
    print(f"용량(표 + 인덱스 + 배송지 + 아웃박스, 실측 {BYTES_PER_USER} B/명) ≈ {gb:.2f} GB  — WAL 은 적재 중 잠깐 그만큼 더")
    if a.events:
        print("이벤트: 폴링 릴레이는 레플리카당 10초에 100건(user-service.outbox) — "
              f"{a.users:,} 건이면 레플리카 1개로 약 {a.users / 10 / 3600:.1f} 시간(docs/79 §4)")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--users", type=int, required=True, help="만들(또는 지울) 사용자 수")
    ap.add_argument("--id-offset", type=int, default=DEFAULT_OFFSET, help=f"예약 id 범위의 시작 (기본 {DEFAULT_OFFSET})")
    ap.add_argument("--chunk", type=int, default=5000, help="한 트랜잭션의 사용자 수 (기본 5000)")
    ap.add_argument("--chunk-index", type=int, help="이 묶음의 SQL 을 낸다(0 부터)")
    ap.add_argument("--seed", type=int, default=79, help="난수 시드 — 같은 번호는 언제나 같은 사람")
    ap.add_argument("--no-addresses", dest="addresses", action="store_false", help="배송지를 만들지 않는다")
    ap.add_argument("--no-events", dest="events", action="store_false",
                    help="user-created 를 쓰지 않는다 — 쿠폰 · 채팅 복제본이 생기지 않는다(docs/47)")
    ap.add_argument("--estimate", action="store_true", help="행 수 · 용량 · 이벤트 소요만")
    ap.add_argument("--purge", action="store_true", help="users DB 의 삭제 SQL")
    ap.add_argument("--purge-replicas", choices=("coupon", "chat"), help="다른 DB 의 복제본 삭제 SQL")
    a = ap.parse_args()
    check_args(a)
    if a.chunk <= 0:
        sys.exit("--chunk 는 1 이상")
    out = sys.stdout
    if a.estimate:
        estimate(a)
    elif a.purge:
        emit_purge(a, out)
    elif a.purge_replicas:
        emit_purge_replicas(a, out)
    elif a.chunk_index is not None:
        emit_chunk(a, out)
    else:
        sys.exit("--chunk-index · --estimate · --purge · --purge-replicas 중 하나")


if __name__ == "__main__":
    main()
