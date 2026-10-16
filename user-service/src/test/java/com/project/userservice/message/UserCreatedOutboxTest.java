package com.project.userservice.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.project.userservice.model.User;
import com.project.userservice.model.VerificationToken;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.repository.jpa.VerificationTokenRepository;
import com.project.userservice.service.impl.UserServiceImpl;

/**
 * 가입 확정 이벤트 — <b>dual-write 를 아웃박스로</b> (docs/48 ②).
 *
 * <h3>무엇이 깨져 있었나</h3>
 *
 * <p>인증 확정은 {@code validateAndGetUser} 가 커밋하고, 이벤트는 그 <b>다음</b>
 * {@code registerConfirmed} 가 비동기 {@code kafkaTemplate.send} 로 쐈다. 둘이 다른
 * 트랜잭션이라 계정만 커밋되고 이벤트는 안 갈 수 있었고, {@code send} 는 결과를 보지
 * 않아 <b>실패해도 로그 한 줄 남지 않았다.</b>
 *
 * <p>실제 결과: {@code user-created} 토픽이 통째로 비어 있는데 계정은 멀쩡했고, 그
 * 사용자는 주문 버튼에서 400 을 받았다(docs/47).
 *
 * <p>아웃박스 테이블은 <b>처음부터 있었다.</b> {@code UserServiceImpl} 에 이렇게 주석
 * 처리된 채로:
 *
 * <pre>{@code
 * //OutboxEvent event = createOutboxEvent(in);
 * //outboxEventRepository.save(event);
 * userCreatedProducer.publish(dto);
 * }</pre>
 *
 * <h3>🔴 그리고 발행 시점 자체가 틀려 있었다</h3>
 *
 * <p>발행은 <b>메일 인증 링크를 누른 순간</b>에 걸려 있었다. 그런데
 * {@code require-email-verification} 기본값이 <b>false</b> 다 — 계정이 곧바로
 * {@code enabled} 로 만들어지므로 <b>그 경로를 아무도 지나지 않는다.</b> 그래서
 * {@code user-created} 이벤트는 단 한 건도 발행된 적이 없었고, 증상이 셋으로 나타났다:
 * 주문 실패(docs/47), 채팅 진입 실패, 그리고 <b>가입 축하 쿠폰 미발급.</b>
 *
 * <p>앞의 둘은 읽기 시점에 "행이 없네" 를 알아차릴 수 있어 눈에 띄었지만, 쿠폰은 <b>안
 * 왔다는 사실조차 알기 어려웠다.</b> 그래서 이벤트를 <b>가입 시점</b>으로 옮겼다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("가입 확정 이벤트 — 아웃박스")
class UserCreatedOutboxTest {

    @Mock private UserRepository userRepository;
    @Mock private VerificationTokenRepository tokenRepository;
    @Mock private com.project.userservice.repository.jpa.RoleRepository roleRepository;
    @Mock private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Mock private UserOutboxHelper userOutboxHelper;

    @Mock private com.project.userservice.security.password.BreachedPasswordChecker breachedPasswordChecker;

    @InjectMocks private UserServiceImpl userService;

    private User user;
    private VerificationToken token;

    @BeforeEach
    void setUp() {
        user = User.builder().email("buyer@example.com").username("buyer").build();
        user.setUserId(42L);
        user.setEnabled(false);

        // 살아 있는 토큰. TTL 을 넉넉히 줘서 이 테스트가 시계에 흔들리지 않게 한다.
        token = new VerificationToken("raw-token", user, java.time.Duration.ofHours(1));

        when(tokenRepository.findByToken(any())).thenReturn(token);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /**
     * 🔴 <b>이 파일의 핵심 가드.</b>
     *
     * <p>이벤트는 <b>가입 시점</b>에 적혀야 한다. 인증 링크 경로에 두면
     * {@code require-email-verification} 이 꺼진 환경(=기본값)에서 <b>아무도 그 경로를
     * 지나지 않아</b> 이벤트가 영영 발행되지 않는다. 실제로 그랬고, 그 결과가 주문
     * 실패·채팅 실패·쿠폰 미발급이었다.
     */
    @Test
    @DisplayName("가입하면 인증 설정과 무관하게 아웃박스에 적는다")
    void recordsTheEventAtSignup() {
        when(roleRepository.findByRoleName(any()))
                .thenReturn(java.util.Optional.of(new com.project.userservice.model.Role(com.project.userservice.constants.AppRole.ROLE_USER)));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setUserId(42L);
            return u;
        });

        com.project.userservice.security.request.SignupRequest request =
                new com.project.userservice.security.request.SignupRequest();
        request.setEmail("buyer@example.com");
        request.setUsername("buyer");
        request.setPassword("pw");

        userService.registerNewUserAccount(request);

        verify(userOutboxHelper).recordUserCreated(any(User.class));
    }

    /**
     * 인증 링크 경로에서는 <b>적지 않는다.</b> 가입 시점에 이미 적었으므로, 여기서 또
     * 적으면 같은 사용자의 이벤트가 두 번 나간다.
     */
    @Test
    @DisplayName("인증 링크 경로에서는 적지 않는다 — 가입 때 이미 적었다")
    void doesNotRecordAgainOnVerification() {
        userService.validateAndGetUser("raw-token");

        assertThat(user.isEnabled()).isTrue();
        verify(userOutboxHelper, never()).recordUserCreated(any());
    }

    /**
     * 🐛 운영 기본값(require-email-verification=false)은 가입 즉시 enabled 다. 예전에는 그 계정이 링크를 눌러도 emailVerified 를
     * 켜지 않아, 나중에 같은 주소의 소셜 로그인이 비밀번호를 "확인된 적 없음" 으로 지웠다(SocialAccountLinker).
     */
    @Test
    @DisplayName("🔴 이미 활성인 계정도 링크를 누르면 emailVerified — 토큰은 남겨 두 번째 클릭도 성공")
    void enabledAccountGetsVerifiedByTheLink() {
        user.setEnabled(true);

        var first = userService.validateAndGetUser("raw-token");
        var second = userService.validateAndGetUser("raw-token");

        assertThat(user.isEmailVerified()).isTrue();
        assertThat(first.getStatus()).isEqualTo(UserServiceImpl.TOKEN_VALID).isEqualTo(second.getStatus());
        verify(tokenRepository, never()).delete(any());
    }

    /**
     * 🔴 {@code MANDATORY} 여야 한다.
     *
     * <p>{@code REQUIRED} 면 트랜잭션 없이 불렸을 때 <b>조용히 새 트랜잭션을 열어</b>
     * 성립해 버린다 — 그게 바로 dual-write 이고, 이 작업 전체가 무의미해진다.
     * {@code MANDATORY} 는 그 경우 예외를 던져 <b>배포 전에</b> 드러나게 한다.
     */
    @Test
    @DisplayName("아웃박스 기록은 호출자의 트랜잭션을 요구한다(MANDATORY)")
    void theOutboxWriteDemandsAnExistingTransaction() throws Exception {
        Method record = UserOutboxHelper.class.getMethod("recordUserCreated", User.class);

        Transactional tx = record.getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.propagation())
                .as("REQUIRED 면 트랜잭션 없이도 성립한다 — 그것이 dual-write 다")
                .isEqualTo(Propagation.MANDATORY);
    }

    /**
     * 🔴 폴러는 발행을 <b>자기 트랜잭션 안에서</b> 끝내야 한다.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} 로 선점한 잠금은 커밋까지만 유지된다. 발행이
     * 비동기면 상태를 바꾸는 콜백이 커밋 뒤에 돌고, 그 사이 행은 STARTED 인데 잠금은
     * 풀려 있다 — 다른 레플리카가 집어 가서 <b>같은 이벤트를 두 번 보낸다.</b>
     * order-service 의 결제 아웃박스가 정확히 그랬다(docs/21).
     */
    @Test
    @DisplayName("폴러는 트랜잭션 안에서 돈다")
    void thePollerRunsInsideATransaction() throws Exception {
        Method process = UserOutboxScheduler.class.getMethod("processOutboxMessage");

        assertThat(process.getAnnotation(Transactional.class))
                .as("잠금이 상태 확정까지 유지돼야 중복 발행이 없다")
                .isNotNull();
        assertThat(process.getAnnotation(Scheduled.class)).isNotNull();
    }

    /**
     * 🔴 선점 쿼리에 {@code SKIP LOCKED} 가 있어야 한다.
     *
     * <p>user-service 는 {@code replicas: 2} 다. 잠금 없이 조회하면 두 레플리카가 같은
     * 행을 읽는다. JPQL 에는 이 구문이 없어 네이티브 쿼리인데, 그래서 <b>리팩터링 중
     * 조용히 사라지기 쉽다.</b>
     */
    @Test
    @DisplayName("선점 쿼리가 FOR UPDATE SKIP LOCKED 를 쓴다")
    void theClaimQuerySkipsLockedRows() throws Exception {
        Method claim = com.project.userservice.message.repository.jpa.OutboxEventRepository.class
                .getMethod("claimPending", int.class);

        org.springframework.data.jpa.repository.Query query =
                claim.getAnnotation(org.springframework.data.jpa.repository.Query.class);

        assertThat(query).isNotNull();
        assertThat(query.nativeQuery()).isTrue();
        assertThat(query.value().toLowerCase())
                .contains("for update")
                .contains("skip locked")
                .contains("outbox_status = 'started'");
    }
}
