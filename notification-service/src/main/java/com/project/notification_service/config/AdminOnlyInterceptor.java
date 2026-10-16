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

        String path = judgedPath(request);
        if (!path.startsWith(ADMIN_PREFIX) && !matchedAdminPattern(request)) {
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

    /** 디코딩 · {@code ;…} 제거 · {@code //} 정리를 하는 Spring 의 기본 설정 — 컨트롤러를 고르는 쪽과 같은 경로를 본다. */
    private static final org.springframework.web.util.UrlPathHelper PATHS = new org.springframework.web.util.UrlPathHelper();

    /**
     * 판정할 경로 — Spring MVC 가 핸들러를 고를 때 보는 것과 같게(퍼센트 디코딩, 세그먼트의 {@code ;…} 제거, {@code //} 정리).
     *
     * <p>🐛 예전에는 {@code getRequestURI()}(디코딩 전 원문)를 앞부분만 비교했다. 핸들러 매칭은 디코딩하고 {@code ;…} 를 떼므로
     * {@code /api/notifications;x=1/admin/broadcasts} · {@code /api/notifications/%61dmin/broadcasts} 는 접두사가 맞지 않아
     * 통과했고 BroadcastAdminController 에 그대로 닿았다 — 로그인한 일반 사용자가 전체 공지를 만들고 · 지우고 · 예약분을 읽었다.
     * catalog AdminWriteInterceptor 와 같은 결함이다(C1).
     *
     * <p>[JA] 判定するパス — Spring MVC がハンドラーを選ぶときと同じく、デコード · {@code ;…} 除去 · {@code //} 整理。以前は生の
     * URI を前方一致で比べ、エンコードや {@code ;} で管理者専用の全体告知 API を一般ユーザーが使えた。
     */
    static String judgedPath(HttpServletRequest request) {
        String path = PATHS.getPathWithinApplication(request);
        return path == null ? "" : path;
    }

    /** 두 번째 확인 — 이미 골라진 핸들러의 패턴이 관리자 경로인가(경로 정규화가 Spring 과 어긋나도 막히게). */
    private static boolean matchedAdminPattern(HttpServletRequest request) {
        Object pattern = request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern != null && pattern.toString().startsWith(ADMIN_PREFIX);
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
