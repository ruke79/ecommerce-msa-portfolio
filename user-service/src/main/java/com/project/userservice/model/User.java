package com.project.userservice.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.project.common.constants.event.CustomerTier;

import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import lombok.AccessLevel;
import lombok.Builder;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


@Getter
@Setter
@Entity
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "users", schema = "users",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = "username"),
                @UniqueConstraint(columnNames = "email")
        }, 
        indexes = {
            @Index(columnList = "username, email", name = "idx_user") })
public class User extends BaseEntity{
    @Id @Tsid    
    @Column(name = "user_id")
    private Long userId;

    // 실명
    @NotBlank
    @Size(max = 20)
    @Column(name = "username")    
    private String username;
    
    // 별명
    @Size(max = 20)
    @Column(name = "name")
    private String name;

    @NotBlank
    @Size(max = 50)
    @Email
    @Column(name = "email")
    private String email;

    @Size(max = 120)
    @Column(name = "password")
    @JsonIgnore  // 보안상 외부노출 방지
    private String password;

    
    private String image;

    @Builder.Default
    private boolean emailVerified = false;

    @Builder.Default
    private boolean accountNonLocked = true;
    @Builder.Default
    private boolean accountNonExpired = true;
    @Builder.Default
    private boolean credentialsNonExpired = true;
    @Builder.Default
    private boolean enabled = true;

    /**
     * 로그인(세션 발급)을 해도 되는 계정인가 — 메일 인증(enabled) · 관리자 잠금 · 계정 만료 · 자격 만료 넷 다.
     *
     * <p>🐛 예전에는 갱신 경로(RefreshTokenController)만 넷을 다 봤다. 비밀번호 로그인은 enabled 만, 패스키 · 메일 확인은
     * 잠금까지만, 소셜 · 2FA 확인은 아무것도 보지 않아 — 관리자가 잠가도 다시 로그인하면 액세스 토큰을 또 받았다. 세션을 주는
     * 곳은 모두 이것을 본다. 로그인 5회 실패의 자동 잠금은 여기가 아니다 — Redis 의 잠금 키(LoginAttemptService).
     */
    // [JA] ログイン(セッション発行)してよいアカウントか — メール認証 · 管理者ロック · アカウント期限 · 資格期限の四つ全部。
    //      以前は更新経路だけが四つを見ており、管理者がロックしても再ログインでアクセストークンを得られた。
    public boolean canSignIn() {
        // 필드가 아니라 게터로 — 지연 로딩 프록시에서 필드는 비어 있다.
        return isEnabled() && isAccountNonLocked() && isAccountNonExpired() && isCredentialsNonExpired();
    }

    private LocalDate credentialsExpiryDate;
    private LocalDate accountExpiryDate;

    // 🔒 TOTP 비밀 — 이 값이 있으면 그 계정의 6자리를 언제든 만들 수 있다(docs/70).
    //    · 응답에 절대 싣지 않는다(@JsonIgnore) — 예전에는 관리자 사용자 목록이 엔티티를 그대로 내보내 모든 사용자의 비밀이 실렸다.
    //    · DB 에는 AES-256-GCM 암호문으로 둔다(TotpSecretConverter). 엔티티 코드는 평문만 본다.
    //    [JA] TOTP の秘密 — 応答に載せない(@JsonIgnore)、DB には暗号文で置く。
    @JsonIgnore
    @jakarta.persistence.Convert(converter = com.project.userservice.security.totp.TotpSecretConverter.class)
    private String twoFactorSecret;
    @Builder.Default
    private boolean isTwoFactorEnabled = false;
    private String signUpMethod;

    /**
     * 비밀번호가 마지막으로 바뀐 시각 (V10). <b>null 이면 한 번도 바꾼 적 없음</b>이고,
     * 그때는 아무 토큰도 거절하지 않는다.
     *
     * <p>세션 무효화의 근거다. 비밀번호를 바꾸면 이 시각보다 <b>먼저 발급된</b> 리프레시
     * 토큰이 거절된다({@code RefreshTokenController}) — 계정이 털려서 비밀번호를 바꾼
     * 경우 공격자가 로그인 상태로 남아 있으면 바꾼 의미가 없다.
     *
     * <p>토큰을 찾아 지우는 것(RefreshTokenService.revokeAll)과 별개로 시각으로도 거르는 이유: 지우기가 실패하거나 늦어도
     * 갱신할 때마다 DB 한 곳만 보면 막힌다 — 레플리카 수 · Redis 상태와 무관하다.
     */
    private java.time.LocalDateTime passwordChangedAt;

    // ── 로그인 실패 잠금 ──────────────────────────────────────────────
    // 엔티티에 없다 — 횟수 · 잠금은 Redis(LoginAttemptService, 이메일 해시 열쇠)다. 🐛 V4 의 failed_login_attempts ·
    // lock_expires_at 열은 Redis 로 옮긴 뒤 아무도 읽지 않는데 필드와 "잠금 판정은 lockExpiresAt" 주석만 남아, 리뷰가 그 주석을
    // 사실로 읽었다(UserLockFieldsTest). 열은 다음 contract 릴리스에서 지운다(failed_login_attempts 는 NOT NULL DEFAULT 0 이라
    // 필드가 없어도 INSERT 가 된다).
    // [JA] エンティティにない — 回数・ロックは Redis。V4 の列は誰も読まず、フィールドと古い注釈だけが残っていた。列は次の contract で削除。

    // ── 회원 탈퇴 (V6) ────────────────────────────────────────────────
    // 상태를 boolean 이 아니라 시각으로 둔다. "언제 지워지는지"를 답할 수 있어야 화면이
    // 남은 기간을 보여 주고, 유예 기간 설정을 바꿔도 재계산된다. 자세한 이유는 V6 참조.
    private LocalDateTime withdrawalRequestedAt;
    private LocalDateTime withdrawnAt;
    /** ⚠️ 사용자가 직접 쓴 문장이 들어올 수 있다. 로그에 남기지 않는다. */
    @Size(max = 255)
    private String withdrawalReason;

    /** 탈퇴를 신청했고 아직 최종 처리되지 않은 상태. 이 동안에는 취소할 수 있다. */
    public boolean isWithdrawalPending() {
        return withdrawalRequestedAt != null && withdrawnAt == null;
    }

    // ── 고객 등급 / 구매 이력 (V9) ────────────────────────────────────
    // 하루 한 번 배치(CustomerTierService)가 채운다. 읽는 쪽은 저장된 값만 본다 —
    // 세그먼트 조회가 order-service 장애에 끌려가지 않도록. 자세한 이유는 V9 참조.

    /** 현재 등급. null 이 될 수 없다 — 읽는 쪽마다 null 해석이 갈리는 것을 막는다. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false, length = 16)
    private CustomerTier tier = CustomerTier.BASIC;

    /**
     * 등급을 마지막으로 계산한 시각.
     *
     * <p>🔴 <b>강등이 이 값으로 이뤄진다.</b> 배치는 최근 구매가 있는 고객만 받으므로,
     * 창에서 빠진 사람은 응답에 없다. 그들을 찾는 유일한 방법이 "이번 회차에 손대지 않은
     * 행" 이다. 감사용 타임스탬프처럼 보이지만 지우면 강등이 멈춘다.
     */
    @Column(name = "tier_computed_at")
    private LocalDateTime tierComputedAt;

    /** 가입 이후 결제된 주문 수. 기간 제한이 없다 — {@code PURCHASED} 세그먼트의 근거. */
    @Builder.Default
    @Column(name = "lifetime_order_count", nullable = false)
    private long lifetimeOrderCount = 0L;

    /** 최근 산정 기간의 실결제액 합계. 등급을 매긴 근거를 남긴다. */
    @Builder.Default
    @Column(name = "recent_paid_amount", nullable = false)
    private long recentPaidAmount = 0L;

    /** 한 번이라도 구매한 적이 있는가. */
    public boolean hasPurchased() {
        return lifetimeOrderCount > 0;
    }

    /** 등급은 비어 있을 수 없다. 옛 행이나 직접 삽입된 행을 읽을 때의 안전망. */
    public CustomerTier tierOrBasic() {
        return tier == null ? CustomerTier.BASIC : tier;
    }

    @ManyToOne(fetch = FetchType.LAZY, cascade = {CascadeType.MERGE})
    @JoinColumn(name = "role_id", referencedColumnName = "role_id")
    @JsonBackReference
    @ToString.Exclude    // 양방향 관계에서 순환 참조 방지
    private Role role;
    
        
    @Builder.Default
    @OneToMany(mappedBy="user", fetch = FetchType.LAZY,
            cascade = CascadeType.PERSIST,targetEntity = ShippingAddress.class)
    @JsonManagedReference // ShippingAddress 리스트의 순환 참조를 관리합니다.
    private List<ShippingAddress> shippingAddresses = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy="user", fetch = FetchType.LAZY,
            cascade = CascadeType.PERSIST,targetEntity = WishItem.class)
    @JsonManagedReference // WishList 리스트의 순환 참조를 관리합니다.
    private List<WishItem> wishLists = new ArrayList<>();

    // @OneToMany(mappedBy="user", fetch = FetchType.LAZY,
    // cascade = CascadeType.PERSIST, targetEntity = Order.class)    
    // private List<Order> orderLists;
    //private List<Long> orderList;

    @Builder.Default
    @OneToMany(mappedBy="reviewedBy", fetch = FetchType.LAZY,
    cascade = CascadeType.PERSIST, targetEntity = Review.class)    
    @JsonManagedReference // Review 리스트의 순환 참조를 관리합니다.
    private List<Review> reviews = new ArrayList<>();


    private String defaultPaymentMethod;

   
    // 🐛 두 생성자는 반드시 this() 로 시작한다. @Builder.Default 는 필드 초기값을 빌더 · 생성된 무인자 생성자로 옮겨서,
    //    손으로 쓴 생성자에는 남지 않는다 — tier 가 null 로 INSERT 돼 V9(NOT NULL) 뒤로 모든 가입이 DB 에서 거절됐다(UserDefaultsTest).
    // [JA] 二つのコンストラクタは必ず this() で始める。@Builder.Default は初期値をビルダー · 生成された引数なしコンストラクタへ移すため、
    //      手書きのコンストラクタには残らない — tier が null で INSERT され、V9(NOT NULL)以降すべての登録が DB に拒否されていた。
    public User(String userName, String email, String password) {
        this();
        this.username = userName;
        this.email = email;
        this.password = password;
    }

    public User(String userName, String email) {
        this();
        this.username = userName;
        this.email = email;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User)) return false;
        return userId != null && userId.equals(((User) o).getUserId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}


