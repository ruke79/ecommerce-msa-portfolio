package com.project.userservice.service;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.hibernate.Hibernate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import com.project.userservice.constants.AppRole;
import com.project.userservice.message.UserOutboxHelper;
import com.project.userservice.model.Role;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.RoleRepository;
import com.project.userservice.repository.jpa.UserRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 공개 데모 계정 — 만들기(기동 때) · 내주기(차례로 돌려서, docs/100). 스키마는 바꾸지 않는다 — 표시는 이미 있는 signUpMethod({@value
 * User#DEMO_SIGN_UP_METHOD}), 차례는 Redis.
 *
 * <p>계정은 {@code demo.accounts.count} 개(기본 5), 이메일은 {@code demo<N>@<email-domain>}(기본 {@code demo.invalid} — 메일이 실제로
 * 나가지 않는 예약 도메인). 비밀번호는 아무도 모르는 난수 — 들어오는 길은 데모 로그인 버튼뿐이다. 가입과 같은 길로 {@code user-created}
 * 아웃박스 행을 써 웰컴 쿠폰 · 채팅 회원 복제본이 생긴다. {@code demo.accounts.enabled=false}(기본)면 아무것도 하지 않고 데모 로그인은 404.
 *
 * <p>내줄 때는 Redis {@value #NEXT_KEY} 를 INCR 해 그 차례의 계정을 준다 — 원자적이라 동시에 누른 두 방문자는 다른 계정을 받고, 한 바퀴
 * 돌면 가장 오래전에 내준 계정이 다시 나온다(계정 수보다 방문자가 많으면 함께 쓴다). Redis 가 실패하면 아무 계정이나(막지 않는다).
 *
 * <p>[JA] 公開デモアカウント — 作成(起動時) · 払い出し(順番に回す)。スキーマは変えない — 表示は既存の signUpMethod、順番は Redis。
 *      メールは demo&lt;N&gt;@demo.invalid(メールが実際に出ない予約ドメイン)、パスワードは誰も知らない乱数 — 入口はデモログインボタン
 *      だけ。加入と同じ道で user-created アウトボックス行を書く。払い出しは Redis の INCR — 同時に押した二人は別のアカウント。
 */
@Slf4j
@Service
public class DemoAccountService {

    /** 내줄 수 있는 데모 계정 — 보통 · 비활성 · 잠금 · 탈퇴 계정은 빼고. [JA] 払い出せるデモアカウント。 */
    static final String CANDIDATES_SQL = "SELECT user_id FROM users.users"
            + " WHERE sign_up_method = '" + User.DEMO_SIGN_UP_METHOD + "' AND enabled AND account_non_locked AND withdrawn_at IS NULL"
            + " ORDER BY user_id";

    /** 초기화 대상 — 잠김 · 비활성과 상관없이 모든 데모 계정. [JA] 初期化対象 — ロック · 無効にかかわらずすべてのデモアカウント。 */
    static final String ALL_DEMO_SQL = "SELECT user_id FROM users.users WHERE sign_up_method = '" + User.DEMO_SIGN_UP_METHOD
            + "' ORDER BY user_id";

    /** 차례 카운터. [JA] 順番カウンター。 */
    static final String NEXT_KEY = "demo:accounts:next";

    /** 후보 목록을 다시 읽는 간격 — 데모 로그인마다 users 표를 훑지 않는다. [JA] 候補一覧を読み直す間隔。 */
    static final long CANDIDATES_TTL_MS = 60_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final boolean enabled;
    private final int count;
    private final String emailDomain;
    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final UserOutboxHelper outbox;
    private final JdbcTemplate jdbc;
    private final TransactionOperations tx;
    private final StringRedisTemplate redis;

    private volatile List<Long> candidates = List.of();
    private volatile long candidatesReadAt;

    @Autowired
    public DemoAccountService(@Value("${demo.accounts.enabled:false}") boolean enabled,
            @Value("${demo.accounts.count:5}") int count,
            @Value("${demo.accounts.email-domain:demo.invalid}") String emailDomain,
            UserRepository users, RoleRepository roles, PasswordEncoder passwordEncoder, UserOutboxHelper outbox,
            JdbcTemplate jdbc, TransactionOperations tx, StringRedisTemplate redis) {
        if (count < 1 || count > 50) {
            throw new IllegalArgumentException("demo.accounts.count 는 1 ~ 50 — " + count);
        }
        this.enabled = enabled;
        this.count = count;
        this.emailDomain = emailDomain;
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.tx = tx;
        this.redis = redis;
    }

    public boolean enabled() {
        return enabled;
    }

    /** 모든 데모 계정 id — 매일 초기화가 쓴다. 꺼져 있으면 빈 목록. [JA] すべてのデモアカウント id — 毎日の初期化用。無効なら空。 */
    public List<Long> allDemoAccountIds() {
        return enabled ? jdbc.queryForList(ALL_DEMO_SQL, Long.class) : List.of();
    }

    static String email(int n, String domain) {
        return "demo" + n + "@" + domain;
    }

    /** 기동 때 모자란 데모 계정을 만들고, 있는 계정은 기한을 늘린다. [JA] 起動時に足りないデモアカウントを作り、既存は期限を延ばす。 */
    @EventListener(ApplicationReadyEvent.class)
    public void provision() {
        if (!enabled) {
            return;
        }
        int created = 0;
        for (int n = 1; n <= count; n++) {
            int i = n;
            try {
                if (Boolean.TRUE.equals(tx.execute(s -> ensure(i)))) {
                    created++;
                }
            } catch (DataIntegrityViolationException e) {
                // 다른 파드가 같은 순간 만들었다 — 그쪽 것을 쓴다 [JA] 別の Pod が同時に作った — そちらを使う
                log.info("데모 계정 {} — 다른 파드가 먼저 만들었다", i);
            }
        }
        log.info("데모 계정 준비: 전체={} 새로 만듦={}", count, created);
    }

    /** @return 새로 만들었으면 true [JA] 新しく作ったら true */
    boolean ensure(int n) {
        String email = email(n, emailDomain);
        Optional<User> existing = users.findByEmail(email);
        if (existing.isPresent()) {
            User u = existing.get();
            if (!u.isDemo()) {
                // 같은 이메일의 보통 계정을 데모로 바꾸지 않는다 [JA] 同じメールの通常アカウントをデモに変えない
                log.warn("데모 계정 {} — 같은 이메일의 보통 계정이 있어 건너뛴다(userId={})", n, u.getUserId());
                return false;
            }
            u.setAccountExpiryDate(LocalDate.now().plusYears(1));
            u.setCredentialsExpiryDate(LocalDate.now().plusYears(1));
            return false;
        }
        Role role = roles.findByRoleName(AppRole.ROLE_USER)
                .orElseThrow(() -> new IllegalStateException("users.roles 에 ROLE_USER 가 없다"));
        User user = new User("demo_visitor_" + n, email, passwordEncoder.encode(randomPassword()));
        user.setName("Demo " + n);
        user.setAccountNonLocked(true);
        user.setAccountNonExpired(true);
        user.setCredentialsNonExpired(true);
        user.setCredentialsExpiryDate(LocalDate.now().plusYears(1));
        user.setAccountExpiryDate(LocalDate.now().plusYears(1));
        user.setTwoFactorEnabled(false);
        user.setSignUpMethod(User.DEMO_SIGN_UP_METHOD);
        user.setEmailVerified(true);
        user.setEnabled(true);
        user.setRole(role);
        User saved = users.save(user);
        outbox.recordUserCreated(saved);
        log.info("데모 계정 만듦: userId={}", saved.getUserId());
        return true;
    }

    private static String randomPassword() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private List<Long> candidates() {
        long now = System.currentTimeMillis();
        if (candidates.isEmpty() || now - candidatesReadAt > CANDIDATES_TTL_MS) {
            candidates = List.copyOf(jdbc.queryForList(CANDIDATES_SQL, Long.class));
            candidatesReadAt = now;
        }
        return candidates;
    }

    /** 이번 차례(0 부터) — Redis 가 실패하면 난수. [JA] 今回の順番 — Redis が失敗したら乱数。 */
    private long nextTurn() {
        try {
            Long n = redis.opsForValue().increment(NEXT_KEY);
            if (n != null) {
                return n - 1;
            }
        } catch (RuntimeException e) {
            log.warn("데모 계정 차례를 Redis 에서 받지 못해 아무 계정이나 준다: {}", e.getClass().getSimpleName());
        }
        return RANDOM.nextInt(Integer.MAX_VALUE);
    }

    /**
     * 차례의 데모 계정을 하나 내준다 — 역할을 읽어 둔 채로(트랜잭션 밖에서 토큰을 만든다).
     * [JA] 順番のデモアカウントを一つ払い出す — ロールを読み込んだ状態で。
     */
    public Optional<User> claimNext() {
        if (!enabled) {
            return Optional.empty();
        }
        List<Long> ids = candidates();
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        Long id = ids.get((int) Math.floorMod(nextTurn(), (long) ids.size()));
        return tx.execute(s -> {
            Optional<User> user = users.findById(id).filter(User::isDemo).filter(User::canSignIn);
            user.ifPresent(u -> Hibernate.initialize(u.getRole()));
            if (user.isEmpty()) {
                candidatesReadAt = 0;   // 그 사이 잠기거나 지워졌다 — 다음에 다시 읽는다 [JA] その間にロック · 削除 — 次に読み直す
            }
            return user;
        });
    }
}
