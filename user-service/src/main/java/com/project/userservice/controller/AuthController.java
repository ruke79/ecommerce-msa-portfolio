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
    // [JA] 2FA 待機トークン — 第一段階は通過し、コード確認が残っている状態(パスワード・ソーシャル共通)。
    private final com.project.userservice.security.PendingTwoFactor pendingTwoFactor;

    /** 로그인 2단계의 두 번째 수단 — SMS(docs/24 §10). */
    // [JA] ログイン 2 段階目の二つ目の手段 — SMS(docs/24 §10)。
    private final com.project.userservice.security.SmsTwoFactor smsTwoFactor;
    /** 2단계 인증 복구 코드(docs/57 §22). */
    // [JA] 2 段階認証のリカバリーコード(docs/57 §22)。
    private final com.project.userservice.security.recovery.RecoveryCodes recoveryCodes;

    /** 위험 기반 로그인의 메일 확인(docs/68 §19). */
    // [JA] リスクベースログインのメール確認(docs/68 §19)。
    private final com.project.userservice.security.risk.LoginRiskGuard loginRiskGuard;
    private final com.project.userservice.service.DeviceRecognitionService deviceRecognitionService;
    private final com.project.userservice.message.producer.SecurityAlertProducer securityAlertProducer;


    //private final RoleRepository roleRepository;

    private final UserService userService;

    
    private final AuthUtil authUtil;

    
    private final TotpService totpService;

    /** 기획전 쿠폰의 대상 판정에 쓴다 — 가입일은 이 서비스만 갖고 있다. */
    // [JA] 企画展クーポンの対象判定に使う — 登録日はこのサービスだけが持っている。
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
            com.project.userservice.message.producer.SecurityAlertProducer securityAlertProducer,
            com.project.userservice.security.SmsTwoFactor smsTwoFactor,
            com.project.userservice.security.recovery.RecoveryCodes recoveryCodes
            ) {
        this.smsTwoFactor = smsTwoFactor;
        this.recoveryCodes = recoveryCodes;
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
     *
     * <p>[JA] 設定済みのソーシャルログインプロバイダ一覧 — フロントエンドがこれを見てボタンを描く。
     * ボタンをハードコードすると client-id のないプロバイダのボタンが描かれ、押して初めて
     * エラーになる。サーバーが知っているもの(Secret に資格情報があるもの)だけを公開する。
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
     *
     * <p>[JA] 2FA 確認 — 第一段階(パスワードログイン・ソーシャルログイン)が仕込んだ PENDING_2FA(httpOnly)Cookie と TOTP コードを一緒に
     * 検査し、通過してはじめて REFRESH Cookie(セッション)を発行する。
     * public パスである理由: この時点のユーザーはまだ認証されていない(それがこの段階の存在理由だ)。身元は待機
     * トークンが証明する — アクセストークンと<b>別のキー</b>で署名されていて、それ自体ではどこにも入れない
     * ({@link com.project.userservice.security.PendingTwoFactor})。誤ったコードはトークンごとに 5 回、そして<b>ユーザーごとに 15 分で 5 回</b>まで(docs/68 §15)。
     * {@code /public/oauth2/verify-2fa} はソーシャル経路しかなかった頃の名前だ — デプロイ中の古い画面のために残す。
     */
    /** 대기 쿠키(PENDING_2FA) 값 — 없으면 null. */
    // [JA] 待機 Cookie(PENDING_2FA)の値 — なければ null。
    private static String pendingCookie(HttpServletRequest request) {
        String pending = null;
        if (request.getCookies() != null) {
            for (jakarta.servlet.http.Cookie c : request.getCookies()) {
                if (com.project.userservice.security.PendingTwoFactor.COOKIE.equals(c.getName())) {
                    pending = c.getValue();
                }
            }
        }
        return pending;
    }

    /**
     * 로그인 2단계 화면이 보여 줄 수단 — 대기 쿠키의 주인이 켠 것(docs/24 §10). 시도를 세지 않는다. 대기 토큰을 가진 사람(비밀번호 ·
     * 소셜을 방금 통과한 사람)에게만 답한다 — 번호는 싣지 않는다(SMS 를 보낼 때 마스킹해서 준다).
     * [JA] ログイン二段階画面が見せる手段 — 待機 Cookie の持ち主が有効にしたもの。試行は数えない。番号は載せない。
     */
    @GetMapping("/public/2fa/methods")
    public ResponseEntity<?> pendingTwoFactorMethods(HttpServletRequest request) {
        Long userId = pendingTwoFactor.peekUserId(pendingCookie(request)).orElse(null);
        User user = userId == null ? null : userRepository.findById(userId).orElse(null);
        if (user == null || !user.requiresTwoFactor() || !user.canSignIn()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "인증 시간이 지났습니다. 로그인을 다시 시작해 주세요."));
        }
        // recovery — 쓰지 않은 복구 코드가 있는가(개수는 싣지 않는다, docs/57 §22).
        // [JA] recovery — 未使用のリカバリーコードがあるか(個数は載せない、docs/57 §22)。
        return ResponseEntity.ok(Map.of("totp", user.isTwoFactorEnabled(), "sms", user.isSmsTwoFactorEnabled(),
                "recovery", recoveryCodes != null && recoveryCodes.remaining(user.getUserId()) > 0));
    }

    /**
     * 로그인 2단계 — SMS 로 코드를 받는다(docs/24 §10). 등록 번호로만 간다(번호를 받지 않는다). 대기 토큰 하나로 {@code 3} 번까지,
     * 번호 · 계정 · 전체 일일 한도는 notification 이 따로 센다.
     * [JA] ログイン二段階目 — SMS でコードを受ける。登録番号へのみ。待機トークン一つで 3 回まで。
     */
    @PostMapping({"/public/2fa/sms", "/public/oauth2/2fa/sms"})
    public ResponseEntity<?> sendPendingTwoFactorSms(HttpServletRequest request) {
        final String expired = "인증 시간이 지났습니다. 로그인을 다시 시작해 주세요.";
        String pending = pendingCookie(request);
        Long userId = pendingTwoFactor.peekUserId(pending).orElse(null);
        User user = userId == null ? null : userRepository.findById(userId).orElse(null);
        if (user == null || !user.isSmsTwoFactorEnabled() || !user.canSignIn()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", expired));
        }
        if (!pendingTwoFactor.reserveSmsSend(pending)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("message", "인증번호를 너무 많이 요청했습니다. 로그인을 다시 시작해 주세요."));
        }
        return smsSentResponse(smsTwoFactor.send(user));
    }

    private static ResponseEntity<?> smsSentResponse(com.project.userservice.security.SmsTwoFactor.Sent sent) {
        return switch (sent.status()) {
            case SENT -> ResponseEntity.ok(Map.of("maskedPhone", String.valueOf(sent.maskedPhone()),
                    "ttlMinutes", sent.ttlMinutes()));
            case TOO_MANY -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(org.springframework.http.HttpHeaders.RETRY_AFTER, String.valueOf(sent.retryAfterSeconds()))
                    .body(Map.of("message", "잠시 후 다시 요청해 주세요.", "retryAfterSeconds", sent.retryAfterSeconds()));
            case NO_PHONE -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("code", "no_phone", "message", "인증된 휴대폰 번호가 없습니다."));
            case PHONE_CHANGED -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("code", "phone_changed",
                            "message", "휴대폰 번호가 2단계 인증을 켤 때와 다릅니다. 다른 인증 수단을 쓰거나 고객센터에 문의해 주세요."));
            case UNAVAILABLE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", "인증번호를 보내지 못했습니다. 잠시 후 다시 시도해 주세요."));
        };
    }

    @PostMapping({"/public/verify-2fa", "/public/oauth2/verify-2fa"})
    public ResponseEntity<?> verifyPendingTwoFactor(@RequestParam String code,
            @RequestParam(name = "method", defaultValue = "totp") String method,
            HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {

        String pending = pendingCookie(request);
        // 수단 — totp(기본, 옛 화면) · sms(docs/24 §10) · recovery(복구 코드, docs/57 §22). 모르는 값은 TOTP 로 읽지 않는다.
        // [JA] 手段 — totp(既定、旧画面) · sms(docs/24 §10) · recovery(リカバリーコード、docs/57 §22)。未知の値は TOTP として読まない。
        boolean sms = "sms".equals(method);
        boolean recovery = "recovery".equals(method);
        if (!sms && !recovery && !"totp".equals(method)) {
            return ResponseEntity.badRequest().body(Map.of("message", "알 수 없는 인증 수단입니다."));
        }
        // 만료(5분)·위조·부재·시도 초과를 구분해 알려주지 않는다 — 어차피 처음부터 다시 하면 된다.
        // [JA] 期限切れ(5 分)・偽造・欠落・試行超過を区別して知らせない — どのみち最初からやり直せばよい。
        final String expired = "인증 시간이 지났습니다. 로그인을 다시 시작해 주세요.";
        // 🔒 시도는 검증 **전에** 센다 — 동시에 보낸 코드가 모두 "아직 0번" 을 읽고 전부 검증되던 것을 막는다(PendingTwoFactor 주석).
        // [JA] 🔒 試行は検証の**前に**数える — 同時に送ったコードがすべて「まだ 0 回」を読み全部検証されていたのを防ぐ(PendingTwoFactor のコメント)。
        var attempt = pendingTwoFactor.reserveAttempt(pending).orElse(null);
        Long userId = attempt == null ? null : attempt.userId();
        User user = userId == null ? null : userRepository.findById(userId).orElse(null);
        // 코드를 받는 사이(최대 5분) 막힌 계정이면 세션을 주지 않는다 — 🐛 예전에는 계정 상태를 보지 않았다.
        // 그 수단이 켜져 있어야 한다 — SMS 만 켠 계정에 TOTP 로, TOTP 만 켠 계정에 SMS 로 들어오지 못한다.
        // 복구 코드는 어느 수단이든 켜져 있으면 쓸 수 있다(그 수단을 잃었을 때 쓰는 것이다).
        // [JA] コードを受け取る間(最大 5 分)にブロックされたアカウントならセッションを与えない — 🐛 以前はアカウント状態を見ていなかった。
        //      その手段が有効でなければならない — SMS だけ有効なアカウントに TOTP で、TOTP だけ有効なアカウントに SMS で入れない。
        //      リカバリーコードはどの手段でも有効なら使える(その手段を失ったときに使うものだ)。
        boolean methodOn = recovery ? user != null && user.requiresTwoFactor()
                : user != null && (sms ? user.isSmsTwoFactorEnabled() : user.isTwoFactorEnabled());
        if (user == null || !methodOn || !user.canSignIn()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", expired));
        }

        // 사용자별 한도(docs/68 §15) — 대기 토큰의 5번은 비밀번호로 다시 로그인하면 새로 생겼다. 막혔으면 맞는 코드도 받지 않는다.
        // 두 수단이 같은 칸을 쓴다(SmsTwoFactor) — 수단을 바꿔 가며 한도를 두 배로 쓰지 못한다.
        // [JA] ユーザー単位の上限 — 待機トークンの 5 回は再ログインで新しく生まれていた。止まっていれば正しいコードも受けない。
        UserService.TwoFactorChange checked = recovery
                ? recoveryCodes.verifyLogin(user, code)
                : sms
                ? smsTwoFactor.verifyLogin(user, code)
                : (code != null && code.matches("\\d{6}")
                        ? userService.verifyLoginTwoFactor(user.getUserId(), Integer.parseInt(code))
                        : UserService.TwoFactorChange.INVALID_CODE);
        if (checked == UserService.TwoFactorChange.UNAVAILABLE) {
            // notification 에 닿지 못했다 — 통과시키지 않는다. 대기 토큰은 남긴다(다시 시도하거나 다른 수단으로).
            // [JA] notification に届かなかった — 通さない。待機トークンは残す(再試行するか別の手段で)。
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", "인증번호를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요."));
        }
        if (checked == UserService.TwoFactorChange.PHONE_CHANGED || checked == UserService.TwoFactorChange.NO_PHONE) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "phone_changed",
                    "message", "휴대폰 번호가 2단계 인증을 켤 때와 다릅니다. 다른 인증 수단을 쓰거나 고객센터에 문의해 주세요."));
        }
        if (checked == UserService.TwoFactorChange.TOO_MANY_ATTEMPTS) {
            pendingTwoFactor.consume(pending);
            response.addCookie(jwtUtils.createCookie(
                    com.project.userservice.security.PendingTwoFactor.COOKIE, null, java.time.Duration.ZERO));
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("message", "인증 코드를 너무 많이 틀렸습니다. 잠시 후 다시 로그인해 주세요."));
        }
        if (checked != UserService.TwoFactorChange.OK) {
            // 이번 시도는 위에서 이미 셌다 — 남은 시도가 없으면 다음 요청은 토큰부터 거절된다.
            // [JA] 今回の試行は上で既に数えた — 残り試行がなければ次のリクエストはトークンの段階で拒否される。
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", attempt.moreAfterThis() ? "인증 코드가 올바르지 않습니다." : expired));
        }

        // 통과 — 정상 로그인과 같은 REFRESH 쿠키를 심는다. 대기 토큰은 1회용이다 — 태우고 쿠키도 지운다.
        // [JA] 通過 — 通常ログインと同じ REFRESH Cookie を仕込む。待機トークンは 1 回限り — 消費して Cookie も消す。
        pendingTwoFactor.consume(pending);
        var refreshToken = refreshTokenService.createRefreshToken(user.getUserId());
        response.addCookie(jwtUtils.createCookie(
                com.project.userservice.constants.TokenType.REFRESH.getType(),
                refreshToken.getToken(), jwtUtils.refreshTokenLifetime()));
        // 지우는 쿠키도 발급할 때와 같은 속성(Secure · HttpOnly · SameSite)으로 — 쿠키는 JwtUtils.createCookie 한 곳에서 만든다.
        // [JA] 消す Cookie も発行時と同じ属性(Secure · HttpOnly · SameSite)で — Cookie は JwtUtils.createCookie 一か所で作る。
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
        // [JA] コードを受け取る間にブロックされたアカウント(停止 · 退会)ならセッションを与えない。
        if (user == null || !user.canSignIn()) {
            response.addCookie(clearPending);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", expired));
        }

        // 이제야 기기를 기록한다 — 확인을 통과한 브라우저만 "아는 기기" 가 된다.
        // [JA] ここで初めて端末を記録する — 確認を通過したブラウザだけが「既知の端末」になる。
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
     *
     * <p>[JA] 会員登録。
     * 🐛 <b>重複チェックが {@code 200 OK} を返していた。</b>フロントの {@code useMutation} は 2xx を成功とみなすので
     * {@code onSuccess} が実行され、画面には<b>「認証メールを送りました」</b>トーストが出てログイン画面に移った。
     * 本文に入った「既に存在する」は誰も見なかった。ユーザーは来ないメールを待ち、その間本物のアカウントはそのままだった。
     * 本文は既に事実を述べていたので、この変更で<b>新たに漏れる情報はない</b> — 画面がそれを読めるようになっただけだ。
     * ⚠️ 登録エンドポイントは本質的に<b>アドレスの登録有無を明かす。</b>防げないからではなく(防ぐには「確認メールを送りました」
     * だけを返し既存ユーザーに案内メールを送るフローが必要)その代償が大きいからだ — タイプミスで他人のアドレスを入れた人が
     * なぜ登録できないのか永遠に分からなくなる。代わりに<b>認証済みかどうかは区別しない</b>: 未認証アカウントかどうかを
     * 応答で知らせるとそれが新たな漏洩になる。だから両方の場合に同じ文言を返し、再送の案内を併せて付ける。
     * ログイン失敗の経路は逆だ — そこではパスワードを当てたあとでだけアカウント状態を知らせる({@code CustomAuthenticationProvider})。
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

                // 계정과 확인 메일 행을 한 트랜잭션에 — 메일은 아웃박스 릴레이가 보낸다(docs/93 ③ 2단계).
                // [JA] アカウントと確認メールの行を一つのトランザクションで — メールはアウトボックスのリレーが送る。
                user = userService.registerNewUserAccountWithConfirmationMail(accountDto, request.getLocale());

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
     *
     * <p>[JA] 確認メールの再送。
     * このエンドポイントが<b>なかった。</b>だからメールが迷惑メールフォルダに入ったり、SMTP が一時的に失敗したり、
     * リンクが期限切れになると(以前の既定値は 3 分だった)ユーザーはアカウントを有効化する手段がまったく
     * なかった — 同じメールでの再登録も「既に存在するアカウント」で弾かれる。
     * <b>アカウントの存在有無を知らせない。</b>未登録のアドレスでも、認証済みのアドレスでも、未認証のアドレスでも
     * 応答は同じだ。違う答えを返すとこのエンドポイントが<b>登録有無の確認ツール</b>になってしまう。
     * 実際に送ったかどうかはサーバーログにだけ残る。
     * ゲートウェイの認証前パスのレート制限(毎分 60 回)がこのエンドポイントにもかかるので、
     * 他人のアドレスにメールを繰り返し送ることも併せて制限される。
     */
    @PostMapping("/public/resend-verification")
    public ResponseEntity<?> resendVerificationEmail(@RequestBody Map<String, String> body,
            final HttpServletRequest request) {

        final String email = body == null ? null : com.project.common.text.Nfkc.of(body.get("email")); // 가입 · 로그인과 같은 NFKC ／ [JA] 登録 · ログインと同じ NFKC
        final GenericResponse sameAnswer = new GenericResponse(
                "입력하신 주소로 확인 메일을 보냈습니다. 메일함(스팸함 포함)을 확인해 주세요.");

        if (email == null || email.isBlank()) {
            return ResponseEntity.ok(sameAnswer);
        }

        try {
            User user = userService.findByEmail(email);

            // 없는 계정, 이미 인증된 계정 — 어느 쪽이든 같은 응답을 돌려주고 아무것도 하지 않는다.
            // [JA] 存在しないアカウント、既に認証済みのアカウント — どちらでも同じ応答を返し何もしない。
            if (user == null || user.isEnabled()) {
                log.debug("재발송 요청이지만 보낼 대상이 아니다(미가입 또는 이미 인증됨)");
                return ResponseEntity.ok(sameAnswer);
            }

            // 새 토큰을 발급하고 메일을 다시 보낸다. 리스너가 커밋 이후 비동기로 처리한다.
            // 아웃박스에 적는다 — 보내기 · 재시도는 릴레이(docs/93 ③ 2단계). [JA] アウトボックスに書く — 送信 · 再試行はリレー。
            userService.requestVerificationMail(user, request.getLocale());

            log.info("확인 메일 재발송 요청: userId={}", user.getUserId());

        } catch (RuntimeException e) {
            // 실패해도 응답은 같다 — 오류 메시지로 계정 존재 여부가 새면 안 된다.
            // [JA] 失敗しても応答は同じ — エラーメッセージでアカウントの存在有無が漏れてはならない。
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
     *
     * <p>[JA] id → メール。<b>内部専用</b>(cart-service が呼ぶ)。
     * 🔒 以前はガードがなかった — {@code /api/auth/user/**} は USER ロールしか要求しないので、ログインした誰でも id を
     * 変えながら<b>他人のメールを収集できた。</b>すぐ下の {@code /user/id} と同じ規則で塞ぐ: ゲートウェイを
     * 経たリクエスト(身元ヘッダーあり)は管理者だけ。塞ぐときは存在しないユーザーと同じ 404 — 403 だと「その id は存在する」と知らせてしまう。
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
    // [JA] 一度に問い合わせられる id の数 — 管理者一覧の 1 ページ分で十分。
    static final int MAX_EMAIL_LOOKUP = 100;

    /**
     * id 여럿 → 이메일. <b>내부 전용</b> — order-service 관리자 주문 목록이 부른다(docs/48 §6-①).
     *
     * <p>order-service 는 예전에 고객 사본(orders.customer)에서 이메일을 읽었다. 사본을 없애면서 표시에 필요한
     * 이메일만 여기서 받는다 — 페이지 하나에 한 번. 가드는 {@link #getUserEmailById} 와 같다. 없는 id 는 빠진다.
     *
     * <p>[JA] 複数の id → メール。<b>内部専用</b> — order-service の管理者注文一覧が呼ぶ(docs/48 §6-①)。
     * order-service は以前、顧客の写し(orders.customer)からメールを読んでいた。写しをなくすにあたり表示に必要な
     * メールだけをここで受け取る — 1 ページに 1 回。ガードは {@link #getUserEmailById} と同じ。存在しない id は抜ける。
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
    // [JA] id → プロフィール。<b>内部専用</b> — ガードは {@link #getUserEmailById} と同じ(以前はなかった)。
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
     *
     * <p>[JA] メールでユーザーを探す — <b>サービス間の内部呼び出し専用</b>(Feign)。
     * 🔒 以前はゲートウェイ経由で<b>ログインした誰でも</b>呼べた。メールさえ入れればその人の userId・名前・ニックネーム・
     * プロフィール画像が出たので、メール一覧さえあれば登録有無の確認(アカウント列挙)と個人情報収集がそのまま可能だった。
     * <b>判定方法。</b>ゲートウェイを経たリクエストにはゲートウェイが {@code User-Role} ヘッダーを付ける(そしてクライアントが
     * 送った同名ヘッダーは消す)。内部 Feign 呼び出しはゲートウェイを経ないのでこのヘッダーがない。したがって
     * <b>ヘッダーがある = 外部から来たリクエスト</b>であり、その場合は管理者だけを許可する。
     * ⚠️ この判定は<b>user-service に外部から直接届かない</b>という前提の上にある。k8s では ClusterIP なので基本的に
     * 成り立つが(このプロジェクトのすべての Feign トラフィックが既に同じ前提を使う)、サービスポートを外部に公開した瞬間に崩れる。
     * 根本的には内部呼び出し用の別ポートかサービス間認証(mTLS)に移るべきだ — docs/27 に後続課題として残した。
     */
    @GetMapping("/user/id")
    public ResponseEntity<?> findUserByEmail(@RequestParam("email") String email,
            @RequestHeader(value = "User-Role", required = false) String callerRole,
            @RequestHeader(value = "User-Email", required = false) String callerEmail,
            @RequestHeader(value = "User-Id", required = false) String callerId) {

        // User-Role **하나만** 보면 role 클레임이 없는 토큰이 헤더 없이 도착해 "내부 호출" 로 통과한다.
        // 세 헤더 중 하나라도 있으면 외부다(InternalCallGuard).
        // [JA] User-Role **一つだけ**を見ると、role クレームのないトークンがヘッダーなしで届き「内部呼び出し」として通ってしまう。
        //      三つのヘッダーのうち一つでもあれば外部である(InternalCallGuard)。
        if (!com.project.common.security.InternalCallGuard.allowed(callerRole, callerEmail, callerId)) {
            log.warn("외부에서 사용자 조회 시도를 차단");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(StatusMessages.USER_NOT_FOUND);
        }

        // 이메일은 개인정보다. 전체를 로그에 남기지 않는다.
        // [JA] メールは個人情報だ。全体をログに残さない。
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
     *
     * <p>[JA] このユーザーが今属している顧客セグメント。
     * 企画展クーポンが「新規登録者のみ」のような条件をかけるとき coupon-service が問い合わせる。
     * 判定はここで行う — 「新規とは何日か」は当方のポリシーで、登録日はこのサービスだけが
     * 持っている({@link com.project.userservice.service.SegmentService} のコメント)。
     * 🔒 認可はすぐ上の {@code /user/id} と同じ規則だ。ゲートウェイを経たリクエストには {@code User-Role} ヘッダーが
     * 付くので<b>ヘッダーがある = 外部から来たリクエスト</b>であり、そのときは管理者だけを許可する。これがないと
     * id だけで他人が新規か(= 登録時期の区間)を知ることができ、登録有無の確認にもそのまま使われる。
     * 存在しないユーザーは<b>空のリスト</b>である。404 で区別するとそれ自体が登録有無を知らせる信号になる。
     */
    @GetMapping("/user/segments")
    public ResponseEntity<?> findUserSegments(
            // 사용자 id 로 묻는다(docs/48 §6-⑥ — coupon 의 신원도 id 다). 예전에는 이메일이었다.
            // [JA] ユーザー id で問い合わせる(docs/48 §6-⑥ — coupon の身元も id だ)。以前はメールだった。
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
    // [JA] ユーザー名 → ユーザー(メール含む)。<b>内部専用</b> — ガードは {@link #getUserEmailById} と同じ(以前はなかった)。
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
     *
     * <p>[JA] パスワード再設定 — 再設定リンクをメールで送る。
     * 🐛 <b>{@code @RequestParam} だった。</b>それはクエリ文字列・フォームしか読まないのに、画面は
     * {@code application/json} の本文で送る({@code ForgotPage.jsx})。だからリクエストは常に
     * <b>400 "Required request parameter 'email' is not present"</b> だった — パスワード再設定の最初の段階から動いたことがない。
     * 🔒 <b>どんな場合も同じ 200 を返す。</b>以前は未登録アドレスだとサービスが例外を投げ、ここで 500 に変わった。
     * 登録済みアドレスは 200 — <b>応答コードがそのまま登録有無だった。</b>同じリポジトリの {@code resend-verification} は
     * この漏洩を防ぐためすべての場合に同じ応答を返すのに、肝心のパスワード再設定が穴になっていた。
     * {@code e.printStackTrace()} もなくした — スタックはロガーに送るべきだ。
     */
    @PostMapping("/public/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody Map<String, String> body) {

        final MessageResponse sameAnswer = new MessageResponse(
                "입력하신 주소로 비밀번호 재설정 메일을 보냈습니다. 메일함(스팸함 포함)을 확인해 주세요.");

        final String email = body == null ? null : com.project.common.text.Nfkc.of(body.get("email")); // 가입 · 로그인과 같은 NFKC ／ [JA] 登録 · ログインと同じ NFKC
        if (email == null || email.isBlank()) {
            return ResponseEntity.ok(sameAnswer);
        }

        try {
            userService.generatePasswordResetToken(email);
        } catch (RuntimeException e) {
            // 실패해도 응답은 같다 — 오류 메시지로 계정 존재 여부가 새면 안 된다.
            // ⚠️ 예외 메시지에 주소가 섞일 수 있으므로 클래스 이름만 남긴다.
            // [JA] 失敗しても応答は同じ — エラーメッセージでアカウントの存在有無が漏れてはならない。
            //      ⚠️ 例外メッセージにアドレスが混じりうるのでクラス名だけを残す。
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
     *
     * <p>[JA] 再設定リンクで新しいパスワードを設定する。
     * 🔒 <b>トークンをクエリ文字列ではなく本文で受け取る。</b>このトークンはそれ自体でアカウントを
     * 奪える資格情報だが、クエリ文字列は nginx アクセスログ・プロキシ・ブラウザ履歴にそのまま残る。
     * リンク(GET)で来るのは仕方ないが、それを実際に使うこのリクエストまで同じ場所に置く理由はない。
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
            // [JA] ここのメッセージはトークンの状態(無効・使用済み・期限切れ)を説明する。トークンを既に手にしている
            //      人に与える情報なので、アカウントの存在有無は漏れない。
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(ClientMessage.of(e)));
        }
    }

    /** 2FA 켜기 전 "최근 로그인" 창 — 패스키 등록 · 탈퇴와 같은 생각(docs/68 §16 · §20). */
    // [JA] 2FA 有効化前の「最近のログイン」ウィンドウ — パスキー登録 · 退会と同じ考え(docs/68 §16 · §20)。
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
        // [JA] 本文は他の 2FA 応答と同じく文字列 — 画面は code ではなく 403 で再ログインを案内する。
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
        // [JA] 🔒 既に有効なら秘密を変えない — セッションだけを持つ者が自分のアプリに差し替えられないように(無効にして再び有効にする、docs/68 §14)。
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
        UserService.TwoFactorChange result = userService.disable2FA(userId, code);
        forgetRecoveryCodesIfOff(result, userId);
        return twoFactorResponse(result, "2FA disabled");
    }

    /** 마지막 수단을 껐으면 복구 코드도 지운다 — 다시 켰을 때 옛 코드가 되살아나지 않게(docs/57 §22). */
    // [JA] 最後の手段を無効にしたらリカバリーコードも消す — 再び有効にしたとき古いコードが生き返らないように(docs/57 §22)。
    private void forgetRecoveryCodesIfOff(UserService.TwoFactorChange result, Long userId) {
        if (result == UserService.TwoFactorChange.OK && recoveryCodes != null) {
            userRepository.findById(userId).ifPresent(recoveryCodes::forgetIfNoTwoFactor);
        }
    }

    /**
     * 2단계 인증 복구 코드를 새로 만든다(docs/57 §22) — 옛 코드는 모두 무효, 원문은 이 응답에만. 최근 로그인 필요(훔친 세션으로 코드를
     * 받아 가 2단계 인증을 건너뛰지 못하게 — 켜기와 같은 창). 2단계 인증이 하나도 켜져 있지 않으면 409.
     * [JA] リカバリーコードを新しく作る — 古いコードは全て無効、原文はこの応答だけ。直近のログインが必要。2FA がなければ 409。
     */
    @PostMapping("/2fa/recovery-codes")
    public ResponseEntity<?> regenerateRecoveryCodes(
            @org.springframework.web.bind.annotation.CookieValue(name = "REFRESH", required = false) String refresh) {
        if (!recentlyAuthenticated(refresh)) {
            return twoFactorReauthRequired();
        }
        User user = authUtil.loggedInUser();
        if (user == null || !user.requiresTwoFactor()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "two_factor_off",
                    "message", "2단계 인증을 먼저 켜 주세요."));
        }
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("codes", recoveryCodes.regenerate(user)));
    }

    @PostMapping("/verify-2fa")
    public ResponseEntity<String> verify2FA(@RequestParam int code,
            @org.springframework.web.bind.annotation.CookieValue(name = "REFRESH", required = false) String refresh) {
        // 켜기와 확인 사이에 창이 닫혔을 수도 있다 — 실제로 켜지는 이 순간에 다시 본다.
        // [JA] 有効化と確認の間にウィンドウが閉じたかもしれない — 実際に有効になるこの瞬間に再確認する。
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
            case NO_PHONE -> ResponseEntity.status(HttpStatus.CONFLICT).body("no_phone");
            case PHONE_CHANGED -> ResponseEntity.status(HttpStatus.CONFLICT).body("phone_changed");
            case UNAVAILABLE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("unavailable");
        };
    }

    // ── 2단계 인증 — SMS 수단 켜기 · 끄기(docs/24 §10) ─────────────────────────────────────────────
    // [JA] ── 2 段階認証 — SMS 手段の有効化 · 無効化(docs/24 §10) ──

    /**
     * SMS 코드를 받는다 — 꺼져 있으면 켜기 위해(지금 등록된 번호로, 최근 로그인 필요), 켜져 있으면 끄기 확인용(켤 때의 번호로).
     * [JA] SMS コードを受ける — 無効なら有効化のため(今の登録番号、直近ログイン必要)、有効なら無効化の確認用(有効化時の番号)。
     */
    @PostMapping("/sms-2fa/code")
    public ResponseEntity<?> sendSmsTwoFactorCode(
            @org.springframework.web.bind.annotation.CookieValue(name = "REFRESH", required = false) String refresh) {
        User user = authUtil.loggedInUser();
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (user.isSmsTwoFactorEnabled()) {
            return smsSentResponse(smsTwoFactor.send(user));
        }
        // 켜기 — 훔친 세션으로 자기 번호를 등록해 2단계 인증을 가져가지 못하게 TOTP 켜기와 같은 "최근 로그인" 창(docs/68 §14).
        // [JA] 有効化 — 盗んだセッションで自分の番号を登録して 2 段階認証を奪えないよう、TOTP 有効化と同じ「最近のログイン」ウィンドウ(docs/68 §14)。
        if (!recentlyAuthenticated(refresh)) {
            return twoFactorReauthRequired();
        }
        return smsSentResponse(smsTwoFactor.sendForEnrollment(user));
    }

    /** SMS 수단을 켠다 — 지금 등록 번호로 받은 코드 + 최근 로그인. 켜지면 주인에게 알린다. */
    // [JA] SMS 手段を有効にする — 今の登録番号で受けたコード + 直近のログイン。
    @PostMapping("/sms-2fa/enable")
    public ResponseEntity<String> enableSmsTwoFactor(@RequestParam String code,
            @org.springframework.web.bind.annotation.CookieValue(name = "REFRESH", required = false) String refresh) {
        if (!recentlyAuthenticated(refresh)) {
            return twoFactorReauthRequired();
        }
        return twoFactorResponse(smsTwoFactor.enable(authUtil.loggedInUserId(), code), "SMS 2FA enabled");
    }

    /** SMS 수단을 끈다 — 켤 때의 번호로 받은 지금 코드가 맞아야 한다(docs/68 §14 와 같은 생각). */
    // [JA] SMS 手段を無効にする — 有効化時の番号で受けた今のコードが必要。
    @PostMapping("/sms-2fa/disable")
    public ResponseEntity<String> disableSmsTwoFactor(@RequestParam String code) {
        Long userId = authUtil.loggedInUserId();
        UserService.TwoFactorChange result = smsTwoFactor.disable(userId, code);
        forgetRecoveryCodesIfOff(result, userId);
        return twoFactorResponse(result, "SMS 2FA disabled");
    }

    @GetMapping("/user/2fa-status")
    public ResponseEntity<?> get2FAStatus() {
        User user = authUtil.loggedInUser();
        if (user != null) {
            // smsEnabled — 두 번째 수단(docs/24 §10). is2faEnabled 는 예전 그대로 TOTP 다(옛 화면이 읽는다).
            // recoveryCodesRemaining — 쓰지 않은 복구 코드 수(docs/57 §22).
            // [JA] smsEnabled — 二つ目の手段(docs/24 §10)。is2faEnabled は以前どおり TOTP である(旧画面が読む)。
            //      recoveryCodesRemaining — 未使用のリカバリーコード数(docs/57 §22)。
            return ResponseEntity.ok().body(Map.of("is2faEnabled", user.isTwoFactorEnabled(),
                    "smsEnabled", user.isSmsTwoFactorEnabled(),
                    "recoveryCodesRemaining", recoveryCodes == null ? 0 : recoveryCodes.remaining(user.getUserId())));
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(StatusMessages.USER_NOT_FOUND);
        }
    }

    // `/public/verify-2fa-login` 은 지웠다(docs/27 §12b). 토큰을 쿼리로 받았고(URL 은 로그에 남는다), 시도 제한 없이
    // 코드가 맞는지만 알려 주는 오라클이었으며, 토큰의 subject(이메일)를 username 으로 조회해 동작한 적도 없다.
    // 화면도 부르지 않았다. 로그인 2단계는 `/public/verify-2fa` 하나다.
    // [JA] `/public/verify-2fa-login` は削除した(docs/27 §12b)。トークンをクエリで受け取り(URL はログに残る)、試行制限なしに
    //      コードが合うかだけを教えるオラクルで、トークンの subject(メール)を username として照会しており動いたこともない。
    //      画面も呼んでいなかった。ログインの 2 段階目は `/public/verify-2fa` 一つである。

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