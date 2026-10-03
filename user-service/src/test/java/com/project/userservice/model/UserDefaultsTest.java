package com.project.userservice.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.constants.event.CustomerTier;

/**
 * 🔴 손으로 쓴 생성자도 필드 기본값을 갖는다.
 *
 * <p>🐛 Lombok {@code @Builder.Default} 는 필드 초기값을 빌더 · 생성된 무인자 생성자로 <b>옮긴다</b> — 손으로 쓴 생성자에는 남지 않는다.
 * 가입 경로(UserServiceImpl · CustomOAuth2UserService)는 {@code new User(이름, 이메일[, 비밀번호])} 로 만들어서
 * tier 가 null 로 INSERT 됐다 — V9 가 tier 를 NOT NULL 로 만든 뒤로 가입이 DB 에서 거절된다.
 */
@DisplayName("User — 생성자 기본값")
class UserDefaultsTest {

    @Test
    @DisplayName("🔴 new User(이름, 이메일, 비밀번호) — tier BASIC · 계정 플래그 true · 컬렉션 비어 있음")
    void passwordConstructorAppliesDefaults() {
        assertDefaults(new User("alice", "a@example.com", "hash"));
    }

    @Test
    @DisplayName("🔴 new User(이름, 이메일) — 소셜 가입도 같은 기본값")
    void socialConstructorAppliesDefaults() {
        assertDefaults(new User("google_1", "a@example.com"));
    }

    @Test
    @DisplayName("빌더도 같은 기본값 — 컬렉션이 null 이 아니다")
    void builderAppliesDefaults() {
        assertDefaults(User.builder().username("bob").email("b@example.com").build());
    }

    private static void assertDefaults(User user) {
        assertThat(user.getTier()).isEqualTo(CustomerTier.BASIC);
        assertThat(user.isAccountNonLocked()).isTrue();
        assertThat(user.isAccountNonExpired()).isTrue();
        assertThat(user.isCredentialsNonExpired()).isTrue();
        assertThat(user.getShippingAddresses()).isNotNull().isEmpty();
        assertThat(user.getWishLists()).isNotNull().isEmpty();
        assertThat(user.getReviews()).isNotNull().isEmpty();
    }
}
