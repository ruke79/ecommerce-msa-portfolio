package com.project.userservice.security;

import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.response.MessageResponse;
import com.project.userservice.constants.TokenType;
import com.project.userservice.dto.UserProfileDto;
import com.project.userservice.model.User;
import com.project.userservice.model.redis.RefreshToken;
import com.project.userservice.security.jwt.JwtUtils;
import com.project.userservice.security.request.LoginRequest;
import com.project.userservice.security.response.LoginResponse;
import com.project.userservice.security.service.UserDetailsImpl;
import com.project.userservice.message.producer.SecurityAlertProducer;
import com.project.userservice.service.DeviceRecognitionService;
import com.project.userservice.service.DeviceRecognitionService.Recognition;
import com.project.userservice.service.NotificationService;
import com.project.userservice.service.RefreshTokenService;
import com.project.userservice.service.UserService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AuthLoginFilter extends UsernamePasswordAuthenticationFilter {

    private final AuthenticationManager authenticationManager;

    private final JwtUtils jwtUtils;

    private final RefreshTokenService refreshTokenService;

    private final NotificationService notificationService;

    /** 새 기기 로그인 감지 — SECURITY_ALERT 를 만들어 내는 유일한 판단. */
    private final DeviceRecognitionService deviceRecognitionService;

    private final SecurityAlertProducer securityAlertProducer;

    /** 다중 로그인 판정의 근거. Redis 에 있으므로 레플리카가 같은 기억을 본다. */
    private final RedisSessionManager sessionManager;

    /** 2FA 계정의 첫 단계 통과 — 세션 대신 대기 토큰을 준다. */
    private final PendingTwoFactor pendingTwoFactor;

    /** 위험 기반 로그인 — 실패 신호 기록 · 처음 보는 기기의 메일 확인(docs/68 §19). */
    private final com.project.userservice.security.risk.LoginRiskGuard loginRiskGuard;
    private final com.project.userservice.service.EmailService emailService;

    /** 실패 처리에서 이메일을 다시 읽을 수 없어(본문은 한 번만 읽힌다) 요청 속성에 둔다 — 응답 · 로그에는 싣지 않는다. */
    static final String EMAIL_ATTR = AuthLoginFilter.class.getName() + ".email";

    /** 비밀번호는 맞았고 코드 확인이 남았다. 프론트는 이 코드를 보고 코드 입력 화면으로 간다. */
    public static final String TWO_FACTOR_REQUIRED_CODE = "two_factor_required";

    @Autowired
    public AuthLoginFilter(AuthenticationManager authenticationManager, JwtUtils jwtUtils,
            RefreshTokenService refreshTokenService,
            NotificationService notificationService,
            DeviceRecognitionService deviceRecognitionService,
            SecurityAlertProducer securityAlertProducer,
            RedisSessionManager sessionManager,
            PendingTwoFactor pendingTwoFactor,
            com.project.userservice.security.risk.LoginRiskGuard loginRiskGuard,
            com.project.userservice.service.EmailService emailService) {
        this.authenticationManager = authenticationManager;
        this.jwtUtils = jwtUtils;
        this.refreshTokenService = refreshTokenService;
        this.notificationService = notificationService;
        this.deviceRecognitionService = deviceRecognitionService;
        this.securityAlertProducer = securityAlertProducer;
        this.sessionManager = sessionManager;
        this.pendingTwoFactor = pendingTwoFactor;
        this.loginRiskGuard = loginRiskGuard;
        this.emailService = emailService;
    }

    /**
     * 요청 본문을 읽어 인증을 시도한다.
     *
     * <p>🐛 <b>예전에는 여기가 모든 실패를 삼켰다.</b> {@code catch (Exception e)} 가
     * {@link AuthenticationException} 까지 잡아 <b>본문 없는 400</b> 을 내보내고 null 을
     * 반환했다. 그런데 {@code AbstractAuthenticationProcessingFilter} 는 null 을 받으면
     * "아직 인증이 끝나지 않았다(다단계 인증 중)"로 보고 <b>그 자리에서 반환한다</b> —
     * {@link #unsuccessfulAuthentication} 이 아예 불리지 않는다.
     *
     * <p>그래서 잠금 안내("약 12분 후 다시 시도해 주세요")도, 메일 인증 안내도, 실패 사유
     * 자체도 사용자에게 닿을 길이 없었다. 화면에는 언제나 "Login Failed." 한 줄만 떴고
     * 진짜 이유는 서버 로그에만 남았다. 실패 처리 코드는 멀쩡히 있었지만 <b>죽은 코드</b>
     * 였다.
     *
     * <p>이제 인증 실패는 그대로 위로 던진다 — 스프링이 {@code unsuccessfulAuthentication}
     * 으로 넘겨 주고, 거기서 사유에 맞는 문구를 본문에 싣는다. 여기서 잡는 것은 <b>본문을
     * 읽지 못한 경우</b>뿐이고, 그것도 null 을 반환하지 않고 예외로 바꿔 같은 경로를 타게
     * 한다.
     */
    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
            throws AuthenticationException {

        LoginRequest loginInfo;
        try {
            loginInfo = new ObjectMapper().readValue(request.getInputStream(), LoginRequest.class);
        } catch (IOException e) {
            // 본문이 JSON 이 아니거나 읽다 끊겼다. 사용자가 고칠 수 있는 것이 아니므로
            // 내용은 로그로만 남긴다 — 파싱 오류 메시지에 본문 일부(=비밀번호)가 섞여
            // 나갈 수 있어 응답에 실을 수 없다.
            log.warn("로그인 요청 본문을 읽지 못했습니다: {}", e.getMessage());
            throw new BadRequestAuthenticationException("요청 형식이 올바르지 않습니다.");
        }

        String email = loginInfo.getEmail() == null ? "" : loginInfo.getEmail().trim();
        String password = loginInfo.getPassword() == null ? "" : loginInfo.getPassword();
        request.setAttribute(EMAIL_ATTR, email);

        // 빈 값으로 인증기에 들어가면 "Bad Credentials" 가 되어 사용자는 비밀번호가 틀린
        // 줄 안다. 실제로는 아무것도 보내지 않은 것이다(폼 검증을 우회했거나 API 직접 호출).
        if (email.isEmpty() || password.isEmpty()) {
            throw new BadRequestAuthenticationException("이메일과 비밀번호를 모두 입력해 주세요.");
        }

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, password));

        SecurityContextHolder.getContext().setAuthentication(authentication);
        return authentication;
    }

    /**
     * 요청 자체가 잘못된 경우 — 400 으로 답해야 하는 실패.
     *
     * <p>{@link AuthenticationException} 을 상속하는 이유: 그래야 스프링이
     * {@link #unsuccessfulAuthentication} 으로 넘겨 주고, 응답 본문을 만드는 자리가
     * <b>한 곳</b>으로 유지된다. 여기서 직접 응답을 쓰면 형식이 갈라지고, 실제로 예전에
     * 그렇게 해서 본문 없는 400 이 나갔다.
     */
    static class BadRequestAuthenticationException extends AuthenticationException {
        BadRequestAuthenticationException(String message) {
            super(message);
        }
    }

    @Override
    protected void successfulAuthentication(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
            Authentication authentication) throws IOException {

        // UserDetailsS
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();

        // Collect roles from the UserDetails
        List<String> roles = userDetails.getAuthorities().stream()
                .map(item -> item.getAuthority())
                .collect(Collectors.toList());

        Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
        Iterator<? extends GrantedAuthority> iterator = authorities.iterator();
        GrantedAuthority auth = iterator.next();

        String role = auth.getAuthority();

        // ── 2단계 인증 ──────────────────────────────────────────────────────────
        //
        // 🔴 예전에는 2FA 계정에도 아래에서 액세스 토큰(헤더)과 REFRESH 쿠키를 **먼저** 내줬다. 코드 입력을
        //    기다린 것은 프론트뿐이라, 비밀번호만 알면 응답 헤더의 토큰으로 그대로 들어올 수 있었다.
        //    이제 여기서 끝낸다 — 토큰도 세션도 없이 대기 토큰(httpOnly 쿠키)만 준다. 코드 확인
        //    (/api/auth/public/verify-2fa)이 통과해야 REFRESH 가 나온다. 소셜 경로와 같은 끝이다.
        //    기기 기록(새 기기 알림)은 한다 — 비밀번호가 새 기기에서 맞았다는 것 자체가 알릴 일이다.
        //    다중 로그인 차단(이전 세션 끊기)은 하지 않는다 — 비밀번호만으로 남의 세션을 끊게 된다.
        if (userDetails.is2faEnabled()) {
            recordDevice(request, response, userDetails);
            response.addCookie(jwtUtils.createCookie(PendingTwoFactor.COOKIE,
                    pendingTwoFactor.issue(userDetails.getId()), java.time.Duration.ofMillis(PendingTwoFactor.TTL_MS)));
            response.setContentType("application/json");
            response.setCharacterEncoding("utf-8");
            response.setStatus(HttpStatus.OK.value());
            response.getWriter().write(new ObjectMapper().writeValueAsString(new LoginResponse(
                    userDetails.getUsername(), roles, "Two-factor code required", TWO_FACTOR_REQUIRED_CODE)));
            log.info("로그인 1단계 통과 — 2FA 확인 대기. userId={}", userDetails.getId());
            return;
        }

        // ── 위험 기반 확인 ───────────────────────────────────────────────────
        //
        // 비밀번호가 맞았다 — 그런데 크리덴셜 스터핑은 "맞는 비밀번호" 로 들어온다(다른 사이트에서 샌 목록). 처음 보는 기기이고 지금
        // 공격 신호(IP 팬아웃 · 표적 계정 · 전체 급증)가 있으면 토큰을 내주지 않고 메일 코드로 한 번 더 확인한다(docs/68 §19).
        // 기기 기록도 하지 않는다 — 기록하면 다음 시도부터 "아는 기기" 가 되어 확인을 건너뛴다.
        // [JA] パスワードは合った。だがスタッフィングは「正しいパスワード」で入ってくる。未知の端末で攻撃信号があれば、トークンを
        //      渡さずメールコードで確認する。端末の記録もしない(記録すると次から「既知の端末」になり確認を飛ばす)。
        if (challengeIfRisky(request, response, userDetails, roles)) {
            return;
        }

        RefreshToken refreshToken = null;

        ObjectMapper om = new ObjectMapper();

        String data = null;

        // 문구(영어)는 코드를 모르는 클라이언트용 폴백이다. 화면에 보이는 말은 아래
        // code 를 받아 프론트 사전이 고른다(LoginResponse 주석).
        String message = "Login Success";
        String code = LOGIN_SUCCESS_CODE;

        // ── 다중 로그인 차단 ────────────────────────────────────────────────
        //
        // ⚠️ 끊을 세션 id 를 **여기서 붙잡아 둔다.** 아래 generateToken 이 새 세션을 등록하면
        //    getSession(email) 은 그때부터 새 세션을 가리킨다. 그 뒤에 읽으면 방금 로그인한
        //    본인을 끊게 된다.
        //
        // ⚠️ 그리고 그 id 를 sendNotification 에 **명시적으로 넘긴다.** 예전에는 인자가
        //    (email, message) 뿐이었고 배달 대상은 NotificationService 의 공유 필드에서
        //    읽었다 — 마지막으로 SSE 에 구독한 사람이 누구냐에 따라 **다른 사용자가**
        //    로그아웃됐다(그 클래스 주석 참조).
        String previousSessionId = sessionManager.getSession(userDetails.getEmail());
        if (previousSessionId != null) {

            // 세션 id 는 INFO 로 남기지 않는다 — 계정당 한 줄씩 쌓이는 식별자다.
            log.debug("이전 세션 종료 요청: userId={}", userDetails.getId());

            // 알림 행 · Redis 메시지에는 userId 만 — 🐛 예전에는 이메일을 실어 users.notification 에 남았고 탈퇴해도 지워지지 않았다(§6).
            notificationService.sendNotification(String.valueOf(userDetails.getId()), previousSessionId, "Logout");

            message = "User attempted to log in multiply, logging out from previous logged in state";
            code = MULTI_LOGIN_CODE;

        }

        
        
        
        // uid 클레임을 함께 넣는다 — 게이트웨이가 이 값을 User-Id 헤더로 넘겨야 하위
        // 서비스가 소유권을 판정할 수 있다(JwtUtils.UID_CLAIM 주석 참조).
        String accessToken = jwtUtils.generateToken(
                userDetails.getId(), userDetails.getEmail(), role, userDetails.is2faEnabled());

        log.debug("로그인 세션 등록: userId={}", userDetails.getId());
        
        refreshToken = refreshTokenService.createRefreshToken(userDetails.getId());

        
        LoginResponse loginInfo = new LoginResponse(userDetails.getUsername(), roles, message, code);
        
        
        // 새 기기 로그인 감지. **토큰을 내보내기 전에** 한다 — 쿠키를 응답에 실어야 하고,
        // 응답 본문을 쓴 뒤에는 헤더를 더할 수 없다.
        recordDevice(request, response, userDetails);

        try {

            data = om.writeValueAsString(loginInfo);
            response.setContentType("application/json");
            response.setCharacterEncoding("utf-8");

            response.setHeader(TokenType.ACCESS.getType(), accessToken);

            response.addCookie(
                    jwtUtils.createCookie(TokenType.REFRESH.getType(), refreshToken.getToken(), jwtUtils.refreshTokenLifetime()));
            // refreshTokenService.deleteByUserId(userDetails.getId());
            // response.setHeader(HttpHeaders.SET_COOKIE,
            response.getWriter().write(data);
            response.setStatus(HttpStatus.OK.value());
            
            
            log.info("Login Success");

        } catch (JsonProcessingException e) {
            // TODO Auto-generated catch block
            response.setStatus(401);
        }

    }

    /**
     * 로그인한 기기를 기록하고, 처음 보는 기기면 알린다.
     *
     * <p>🐛 {@code SECURITY_ALERT} 는 메일 제목·SMS 문구·<b>"사용자가 꺼도 보낸다"는 수신
     * 설정 예외</b>까지 전부 갖춰져 있었는데 만드는 쪽만 없었다. 그러려면 "이 로그인이
     * 처음 보는 기기인가" 에 답할 수 있어야 하고, 그 판단이
     * {@link DeviceRecognitionService} 다.
     *
     * <p><b>어떤 실패도 로그인을 막지 않는다.</b> 기기 기록은 로그인에 <b>부수적</b>이다.
     * 여기서 예외가 올라가면 비밀번호를 맞게 넣은 사용자가 로그인하지 못한다 — 보안
     * 알림을 붙이려다 서비스를 멈추는 것이고, 그건 훨씬 나쁘다.
     */
    /** 확인 코드가 나가는 응답 코드 — 프론트가 이 값을 보고 코드 입력 화면으로 간다. */
    public static final String LOGIN_CHECK_REQUIRED_CODE = "login_check_required";

    /** @return 확인을 걸어 응답을 썼으면 true(호출자는 여기서 끝낸다) */
    private boolean challengeIfRisky(HttpServletRequest request, HttpServletResponse response,
            UserDetailsImpl userDetails, List<String> roles) throws IOException {
        java.util.List<com.project.userservice.security.risk.LoginRiskGuard.Signal> signals;
        com.project.userservice.security.risk.LoginRiskGuard.IssuedChallenge issued;
        try {
            if (deviceRecognitionService.isKnown(userDetails.getId(), DeviceIdentity.tokenFrom(request))) {
                return false;   // 아는 기기 — 목록으로 들어온 공격자는 이 쿠키를 갖고 있지 않다
            }
            signals = loginRiskGuard.signals(userDetails.getEmail(), DeviceIdentity.trustedClientIp(request));
            if (!com.project.userservice.security.risk.LoginRiskGuard.needsChallenge(false, signals)) {
                return false;
            }
            issued = loginRiskGuard.issue(userDetails.getId(), signals);
        } catch (RuntimeException e) {
            // 판단할 수 없으면 막지 않는다(Redis · DB 장애가 로그인 장애가 되지 않게). 종류만 남긴다.
            log.warn("로그인 위험 판단을 하지 못해 통과시켰다 — {}", e.getClass().getSimpleName());
            return false;
        }

        response.setContentType("application/json");
        response.setCharacterEncoding("utf-8");
        if (!emailService.sendLoginChallengeEmail(userDetails.getId(), userDetails.getEmail(), issued.code())) {
            // 공격 신호가 있는데 확인 메일을 보낼 수 없다 — 이때는 통과시키지 않는다(신호가 없는 평소에는 여기 오지 않는다).
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.getWriter().write(new ObjectMapper().writeValueAsString(java.util.Map.of("message",
                    com.project.common.i18n.ClientText.localize("지금은 로그인 확인 메일을 보낼 수 없습니다. 잠시 후 다시 시도해 주세요."))));
            return true;
        }
        response.addCookie(jwtUtils.createCookie(com.project.userservice.security.risk.LoginRiskGuard.COOKIE,
                issued.issued().cookieValue(), com.project.userservice.security.risk.LoginRiskGuard.CHALLENGE_TTL));
        response.setStatus(HttpStatus.OK.value());
        response.getWriter().write(new ObjectMapper().writeValueAsString(new LoginResponse(
                userDetails.getUsername(), roles, "Login check required", LOGIN_CHECK_REQUIRED_CODE)));
        // 코드 · 쿠키 값은 남기지 않는다 — 신호 이름만.
        log.info("로그인 위험 신호 — 메일 확인 대기. userId={}, signals={}", userDetails.getId(), signals);
        return true;
    }

    private void recordDevice(HttpServletRequest request, HttpServletResponse response,
            UserDetailsImpl userDetails) {
        try {
            Recognition recognition = deviceRecognitionService.recognize(
                    userDetails.getId(),
                    DeviceIdentity.tokenFrom(request),
                    request.getHeader("User-Agent"),
                    DeviceIdentity.clientIp(request));

            if (recognition.cookieToken() != null) {
                // 아는 기기면 다시 심지 않는다 — 값이 그대로다.
                response.addCookie(jwtUtils.createCookie(DeviceIdentity.COOKIE_NAME,
                        recognition.cookieToken(), java.time.Duration.ofSeconds(DeviceIdentity.COOKIE_MAX_AGE_SECONDS)));
            }

            if (recognition.shouldAlert()) {
                securityAlertProducer.publishNewDevice(
                        userDetails.getId(), userDetails.getEmail(), recognition.device());
            }

        } catch (Exception e) {
            // 기록도 알림도 로그인의 성공 조건이 아니다.
            log.error("기기 기록에 실패했습니다 — 로그인은 계속합니다: {}", e.getMessage());
        }
    }

    /**
     * 자격 증명이 틀렸을 때 보여 줄 문구.
     *
     * <p><b>일부러 뭉뚱그린다.</b> "그런 계정이 없습니다"와 "비밀번호가 틀렸습니다"를
     * 구분해 주면, 주소 목록을 넣어 가며 <b>어떤 주소가 가입돼 있는지</b>를 알아내는 도구가
     * 된다(user enumeration). 실패 횟수나 잠금까지 남은 횟수도 같은 이유로 싣지 않는다 —
     * 임계값을 알면 공격자는 그 직전까지만 시도한다.
     *
     * <p>대신 <b>모두에게 똑같이</b> 보이는 안내를 덧붙인다. 소셜로 가입한 사람이 비밀번호
     * 로그인을 반복하는 것이 흔한 막다른 길인데, 이 문장은 계정이 있든 없든 똑같이 나가므로
     * 아무것도 흘리지 않으면서 그 사람을 꺼내 준다.
     */
    static final String GENERIC_FAILURE =
            "이메일 또는 비밀번호가 올바르지 않습니다. "
                    + "소셜 계정으로 가입하셨다면 아래 소셜 로그인을 이용해 주세요.";

    /**
     * 실패 사유를 <b>사용자가 무엇을 해야 하는지</b>로 바꾼다.
     *
     * <p>🐛 이 메서드는 그동안 <b>한 번도 불리지 않았다</b>({@link #attemptAuthentication}
     * 주석). 사용자는 어떤 이유로 실패하든 "Login Failed." 만 봤다.
     *
     * <p>사유별로 나누는 기준은 <b>다음 행동이 다른가</b>이다:
     *
     * <ul>
     *   <li><b>잠금</b> — 기다려야 한다. 비밀번호를 다시 치는 것은 소용이 없고, 계속 치면
     *       잠금만 길어진다고 오해한다. 남은 시간을 알려 준다.</li>
     *   <li><b>메일 미인증</b> — 메일함을 봐야 한다. 여기에 "비밀번호가 틀렸다"를 주면
     *       사용자는 맞는 비밀번호를 계속 다시 치다가 결국 잠긴다.</li>
     *   <li><b>요청 오류</b> — 입력이 비었다. "비밀번호가 틀렸다"로 보이면 맞는 비밀번호를
     *       의심하게 된다.</li>
     *   <li><b>일시 장애</b> — 내 잘못이 아니다. 자격 증명을 의심하며 반복하지 않도록
     *       구분해 준다(DB·Redis 가 잠깐 안 뜨는 배포 중에 실제로 일어난다).</li>
     * </ul>
     *
     * <p>나머지는 전부 {@link #GENERIC_FAILURE} 다.
     */
    static Failure describe(AuthenticationException failed) {
        if (failed instanceof BadRequestAuthenticationException) {
            return new Failure(HttpStatus.BAD_REQUEST.value(), failed.getMessage(), null);
        }
        if (failed instanceof org.springframework.security.authentication.LockedException) {
            // 남은 시간이 담긴 문구를 LoginAttemptService 가 만들어 준다.
            return new Failure(HttpStatus.UNAUTHORIZED.value(), failed.getMessage(), null);
        }
        if (failed instanceof org.springframework.security.authentication.DisabledException) {
            // 이 예외는 **비밀번호가 맞은 뒤에만** 나온다(CustomAuthenticationProvider).
            // 그래서 이 문구를 보는 사람은 본인이고, 가입 여부가 새지 않는다.
            //
            // 🔑 여기만 code 를 붙인다. 화면이 "확인 메일 재발송" 링크를 띄워야 하는 유일한
            //    경우인데, 그 판단을 한국어 문구 비교로 하면 문구를 다듬는 순간 조용히
            //    깨진다 — 사용자는 재발송 경로가 사라진 것을 알 방법이 없다.
            return new Failure(HttpStatus.UNAUTHORIZED.value(), failed.getMessage(),
                    EMAIL_NOT_VERIFIED_CODE);
        }
        if (failed instanceof org.springframework.security.authentication.AccountExpiredException
                || failed instanceof org.springframework.security.authentication.CredentialsExpiredException) {
            // 관리자가 만료한 계정 — 이것도 비밀번호가 맞은 뒤에만 나온다(CustomAuthenticationProvider).
            return new Failure(HttpStatus.UNAUTHORIZED.value(), failed.getMessage(), null);
        }
        if (failed instanceof org.springframework.security.authentication.AuthenticationServiceException) {
            // 인증 절차 자체가 실패했다(DB·Redis 등). 사용자의 자격 증명 문제가 아니다.
            return new Failure(HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "일시적인 오류로 로그인하지 못했습니다. 잠시 후 다시 시도해 주세요.", null);
        }
        return new Failure(HttpStatus.UNAUTHORIZED.value(), GENERIC_FAILURE, null);
    }

    /**
     * 메일 인증이 안 된 계정임을 화면에 알리는 코드.
     *
     * <p>⚠️ 이 코드는 <b>비밀번호가 맞은 뒤에만</b> 나간다. 그렇지 않으면 이 값 하나로
     * "이 주소는 가입돼 있고 아직 미인증" 을 알아낼 수 있게 된다.
     */
    static final String EMAIL_NOT_VERIFIED_CODE = "email_not_verified";

    /**
     * 로그인 <b>성공</b>에도 코드를 붙인다.
     *
     * <p>서버는 사용자의 표시 언어를 모른다 — 언어는 브라우저가 고르고 localStorage 에
     * 있다(docs/45). 그래서 문구는 프론트 사전이 고르고, 서버는 무슨 일이 있었는지만
     * 알린다. 실패 경로가 이미 쓰는 방식과 같다.
     *
     * @see com.project.userservice.security.response.LoginResponse
     */
    public static final String LOGIN_SUCCESS_CODE = "login_success";

    /** 다른 곳에서 로그인해 있어 그 세션을 끊었다. */
    public static final String MULTI_LOGIN_CODE = "multi_login";

    /** 응답 상태와 사용자에게 보여 줄 문구, 그리고 화면이 분기할 코드(없으면 null). */
    record Failure(int status, String message, String code) {
    }

    @Override
    protected void unsuccessfulAuthentication(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException failed) {

        Failure failure = describe(failed);

        // 비밀번호가 틀린 시도만 위험 신호로 센다(docs/68 §19) — 잠긴 계정 · 빈 요청은 스터핑의 흔적이 아니다.
        if (failed instanceof org.springframework.security.authentication.BadCredentialsException
                || failed instanceof org.springframework.security.core.userdetails.UsernameNotFoundException) {
            Object email = request.getAttribute(EMAIL_ATTR);
            loginRiskGuard.recordFailure(email == null ? "" : email.toString(), DeviceIdentity.trustedClientIp(request));
        }

        // 로그에는 예외 종류만 남긴다. 메시지에 이메일이 섞여 있을 수 있고(예:
        // UsernameNotFoundException), 실패한 로그인마다 주소가 로그에 쌓이면 그 로그가
        // 곧 가입자 명단이 된다.
        if (failure.status() >= 500) {
            log.error("로그인 처리 실패: {}", failed.getClass().getSimpleName(), failed);
        } else {
            log.info("로그인 거부: {}", failed.getClass().getSimpleName());
        }

        response.setStatus(failure.status());
        try {
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            // Map.of 는 null 값을 허용하지 않는다 — code 가 없는 실패가 대부분이므로
            // 있을 때만 넣는다. (여기서 NPE 가 나면 본문 없는 401 이 되고, 사용자는 다시
            // "이유를 알 수 없는 로그인 실패"를 보게 된다.)
            java.util.Map<String, String> payload = new java.util.LinkedHashMap<>();
            // 필터가 본문을 직접 쓴다 — ClientTextResponseAdvice 를 거치지 않으므로 여기서 요청 언어로(docs/60 §4).
            payload.put("message", com.project.common.i18n.ClientText.localize(failure.message()));
            if (failure.code() != null) {
                payload.put("code", failure.code());
            }
            response.getWriter().write(new ObjectMapper().writeValueAsString(payload));
        } catch (IOException e) {
            // 본문 없이 상태 코드만 나가도 치명적이지는 않다 — 조용히 넘어간다.
            log.warn("로그인 실패 응답을 쓰지 못했습니다: {}", e.getMessage());
        }
    }

}
