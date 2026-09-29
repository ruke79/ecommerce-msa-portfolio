package com.project.catalog_service.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 쓰기 요청의 역할(ROLE_ADMIN) 검사.
 *
 * <p>이 서비스에는 Spring Security 가 없다 — 인증은 게이트웨이의 JwtAuthorization 이
 * 끝내고, 검증된 신원을 {@code User-Id}/{@code User-Role} 헤더로 넘긴다. 그 헤더는
 * 게이트웨이 default-filters 가 외부 값을 무조건 지운 뒤 검증된 값만 다시 넣으므로
 * (application-local.yml 의 RemoveRequestHeader 주석 참조) 게이트웨이를 거친 요청에서는
 * 위조할 수 없다. 여기서는 그 역할 값으로 인가만 판정한다.
 *
 * <p>왜 필요한가: 게이트웨이의 catalog-service-write 라우트는 "로그인했는가"까지만 본다.
 * 로그인한 **일반 사용자**도 통과한다. 상품 등록·카테고리 생성·배치 잡 기동은 관리자
 * 전용이어야 하므로 역할 검사가 한 겹 더 필요하다. 실제로 이 서비스의 유일한
 * {@code @PreAuthorize} 는 주석 처리된 채 배포됐고, 사이트가 공개된 뒤 쓰기 API 전체가
 * 무인증으로 열려 있었다.
 *
 * <p>규칙 하나 더: {@code /api/content/admin/**} 은 반대로 <b>읽기까지</b> 관리자 전용이다.
 * 그 아래에 비공개 배너와 초안 공지 목록이 있어서, 쓰기만 막으면 GET 으로 미공개
 * 기획전의 존재와 시작 시각이 새어 나간다.
 *
 * <p>{@code PUT /api/product/rating} 은 <b>내부 전용</b>이다(docs/57 §3). 별점은 사람이 직접 매기는 값이 아니라
 * user-service 가 리뷰의 평균으로 계산해 보내는 값이다. 🐛 예전에는 "로그인만" 요구했다 — 그래서
 * <ul>
 *   <li>로그인한 아무나 아무 상품의 별점을 임의 값으로 덮어쓸 수 있었고,</li>
 *   <li>정작 신원 헤더 없이 오는 user-service 의 내부 호출은 아래 401 에 막혀, <b>리뷰 저장까지 롤백됐다</b>
 *       (별점 반영이 리뷰와 같은 트랜잭션이었다).</li>
 * </ul>
 * 이제 내부 호출(신원 헤더 없음)과 관리자만 통과한다({@code InternalCallGuard.allowed}). 게이트웨이의 쓰기 라우트는
 * 언제나 JWT 필터를 거치므로 밖에서 온 쓰기 요청에는 신원 헤더가 반드시 있다.
 */
@Slf4j
@Component
public class AdminWriteInterceptor implements HandlerInterceptor {

    static final String ROLE_HEADER = "User-Role";
    static final String USER_HEADER = "User-Id";
    static final String ADMIN = "ROLE_ADMIN";
    /** 이 접두사 아래는 메서드와 무관하게 관리자 전용이다(비공개 배너·초안 공지). */
    static final String ADMIN_CONTENT_PREFIX = "/api/content/admin";
    /** 별점 — 내부 전용(user-service 가 리뷰 평균을 보낸다). */
    static final String RATING_PATH = "/api/product/rating";
    /** 이 접두사 아래는 내부 전용이다 — 읽기도(docs/59). */
    static final String INTERNAL_PREFIX = "/api/internal/";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        String method = request.getMethod();
        String path = request.getRequestURI();

        // 콘텐츠 관리 경로는 **읽기도** 관리자 전용이다. 그 아래에 비공개 배너·초안 공지
        // 목록이 있어서, 쓰기만 막으면 GET 으로 미공개 기획전의 존재와 시작 시각이
        // 그대로 새어 나간다. 경로 규칙이 곧 인가 규칙이므로 새 관리 엔드포인트는 반드시
        // 이 접두사 아래에 둘 것.
        boolean adminOnlyPath = path.startsWith(ADMIN_CONTENT_PREFIX) || path.startsWith(INTERNAL_PREFIX);

        // 읽기는 공개다. CORS preflight(OPTIONS)도 여기서 막으면 안 된다.
        if (!adminOnlyPath && !("POST".equals(method) || "PUT".equals(method)
                || "DELETE".equals(method) || "PATCH".equals(method))) {
            return true;
        }

        // preflight 는 신원 헤더를 달고 오지 않는다. 여기서 막으면 브라우저가 본 요청을
        // 보내지도 못한다.
        if ("OPTIONS".equals(method)) {
            return true;
        }

        String userId = request.getHeader(USER_HEADER);
        String role = request.getHeader(ROLE_HEADER);

        // 내부 API(재고 예약 등, docs/59) — 메서드와 무관하게 내부 호출(신원 헤더 없음)·관리자만. 게이트웨이를 거친 요청에는
        // 언제나 신원 헤더가 있다.
        if (path.startsWith(INTERNAL_PREFIX)) {
            if (com.project.common.security.InternalCallGuard.allowed(role, request.getHeader("User-Email"), userId)) {
                return true;
            }
            log.warn("내부 API 외부 접근 거부. {} {} (userId={})", method, path, userId);
            return reject(response, HttpStatus.FORBIDDEN, "접근할 수 없습니다.");
        }

        // 별점 — 내부(리뷰 평균을 보내는 user-service)와 관리자만. 신원 헤더 검사보다 먼저 본다 — 내부 호출에는
        // 헤더가 없다.
        if (RATING_PATH.equals(path)) {
            if (com.project.common.security.InternalCallGuard.allowed(role, request.getHeader("User-Email"), userId)) {
                return true;
            }
            log.warn("별점 직접 변경 거부 — 별점은 리뷰로만 바뀐다. userId={}", userId);
            return reject(response, HttpStatus.FORBIDDEN, "별점은 리뷰를 통해서만 바뀝니다.");
        }

        // 게이트웨이를 안 거친(또는 라우트 설정이 깨진) 요청. 인증이 안 됐다는 뜻이므로
        // 401 — 게이트웨이가 정상이라면 이 분기는 나올 수 없다.
        if (userId == null || userId.isBlank()) {
            log.warn("쓰기 요청에 신원 헤더가 없다 — 게이트웨이를 거치지 않았거나 라우트가 깨졌다. {} {}", method, path);
            return reject(response, HttpStatus.UNAUTHORIZED, "로그인이 필요한 서비스입니다.");
        }

        if (!ADMIN.equals(role)) {
            // 값이 아니라 사실만 남긴다 — userId 는 숫자 id 라 그대로 남겨도 되지만
            // 역할 문자열은 클레임 그대로라 로그에 남길 이유가 없다.
            log.warn("관리자 아님 — 쓰기 거부. {} {} (userId={})", method, path, userId);
            return reject(response, HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다.");
        }

        return true;
    }

    private boolean reject(HttpServletResponse response, HttpStatus status, String message)
            throws Exception {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // 응답 본문을 직접 쓰므로 ClientTextResponseAdvice 를 거치지 않는다 — 여기서 요청 언어로 바꾼다(docs/60 §4).
        // 문구는 이 클래스의 상수뿐이라 JSON 이스케이프가 필요 없다(따옴표·역슬래시 없음).
        response.getWriter().write("{\"message\":\"" + com.project.common.i18n.ClientText.localize(message) + "\"}");
        return false;
    }
}
