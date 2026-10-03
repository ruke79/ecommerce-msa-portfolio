package com.project.userservice.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.AccessLevel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

import io.hypersistence.utils.hibernate.id.Tsid;

// 🐛 예전 @Data — 원문을 해시하지 않고 넣는 public setToken() 이 열려 있었고, equals/hashCode 가 지연 로딩 user 까지
//    건드렸다. 읽기만 열고, 바꾸는 길은 refresh() · markUsed() 둘뿐이다.
// [JA] 以前は @Data — 原文をハッシュせずに入れる setToken() が公開され、equals/hashCode が遅延ロードの user まで触っていた。
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name="password_reset_token", schema="users",  
       indexes = {
        @Index(columnList = "token, user_id", name = "idx_password_reset_token") }
)
public class PasswordResetToken {

    @Version
    @Column(name = "version")
    private Long version;
    
    @Id @Tsid
    //@GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 원문이 아니라 <b>SHA-256 해시</b>가 들어간다.
     *
     * <p>🔒 예전에는 원문을 그대로 저장했다. {@link VerificationToken} 은 같은 종류의 값을
     * 해시로만 두고 그 이유를 주석에 적어 두었는데, <b>더 강한 토큰인 이쪽에는 적용되지
     * 않았다.</b> 가입 확인 링크는 계정을 활성화할 뿐이지만 재설정 링크는 계정을 통째로
     * 가져간다 — DB 덤프 한 번이 곧 계정 탈취 수단이었다.
     *
     * <p>원문은 메일 링크로 나간 뒤 서버 어디에도 남지 않는다.
     */
    @Column(nullable = false, unique = true)
    private String token;

    @Column(nullable = false)
    private Instant expiryDate;

    private boolean used;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** @param rawToken 원문. 저장은 해시로 한다 — 호출자가 해싱을 기억할 필요가 없게 한다. */
    public PasswordResetToken(String rawToken, Instant expiryDate, User user) {
        this.token = hash(rawToken);
        this.expiryDate = expiryDate;
        this.user = user;
    }

    /**
     * 원문 토큰을 조회·비교용 해시로 바꾼다. 저장할 때와 찾을 때 모두 이걸 거친다.
     *
     * <p>{@link VerificationToken#hash} 와 같은 이유로 솔트·페퍼가 없다: 값이
     * {@code UUID.randomUUID()} 라 122비트 엔트로피이고, 그만큼이면 사전 계산이 불가능하다.
     * (6자리 SMS 코드처럼 후보가 100만 개뿐인 값이라면 페퍼가 필요하다.)
     */
    public static String hash(final String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 모든 JVM 이 반드시 제공한다. 여기 오면 실행 환경이 깨진 것이다.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 같은 행을 재사용해 새 링크를 발급한다 — 사용자당 토큰이 하나만 살아 있게 유지된다.
     *
     * <p>🔒 예전에는 요청할 때마다 새 행을 넣고 이전 것을 그대로 뒀다. 열 번 누르면
     * 24시간짜리 유효 링크가 열 개 살아 있었다. {@code used} 도 함께 되돌린다 — 되돌리지
     * 않으면 이미 쓴 행을 재사용했을 때 새 링크가 곧바로 "이미 사용됨" 이 된다.
     */
    public void refresh(final String rawToken, final Instant expiryDate) {
        this.token = hash(rawToken);
        this.expiryDate = expiryDate;
        this.used = false;
    }

    /**
     * ⚠️ 해시라 해도 토큰 값을 로그에 남기지 않는다. 해시를 아는 사람은 DB 를 조회해
     * 그 행이 누구 것인지 알 수 있고, 무엇보다 여기 찍을 이유가 없다.
     */
    @Override
    public String toString() {
        return "PasswordResetToken(id=" + id + ", used=" + used + ", expiryDate=" + expiryDate + ")";
    }

    /** 링크를 썼다 — 한 번만 쓰인다. */
    public void markUsed() {
        this.used = true;
    }
}
