package com.project.userservice.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.project.userservice.constants.TokenType;
import com.project.userservice.model.User;
import com.project.userservice.model.redis.RefreshToken;
import com.project.userservice.security.jwt.JwtUtils;
import com.project.userservice.security.response.LoginResponse;
import com.project.userservice.service.DemoAccountService;
import com.project.userservice.service.RefreshTokenService;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 데모로 둘러보기 — 비밀번호 없이 데모 계정 하나로 로그인한다(docs/100). 응답 모양은 보통 로그인(AuthLoginFilter)과 같다 — 액세스 토큰은
 * 헤더, 리프레시는 쿠키. 데모 계정이 꺼져 있으면 404, 내줄 계정이 없으면 503. 기기 기록 · 새 기기 알림 · 로그인 위험 확인은 하지 않는다
 * (받을 사람이 없는 예약 도메인 · 방문자마다 새 기기다). 유입 제한은 게이트웨이의 공개 라우트 것.
 *
 * <p>[JA] デモで見て回る — パスワードなしでデモアカウント一つにログインする。応答の形は通常ログインと同じ。無効なら 404、払い出せるものが
 *      なければ 503。端末記録 · 新端末通知 · ログインリスク確認はしない(受け取る人のいない予約ドメイン · 訪問者ごとに新端末)。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth/public")
@RequiredArgsConstructor
public class DemoLoginController {

    private final DemoAccountService demo;
    private final JwtUtils jwtUtils;
    private final RefreshTokenService refreshTokenService;

    /** 화면이 버튼을 보일지 — 켜져 있는가만. [JA] 画面がボタンを出すか — 有効かだけ。 */
    @GetMapping("/demo-login")
    public java.util.Map<String, Boolean> demoLoginStatus() {
        return java.util.Map.of("enabled", demo.enabled());
    }

    @PostMapping("/demo-login")
    public ResponseEntity<LoginResponse> demoLogin(HttpServletResponse response) {
        if (!demo.enabled()) {
            return ResponseEntity.notFound().build();
        }
        User user = demo.claimNext().orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        String role = user.getRole().getRoleName().name();
        String accessToken = jwtUtils.generateTokenFromUser(user);
        RefreshToken refresh = refreshTokenService.createRefreshToken(user.getUserId());
        response.setHeader(TokenType.ACCESS.getType(), accessToken);
        response.addCookie(jwtUtils.createCookie(TokenType.REFRESH.getType(), refresh.getToken(), jwtUtils.refreshTokenLifetime()));
        log.info("데모 로그인: userId={}", user.getUserId());
        return ResponseEntity.ok(new LoginResponse(user.getUsername(), List.of(role), "demo", "demo"));
    }
}
