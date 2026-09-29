package com.project.userservice.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Date;
import java.util.HexFormat;

import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.*;

/**
 * 회원가입 확인 토큰.
 *
 * <p><b>만료 시간이 3분이었다.</b> 상수 옆에 {@code //60 * 24} 라는 주석이 남아 있는 것으로
 * 보아 원래 24시간이던 값을 테스트하려고 3으로 줄인 뒤 되돌리지 않은 것으로 보인다. 메일이
 * 배달되는 데 걸리는 시간(스팸 필터, 큐잉)만으로도 3분을 넘기는 경우가 흔하므로, 사실상
 * <b>상당수의 가입자가 링크를 눌러도 실패</b>하는 상태였다. 이제 기본 24시간이고 설정으로
 * 바꿀 수 있다.
 *
 * <p><b>토큰을 평문으로 저장하지 않는다.</b> 이 토큰은 그 자체로 계정을 활성화할 수 있는
 * 자격증명(bearer credential)이다. DB 를 읽을 수 있는 사람 — 백업 파일, 덤프, 읽기 전용
 * 리플리카에 접근하는 누구든 — 이 미확인 계정을 대신 활성화할 수 있었다. 비밀번호를 해시로
 * 저장하는 것과 같은 이유로 해시만 저장한다.
 *
 * <p>SMS 인증번호(notification-service)에는 페퍼를 섞었지만 여기서는 쓰지 않는다. 6자리 숫자는
 * 경우의 수가 100만 개뿐이라 해시를 미리 계산해 둘 수 있지만, 이 토큰은 UUIDv4 (122비트
 * 무작위)라 사전 계산이 불가능하다. 페퍼는 이 경우 보호를 더하지 못하면서 키 관리 부담만 는다.
 */
@Entity
@Table(name="verfication_token", schema="users",
         indexes = {
          @Index(columnList = "token, user_id", name = "idx_verification_token") }
)
public class VerificationToken {

    /** 기본 만료. 설정으로 덮어쓸 수 있다(app.verification.token-ttl). */
    public static final Duration DEFAULT_TTL = Duration.ofHours(24);

    @Id @Tsid
    private Long id;

    /** ⚠️ 원문이 아니라 SHA-256 해시다. 조회할 때도 입력을 해시해서 찾는다. */
    private String token;

    @OneToOne(targetEntity = User.class, fetch = FetchType.LAZY)
    @JoinColumn(nullable = false, name = "user_id", foreignKey = @ForeignKey(name = "FK_VERIFY_USER"))
    private User user;

    private Date expiryDate;

    protected VerificationToken() {
        super();
    }

    public VerificationToken(final String rawToken, final User user, final Duration ttl) {
        super();
        this.token = hash(rawToken);
        this.user = user;
        this.expiryDate = expiryFrom(ttl);
    }

    /**
     * 원문 토큰을 조회·비교용 해시로 바꾼다.
     *
     * <p>저장할 때와 찾을 때 모두 이걸 거친다. 원문은 메일로 나간 뒤 어디에도 남지 않는다.
     */
    public static String hash(final String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 모든 JVM 이 반드시 제공한다. 여기 오면 실행 환경이 깨진 것이다.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Date expiryFrom(final Duration ttl) {
        Duration effective = (ttl == null || ttl.isZero() || ttl.isNegative()) ? DEFAULT_TTL : ttl;
        return new Date(System.currentTimeMillis() + effective.toMillis());
    }

    public Long getId() {
        return id;
    }

    public String getToken() {
        return token;
    }

    public User getUser() {
        return user;
    }

    public void setUser(final User user) {
        this.user = user;
    }

    public Date getExpiryDate() {
        return expiryDate;
    }

    public boolean isExpired() {
        return expiryDate == null || expiryDate.getTime() - System.currentTimeMillis() <= 0;
    }

    /** 재발송 시 같은 행을 재사용한다 — 사용자당 토큰이 하나만 살아 있게 유지된다. */
    public void refresh(final String rawToken, final Duration ttl) {
        this.token = hash(rawToken);
        this.expiryDate = expiryFrom(ttl);
    }

    // id 기반 equals/hashCode. 예전에는 token·expiryDate·user 를 모두 썼는데, user 가 LAZY
    // 프록시라 equals 를 부르는 것만으로 추가 쿼리가 나갔고 영속화 전(id=null) 인스턴스와
    // 후의 해시가 달라 컬렉션에서 사라지는 문제가 있다.

    @Override
    public int hashCode() {
        return id == null ? System.identityHashCode(this) : id.hashCode();
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof VerificationToken other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    /** ⚠️ 토큰 값은 절대 넣지 않는다 — 로그로 새면 해시 저장이 무의미해진다. */
    @Override
    public String toString() {
        return "VerificationToken[id=" + id + ", expires=" + expiryDate + "]";
    }

}
