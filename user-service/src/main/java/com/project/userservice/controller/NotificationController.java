package com.project.userservice.controller;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.project.userservice.constants.TokenType;
import com.project.userservice.service.NotificationService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    
    @GetMapping(value = "/sse", produces = "text/event-stream; charset=utf-8")
    public SseEmitter streamNotifications(@AuthenticationPrincipal UserDetails userDetails, 
    HttpServletRequest request, HttpServletResponse response) {

        log.info("SSE 연결 시도"); // 메소드 진입 로그
        log.info("Spring Security UserDetails: {}", userDetails != null ? userDetails.getUsername() : "null");

        String authorization = request.getHeader("Authorization");

        // ⚠️ 이 헤더를 **절대 로그에 찍지 말 것.** 예전에는 두 줄이 있었다:
        //
        //     log.info("Request Authorization Header: {}", accessToken);
        //     log.info("토큰에서 추출한 Session ID: {}", sessionId);   // 실은 토큰 전문이었다
        //
        // 둘 다 유효한 액세스 토큰을 평문으로 남긴다. 로그를 읽을 수 있는 사람은 누구나
        // 그 토큰으로 **그 사용자를 사칭**할 수 있다 — 만료 전까지. 게다가 SSE 는 접속할
        // 때마다 찍히고, 토큰 갱신마다 다시 찍힌다(TokenRefresher 가 만료 10초 전에
        // 갱신한다). 로그 수집기로 나가는 순간 회수할 방법도 없다.
        //
        // 두 번째 줄은 이름까지 틀렸다. substring(7) 은 세션 id 가 아니라 토큰 전문이다.
        // 세션 id 는 JwtUtils 가 토큰을 해석해야 나온다.
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            // 예전에는 `== null` 만 봤다. "Bearer " 로 시작하지 않는 헤더가 오면
            // substring(7) 이 엉뚱한 문자열을 잘라내거나 StringIndexOutOfBounds 로 죽었다.
            log.warn("Authorization 헤더가 없거나 Bearer 형식이 아닙니다 — SSE 구독을 거절합니다");
            return null;
        }

        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");  // http 1.1에서는 기본값이 keep-alive
        response.setHeader("Cache-Control", "no-cache");

        return notificationService.createEmitter(authorization.substring("Bearer ".length()));
    }


}