package com.project.userservice.controller;

import java.time.Duration;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.project.common.constants.StatusMessages;
import com.project.common.response.MessageResponse;
import com.project.userservice.constants.TokenType;
import com.project.userservice.model.User;
import com.project.userservice.security.jwt.JwtUtils;
import com.project.userservice.security.service.UserDetailsImpl;
import com.project.userservice.service.RefreshTokenService;
import com.project.userservice.service.impl.UserServiceImpl;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 모든 기기에서 로그아웃(docs/68 §8).
 *
 * <p>"새로운 기기에서 로그인되었습니다" · "보안을 위해 로그인이 해제되었습니다" 알림을 받은 사용자가 <b>당장 할 수 있는 조치</b>다.
 * 예전에는 비밀번호 변경뿐이었다 — 로그아웃은 지금 기기 하나만 끊었고, "기억 지우기" 는 세션을 끊지 않는다.
 *
 * <p>🔒 신원은 토큰에서만 — 사용자 id 를 받지 않는다(남의 로그인을 끊을 수 있게 된다).
 *
 * <p>이 기기도 함께 로그아웃된다(리프레시 쿠키를 지운다). "이 기기는 남기기" 를 하지 않은 이유: 이 기기가 도둑의 것일 수도 있고,
 * 다시 로그인하는 비용은 작다. 다른 기기는 <b>다음 갱신에서</b> 로그인 화면으로 간다 — 이미 가진 액세스 토큰은 수명(15분)까지 유효하다.
 */
// [JA] すべての端末からログアウト。本人の身元はトークンからのみ。この端末もログアウトする(Cookie を消す)。
//      他の端末は次の更新でログイン画面へ(手元のアクセストークンは寿命 15 分まで有効)。
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/user/profile/sessions")
public class SessionController {

    private final UserServiceImpl userService;
    private final RefreshTokenService refreshTokenService;
    private final JwtUtils jwtUtils;

    @PostMapping("/revoke-all")
    public ResponseEntity<?> revokeAll(@AuthenticationPrincipal UserDetails userDetails, HttpServletResponse response) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse(StatusMessages.USER_NOT_FOUND));
        }
        // 🐛 예전: findByEmail(getUsername()) — getUsername() 은 이메일이 아니라 가입 아이디(소셜은 google_…)라 거의 모든
        //    사용자에게 401 이었다. 새 기기 알림을 받고 눌러도 공격자의 세션이 남았다. 테스트가 getUsername 을 이메일로 목킹해 가렸다.
        // [JA] 以前は findByEmail(getUsername()) — getUsername() はメールではなく登録 ID なので、ほぼ全員 401 だった。
        User user = userDetails instanceof UserDetailsImpl impl
                ? userService.findById(impl.getId())
                : userService.findByUsername(userDetails.getUsername());
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse(StatusMessages.USER_NOT_FOUND));
        }

        int revoked = refreshTokenService.revokeAll(user.getUserId());
        // 이 기기의 리프레시 쿠키도 지운다 — 서버에서는 이미 끊겼지만, 남겨 두면 다음 로딩에 400 을 한 번 더 본다.
        response.addCookie(jwtUtils.createCookie(TokenType.REFRESH.getType(), null, Duration.ZERO));
        return ResponseEntity.ok(Map.of("revoked", revoked));
    }
}
