package com.project.userservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.project.userservice.constants.AppRole;
import com.project.userservice.model.PasswordResetToken;
import com.project.userservice.model.Role;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.PasswordResetTokenRepository;
import com.project.userservice.repository.jpa.RoleRepository;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.security.password.BreachedPasswordChecker;
import com.project.userservice.security.password.BreachedPasswordException;
import com.project.userservice.security.request.SignupRequest;
import com.project.userservice.service.impl.UserServiceImpl;

/**
 * 비밀번호를 정하는 네 경로(가입 · 본인 변경 · 재설정 · 관리자 변경)는 유출 확인(HIBP — 외부 호출, 최대 2초)을 <b>트랜잭션 밖에서</b> 하고,
 * 저장은 트랜잭션 안에서 한다.
 *
 * <p>🐛 예전: 클래스의 {@code @Transactional} 이 메서드 전체를 감싸 HIBP 를 기다리는 동안 DB 커넥션(풀 5개)을 쥐었다. HIBP 가 느린 날
 * 가입 몇 건이면 같은 풀을 쓰는 로그인 · 조회까지 커넥션을 기다렸다.
 *
 * <p>운영과 같은 판정을 보려고 서비스를 <b>실제 Spring 트랜잭션 프록시</b>({@link TransactionInterceptor} +
 * {@link AnnotationTransactionAttributeSource} — 클래스 · 메서드의 {@code @Transactional} 을 그대로 읽는다)로 감싸 부른다. 메서드의
 * {@code NOT_SUPPORTED} 를 지우면 클래스의 REQUIRED 가 다시 감싸 실패한다.
 *
 * <p>[JA] パスワードを決める 4 経路は漏えい確認(HIBP)をトランザクションの外で行い、保存はトランザクション内で行う。実際の Spring
 * トランザクションプロキシで包んで呼ぶ。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("비밀번호 정책 · 유출 확인은 트랜잭션 밖, 저장은 안")
class PasswordPolicyOutsideTransactionTest {

    private final TestTransactions.Recording manager = new TestTransactions.Recording();

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private com.project.userservice.message.UserOutboxHelper userOutboxHelper;
    @Mock private com.project.userservice.security.RedisSessionManager sessionManager;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private com.project.userservice.message.producer.SecurityAlertProducer securityAlertProducer;
    @Mock private com.project.userservice.security.CurrentPasswordGuard currentPasswordGuard;
    @Mock private BreachedPasswordChecker breachedPasswordChecker;
    @Spy private TransactionTemplate tx = TestTransactions.template(manager);
    @InjectMocks private UserServiceImpl target;

    /** 운영처럼 @Transactional 을 해석하는 프록시 — 같은 트랜잭션 관리자. */
    private UserService service;

    /** 유출 확인 · 저장이 불렸을 때 트랜잭션이 열려 있었나. */
    private final List<Boolean> hibpInTx = new ArrayList<>();
    private final List<Boolean> saveInTx = new ArrayList<>();

    private User user;

    @BeforeEach
    void setUp() {
        ProxyFactory pf = new ProxyFactory(target);
        pf.addInterface(UserService.class);
        pf.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        service = (UserService) pf.getProxy();

        user = User.builder().email("buyer@example.com").username("buyer").build();
        user.setUserId(7L);
        user.setPassword("$2a$old");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode(any())).thenReturn("$2a$new");
        when(passwordEncoder.matches("old", "$2a$old")).thenReturn(true);
        when(roleRepository.findByRoleName(AppRole.ROLE_USER)).thenReturn(Optional.of(new Role(AppRole.ROLE_USER)));
        doAnswer(inv -> {
            hibpInTx.add(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(breachedPasswordChecker).assertAcceptable(any());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            saveInTx.add(TransactionSynchronizationManager.isActualTransactionActive());
            return inv.getArgument(0);
        });
    }

    private void assertHibpOutsideSaveInside() {
        assertThat(hibpInTx).as("유출 확인이 불렸고, 그때 트랜잭션이 없었다").containsExactly(false);
        assertThat(saveInTx).as("저장은 트랜잭션 안").isNotEmpty().containsOnly(true);
        assertThat(manager.commits).as("짧은 트랜잭션이 커밋됐다").isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("🔴 가입 — HIBP 는 트랜잭션 밖, 계정 저장 · 가입 이벤트(아웃박스, MANDATORY)는 한 트랜잭션 안")
    void signup() {
        List<Boolean> outboxInTx = new ArrayList<>();
        doAnswer(inv -> {
            outboxInTx.add(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(userOutboxHelper).recordUserCreated(any());
        SignupRequest r = new SignupRequest();
        r.setUsername("tester");
        r.setEmail("tester@example.com");
        r.setPassword("pw12345678");

        service.registerNewUserAccount(r);

        assertHibpOutsideSaveInside();
        assertThat(outboxInTx).containsExactly(true);
        assertThat(manager.commits).as("트랜잭션 하나").isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 본인 변경 — 현재 비밀번호 확인 → HIBP(밖) → 저장(안). 현재 비밀번호가 틀리면 HIBP 를 부르지 않는다")
    void selfChange() {
        service.updatePassword(7L, "old", "newpw123");
        assertHibpOutsideSaveInside();
        assertThat(user.getPassword()).isEqualTo("$2a$new");

        hibpInTx.clear();
        assertThatThrownBy(() -> service.updatePassword(7L, "wrong", "newpw123"))
                .isInstanceOf(UserServiceImpl.InvalidCurrentPasswordException.class);
        assertThat(hibpInTx).isEmpty();
    }

    @Test
    @DisplayName("🔴 재설정 — 링크 확인 → HIBP(밖) → 다시 확인하고 저장(안). 링크가 틀리면 HIBP 를 부르지 않는다")
    void reset() {
        PasswordResetToken token = new PasswordResetToken("raw-token", Instant.now().plus(1, ChronoUnit.HOURS), user);
        when(passwordResetTokenRepository.findByToken(PasswordResetToken.hash("raw-token"))).thenReturn(Optional.of(token));

        service.resetPassword("raw-token", "newpw123");

        assertHibpOutsideSaveInside();
        assertThat(token.isUsed()).isTrue();
        verify(refreshTokenService).revokeAll(7L);

        hibpInTx.clear();
        assertThatThrownBy(() -> service.resetPassword("unknown", "newpw123")).hasMessageContaining("유효하지 않은 링크");
        assertThat(hibpInTx).as("인증 없는 경로가 외부 호출을 만들지 않는다").isEmpty();
    }

    @Test
    @DisplayName("🔴 재설정 — 유출된 비밀번호면 링크를 소진하지 않는다(다시 시도할 수 있다)")
    void resetWithBreachedPasswordKeepsLink() {
        PasswordResetToken token = new PasswordResetToken("raw-token", Instant.now().plus(1, ChronoUnit.HOURS), user);
        when(passwordResetTokenRepository.findByToken(PasswordResetToken.hash("raw-token"))).thenReturn(Optional.of(token));
        org.mockito.Mockito.doThrow(new BreachedPasswordException()).when(breachedPasswordChecker).assertAcceptable(any());

        assertThatThrownBy(() -> service.resetPassword("raw-token", "password1")).isInstanceOf(BreachedPasswordException.class);

        assertThat(token.isUsed()).isFalse();
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("🔴 관리자 변경 — HIBP(밖) → 저장 · 로그인 끊기(안). 유출 · 길이 위반은 이유를 그대로 던진다")
    void adminChange() {
        service.updatePassword(7L, "newpw123");
        assertHibpOutsideSaveInside();
        verify(refreshTokenService).revokeAll(7L);

        assertThatThrownBy(() -> service.updatePassword(7L, "x")).isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.doThrow(new BreachedPasswordException()).when(breachedPasswordChecker).assertAcceptable(any());
        assertThatThrownBy(() -> service.updatePassword(7L, "password1")).isInstanceOf(BreachedPasswordException.class);
    }
}
