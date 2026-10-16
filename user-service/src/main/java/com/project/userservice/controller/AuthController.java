package com.project.userservice.controller;

import com.project.common.exception.ClientMessage;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.project.common.constants.StatusMessages;
import com.project.common.dto.SharedUserDto;
import com.project.common.response.GenericResponse;
import com.project.common.response.MessageResponse;
import com.project.userservice.dto.UserProfileDto;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.security.jwt.JwtUtils;

import com.project.userservice.security.request.SignupRequest;

import com.project.userservice.service.TotpService;
import com.project.userservice.service.UserService;
import com.project.userservice.util.AuthUtil;
import com.warrenstrange.googleauth.GoogleAuthenticatorKey;



import jakarta.servlet.http.HttpServletRequest;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    
    private final JwtUtils jwtUtils;

    
    private final  ApplicationEventPublisher eventPublisher;

    
    private final UserRepository userRepository;

    private final com.project.userservice.security.oauth2.repository.CustomClientRegistrationRepository customClientRegistrationRepository;

    private final com.project.userservice.service.RefreshTokenService refreshTokenService;

    /** 2FA 대기 토큰 — 첫 단계는 통과했고 코드 확인이 남은 상태(비밀번호·소셜 공통). */
    private final com.project.userservice.security.PendingTwoFactor pendingTwoFactor;

    /** 위험 기반 로그인의 메일 확인(docs/68 §19). */
    private final com.project.userservice.security.risk.LoginRiskGuard loginRiskGuard;
    private final com.project.userservice.service.DeviceRecognitionService deviceRecognitionService;
    private final com.project.userservice.message.producer.SecurityAlertProducer securityAlertProducer;


    //private final RoleRepository roleRepository;

    private final UserService userService;

    
    private final AuthUtil authUtil;

    
    private final TotpService totpService;

    /** 기획전 쿠폰의 대상 판정에 쓴다 — 가입일은 이 서비스만 갖고 있다. */
    private final com.project.userservice.service.SegmentService segmentService;

            
    @Autowired
    public AuthController(JwtUtils jwtUtils,
            ApplicationEventPublisher eventPublisher, UserRepository userRepository,
            UserService userService, AuthUtil authUtil, TotpService totpService,
            com.project.userservice.security.oauth2.repository.CustomClientRegistrationRepository customClientRegistrationRepository,
            com.project.userservice.service.RefreshTokenService refreshTokenService,
            com.project.userservice.service.SegmentService segmentService,
            com.project.userservice.security.PendingTwoFactor pendingTwoFactor,
            com.project.userservice.security.risk.LoginRiskGuard loginRiskGuard,
            com.project.userservice.service.DeviceRecognitionService deviceRecognitionService,
            com.project.userservice.message.producer.SecurityAlertProducer securityAlertProducer
            ) {
        this.segmentService = segmentService;
        this.pendingTwoFactor = pendingTwoFactor;
        this.loginRiskGuard = loginRiskGuard;
        this.deviceRecognitionService = deviceRecognitionService;
        this.securityAlertProducer = securityAlertProducer;
        this.jwtUtils = jwtUtils;
        this.customClientRegistrationRepository = customClientRegistrationRepository;
        this.refreshTokenService = refreshTokenService;

        this.eventPublisher = eventPublisher;
        this.userRepository = userRepository;
        this.userService = userService;
        this.authUtil = authUtil;
        this.totpService = totpService;        
    }
    
  
    @PostMapping("/public/signup")
    public ResponseEntity<?> registerUser(@Valid @RequestBody SignupRequest signUpRequest) {
       
       
        if (userRepository.existsByUsername(signUpRequest.getUsername())) {
            return ResponseEntity.badRequest().body(new MessageResponse(StatusMessages.USERNAME_IN_USE));
        }

        if (userRepository.existsByEmail(signUpRequest.getEmail())) {
            return ResponseEntity.badRequest().body(new MessageResponse(StatusMessages.EMAIL_IN_USE));
        }

        userService.registerNewUserAccount(signUpRequest);

        return ResponseEntity.ok(new MessageResponse(StatusMessages.USER_REGISTRATION_SUCCESS));
    }

    // Email Vertification
    /**
     * 설정된 소셜 로그인 프로바이더 목록 — 프론트엔드가 이걸 보고 버튼을 그린다.
     *
     * <p>버튼을 하드코딩하면 client-id 가 없는 프로바이더의 버튼이 그려지고, 누르면
     * 그제서야 에러가 난다. 서버가 아는 것(Secret 에 자격증명이 있는 것)만 노출한다.
     */
    @org.springframework.web.bind.annotation.GetMapping("/public/oauth2/providers")
    public ResponseEntity<java.util.List<String>> oauth2Providers() {
        return ResponseEntity.ok(customClientRegistrationRepository.enabledProviders());
    }

    /**
     * 2FA 확인 — 첫 단계(비밀번호 로그인·소셜 로그인)가 심은 PENDING_2FA(httpOnly) 쿠키와 TOTP 코드를 함께
     * 검사하고, 통과해야 REFRESH 쿠키(세션)를 발급한다.
     *
     * <p>public 경로인 이유: 이 시점의 사용자는 아직 인증되지 않았다(그게 이 단계의 존재 이유다). 신원은 대기
     * 토큰이 증명한다 — 액세스 토큰과 <b>다른 키</b>로 서명돼 있어 그 자체로는 어디에도 들어갈 수 없다
     * ({@link com.project.userservice.security.PendingTwoFactor}). 틀린 코드는 토큰당 5번, 그리고 <b>사용자당 15분에 5번</b>까지다(docs/68 §15).
     *
     * <p>{@code /public/oauth2/verify-2fa} 는 소셜 경로만 있던 때의 이름이다 — 배포 중인 옛 화면을 위해 남긴다.
     */
    @PostMapping({"/public/verify-2fa", "/public/oauth2/verify-2fa"})
    public ResponseEntity<?> verifyPendingTwoFactor(@RequestParam int code,
            HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {

        String pending = null;
        if (request.getCookies() != null) {
            for (jakarta.servlet.http.Cookie c : request.getCookies()) {
                if (com.project.userservice.security.PendingTwoFactor.COOKIE.equals(c.getName())) {
                    pending = c.getValue();
                }
            }
        }
        // 만료(5분)·위조·부재·시도 초과를 구분해 알려주지 않는다 — 어차피 처음부터 다시 하면 된다.
        final String expired = "인증 시간이 지났습니다. 로그인을 다시 시작해 주세요.";
        Long userId = pendingTwoFactor.userIdOf(pending).orElse(null);
        User user = userId == null ? null : userRepository.findById(userId).orElse(null);
        // 코드를 받는 사이(최대 5분) 막힌 계정이면 세션을 주지 않는다 — 🐛 예전에는 계정 상태를 보지 않았다.
        if (user == null || !user.isTwoFactorEnabled() || !user.canSignIn()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", expired));
        }

        // 사용자별 한도(docs/68 §15) — 대기 토큰의 5번은 비밀번호로 다시 로그인하면 새로 생겼다. 막혔으면 맞는 코드도 받지 않는다.
        // [JA] ユーザー単位の上限 — 待機トークンの 5 回は再ログインで新しく生まれていた。止まっていれば正しいコードも受けない。
        UserService.TwoFactorChange checked = userService.verifyLoginTwoFactor(user.getUserId(), code);
        if (checked == UserService.TwoFactorChange.TOO_MANY_ATTEMPTS) {
            pendingTwoFactor.consume(pending);
            response.addCookie(jwtUtils.createCookie(
                    com.project.userservice.security.PendingTwoFactor.COOKIE, null, java.time.Duration.ZERO));
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("message", "인증 코드를 너무 많이 틀렸습니다. 잠시 후 다시 로그인해 주세요."));
        }
        if (checked != UserService.TwoFactorChange.OK) {
            boolean more = pendingTwoFactor.recordFailure(pending);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", more ? "인증 코드가 올바르지 않습니다." : expired));
        }

        // 통과 — 정상 로그인과 같은 REFRESH 쿠키를 심는다. 대기 토큰은 1회용이다 — 태우고 쿠키도 지운다.
        pendingTwoFactor.consume(pending);
        var refreshToken = refreshTokenService.createRefreshToken(user.getUserId());
        response.addCookie(jwtUtils.createCookie(
                com.project.userservice.constants.TokenType.REFRESH.getType(),
                refreshToken.getToken(), jwtUtils.refreshTokenLifetime()));
        // 지우는 쿠키도 발급할 때와 같은 속성(Secure · HttpOnly · SameSite)으로 — 쿠키는 JwtUtils.createCookie 한 곳에서 만든다.
        response.addCookie(jwtUtils.createCookie(
                com.project.userservice.security.PendingTwoFactor.COOKIE, null, java.time.Duration.ZERO));

        return ResponseEntity.ok(Map.of("message", "ok"));
    }

    /**
     * 위험 기반 로그인의 메일 확인 — 비밀번호 단계가 심은 PENDING_LOGIN_CHECK(httpOnly) 쿠키와 메일로 받은 6자리 코드(docs/68 §19).
     *
     * <p>통과하면 그때 기기를 기록하고(새 기기 알림 포함) REFRESH 쿠키를 준다 — 2FA 확인과 같은 끝이다. 대기 값은 한 번만 쓰이고,
     * 틀린 코드는 대기 값마다 5번까지다. 만료 · 위조 · 시도 초과는 구분해 알려 주지 않는다 — 어차피 처음부터 다시 하면 된다.
     */
    // [JA] リスクベースログインのメール確認。通過したらそのとき端末を記録し(新しい端末の通知を含む) REFRESH Cookie を渡す。
    @PostMapping("/public/verify-login-check")
    public ResponseEntity<?> verifyLoginCheck(@RequestParam String code,
            HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
        final String expired = "인증 시간이 지났습니다. 로그인을 다시 시작해 주세요.";
        var clearPending = jwtUtils.createCookie(com.project.userservice.security.risk.LoginRiskGuard.COOKIE, null,
                java.time.Duration.ZERO);

        var result = loginRiskGuard.verify(
                com.project.userservice.security.risk.LoginRiskGuard.cookieValue(request).orElse(null), code);
        if (result instanceof com.project.userservice.security.risk.LoginRiskGuard.Wrong wrong) {
            if (!wrong.more()) {
                response.addCookie(clearPending);
            }
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", wrong.more() ? "인증 코드가 올바르지 않습니다." : expired));
        }
        if (!(result instanceof com.project.userservice.security.risk.LoginRiskGuard.Passed passed)) {
            response.addCookie(clearPending);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", expired));
        }

        User user = userRepository.findById(passed.userId()).orElse(null);
        // 코드를 받는 사이 막힌 계정(정지 · 탈퇴)이면 세션을 주지 않는다.
        if (user == null || !user.canSignIn()) {
            response.addCookie(clearPending);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", expired));
        }

        // 이제야 기기를 기록한다 — 확인을 통과한 브라우저만 "아는 기기" 가 된다.
        try {
            var recognition = deviceRecognitionService.recognize(user.getUserId(),
                    com.project.userservice.security.DeviceIdentity.tokenFrom(request),
                    request.getHeader("User-Agent"), com.project.userservice.security.DeviceIdentity.clientIp(request));
            if (recognition.cookieToken() != null) {
                response.addCookie(jwtUtils.createCookie(com.project.userservice.security.DeviceIdentity.COOKIE_NAME,
                        recognition.cookieToken(),
                        java.time.Duration.ofSeconds(com.project.userservice.security.DeviceIdentity.COOKIE_MAX_AGE_SECONDS)));
            }
            if (recognition.shouldAlert()) {
                securityAlertProducer.publishNewDevice(user.getUserId(), user.getEmail(), recognition.device());
            }
        } catch (RuntimeException e) {
            log.error("기기 기록에 실패했습니다 — 로그인은 계속합니다: {}", e.getClass().getSimpleName());
        }

        var refreshToken = refreshTokenService.createRefreshToken(user.getUserId());
        response.addCookie(jwtUtils.createCookie(
                com.project.userservice.constants.TokenType.REFRESH.getType(),
                refreshToken.getToken(), jwtUtils.refreshTokenLifetime()));
        response.addCookie(clearPending);
        log.info("로그인 메일 확인 통과 — userId={}", user.getUserId());
        return ResponseEntity.ok(Map.of("message", "ok"));
    }

    /**
     * 회원가입.
     *
     * <p>🐛 <b>중복 검사가 {@code 200 OK} 를 돌려주고 있었다.</b> 프론트의 {@code useMutation} 은
     * 2xx 를 성공으로 보므로 {@code onSuccess} 가 실행됐고, 화면에는 <b>"인증 메일을 보냈습니다"</b>
     * 토스트가 뜬 뒤 로그인 화면으로 넘어갔다. 본문에 담긴 "이미 존재한다"는 아무도 보지 못했다.
     * 사용자는 오지 않을 메일을 기다렸고, 그 사이 진짜 계정은 그대로 있었다.
     *
     * <p>본문은 이미 사실을 말하고 있었으므로, 이 변경으로 <b>새로 새는 정보는 없다</b> —
     * 화면이 그것을 읽을 수 있게 되었을 뿐이다.
     *
     * <p>⚠️ 가입 엔드포인트는 본질적으로 <b>주소의 가입 여부를 드러낸다.</b> 막을 수 없기
     * 때문이 아니라(막으려면 "확인 메일을 보냈습니다"만 답하고 기존 사용자에게 안내 메일을
     * 보내는 흐름이 필요하다) 그 대가가 크기 때문이다 — 오타로 남의 주소를 넣은 사람이 왜
     * 가입이 안 되는지 영영 알 수 없게 된다. 대신 <b>인증 여부는 구분하지 않는다</b>:
     * 미인증 계정인지 아닌지를 응답으로 알려주면 그것이 새로운 누설이 된다. 그래서 두 경우
     * 모두 같은 문구를 주고, 재발송 안내를 함께 붙인다.
     *
     * <p>로그인 실패 경로는 반대다 — 거기서는 비밀번호를 맞힌 뒤에만 계정 상태를 알려준다
     * ({@code CustomAuthenticationProvider}).
     */
    @PostMapping("/public/register")
    public ResponseEntity<?> registerUserAccount(@Valid @RequestBody SignupRequest accountDto,
            final HttpServletRequest request) throws JsonProcessingException {

            try {

                User user = userService.findByEmail(accountDto.getEmail());

                if (user != null ) {

                    return new ResponseEntity<>(new GenericResponse(StatusMessages.EMAIL_ALREADY_REGISTERED),
                            HttpStatus.CONFLICT);
                }

                user = userService.findByUsername(accountDto.getUsername());

                if (user != null ) {

                    return new ResponseEntity<>(new GenericResponse(StatusMessages.USERNAME_ALREADY_TAKEN),
                            HttpStatus.CONFLICT);
                }

                user = userService.registerNewUserAccount(accountDto);
                //userService.addUserLocation(registered, getClientIP(request));
                eventPublisher.publishEvent(new com.project.userservice.registration.OnRegistrationCompleteEvent(user,
                        request.getLocale(), getAppUrl(request)));

                //userService.registerConfirmed(user);

                return new ResponseEntity<>(new GenericResponse(StatusMessages.USER_REGISTRATION_SUCCESS), HttpStatus.OK);
                }
                catch(RuntimeException e) {
                    // 🐛 예전: 본문이 문자열 하나라 화면(error.response.data.message)이 읽지 못하고 "가입하지 못했습니다" 만 띄웠다 —
                    //    유출 비밀번호(docs/68 §18)처럼 사용자가 고칠 수 있는 이유가 보이지 않았다. 409 와 같은 모양으로.
                    // [JA] 以前は本文が文字列一つで、画面が理由を読めなかった。409 と同じ形にする。
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new GenericResponse(ClientMessage.of(e)));
                }
    }
    

    /**
     * 확인 메일 재발송.
     *
     * <p>이 엔드포인트가 <b>없었다.</b> 그래서 메일이 스팸함으로 가거나, SMTP 가 일시적으로
     * 실패하거나, 링크가 만료되면(예전 기본값은 3분이었다) 사용자는 계정을 활성화할 방법이
     * 전혀 없었다 — 같은 이메일로 재가입도 "이미 존재하는 계정"으로 막힌다.
     *
     * <p><b>계정 존재 여부를 알려주지 않는다.</b> 가입되지 않은 주소든, 이미 인증된 주소든,
     * 미인증 주소든 응답이 같다. 다르게 답하면 이 엔드포인트가 <b>가입 여부 확인 도구</b>가
     * 되어 버린다. 실제 발송 여부는 서버 로그에만 남는다.
     *
     * <p>게이트웨이의 인증 전 경로 속도 제한(분당 60회)이 이 엔드포인트에도 적용되므로,
     * 남의 주소로 메일을 반복 발송하는 것도 함께 제한된다.
     */
    @PostMapping("/public/resend-verification")
    public ResponseEntity<?> resendVerificationEmail(@RequestBody Map<String, String> body,
            final HttpServletRequest request) {

        final String email = body == null ? null : body.get("email");
        final GenericResponse sameAnswer = new GenericResponse(
                "입력하신 주소로 확인 메일을 보냈습니다. 메일함(스팸함 포함)을 확인해 주세요.");

        if (email == null || email.isBlank()) {
            return ResponseEntity.ok(sameAnswer);
        }

        try {
            User user = userService.findByEmail(email);

            // 없는 계정, 이미 인증된 계정 — 어느 쪽이든 같은 응답을 돌려주고 아무것도 하지 않는다.
            if (user == null || user.isEnabled()) {
                log.debug("재발송 요청이지만 보낼 대상이 아니다(미가입 또는 이미 인증됨)");
                return ResponseEntity.ok(sameAnswer);
            }

            // 새 토큰을 발급하고 메일을 다시 보낸다. 리스너가 커밋 이후 비동기로 처리한다.
            eventPublisher.publishEvent(new com.project.userservice.registration.OnRegistrationCompleteEvent(
                    user, request.getLocale(), getAppUrl(request)));

            log.info("확인 메일 재발송 요청: userId={}", user.getUserId());

        } catch (RuntimeException e) {
            // 실패해도 응답은 같다 — 오류 메시지로 계정 존재 여부가 새면 안 된다.
            log.error("확인 메일 재발송 처리 중 오류", e);
        }

        return ResponseEntity.ok(sameAnswer);
    }

    @GetMapping("/user")
    public ResponseEntity<?> getUserDetails(@AuthenticationPrincipal UserDetails userDetails) {
        User user = userService.findByUsername(userDetails.getUsername());

        if (null != user) {

            List<String> roles = userDetails.getAuthorities().stream()
                    .map(item -> item.getAuthority())
                    .collect(Collectors.toList());

            UserProfileDto response = UserProfileDto.builder()
                    .userId(Long.toString(user.getUserId()))
                    .username(user.getUsername())
                    .name(user.getName())
                    .email(user.getEmail())
                    .image(user.getImage())
                    .isAccountNonLocked(user.isAccountNonLocked())
                    .isAccountNonExpired(user.isAccountNonExpired())
                    .isCredentialsNonExpired(user.isCredentialsNonExpired())
                    .isEnabled(user.isEnabled())
                    .credentialsExpiryDate(user.getCredentialsExpiryDate())
                    .accountExpiryDate(user.getAccountExpiryDate())
                    .isTwoFactorEnabled(user.isTwoFactorEnabled())
                    .signUpMethod(user.getSignUpMethod())
                    .defaultPaymentMethod(user.getDefaultPaymentMethod())                    
                    .roles(roles)
                    .createdDate(user.getCreatedAt())
                    .updatedDate(user.getUpdatedAt())
                    .build();

            return ResponseEntity.ok().body(response);
        }
        
        
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(StatusMessages.USER_NOT_FOUND);
        
    }

    /**
     * id → 이메일. <b>내부 전용</b>(cart-service 가 부른다).
     *
     * <p>🔒 예전에는 가드가 없었다 — {@code /api/auth/user/**} 는 USER 역할만 요구하므로, 로그인한 아무나 id 를
     * 바꿔 가며 <b>남의 이메일을 긁어 갈 수 있었다.</b> 바로 아래 {@code /user/id} 와 같은 규칙으로 막는다: 게이트웨이를
     * 거친 요청(신원 헤더 있음)은 관리자만. 막을 때는 없는 사용자와 같은 404 다 — 403 이면 "그 id 는 있다" 를 알려 준다.
     */
    @GetMapping("/user/email/{userId}")
    public ResponseEntity<?> getUserEmailById(@PathVariable Long userId,
            @RequestHeader(value = "User-Role", required = false) String callerRole,
            @RequestHeader(value = "User-Email", required = false) String callerEmail,
            @RequestHeader(value = "User-Id", required = false) String callerId) {
        if (!com.project.common.security.InternalCallGuard.allowed(callerRole, callerEmail, callerId)) {
            log.warn("외부에서 id→이메일 조회 시도를 차단");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(StatusMessages.USER_NOT_FOUND);
        }
        User user = userService.findById(userId);
        if (null != user) {
            return ResponseEntity.ok().body(Map.of("email", user.getEmail()));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(StatusMessages.USER_NOT_FOUND);
    }

    /** 한 번에 물을 수 있는 id 수 — 관리자 목록 한 페이지면 충분하다. */
    static final int MAX_EMAIL_LOOKUP = 100;

    /**
     * id 여럿 → 이메일. <b>내부 전용</b> — order-service 관리자 주문 목록이 부른다(docs/48 §6-①).
     *
     * <p>order-service 는 예전에 고객 사본(orders.customer)에서 이메일을 읽었다. 사본을 없애면서 표시에 필요한
     * 이메일만 여기서 받는다 — 페이지 하나에 한 번. 가드는 {@link #getUserEmailById} 와 같다. 없는 id 는 빠진다.
     */
    @GetMapping("/user/emails")
    public ResponseEntity<?> getUserEmailsByIds(@RequestParam("ids") java.util.List<Long> ids,
            @RequestHeader(value = "User-Role", required = false) String callerRole,
            @RequestHeader(value = "User-Email", required = false) String callerEmail,
            @RequestHeader(value = "User-Id", required = false) String callerId) {
        if (!com.project.common.security.InternalCallGuard.allowed(callerRole, callerEmail, callerId)) {
            log.warn("외부에서 id→이메일 일괄 조회 시도를 차단");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(StatusMessages.USER_NOT_FOUND);
        }
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.ok(Map.of());
        }
        if (ids.size() > MAX_EMAIL_LOOKUP) {
            return ResponseEntity.badRequest().body(Map.of("message", "한 번에 " + MAX_EMAIL_LOOKUP + "개까지 물을 수 있습니다."));
        }
        Map<String, String> emails = new java.util.LinkedHashMap<>();
        for (User user : userRepository.findAllById(ids)) {
            emails.put(String.valueOf(user.getUserId()), user.getEmail());
        }
        return ResponseEntity.ok(emails);
    }

    /** id → 프로필. <b>내부 전용</b> — 가드는 {@link #getUserEmailById} 와 같다(예전에는 없었다). */
    @GetMapping("/user/profile/{userId}")
    public ResponseEntity<?> getUserProfileById(@PathVariable Long userId,
            @RequestHeader(value = "User-Role", required = false) String callerRole,
            @RequestHeader(value = "User-Email", required = false) String callerEmail,
            @RequestHeader(value = "User-Id", required = false) String callerId) {
        if (!com.project.common.security.InternalCallGuard.allowed(callerRole, callerEmail, callerId)) {
            log.warn("외부에서 id→프로필 조회 시도를 차단");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(StatusMessages.USER_NOT_FOUND);
        }
        User user = userService.findById(userId);

        if (null != user) {

            String role = user.getRole().getRoleName().name();

            UserProfileDto response = UserProfileDto.builder()
                    .userId(Long.toString(user.getUserId()))
                    .username(user.getUsername())
                    .name(user.getName())
                    .email(user.getEmail())
                    .image(user.getImage())
                    .isAccountNonLocked(user.isAccountNonLocked())
                    .isAccountNonExpired(user.isAccountNonExpired())
                    .isCredentialsNonExpired(user.isCredentialsNonExpired())
                    .isEnabled(user.isEnabled())
                    .credentialsExpiryDate(user.getCredentialsExpiryDate())
                    .accountExpiryDate(user.getAccountExpiryDate())
                    .isTwoFactorEnabled(user.isTwoFactorEnabled())
                    .signUpMethod(user.getSignUpMethod())
                    .defaultPaymentMethod(user.getDefaultPaymentMethod())                    
                    .roles(List.of(role))
                    .createdDate(user.getCreatedAt())
                    .updatedDate(user.getUpdatedAt())
                    .build();

            return ResponseEntity.ok().body(response);
        }
        
        
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(StatusMessages.USER_NOT_FOUND);
        
    }

    /**
     * 이메일로 사용자를 찾는다 — <b>서비스 간 내부 호출 전용</b>(Feign).
     *
     * <p>🔒 예전에는 게이트웨이를 통해 <b>로그인한 아무나</b> 호출할 수 있었다. 이메일만
     * 넣으면 그 사람의 userId·이름·닉네임·프로필 이미지가 나왔으므로, 이메일 목록만 있으면
     * 가입 여부 확인(계정 열거)과 개인정보 수집이 그대로 가능했다.
     *
     * <p><b>판정 방법.</b> 게이트웨이를 거친 요청에는 게이트웨이가 {@code User-Role} 헤더를
     * 붙인다(그리고 클라이언트가 보낸 동명 헤더는 지운다). 내부 Feign 호출은 게이트웨이를
     * 거치지 않으므로 이 헤더가 없다. 따라서 <b>헤더가 있다 = 외부에서 온 요청</b>이고, 이
     * 경우 관리자만 허용한다.
     *
     * <p>⚠️ 이 판정은 <b>user-service 가 외부에서 직접 닿지 않는다</b>는 전제 위에 있다.
     * k8s 에서는 ClusterIP 라 기본적으로 성립하지만(이 프로젝트의 모든 Feign 트래픽이 이미
     * 같은 전제를 쓴다), 서비스 포트를 외부에 노출하는 순간 무너진다. 근본적으로는 내부
     * 호출용 별도 포트나 서비스 간 인증(mTLS)으로 가야 한다 — docs/27 에 후속 과제로 남겼다.
     */
    @GetMapping("/user/id")
    public ResponseEntity<?> findUserByEmail(@RequestParam("email") String email,
            @RequestHeader(value = "User-Role", required = false) String callerRole,
            @RequestHeader(value = "User-Email", required = false) String callerEmail,
            @RequestHeader(value = "User-Id", required = false) String callerId) {

        // User-Role **하나만** 보면 role 클레임이 없는 토큰이 헤더 없이 도착해 "내부 호출" 로 통과한다.
        // 세 헤더 중 하나라도 있으면 외부다(InternalCallGuard).
        if (!com.project.common.security.InternalCallGuard.allowed(callerRole, callerEmail, callerId)) {
            log.warn("외부에서 사용자 조회 시도를 차단");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(StatusMessages.USER_NOT_FOUND);
        }

        // 이메일은 개인정보다. 전체를 로그에 남기지 않는다.
        log.debug("internal user lookup");

        User user = userService.findByEmail(email);

        if (null != user ) {

            SharedUserDto response = SharedUserDto.builder()
            .userId(user.getUserId())
            .username(user.getUsername())
            .nickname(user.getName())
            .email(email)
            .image(user.getImage())
            .build();

           return  ResponseEntity.ok().body(response);

        }

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(StatusMessages.USER_NOT_FOUND);
    }    

    /**
     * 이 사용자가 지금 속한 고객군.
     *
     * <p>기획전 쿠폰이 "신규 가입자만" 같은 조건을 걸 때 coupon-service 가 물어본다.
     * 판정은 여기서 한다 — "신규가 며칠인가" 는 우리 정책이고 가입일은 이 서비스만
     * 갖고 있다({@link com.project.userservice.service.SegmentService} 주석).
     *
     * <p>🔒 인가는 바로 위 {@code /user/id} 와 같은 규칙이다. 게이트웨이를 거친 요청에는
     * {@code User-Role} 헤더가 붙으므로 <b>헤더가 있다 = 외부에서 온 요청</b>이고, 그때는
     * 관리자만 허용한다. 이게 없으면 id 만으로 남이 신규인지(=가입 시점 구간)를 알아낼
     * 수 있고, 가입 여부 확인에도 그대로 쓰인다.
     *
     * <p>없는 사용자는 <b>빈 목록</b>이다. 404 로 구분해 주면 그 자체가 가입 여부를
     * 알려주는 신호가 된다.
     */
    @GetMapping("/user/segments")
    public ResponseEntity<?> findUserSegments(
            // 사용자 id 로 묻는다(docs/48 §6-⑥ — coupon 의 신원도 id 다). 예전에는 이메일이었다.
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestHeader(value = "User-Role", required = false) String callerRole,
            @RequestHeader(value = "User-Email", required = false) String callerEmail,
            @RequestHeader(value = "User-Id", required = false) String callerId) {

        if (!com.project.common.security.InternalCallGuard.allowed(callerRole, callerEmail, callerId)) {
            log.warn("외부에서 세그먼트 조회 시도를 차단");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(StatusMessages.USER_NOT_FOUND);
        }

        User user = userId == null ? null : userRepository.findById(userId).orElse(null);
        List<String> segments = segmentService.segmentsOf(user).stream()
                .map(Enum::name)
                .toList();

        return ResponseEntity.ok(java.util.Map.of("segments", segments));
    }

    /** 사용자 이름 → 사용자(이메일 포함). <b>내부 전용</b> — 가드는 {@link #getUserEmailById} 와 같다(예전에는 없었다). */
    @GetMapping("/user/name")
    public ResponseEntity<?> findUserByName(@RequestParam("userName") String userName,
            @RequestHeader(value = "User-Role", required = false) String callerRole,
            @RequestHeader(value = "User-Email", required = false) String callerEmail,
            @RequestHeader(value = "User-Id", required = false) String callerId) {
        if (!com.project.common.security.InternalCallGuard.allowed(callerRole, callerEmail, callerId)) {
            log.warn("외부에서 이름→사용자 조회 시도를 차단");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(StatusMessages.USER_NOT_FOUND);
        }

        
        User user = userService.findByUsername(userName);

        if (null != user ) {

            SharedUserDto response = SharedUserDto.builder()
            .userId(user.getUserId())
            .username(user.getUsername())
            .nickname(user.getName())
            .email(user.getEmail())
            .image(user.getImage())
            .build();

           return  ResponseEntity.ok().body(response);

        }

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(StatusMessages.USER_NOT_FOUND);
    }    


    @GetMapping("/username")
    public String currentUserName(@AuthenticationPrincipal UserDetails userDetails) {
        return (userDetails != null) ? userDetails.getUsername() : "";
    }

    /**
     * 비밀번호 찾기 — 재설정 링크를 메일로 보낸다.
     *
     * <p>🐛 <b>{@code @RequestParam} 이었다.</b> 그건 쿼리스트링·폼만 읽는데 화면은
     * {@code application/json} 바디로 보낸다({@code ForgotPage.jsx}). 그래서 요청은
     * 언제나 <b>400 "Required request parameter 'email' is not present"</b> 였다 —
     * 비밀번호 찾기의 첫 단계부터 동작한 적이 없다.
     *
     * <p>🔒 <b>어떤 경우에도 같은 200 을 돌려준다.</b> 예전에는 미가입 주소면 서비스가
     * 예외를 던지고 여기서 500 으로 바뀌었다. 가입된 주소는 200 — <b>응답 코드가 곧 가입
     * 여부였다.</b> 같은 저장소의 {@code resend-verification} 은 이 누설을 막으려고 모든
     * 경우에 같은 응답을 주는데, 정작 비밀번호 찾기가 뚫려 있었다.
     *
     * <p>{@code e.printStackTrace()} 도 없앴다 — 스택은 로거로 가야 한다.
     */
    @PostMapping("/public/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody Map<String, String> body) {

        final MessageResponse sameAnswer = new MessageResponse(
                "입력하신 주소로 비밀번호 재설정 메일을 보냈습니다. 메일함(스팸함 포함)을 확인해 주세요.");

        final String email = body == null ? null : body.get("email");
        if (email == null || email.isBlank()) {
            return ResponseEntity.ok(sameAnswer);
        }

        try {
            userService.generatePasswordResetToken(email);
        } catch (RuntimeException e) {
            // 실패해도 응답은 같다 — 오류 메시지로 계정 존재 여부가 새면 안 된다.
            // ⚠️ 예외 메시지에 주소가 섞일 수 있으므로 클래스 이름만 남긴다.
            log.error("비밀번호 재설정 메일 처리 중 오류: {}", e.getClass().getSimpleName());
        }

        return ResponseEntity.ok(sameAnswer);
    }

    /**
     * 재설정 링크로 새 비밀번호를 설정한다.
     *
     * <p>🔒 <b>토큰을 쿼리스트링이 아니라 바디로 받는다.</b> 이 토큰은 그 자체로 계정을
     * 가져갈 수 있는 자격증명인데, 쿼리스트링은 nginx 액세스 로그·프록시·브라우저 히스토리에
     * 그대로 남는다. 링크(GET)로 오는 것은 어쩔 수 없지만, 그것을 실제로 쓰는 이 요청까지
     * 같은 자리에 둘 이유는 없다.
     */
    @PostMapping("/public/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, String> body) {

        final String token = body == null ? null : body.get("token");
        final String newPassword = body == null ? null : body.get("newPassword");

        if (token == null || token.isBlank() || newPassword == null || newPassword.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new MessageResponse("링크와 새 비밀번호가 모두 필요합니다."));
        }

        try {
            userService.resetPassword(token, newPassword);
            return ResponseEntity.ok(new MessageResponse("비밀번호가 변경되었습니다. 새 비밀번호로 로그인해 주세요."));
        } catch (RuntimeException e) {
            // 여기 메시지는 토큰 상태(무효·사용됨·만료)를 설명한다. 토큰을 이미 손에 쥔
            // 사람에게 주는 정보라 계정 존재 여부가 새지 않는다.
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(ClientMessage.of(e)));
        }
    }

    /** 2FA 켜기 전 "최근 로그인" 창 — 패스키 등록 · 탈퇴와 같은 생각(docs/68 §16 · §20). */
    @org.springframework.beans.factory.annotation.Value("${auth.two-factor.reauth-window:PT10M}")
    private java.time.Duration twoFactorReauthWindow = java.time.Duration.ofMinutes(10);

    /**
     * 🔒 이 브라우저의 로그인이 방금(창 안에) 시작됐는가 — 비밀번호 · 소셜 · 2FA 를 다시 거쳤다는 뜻이다.
     *
     * <p>🐛 예전: 2FA 켜기는 세션만 있으면 됐다. 훔친 세션(공용 PC · 탈취된 쿠키)으로 공격자가 <b>자기</b> 인증 앱을 등록하면, 주인은 다음
     * 로그인부터 코드를 몰라 들어오지 못한다(비밀번호 재설정은 2FA 를 풀지 않고 복구 코드도 없다). 패스키 등록은 이미 이것을 요구했다.
     */
    // [JA] このブラウザのログインが直前(窓の中)に始まったか。以前は 2FA の有効化がセッションだけでできた — 盗んだセッションで攻撃者が
    //      自分の認証アプリを登録すると、持ち主は次のログインから入れなくなった。
    private boolean recentlyAuthenticated(String refresh) {
        User user = authUtil.loggedInUser();
        if (user == null) {
            return false;
        }
        java.util.OptionalLong authTime = refreshTokenService.authTimeOf(refresh, user);
        long now = java.time.Instant.now().getEpochSecond();
        return authTime.isPresent() && now - authTime.getAsLong() <= twoFactorReauthWindow.toSeconds();
    }

    private ResponseEntity<String> twoFactorReauthRequired() {
        // 본문은 다른 2FA 응답처럼 문자열 — 화면은 code 대신 403 으로 다시 로그인을 안내한다.
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("reauth_required");
    }

    // 2FA Authentication
    @PostMapping("/enable-2fa")
    public ResponseEntity<String> enable2FA(
            @org.springframework.web.bind.annotation.CookieValue(name = "REFRESH", required = false) String refresh) {
        if (!recentlyAuthenticated(refresh)) {
            return twoFactorReauthRequired();
        }
        Long userId = authUtil.loggedInUserId();
        // 🔒 이미 켜져 있으면 비밀을 바꾸지 않는다 — 세션만 가진 사람이 자기 앱으로 바꿔 끼우지 못하게(끄고 다시 켠다, docs/68 §14).
        if (userService.start2FASetup(userId) == UserService.TwoFactorChange.ALREADY_ENABLED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("2FA already enabled");
        }
        GoogleAuthenticatorKey secret = userService.generate2FASecret(userId);
        String qrCodeUrl = totpService.getQrCodeUrl(secret,
                userService.getUserProfileById(userId).getUsername());
        return ResponseEntity.ok(qrCodeUrl);
    }

    /**
     * 🔒 끄려면 지금 코드가 맞아야 한다(docs/68 §14). 🐛 예전에는 세션만 있으면 아무것도 묻지 않고 껐다 — 공용 PC 에 남은 세션 하나로
     * 2FA 를 끄고 계정을 가져갈 수 있었다. 틀린 시도는 세어 막고, 바뀌면 주인에게 알린다.
     */
    // [JA] 無効化には現在のコードが必要。以前はセッションだけで何も聞かずに無効化していた。
    @PostMapping("/disable-2fa")
    public ResponseEntity<String> disable2FA(@RequestParam int code) {
        Long userId = authUtil.loggedInUserId();
        return twoFactorResponse(userService.disable2FA(userId, code), "2FA disabled");
    }

    @PostMapping("/verify-2fa")
    public ResponseEntity<String> verify2FA(@RequestParam int code,
            @org.springframework.web.bind.annotation.CookieValue(name = "REFRESH", required = false) String refresh) {
        // 켜기와 확인 사이에 창이 닫혔을 수도 있다 — 실제로 켜지는 이 순간에 다시 본다.
        if (!recentlyAuthenticated(refresh)) {
            return twoFactorReauthRequired();
        }
        Long userId = authUtil.loggedInUserId();
        return twoFactorResponse(userService.confirm2FASetup(userId, code), "2FA Verified");
    }

    private static ResponseEntity<String> twoFactorResponse(UserService.TwoFactorChange result, String ok) {
        return switch (result) {
            case OK -> ResponseEntity.ok(ok);
            case INVALID_CODE -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid 2FA Code");
            case TOO_MANY_ATTEMPTS -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body("Too many attempts");
            case ALREADY_ENABLED -> ResponseEntity.status(HttpStatus.CONFLICT).body("2FA already enabled");
            case NOT_ENABLED -> ResponseEntity.status(HttpStatus.CONFLICT).body("2FA is not enabled");
        };
    }

    @GetMapping("/user/2fa-status")
    public ResponseEntity<?> get2FAStatus() {
        User user = authUtil.loggedInUser();
        if (user != null) {
            return ResponseEntity.ok().body(Map.of("is2faEnabled", user.isTwoFactorEnabled()));
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(StatusMessages.USER_NOT_FOUND);
        }
    }

    // `/public/verify-2fa-login` 은 지웠다(docs/27 §12b). 토큰을 쿼리로 받았고(URL 은 로그에 남는다), 시도 제한 없이
    // 코드가 맞는지만 알려 주는 오라클이었으며, 토큰의 subject(이메일)를 username 으로 조회해 동작한 적도 없다.
    // 화면도 부르지 않았다. 로그인 2단계는 `/public/verify-2fa` 하나다.

    private String getAppUrl(HttpServletRequest request) {
        String verifyPath = request.getScheme() +"://" + request.getServerName() + ":" + request.getServerPort() + request.getContextPath();
        log.info(verifyPath);
        return verifyPath;
    }

    private String getClientIP(HttpServletRequest request) {
        final String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null || xfHeader.isEmpty() || !xfHeader.contains(request.getRemoteAddr())) {
            return request.getRemoteAddr();
        }
        return xfHeader.split(",")[0];
    }

   


    

}