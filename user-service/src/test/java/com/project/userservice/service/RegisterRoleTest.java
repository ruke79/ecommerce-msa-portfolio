package com.project.userservice.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.project.userservice.constants.AppRole;
import com.project.userservice.model.Role;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.RoleRepository;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.security.request.SignupRequest;
import com.project.userservice.service.impl.UserServiceImpl;

/**
 * 회원가입의 역할 결정 회귀 테스트.
 *
 * <p>두 가지 실제 사고를 고정한다:
 *
 * <ol>
 *   <li><b>권한 상승.</b> {@code /api/auth/public/register} 는 무인증 공개 경로인데,
 *       예전에는 요청 본문의 {@code role: ["admin"]} 을 그대로 믿고 ROLE_ADMIN 을 줬다.
 *       누구나 한 번의 POST 로 관리자가 될 수 있었고, 카탈로그 쓰기를 ROLE_ADMIN 으로
 *       잠근 것이 통째로 무의미해진다.
 *   <li><b>회원가입 전면 실패.</b> {@code users.roles} 에 ROLE_USER 행이 없으면
 *       "Error: Role is not found." 400 이 났다. 역할을 심던 코드는 주석 처리돼 있었고
 *       마이그레이션도 넣지 않아, <b>깨끗한 DB 에서는 아무도 가입할 수 없었다</b>
 *       (V3__seed_roles.sql 로 해결). 그때 메시지가 원인을 전혀 알려주지 않았다.
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RegisterRoleTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private PasswordEncoder passwordEncoder;
    /**
     * 가입은 이제 <b>같은 트랜잭션에</b> user-created 아웃박스 행을 적는다(docs/48 ②·④).
     * 그 전에는 메일 인증 링크를 누른 순간에 발행했는데, 인증 게이트가 꺼진 환경(기본값)
     * 에서는 아무도 그 경로를 지나지 않아 <b>이벤트가 한 건도 나가지 않았다.</b>
     */
    @Mock private com.project.userservice.message.UserOutboxHelper userOutboxHelper;

    @Mock private com.project.userservice.security.password.BreachedPasswordChecker breachedPasswordChecker;

    @InjectMocks private UserServiceImpl userService;

    private SignupRequest signup(Set<String> roles) {
        SignupRequest r = new SignupRequest();
        r.setUsername("tester");
        r.setEmail("tester@example.com");
        r.setPassword("pw12345678");
        r.setRole(roles);
        return r;
    }

    private void givenRolesExist() {
        when(passwordEncoder.encode(any())).thenReturn("$2a$hash");
        when(roleRepository.findByRoleName(AppRole.ROLE_USER))
                .thenReturn(Optional.of(new Role(AppRole.ROLE_USER)));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("🔴 유출 · 흔한 비밀번호로는 가입하지 않는다 — 계정도 이벤트도 만들지 않는다(docs/68 §18)")
    void breachedPasswordSignupRejected() {
        givenRolesExist();
        org.mockito.Mockito.doThrow(new com.project.userservice.security.password.BreachedPasswordException())
                .when(breachedPasswordChecker).assertAcceptable(org.mockito.ArgumentMatchers.any());

        org.junit.jupiter.api.Assertions.assertThrows(com.project.userservice.security.password.BreachedPasswordException.class,
                () -> userService.registerNewUserAccount(signup(null)));
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(userOutboxHelper, never()).recordUserCreated(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("역할을 지정하지 않은 정상 가입은 ROLE_USER")
    void normalSignupGetsUserRole() {
        givenRolesExist();

        User saved = userService.registerNewUserAccount(signup(null));

        assertEquals(AppRole.ROLE_USER, saved.getRole().getRoleName());
    }

    @Test
    @DisplayName("role:[\"admin\"] 을 보내도 ROLE_USER — 공개 경로로 관리자가 될 수 없다")
    void adminRoleRequestIsIgnored() {
        givenRolesExist();

        User saved = userService.registerNewUserAccount(signup(Set.of("admin")));

        assertEquals(AppRole.ROLE_USER, saved.getRole().getRoleName());
        // ROLE_ADMIN 은 조회조차 하지 않는다 — 요청 값이 분기에 영향을 주지 않는다.
        verify(roleRepository, never()).findByRoleName(AppRole.ROLE_ADMIN);
    }

    @Test
    @DisplayName("역할을 지정해도 계정 초기화가 빠지지 않는다 — 예전엔 정상 경로에서 통째로 건너뛰었다")
    void accountFieldsAreAlwaysInitialised() {
        givenRolesExist();

        User saved = userService.registerNewUserAccount(signup(null));

        assertTrue(saved.isAccountNonLocked());
        assertTrue(saved.isAccountNonExpired());
        assertTrue(saved.isCredentialsNonExpired());
        assertNotNull(saved.getCredentialsExpiryDate());
        assertNotNull(saved.getAccountExpiryDate());
        assertEquals("email", saved.getSignUpMethod());
    }

    @Test
    @DisplayName("require-email-verification=false(기본)면 가입 즉시 활성 — 메일이 안 나가는 환경에서 잠기지 않는다")
    void enabledByDefaultWhenVerificationNotRequired() {
        givenRolesExist();

        User saved = userService.registerNewUserAccount(signup(null));

        assertTrue(saved.isEnabled());
    }

    @Test
    @DisplayName("require-email-verification=true 면 비활성으로 생성 — 확인 링크가 실제로 게이트가 된다")
    void disabledWhenVerificationRequired() {
        givenRolesExist();
        org.springframework.test.util.ReflectionTestUtils.setField(
                userService, "requireEmailVerification", true);

        User saved = userService.registerNewUserAccount(signup(null));

        // 예전에는 엔티티 기본값이 true 라 이 값이 언제나 true 였고, 메일 인증은
        // 아무것도 막지 못했다.
        assertFalse(saved.isEnabled());
    }

    @Test
    @DisplayName("ROLE_USER 행이 없으면 무엇을 해야 하는지 알려주는 예외 — 'Role is not found' 로는 알 수 없었다")
    void missingRoleRowExplainsItself() {
        when(passwordEncoder.encode(any())).thenReturn("$2a$hash");
        when(roleRepository.findByRoleName(AppRole.ROLE_USER)).thenReturn(Optional.empty());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> userService.registerNewUserAccount(signup(null)));

        assertTrue(e.getMessage().contains("V3__seed_roles"),
                "마이그레이션 이름이 메시지에 있어야 조치할 수 있다: " + e.getMessage());
        verify(userRepository, never()).save(any());
    }
}
