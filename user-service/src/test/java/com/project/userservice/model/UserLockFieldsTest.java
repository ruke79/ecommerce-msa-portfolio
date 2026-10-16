package com.project.userservice.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.userservice.security.LoginAttemptService;

/**
 * 로그인 실패 잠금의 판정은 한 곳 — Redis(LoginAttemptService). 엔티티에 두 번째 판정 근거를 되살리지 않는다.
 *
 * <p>🐛 예전: Redis 로 옮긴 뒤에도 {@code User.lockExpiresAt} · {@code failedLoginAttempts} 필드와 "잠금 판정은 lockExpiresAt" 주석이
 * 다섯 곳에 남아, 리뷰가 그 주석을 사실로 읽고 "관리자 잠금은 lockExpiresAt 도 세워야 한다" 는 잘못된 처방을 냈다. 열(V4)은 다음
 * contract 릴리스에서 지운다.
 *
 * <p>[JA] ログイン失敗ロックの判定は一か所(Redis)。エンティティに二つ目の判定根拠を戻さない。
 */
@DisplayName("로그인 실패 잠금 — 판정은 Redis 한 곳")
class UserLockFieldsTest {

    @Test
    @DisplayName("🔴 User 엔티티에 lockExpiresAt · failedLoginAttempts 가 없다 · LoginAttemptService 는 DB 를 보지 않는다")
    void lockLivesInRedisOnly() {
        assertThat(Arrays.stream(User.class.getDeclaredFields()).map(Field::getName))
                .doesNotContain("lockExpiresAt", "failedLoginAttempts");
        assertThat(Arrays.stream(LoginAttemptService.class.getDeclaredFields()).map(f -> f.getType().getName()))
                .noneMatch(t -> t.contains(".repository.") || t.endsWith("EntityManager"));
    }
}
