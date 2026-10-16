package com.project.userservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.project.userservice.constants.AppRole;
import com.project.userservice.model.Role;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.RoleRepository;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.security.service.UserDetailsServiceImpl;
import com.project.userservice.service.impl.UserServiceImpl;

/**
 * 관리자 회원 관리 — 목록은 쿼리 하나, 역할을 바꾸면 로그인을 끊는다.
 *
 * <p>[JA] 管理者の会員管理 — 一覧はクエリ一つ、ロールを変えたらログインを切る。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("관리자 회원 관리")
class AdminUserManagementTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private UserDetailsServiceImpl userDetailsService;
    @Mock private RefreshTokenService refreshTokenService;

    @InjectMocks private UserServiceImpl userService;

    private static User user(long id, AppRole role) {
        User u = new User("u" + id, "u" + id + "@example.com", "x");
        u.setUserId(id);
        u.setRole(new Role(role));
        return u;
    }

    @Test
    @DisplayName("🔴 회원 목록은 역할을 함께 읽는다 — 사용자마다 따로 조회하지 않는다(N+1)")
    void listReadsRolesTogether() {
        when(userRepository.findAllWithRole()).thenReturn(List.of(user(1, AppRole.ROLE_USER), user(2, AppRole.ROLE_ADMIN)));

        var list = userService.getAllUserProfiles();

        assertThat(list).hasSize(2);
        verify(userDetailsService, never()).loadUserByUsername(any());
        verify(userRepository, never()).findAll();
    }

    /** 🐛 예전: 역할을 바꿔도 로그인이 그대로라, 관리자에서 내린 계정이 리프레시로 ROLE_ADMIN 토큰을 계속 받았다. */
    @Test
    @DisplayName("🔴 역할을 바꾸면 그 사용자의 모든 로그인을 끊는다")
    void roleChangeEndsSessions() {
        User admin = user(7, AppRole.ROLE_ADMIN);
        when(userRepository.findById(7L)).thenReturn(Optional.of(admin));
        when(roleRepository.findByRoleName(AppRole.ROLE_USER)).thenReturn(Optional.of(new Role(AppRole.ROLE_USER)));

        userService.updateUserRole(7L, "ROLE_USER");

        verify(refreshTokenService).revokeAll(7L);
    }
}
