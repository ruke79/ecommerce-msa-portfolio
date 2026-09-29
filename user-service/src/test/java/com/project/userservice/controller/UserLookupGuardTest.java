package com.project.userservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import com.project.userservice.model.User;
import com.project.userservice.service.SegmentService;
import com.project.userservice.service.UserService;

/**
 * 🔒 사용자 조회 엔드포인트는 <b>내부 전용</b>이다 (docs/27).
 *
 * <p>{@code /api/auth/user/**} 는 USER 역할만 요구한다. 그래서 {@code /user/email/{id}}·{@code /user/profile/{id}}·
 * {@code /user/name} 에 가드가 없던 동안 <b>로그인한 아무나 남의 이메일·프로필을 id 로 긁어 갈 수 있었다</b>(IDOR).
 * 판정은 {@code InternalCallGuard}: 게이트웨이를 거친 요청(신원 헤더 셋 중 하나라도 있음)은 관리자만, 서비스 간
 * Feign 호출(신원 헤더 없음 — Authorization 만 전달)은 통과. 막을 때 id·이름 조회는 없는 사용자와 같은 404 다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("사용자 조회 — 내부 전용 가드")
class UserLookupGuardTest {

    @Mock private UserService userService;
    @Mock private SegmentService segmentService;
    @Mock private com.project.userservice.repository.jpa.UserRepository userRepository;
    @InjectMocks private AuthController controller;

    private User user() {
        User u = mock(User.class);
        when(u.getEmail()).thenReturn("victim@example.com");
        when(u.getUserId()).thenReturn(42L);
        return u;
    }

    @Test
    @DisplayName("🔴 로그인한 일반 사용자가 남의 id 로 이메일을 물으면 404 — 조회 자체를 하지 않는다")
    void customerCannotReadOthersEmail() {
        var response = controller.getUserEmailById(42L, "ROLE_USER", "attacker@example.com", "7");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(userService, never()).findById(anyLong());
    }

    @Test
    @DisplayName("🔴 프로필·이름 조회도 같다")
    void profileAndNameAreInternalToo() {
        assertThat(controller.getUserProfileById(42L, "ROLE_USER", "attacker@example.com", "7").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.findUserByName("victim", "ROLE_USER", "attacker@example.com", "7").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        verify(userService, never()).findById(anyLong());
        verify(userService, never()).findByUsername(anyString());
    }

    @Test
    @DisplayName("서비스 간 호출(신원 헤더 없음)은 그대로 된다 — cart-service 가 쓴다")
    void internalCallStillWorks() {
        User u = user();
        when(userService.findById(42L)).thenReturn(u);

        var response = controller.getUserEmailById(42L, null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().toString()).contains("victim@example.com");
    }

    @Test
    @DisplayName("관리자는 게이트웨이를 거쳐도 된다")
    void adminAllowed() {
        User u = user();
        when(userService.findById(42L)).thenReturn(u);

        assertThat(controller.getUserEmailById(42L, "ROLE_ADMIN", "admin@example.com", "1").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("🔴 역할 헤더가 없어도 신원 헤더가 하나라도 있으면 외부다 — 예전 /user/id 는 역할만 봤다")
    void roleHeaderAloneIsNotEnough() {
        var response = controller.findUserByEmail("victim@example.com", null, null, "7");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(userService, never()).findByEmail(any());
    }

    @Test
    @DisplayName("🔴 id 여럿 → 이메일도 내부 전용 — 로그인한 일반 사용자는 404, 조회 자체를 안 한다")
    void bulkEmailLookupIsInternal() {
        var blocked = controller.getUserEmailsByIds(java.util.List.of(42L, 43L), "ROLE_USER", "attacker@example.com", "7");

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(userRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("내부 호출은 id→이메일 맵을 받고, 한도를 넘으면 400")
    void bulkEmailLookupForInternalCaller() {
        User u = user();
        when(userRepository.findAllById(java.util.List.of(42L))).thenReturn(java.util.List.of(u));

        var ok = controller.getUserEmailsByIds(java.util.List.of(42L), null, null, null);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).isEqualTo(java.util.Map.of("42", "victim@example.com"));

        var tooMany = controller.getUserEmailsByIds(
                java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList(), null, null, null);
        assertThat(tooMany.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** coupon 의 신원이 id 로 바뀌었다 — 고객군도 id 로 묻는다(docs/48 §6-⑥). */
    @Test
    @DisplayName("고객군은 사용자 id 로 묻는다 — 내부 호출만, 외부면 403 이고 조회하지 않는다")
    void segmentsById() {
        User u = user();
        when(userRepository.findById(42L)).thenReturn(java.util.Optional.of(u));
        when(segmentService.segmentsOf(u)).thenReturn(java.util.List.of(
                com.project.common.constants.event.AudienceSegment.NEW_USER));

        var ok = controller.findUserSegments(42L, null, null, null);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).isEqualTo(java.util.Map.of("segments", java.util.List.of("NEW_USER")));
        verify(userService, never()).findByEmail(any());

        var blocked = controller.findUserSegments(42L, "ROLE_USER", "attacker@example.com", "7");
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("없는 사용자·빈 요청은 빈 목록 — 404 로 가입 여부를 흘리지 않는다")
    void segmentsOfUnknownUserAreEmpty() {
        when(userRepository.findById(99L)).thenReturn(java.util.Optional.empty());
        when(segmentService.segmentsOf(null)).thenReturn(java.util.List.of());

        assertThat(controller.findUserSegments(99L, null, null, null).getBody())
                .isEqualTo(java.util.Map.of("segments", java.util.List.of()));
        assertThat(controller.findUserSegments(null, null, null, null).getBody())
                .isEqualTo(java.util.Map.of("segments", java.util.List.of()));
    }
}
