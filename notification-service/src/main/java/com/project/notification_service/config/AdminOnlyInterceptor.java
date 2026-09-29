package com.project.notification_service.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code /api/notifications/admin/**} 아래를 관리자 전용으로 막는다.
 *
 * <p>이 서비스에는 Spring Security 가 없다 — 인증은 게이트웨이의 JwtAuthorization 이
 * 끝내고, 검증된 신원을 {@code User-Id}/{@code User-Role} 헤더로 넘긴다. 게이트웨이가
 * 외부 요청의 이 헤더를 먼저 지운 뒤 자기 값으로 채우므로(IdentityHeaderScrubFilter)
 * 게이트웨이를 거친 요청에서는 위조할 수 없다. 여기서는 그 역할로 인가만 판정한다.
 *
 * <p><b>왜 컨트롤러 안에서 검사하지 않는가.</b> catalog-service 에서 정확히 그 형태의
 * 구멍을 겪었다 — 검사 코드를 메서드마다 넣는 방식은, 새 엔드포인트를 추가하면서 한 줄을
 * 잊는 순간 그 엔드포인트만 조용히 무방비가 된다. <b>경로 규칙이 곧 인가 규칙</b>이면
 * 잊을 수 없다: 관리자용이면 이 접두사 아래에 두고, 아니면 두지 않는다.
 *
 * <p>전체 알림은 <b>모든 사용자에게</b> 보이는 글이다. 일반 사용자가 발송할 수 있으면
 * 사이트 전체에 대고 아무 문구나 띄울 수 있다.
 */
@Slf4j
@Component
public class AdminOnlyInterceptor implements HandlerInterceptor {

    static final String ROLE_HEADER = "User-Role";
    static final String USER_HEADER = "User-Id";
    static final String ADMIN = "ROLE_ADMIN";

    /** 이 접두사 아래는 메서드와 무관하게(읽기까지) 관리자 전용이다. */
    static final String ADMIN_PREFIX = "/api/notifications/admin";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        String path = request.getRequestURI();
        if (!path.startsWith(ADMIN_PREFIX)) {
            return true;
        }

        // preflight 는 신원 헤더를 달고 오지 않는다. 여기서 막으면 브라우저가 본 요청을
        // 보내지도 못한다.
        if ("OPTIONS".equals(request.getMethod())) {
            return true;
        }

        String userId = request.getHeader(USER_HEADER);
        if (userId == null || userId.isBlank()) {
            // 게이트웨이를 안 거쳤거나 라우트가 깨졌다. 정상이라면 나올 수 없는 분기다.
            log.warn("관리자 경로에 신원 헤더가 없다 — 게이트웨이를 거치지 않았다. {} {}",
                    request.getMethod(), path);
            return reject(response, HttpStatus.UNAUTHORIZED, "로그인이 필요한 서비스입니다.");
        }

        if (!ADMIN.equals(request.getHeader(ROLE_HEADER))) {
            // 역할 문자열은 클레임 그대로라 로그에 남기지 않는다. 사실만 남긴다.
            log.warn("관리자 아님 — 거부. {} {} (userId={})", request.getMethod(), path, userId);
            return reject(response, HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다.");
        }

        return true;
    }

    private boolean reject(HttpServletResponse response, HttpStatus status, String message)
            throws Exception {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // 응답을 직접 쓰므로 ClientTextResponseAdvice 를 거치지 않는다 — 여기서 요청 언어로(docs/60 §4).
        response.getWriter().write("{\"message\":\"" + com.project.common.i18n.ClientText.localize(message) + "\"}");
        return false;
    }
}
