package com.project.userservice.security.request;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.userservice.model.User;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Size;

/**
 * 프로필 이름 — 요청이 받는 길이 ≤ 저장할 수 있는 길이.
 *
 * <p>🐛 예전: 요청은 50자까지, 열 · 엔티티는 20자 — 21~50자는 검증을 지나 저장에서 실패해 404 로 보였다.
 *
 * <p>[JA] プロフィール名 — リクエストが受ける長さ ≤ 保存できる長さ。
 */
@DisplayName("프로필 이름 길이 — 요청 ≤ 저장")
class ProfileNameLengthTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("🔴 요청의 최대 길이가 엔티티(열)의 최대 길이를 넘지 않는다")
    void requestLimitFitsTheColumn() throws Exception {
        int request = ProfileUpdateRequest.class.getDeclaredField("name").getAnnotation(Size.class).max();
        int entity = User.class.getDeclaredField("name").getAnnotation(Size.class).max();
        assertThat(request).isLessThanOrEqualTo(entity);
    }

    @Test
    @DisplayName("21자 이름은 요청 단계에서 400 문구로 거절된다")
    void tooLongNameIsRejectedUpFront() {
        ProfileUpdateRequest r = new ProfileUpdateRequest();
        r.setName("가".repeat(21));
        assertThat(validator.validate(r)).extracting(v -> v.getMessage()).contains("이름은 20자까지 입력할 수 있습니다.");
        r.setName("가".repeat(20));
        assertThat(validator.validate(r)).isEmpty();
    }
}
