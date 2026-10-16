package com.project.common.outbox.partition;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 아웃박스 월 파티션을 <b>앞으로 몇 달치 미리</b> 만들어 둔다 (docs/09 §4.6 · docs/62 §3).
 *
 * <h3>왜</h3>
 *
 * <p>아웃박스 테이블(cart · order · pay · user)은 {@code created_at} 월 단위 RANGE 파티션이다. V1 마이그레이션이
 * 2026-07 ~ 2027-01 을 만들어 두었고, <b>그 뒤를 만드는 장치가 없었다.</b> 2027-02 부터는 모든 행이 DEFAULT 파티션에
 * 쌓인다. 에러는 나지 않는다 — 그래서 아무도 모른다. 그러다 누군가 2027-02 파티션을 손으로 만들면
 * {@code updated partition constraint for default partition would be violated} 로 실패한다(DEFAULT 에 그 달의 행이 이미 있다).
 *
 * <h3>어떻게</h3>
 *
 * <ul>
 *   <li>이번 달부터 {@code monthsAhead} 달 뒤까지, 없는 달의 파티션을 만든다. 이름은 V1 과 같은 {@code <테이블>_YYYY_MM}.</li>
 *   <li><b>DEFAULT 에 그 달의 행이 이미 있어도 만든다.</b> {@code PARTITION OF} 로 곧바로 만들면 위의 오류가 난다 — 그래서
 *       빈 테이블을 만들고({@code LIKE}), DEFAULT 에서 그 달의 행을 옮긴 뒤({@code DELETE … RETURNING → INSERT}),
 *       {@code ATTACH PARTITION} 한다. 한 트랜잭션이라 중간에 실패하면 전부 되돌아간다.</li>
 *   <li><b>레플리카가 여럿이어도 한 번만.</b> 테이블마다 {@code pg_advisory_xact_lock} 을 잡는다. 늦게 온 쪽은 앞의 것이
 *       끝나기를 기다렸다가, 이미 있는 것을 보고 아무것도 하지 않는다.</li>
 *   <li>시각은 UTC 다 — 아웃박스의 {@code created_at} 이 UTC 로 쓰인다(B-21).</li>
 *   <li>PostgreSQL 이 아니면(테스트의 H2 등) 아무것도 하지 않는다. 파티션 테이블이 아니어도 건너뛴다.</li>
 * </ul>
 *
 * <p>지우지는 않는다. 오래된 파티션의 폐기(DETACH → DROP)는 보존 정책을 정한 뒤 사람이 한다 — 되돌릴 수 없는 일을
 * 잡이 조용히 하지 않게.
 *
 * <p>[JA] アウトボックスの月次パーティションを<b>数か月先の分まで前もって</b>作っておく (docs/09 §4.6 · docs/62 §3)。
 *
 * <h3>なぜ</h3>
 *
 * <p>アウトボックステーブル(cart · order · pay · user)は {@code created_at} の月単位 RANGE パーティションである。V1 マイグレーションが
 * 2026-07 ~ 2027-01 を作っておいたが、<b>その先を作る仕組みがなかった。</b> 2027-02 以降はすべての行が DEFAULT パーティションに
 * 溜まる。エラーにはならない — だから誰も気づかない。そのうち誰かが 2027-02 パーティションを手で作ると
 * {@code updated partition constraint for default partition would be violated} で失敗する(DEFAULT にその月の行が既にある)。
 *
 * <h3>どうやって</h3>
 *
 * <ul>
 *   <li>今月から {@code monthsAhead} か月後まで、存在しない月のパーティションを作る。名前は V1 と同じ {@code <テーブル>_YYYY_MM}。</li>
 *   <li><b>DEFAULT にその月の行が既にあっても作る。</b>{@code PARTITION OF} で直接作ると上記のエラーになる — そのため
 *       空テーブルを作り({@code LIKE})、DEFAULT からその月の行を移したうえで({@code DELETE … RETURNING → INSERT})、
 *       {@code ATTACH PARTITION} する。一つのトランザクションなので途中で失敗すればすべて巻き戻る。</li>
 *   <li><b>レプリカが複数あっても一回だけ。</b>テーブルごとに {@code pg_advisory_xact_lock} を取る。後から来た側は先行側が
 *       終わるのを待ち、既に存在するのを見て何もしない。</li>
 *   <li>時刻は UTC である — アウトボックスの {@code created_at} は UTC で書かれる(B-21)。</li>
 *   <li>PostgreSQL でなければ(テストの H2 など)何もしない。パーティションテーブルでない場合もスキップする。</li>
 * </ul>
 *
 * <p>削除はしない。古いパーティションの廃棄(DETACH → DROP)は保持ポリシーを決めたうえで人が行う — 取り返しのつかない操作を
 * ジョブが黙って行わないように。
 */
public class OutboxPartitionMaintainer {

    private static final Logger log = LoggerFactory.getLogger(OutboxPartitionMaintainer.class);

    /** {@code 스키마.테이블} — 식별자를 SQL 에 이어 붙이므로 이 모양만 받는다. */
    // [JA] {@code スキーマ.テーブル} — 識別子を SQL に連結するので、この形だけを受け付ける。
    static final Pattern QUALIFIED = Pattern.compile("^[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*$");

    /** DDL 트랜잭션의 잠금 대기 상한 — {@link #limitLockWait}. */
    // [JA] DDL トランザクションのロック待ちの上限。
    static final String LOCK_TIMEOUT = "3s";

    private final DataSource dataSource;
    private final List<String> tables;
    private final int monthsAhead;
    private final Clock clock;

    /** 마지막 점검 결과 — 지표가 읽는다. 테이블 → 상태. */
    // [JA] 最後の点検結果 — メトリクスが読む。テーブル → 状態。
    private final Map<String, Status> last = new ConcurrentHashMap<>();

    /**
     * 테이블마다 마지막으로 점검에 성공한 시각 — 지표 {@code outbox_partition_last_success_seconds}. 처음 값은 <b>이 객체를 만든
     * 시각(≈ 기동 시각)</b>이다. 비워 두면 한 번도 성공하지 못한 동안 값이 NaN 이고, Prometheus 에서 NaN 과의 비교는 늘 거짓이라
     * "기동 뒤로 한 번도 못 돌았다" 가 경보에 잡히지 않는다. 파티션 테이블이 아니어서 건너뛴 점검(ensure 가 null)은 성공으로 치지
     * 않는다 — 설정이 틀린 것이므로 조용히 넘기지 않는다.
     *
     * <p>[JA] テーブルごとに最後に点検に成功した時刻。初期値は<b>このオブジェクトを作った時刻(≈ 起動時刻)</b>。空にすると一度も成功して
     * いない間は NaN になり、Prometheus で NaN との比較は常に偽なので「起動後一度も回っていない」がアラートに掛からない。パーティション
     * テーブルでないため飛ばした点検は成功と数えない — 設定の誤りなので黙って見過ごさない。
     */
    private final Map<String, Instant> lastSuccess = new ConcurrentHashMap<>();

    /**
     * @param monthsAhead 몇 달 뒤까지 미리 둘까. 0 이면 이번 달만. 잡이 몇 번 실패해도 버티도록 3 이 기본이다
     *                    [JA] 何か月先まで前もって用意するか。0 なら今月のみ。ジョブが何度か失敗しても耐えられるよう 3 がデフォルトである
     */
    public OutboxPartitionMaintainer(DataSource dataSource, List<String> tables, int monthsAhead, Clock clock) {
        for (String t : tables) {
            if (!QUALIFIED.matcher(t).matches()) {
                throw new IllegalStateException("outbox.partitions.tables 는 '스키마.테이블'(소문자) 이어야 한다: " + t);
            }
        }
        if (monthsAhead < 0) {
            throw new IllegalStateException("outbox.partitions.months-ahead 는 0 이상: " + monthsAhead);
        }
        this.dataSource = dataSource;
        this.tables = List.copyOf(tables);
        this.monthsAhead = monthsAhead;
        this.clock = clock;
        Instant start = clock.instant();
        for (String t : this.tables) {
            lastSuccess.put(t, start);
        }
    }

    /**
     * 점검 결과. {@code monthsCovered} = 이번 달 <b>뒤로</b> 끊기지 않고 이어진 미래 파티션 수 — 정상이면 설정의
     * {@code monthsAhead} 와 같다. 이번 달만 있으면 0, 이번 달 파티션도 없으면 -1 이다. {@code coveredThrough} 는 끊기지 않고
     * 이어진 마지막 달 — 지표는 이 값과 <b>지금</b>의 차이로 남은 달을 매번 계산한다({@link #monthsAheadNow}).
     *
     * <p>[JA] 点検結果。{@code monthsCovered} = 今月<b>より後</b>に途切れずに続く将来パーティションの数 — 正常なら設定の
     * {@code monthsAhead} と同じ。今月の分だけなら 0、今月のパーティションもなければ -1(メトリクス · アラートがそのまま読む)。
     */
    public record Status(int monthsCovered, YearMonth coveredThrough, long defaultRows, List<String> created) {
    }

    public List<String> tables() {
        return tables;
    }

    public Map<String, Status> lastStatus() {
        return Map.copyOf(last);
    }

    /** 모든 테이블을 점검한다. 한 테이블의 실패가 다른 테이블을 막지 않는다. */
    // [JA] すべてのテーブルを点検する。一つのテーブルの失敗が他のテーブルを妨げない。
    public void runOnce() {
        for (String table : tables) {
            try {
                Status s = ensure(table);
                if (s != null) {
                    record(table, s);
                    // 만들 것이 없어도 한 줄 남긴다 — 잡이 도는지는 로그만으로 알 수 있어야 한다(6시간에 테이블당 한 줄).
                    // 예전에는 생성했을 때만 남겨서, V1 이 이미 만들어 둔 달만 남은 동안(2026-10 까지) 잡이 도는지 알 길이 없었다.
                    // [JA] 作るものがなくても一行残す — ジョブが回っているかはログだけで分かるべきである(6 時間ごとにテーブルあたり一行)。
                    //      以前は作成したときだけ残していたので、V1 が作っておいた月しか残っていない間(2026-10 まで)は
                    //      ジョブが回っているか知るすべがなかった。
                    log.info("아웃박스 파티션 점검. table={}, 미리 만든 달={}, 생성={}, DEFAULT 행={}",
                            table, s.monthsCovered(), s.created(), s.defaultRows());
                    if (s.defaultRows() > 0) {
                        // 미리 만들어 두는 한 DEFAULT 는 비어 있어야 한다 — 있으면 과거 이관분이거나 잡이 오래 멈췄던 것이다.
                        // [JA] 前もって作っている限り DEFAULT は空のはずである — 行があれば過去の移行分か、ジョブが長く止まっていたかである。
                        log.warn("아웃박스 DEFAULT 파티션에 행이 있다. table={}, rows={} — docs/09 §4.6", table, s.defaultRows());
                    }
                }
            } catch (SQLException | RuntimeException e) {
                log.error("아웃박스 파티션 점검 실패. table={} — 다음 주기에 다시 한다: {}", table, e.getMessage());
            }
        }
    }

    /**
     * 한 테이블을 점검한다. PostgreSQL 이 아니거나 파티션 테이블이 아니면 {@code null}.
     *
     * <p>[JA] 一つのテーブルを点検する。PostgreSQL でない、またはパーティションテーブルでなければ {@code null}。
     */
    Status ensure(String table) throws SQLException {
        String schema = table.substring(0, table.indexOf('.'));
        String name = table.substring(table.indexOf('.') + 1);

        try (Connection c = dataSource.getConnection()) {
            if (!"PostgreSQL".equalsIgnoreCase(c.getMetaData().getDatabaseProductName())) {
                return null;
            }
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                lock(c, table);
                limitLockWait(c);
                if (!isPartitioned(c, schema, name)) {
                    c.rollback();
                    // 운영 DB 를 베이스라인으로 옮겼으면 V1(파티션 전환)을 건너뛰었을 수 있다 — 조용히 넘기지 않는다.
                    // [JA] 本番 DB をベースラインで移行した場合、V1(パーティション化)を飛ばしている可能性がある — 黙って見過ごさない。
                    log.info("아웃박스 파티션: 파티션 테이블이 아니다 — 건너뜀. table={} (docs/09 §4.6)", table);
                    return null;
                }
                String defaultPartition = defaultPartition(c, schema, name);
                List<String> columns = columns(c, schema, name);

                YearMonth now = YearMonth.from(LocalDateTime.now(clock.withZone(ZoneOffset.UTC)));
                List<String> created = new ArrayList<>();
                List<String> conflicts = new ArrayList<>();
                for (int i = 0; i <= monthsAhead; i++) {
                    YearMonth m = now.plusMonths(i);
                    String part = partitionName(name, m);
                    if (!attached(c, schema, name, part)) {
                        if (relationExists(c, schema, part)) {
                            // 이름만 같은 다른 것(DETACH 된 채 남은 테이블 · 같은 이름의 인덱스 등)이 있다. 예전에는 이것을
                            // "그 달이 있다" 로 보고 건너뛰어, 그 달 행이 모두 조용히 DEFAULT 로 가는데 지표는 꽉 찼다고
                            // 보고했다. 그 테이블에 무엇이 들었는지 모르니 붙이지도 지우지도 않고 멈춘다 — 사람이 정한다.
                            // 이 점검이 실패로 남으므로 last_success 경보(24시간)와 줄어드는 months_ahead 가 알린다.
                            // [JA] 名前だけ同じ別のもの(DETACH されたまま残ったテーブル・同名のインデックスなど)がある。以前はこれを
                            //      「その月がある」と見なして飛ばし、その月の行がすべて黙って DEFAULT に入るのに指標は満たされていると
                            //      報告していた。中身が分からないので付けも消しもせずに止まる — 人が決める。
                            //
                            // 🐛 예전에는 여기서 바로 던져 트랜잭션 전체가 롤백됐다 — 이 달 하나 때문에 <b>뒤의 달도</b> 만들지 못해,
                            //    사람이 고칠 때까지 남은 달까지 차례로 DEFAULT 로 갔다. 이제 이 달만 건너뛰고 나머지를 만든 뒤
                            //    커밋하고, 그다음에 실패로 알린다(성공으로 기록하지 않는다 — 경보는 그대로).
                            // [JA] 以前はここで即座に投げてトランザクション全体がロールバックされ、後ろの月も作れなかった。
                            //      今はこの月だけ飛ばして残りを作り、コミットしてから失敗として知らせる。
                            conflicts.add(schema + "." + part);
                            continue;
                        }
                        createPartition(c, schema, name, part, m, defaultPartition, columns);
                        created.add(schema + "." + part);
                    }
                }
                int covered = 0;
                while (attached(c, schema, name, partitionName(name, now.plusMonths(covered)))) {
                    covered++;
                }
                long defaultRows = defaultPartition == null ? 0 : count(c, schema, defaultPartition);
                c.commit();
                if (!conflicts.isEmpty()) {
                    throw new IllegalStateException("아웃박스 파티션 이름 " + String.join(", ", conflicts)
                            + " 이 이미 쓰이고 있지만 " + table + " 의 파티션이 아니다(DETACH 된 테이블?)"
                            + " — 확인해서 ATTACH 하거나 이름을 바꾼다/지운다. 다른 달은 만들었다(" + created + "). docs/09 §4.6");
                }
                return new Status(covered - 1, now.plusMonths(covered - 1), defaultRows, created);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        }
    }

    /** 성공한 점검 결과를 남긴다 — 지표가 읽는 두 값(결과 · 성공 시각)을 함께. */
    // [JA] 成功した点検結果を残す — 指標が読む二つの値(結果 · 成功時刻)を一緒に。
    void record(String table, Status s) {
        last.put(table, s);
        lastSuccess.put(table, clock.instant());
    }

    /**
     * 지금 기준으로 이번 달 뒤에 남은 미래 파티션 수 — 지표 {@code outbox_partition_months_ahead}.
     *
     * <p>점검할 때의 값을 그대로 내지 않고 마지막 성공의 {@code coveredThrough} 와 <b>지금</b>으로 매번 계산한다. 점검 결과를
     * 그대로 내면 성공할 때마다 늘 {@code monthsAhead}(3)라 "줄어든다" 를 볼 수 없고, 실패가 이어지는 동안에는 마지막 값에
     * 멈춰 있다 — 경보가 잡아야 할 바로 그 상황(잡이 조용히 멈춤)에서 아무것도 알려 주지 못했다. 이제 잡이 멈추면 달이 바뀔
     * 때마다 1 씩 줄어든다. 한 번도 점검하지 못했으면 NaN — 그 경우는 {@code last_success} 경보가 잡는다.
     *
     * <p>[JA] 今を基準に今月より後に残る将来パーティションの数。点検時の値をそのまま出さず、最後の成功の {@code coveredThrough} と
     * <b>今</b>から毎回計算する — ジョブが止まると月が変わるたびに 1 ずつ減る。
     */
    public double monthsAheadNow(String table) {
        Status s = last.get(table);
        if (s == null) {
            return Double.NaN;
        }
        YearMonth now = YearMonth.from(LocalDateTime.now(clock.withZone(ZoneOffset.UTC)));
        return ChronoUnit.MONTHS.between(now, s.coveredThrough());
    }

    /** 마지막 성공 점검의 DEFAULT 파티션 행 수 — 지표 {@code outbox_partition_default_rows}. 점검 전에는 NaN. */
    // [JA] 最後に成功した点検の DEFAULT パーティションの行数。点検前は NaN。
    public double defaultRows(String table) {
        Status s = last.get(table);
        return s == null ? Double.NaN : s.defaultRows();
    }

    /** 마지막으로 점검에 성공한 시각(유닉스 초) — 지표 {@code outbox_partition_last_success_seconds}. 처음 값은 기동 시각. */
    // [JA] 最後に点検に成功した時刻(UNIX 秒)。初期値は起動時刻。
    public double lastSuccessEpochSeconds(String table) {
        Instant i = lastSuccess.get(table);
        return i == null ? Double.NaN : i.getEpochSecond();
    }

    static String partitionName(String table, YearMonth m) {
        return String.format("%s_%04d_%02d", table, m.getYear(), m.getMonthValue());
    }

    private void createPartition(Connection c, String schema, String table, String part, YearMonth m,
            String defaultPartition, List<String> columns) throws SQLException {
        String parent = schema + "." + table;
        String child = schema + "." + part;
        LocalDateTime from = m.atDay(1).atStartOfDay();
        LocalDateTime to = m.plusMonths(1).atDay(1).atStartOfDay();

        try (Statement st = c.createStatement()) {
            // NOT NULL·CHECK·기본값을 부모에서 가져온다 — ATTACH 는 자식이 부모의 CHECK 를 모두 갖기를 요구한다.
            // [JA] NOT NULL・CHECK・デフォルト値を親から引き継ぐ — ATTACH は子が親の CHECK をすべて持つことを要求する。
            st.execute("CREATE TABLE " + child + " (LIKE " + parent + " INCLUDING DEFAULTS INCLUDING CONSTRAINTS)");
        }
        if (defaultPartition != null) {
            String cols = String.join(", ", columns);
            // DEFAULT 에 이미 들어온 그 달의 행을 새 파티션으로 옮긴다. 없으면 0 행 — 그래도 ATTACH 가 DEFAULT 를
            // 훑어 확인하므로 이 단계가 있어야 "이미 들어온 행" 이 있을 때도 실패하지 않는다.
            // [JA] DEFAULT に既に入っているその月の行を新しいパーティションへ移す。なければ 0 行 — それでも ATTACH は DEFAULT を
            //      走査して確認するので、この段階があってこそ「既に入っている行」がある場合にも失敗しない。
            String sql = "WITH moved AS (DELETE FROM " + schema + "." + defaultPartition
                    + " WHERE created_at >= ? AND created_at < ? RETURNING " + cols + ") "
                    + "INSERT INTO " + child + " (" + cols + ") SELECT " + cols + " FROM moved";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, from);
                ps.setObject(2, to);
                int moved = ps.executeUpdate();
                if (moved > 0) {
                    log.warn("DEFAULT 파티션의 행을 옮겼다. table={}, partition={}, rows={}", parent, child, moved);
                }
            }
        }
        try (Statement st = c.createStatement()) {
            // 부모의 인덱스·PK 는 ATTACH 할 때 자식에 만들어진다(파티션 인덱스).
            // [JA] 親のインデックス・PK は ATTACH 時に子に作られる(パーティションインデックス)。
            st.execute("ALTER TABLE " + parent + " ATTACH PARTITION " + child
                    + " FOR VALUES FROM ('" + m.atDay(1) + "') TO ('" + m.plusMonths(1).atDay(1) + "')");
        }
    }

    private static void lock(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))")) {
            ps.setString(1, "outbox-partition:" + table);
            ps.execute();
        }
    }

    /**
     * 이 트랜잭션의 잠금 대기에 상한을 둔다({@link #LOCK_TIMEOUT}).
     *
     * <p>DEFAULT 파티션이 있으면 {@code ATTACH} 는 DEFAULT 에 ACCESS EXCLUSIVE 를 잡는다. 백업(pg_dump · 매일 03:00 UTC, 약 3분)
     * 처럼 오래 ACCESS SHARE 를 쥔 쪽이 있으면 ATTACH 가 잠금 줄에 서고, PostgreSQL 의 잠금 줄은 차례대로라 <b>그 뒤에 오는 DEFAULT
     * 행 INSERT 까지 함께 멈춘다</b>. 못 잡으면 빨리 실패해 롤백하고(오류 55P03), {@link #runOnce} 가 받아 다음 주기에 다시 한다 —
     * 미리 3달을 두므로 몇 번 실패해도 괜찮다. advisory lock 을 잡은 <b>뒤</b>에 건다: 다른 레플리카를 기다리는 것은 그쪽의 DDL 이 이
     * 상한으로 끝나므로 길어지지 않고, 먼저 걸면 동시에 뜬 레플리카가 서로 실패한다.
     *
     * <p>[JA] このトランザクションのロック待ちに上限を置く。DEFAULT パーティションがあると {@code ATTACH} は DEFAULT に ACCESS
     * EXCLUSIVE を取る。バックアップ(pg_dump)のように長く ACCESS SHARE を握る側がいると ATTACH がロック待ちの列に並び、PostgreSQL の
     * ロック待ちは順番どおりなので<b>その後に来る DEFAULT 行の INSERT まで一緒に止まる</b>。取れなければすぐ失敗してロールバックし
     * (エラー 55P03)、{@link #runOnce} が受けて次の周期にやり直す。advisory lock を取った<b>後</b>に掛ける。
     */
    /**
     * 🐛 문장 시간 상한도 둔다 — ATTACH 는 DEFAULT 파티션을 훑는 동안 그 파티션을 잠근다(그 사이 DEFAULT 로 가는 삽입이 기다린다).
     * DEFAULT 에 행이 많이 쌓였으면 그 시간이 길어졌다 — lock_timeout 은 잠금을 <b>얻는</b> 시간만 묶는다. 넘으면 이번 주기는 실패하고
     * 다음 주기에 다시 한다(경보가 알린다).
     */
    // [JA] 文の時間上限も置く — ATTACH は DEFAULT を走査する間ロックする。lock_timeout はロックを得る時間しか縛らない。
    static final String STATEMENT_TIMEOUT = "30s";

    private static void limitLockWait(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("SET LOCAL lock_timeout = '" + LOCK_TIMEOUT + "'");
            st.execute("SET LOCAL statement_timeout = '" + STATEMENT_TIMEOUT + "'");
        }
    }

    private static boolean isPartitioned(Connection c, String schema, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM pg_partitioned_table p JOIN pg_class k ON k.oid = p.partrelid "
                        + "JOIN pg_namespace n ON n.oid = k.relnamespace WHERE n.nspname = ? AND k.relname = ?")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String defaultPartition(Connection c, String schema, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT ch.relname FROM pg_inherits i JOIN pg_class ch ON ch.oid = i.inhrelid "
                        + "JOIN pg_class pa ON pa.oid = i.inhparent JOIN pg_namespace n ON n.oid = pa.relnamespace "
                        + "WHERE n.nspname = ? AND pa.relname = ? AND pg_get_expr(ch.relpartbound, ch.oid) = 'DEFAULT'")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static List<String> columns(Connection c, String schema, String table) throws SQLException {
        List<String> cols = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT a.attname FROM pg_attribute a JOIN pg_class k ON k.oid = a.attrelid "
                        + "JOIN pg_namespace n ON n.oid = k.relnamespace "
                        + "WHERE n.nspname = ? AND k.relname = ? AND a.attnum > 0 AND NOT a.attisdropped ORDER BY a.attnum")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(quote(rs.getString(1)));
                }
            }
        }
        return cols;
    }

    /**
     * {@code part} 가 지금 {@code parent} 에 붙어 있는 파티션(테이블)인가. 이름만 보지 않는다 — {@code pg_class} 에는 인덱스 ·
     * 시퀀스 · 뷰, DETACH 된 테이블도 있다.
     *
     * <p>[JA] {@code part} が今 {@code parent} に付いているパーティション(テーブル)か。名前だけを見ない — {@code pg_class} には
     * インデックス・シーケンス・ビュー、DETACH されたテーブルもある。
     */
    private static boolean attached(Connection c, String schema, String parent, String part) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM pg_inherits i JOIN pg_class ch ON ch.oid = i.inhrelid "
                        + "JOIN pg_class pa ON pa.oid = i.inhparent JOIN pg_namespace n ON n.oid = pa.relnamespace "
                        + "JOIN pg_namespace cn ON cn.oid = ch.relnamespace "
                        + "WHERE n.nspname = ? AND pa.relname = ? AND cn.nspname = ? AND ch.relname = ? "
                        + "AND ch.relkind IN ('r', 'p') AND ch.relispartition")) {
            ps.setString(1, schema);
            ps.setString(2, parent);
            ps.setString(3, schema);
            ps.setString(4, part);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** 그 이름의 관계가 종류와 상관없이 있는가 — 만들기 전에 이름 충돌을 본다. */
    // [JA] その名前のリレーションが種類を問わずあるか — 作る前に名前の衝突を見る。
    private static boolean relationExists(Connection c, String schema, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM pg_class k JOIN pg_namespace n ON n.oid = k.relnamespace WHERE n.nspname = ? AND k.relname = ?")) {
            ps.setString(1, schema);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static long count(Connection c, String schema, String table) throws SQLException {
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT count(*) FROM " + schema + "." + quote(table))) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
