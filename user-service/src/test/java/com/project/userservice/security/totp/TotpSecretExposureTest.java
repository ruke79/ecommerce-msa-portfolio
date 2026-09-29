package com.project.userservice.security.totp;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.project.userservice.dto.UserProfileDto;
import com.project.userservice.model.User;

/**
 * 2FA 비밀은 어떤 응답에도 실리지 않는다(docs/70).
 *
 * <p>🐛 예전: {@code GET /api/admin/getusers} 가 {@code User} 엔티티 목록을 그대로 돌려줘, 모든 사용자의 TOTP 비밀이 JSON 에 있었다.
 */
@DisplayName("2FA 비밀 — 응답 노출 금지")
class TotpSecretExposureTest {

    @Test
    @DisplayName("🔴 User 를 JSON 으로 바꿔도 2FA 비밀 · 비밀번호가 없다")
    void entityJsonHasNoSecret() throws Exception {
        User u = User.builder().twoFactorSecret("JBSWY3DPEHPK3PXP").password("hashed-password").build();

        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(u);

        assertThat(json).doesNotContain("JBSWY3DPEHPK3PXP").doesNotContain("twoFactorSecret").doesNotContain("hashed-password");
    }

    @Test
    @DisplayName("응답 DTO 에는 2FA 비밀 필드 자체가 없다")
    void profileDtoHasNoSecretField() {
        assertThat(UserProfileDto.class.getDeclaredFields())
                .noneMatch(f -> f.getName().toLowerCase().contains("secret"));
    }

    /** 컨트롤러가 엔티티를 그대로 돌려주면 @JsonIgnore 하나를 빠뜨리는 순간 새는 구조다. 응답은 DTO 로만. */
    @Test
    @DisplayName("🔴 어떤 컨트롤러도 User 엔티티(또는 그 목록)를 응답으로 돌려주지 않는다")
    void noControllerReturnsTheEntity() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Pattern entity = Pattern.compile("\\bcom\\.project\\.userservice\\.model\\.User\\b(?![A-Za-z0-9_$])");
        List<String> offenders = new ArrayList<>();
        var controllers = scanner.findCandidateComponents("com.project.userservice");
        for (var bd : controllers) {
            for (Method m : Class.forName(bd.getBeanClassName()).getDeclaredMethods()) {
                if (entity.matcher(m.getGenericReturnType().getTypeName()).find()) {
                    offenders.add(bd.getBeanClassName() + "#" + m.getName());
                }
            }
        }
        assertThat(controllers).as("컨트롤러를 찾지 못했다 — 검사가 헛돈다").isNotEmpty();
        assertThat(offenders).isEmpty();
    }
}
