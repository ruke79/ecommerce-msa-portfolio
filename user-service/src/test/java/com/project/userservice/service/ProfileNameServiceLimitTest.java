package com.project.userservice.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.service.impl.UserServiceImpl;

/** 프로필 이름 — 서비스도 열(20자)과 같은 한도. 🐛 예전에는 50 이라 21~50자가 저장에서 실패해 404 로 보였다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("프로필 이름 — 서비스 한도 20자")
class ProfileNameServiceLimitTest {

    @Mock private UserRepository userRepository;

    @InjectMocks private UserServiceImpl userService;

    @Test
    @DisplayName("🔴 21자는 저장 전에 거절한다")
    void rejectsBeforeSaving() {
        when(userRepository.findByUsername("kim")).thenReturn(Optional.of(new User("kim", "kim@example.com", "x")));

        assertThatThrownBy(() -> userService.updateMyProfile("kim", "가".repeat(21)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("이름은 20자까지 입력할 수 있습니다.");
        verify(userRepository, never()).save(any());
    }
}
