package com.project.eurekaserver;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import com.project.common.security.InternalCallerFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Eureka 등록부를 바꾸는 요청은 <b>그 서비스 자신의 인증서</b>로만 받는다(docs/57 §16 "Eureka 쓰기").
 *
 * <p>🐛 예전: Eureka 는 클라이언트 인증서를 보지 않았다. ecommerce 네임스페이스의 파드 하나만 뚫려도
 * {@code PUT /eureka/apps/ORDER-SERVICE/<id>/status?value=OUT_OF_SERVICE} 한 줄로 order-service 의 모든 인스턴스가 목록에서
 * 빠지고(게이트웨이 · Feign 의 {@code lb://} 가 대상을 잃어 503), {@code POST /eureka/apps/ORDER-SERVICE} 로 가짜 인스턴스를
 * 끼워 넣으면 서비스 간 호출이 그쪽으로 갔다. 서비스 간 mTLS(§16)는 백엔드끼리의 호출만 지켰다.
 *
 * <p>판정: 읽기(GET · HEAD · OPTIONS)는 그대로 둔다. 쓰기(등록 · 하트비트 · 상태 · 메타데이터 · 취소)는 내부 CA 가 서명한 클라이언트
 * 인증서의 CN 이 경로의 앱 이름과 같아야 한다(대소문자 무시 — Eureka 앱 이름은 {@code spring.application.name} 을 대문자로 쓴다).
 * {@code /eureka/apps/} 밖의 쓰기(피어 복제 · ASG)는 이 단독 Eureka 에 올 일이 없으므로 enforce 에서 거절한다.
 *
 * <p>모드 {@code eureka-guard.write-mode}(ConfigMap {@code EUREKA_WRITE_AUTH_MODE}): off(기본) · observe(어긋난 쓰기를
 * {@code [eureka write]} 로 남기고 통과) · enforce(403). 경로는 Spring MVC 와 같게 디코딩 · {@code ;…} 제거 뒤에 본다
 * ({@code /eureka/apps/ORDER%2DSERVICE} 로 앱 이름 비교를 비껴가지 못하게).
 *
 * <p>[JA] Eureka の登録を変えるリクエストは、そのサービス自身の証明書でのみ受け付ける。以前は名前空間内のポッド一つから
 * OUT_OF_SERVICE 指定や偽インスタンス登録ができ、lb:// の呼び出しが 503 になったり偽の宛先へ流れたりした。読み取りはそのまま、
 * 書き込みは内部 CA が署名した証明書の CN がパスのアプリ名と一致すること。
 */
@Slf4j
public class RegistryWriteGuard extends OncePerRequestFilter {

    public enum Mode { OFF, OBSERVE, ENFORCE }

    static final String CERT_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";
    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Pattern APP = Pattern.compile("^/eureka/apps/([^/]+)(?:/.*)?$");
    private static final int MAX_SEEN = 1000;
    private static final UrlPathHelper PATHS = new UrlPathHelper();

    private final Mode mode;
    private final X509Certificate ca;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicLong rejected = new java.util.concurrent.atomic.AtomicLong();

    public RegistryWriteGuard(Mode mode, X509Certificate ca) {
        this.mode = mode;
        this.ca = ca;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return mode == Mode.OFF || READS.contains(request.getMethod().toUpperCase(Locale.ROOT));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = PATHS.getPathWithinApplication(request);
        if (path == null || !path.startsWith("/eureka/")) {
            chain.doFilter(request, response);
            return;
        }
        Object certs = request.getAttribute(CERT_ATTRIBUTE);
        String caller = InternalCallerFilter.verifiedCommonName(certs instanceof X509Certificate[] c ? c : null, ca);
        String app = targetApp(path);
        String why = violation(caller, app);
        if (why == null) {
            chain.doFilter(request, response);
            return;
        }
        // 인스턴스 id(호스트 · IP 가 든다)는 남기지 않는다 — 누가 어느 앱에 무엇을 했는지만.
        String key = (caller == null ? "-" : caller) + " " + request.getMethod() + " " + (app == null ? path : app);
        if (seen.size() < MAX_SEEN && seen.add(key)) {
            log.warn("[eureka write] {} — caller={} {} app={} ({})", why, caller == null ? "(인증서 없음)" : caller,
                    request.getMethod(), app == null ? path : app, mode == Mode.ENFORCE ? "거절" : "observe — 통과");
        }
        if (mode == Mode.ENFORCE) {
            rejected.incrementAndGet();
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }

    /** {@code /eureka/apps/{APP}…} 의 앱 이름, 그 밖의 쓰기 경로면 null. */
    static String targetApp(String path) {
        Matcher m = APP.matcher(path);
        return m.matches() ? m.group(1) : null;
    }

    /** 이 쓰기를 받을 수 없는 이유, 받을 수 있으면 null. */
    static String violation(String caller, String app) {
        if (caller == null) {
            return "내부 CA 인증서 없이 등록부를 바꾸려 했다";
        }
        if (app == null) {
            return "앱 등록 밖의 쓰기(피어 복제 · ASG)는 이 단독 Eureka 가 받지 않는다";
        }
        return caller.equalsIgnoreCase(app) ? null : "다른 서비스의 등록을 바꾸려 했다";
    }

    Mode mode() {
        return mode;
    }

    /** enforce 로 거절한 쓰기 수(기동 뒤 누적). */
    long rejected() {
        return rejected.get();
    }
}
