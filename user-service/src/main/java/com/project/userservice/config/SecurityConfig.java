package com.project.userservice.config;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.security.config.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.SessionManagementConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import com.project.common.message.dto.request.UserCreatedRequest;
import com.project.userservice.constants.AppRole;
import com.project.userservice.handler.OAuth2FailureHandler;
import com.project.userservice.handler.OAuth2SuccessHandler;
import com.project.userservice.message.producer.UserCreatedProducer;
import com.project.userservice.model.Role;
import com.project.userservice.model.User;
import com.project.userservice.model.redis.RefreshToken;
import com.project.userservice.repository.jpa.RoleRepository;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.security.AuthLoginFilter;
import com.project.userservice.security.AuthLogoutFilter;
import com.project.userservice.security.CustomAuthenticationProvider;
import com.project.userservice.security.jwt.JwtAuthEntryPoint;
import com.project.userservice.security.jwt.JwtAuthFilter;
import com.project.userservice.security.jwt.JwtUtils;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;

import com.project.userservice.security.oauth2.repository.CustomClientRegistrationRepository;
import com.project.userservice.security.oauth2.repository.RedisOAuth2AuthorizationRequestRepository;
import com.project.userservice.security.service.CustomOAuth2UserService;
import com.project.userservice.security.service.UserDetailsServiceImpl;
import com.project.userservice.service.NotificationService;
import com.project.userservice.service.RefreshTokenService;
import com.project.userservice.service.UserService;

import lombok.RequiredArgsConstructor;

import static org.springframework.security.config.Customizer.withDefaults;

import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.CorsUtils;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true, jsr250Enabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

        private final CorsConfigurationSource corsConfigurationSource;

        private final JwtAuthEntryPoint unauthorizedHandler;

        private final CustomAuthenticationProvider customAuthenticationProvider;

        private final JwtUtils jwtUtils;

        private final RefreshTokenService refreshTokenService;

        private final NotificationService notificationService;

        /** 새 기기 로그인 감지 — SECURITY_ALERT 를 만들어 내는 판단(docs/44 #6). */
        // [JA] 新しい端末からのログイン検知 — SECURITY_ALERT を生成するかどうかの判断(docs/44 #6)。
        private final com.project.userservice.service.DeviceRecognitionService deviceRecognitionService;

        private final com.project.userservice.message.producer.SecurityAlertProducer securityAlertProducer;

        /** 다중 로그인 판정의 근거(Redis). 예전에는 정적 싱글턴 + 인메모리 맵이었다. */
        // [JA] 多重ログイン判定の根拠(Redis)。以前は静的シングルトン + インメモリマップだった。
        private final com.project.userservice.security.RedisSessionManager redisSessionManager;

        private final com.project.userservice.security.PendingTwoFactor pendingTwoFactor;

        private final CustomOAuth2UserService customOAuth2UserService;
        private final com.project.userservice.security.service.CustomOidcUserService customOidcUserService;
        private final CustomClientRegistrationRepository customClientRegistrationRepository;
        private final OAuth2SuccessHandler oAuth2SuccessHandler;
        private final OAuth2FailureHandler oAuth2FailureHandler;

        private final UserCreatedProducer userCreatedProducer;

        private final RedisOAuth2AuthorizationRequestRepository authorizationRequestRepository;

        @Bean
        public JwtAuthFilter authenticationJwtTokenFilter() {
                return new JwtAuthFilter();
        }

        /**
         * PKCE(RFC 7636)를 켠 인가 요청 리졸버.
         *
         * <p>스프링의 기본 리졸버는 PKCE 를 <b>공개 클라이언트에만</b> 자동 적용한다 —
         * {@code DefaultOAuth2AuthorizationRequestResolver} 가
         * {@code ClientAuthenticationMethod.NONE} 일 때만 PKCE 를 붙인다. 우리 등록은 셋 다
         * 클라이언트 시크릿을 쓰는 기밀 클라이언트라(CommonOAuth2Provider 가 구글·깃허브는
         * CLIENT_SECRET_BASIC, 페이스북은 CLIENT_SECRET_POST 로 지정한다) 그 분기에
         * 걸리지 않았고, 따라서 <b>PKCE 가 전혀 적용되지 않고 있었다.</b>
         *
         * <p>기밀 클라이언트라 PKCE 없이도 시크릿과 {@code state} 로 방어가 되지만,
         * OAuth 2.1 과 현행 Security BCP 는 기밀 클라이언트에도 PKCE 를 권고한다 —
         * <b>인가 코드 주입</b>(빼돌린 코드를 공격자가 자기 세션에 밀어 넣는 공격)이
         * 한 겹 더 막히고, 리다이렉트 URI 설정 실수나 프로바이더 쪽 오픈 리다이렉트로
         * 코드가 새는 경우의 피해가 줄어든다. 구글·깃허브·페이스북 모두 지원한다.
         *
         * <p>{@code withPkce()} 는 {@code code_verifier} 를 인가 요청의 <b>attributes</b> 에,
         * {@code code_challenge}/{@code code_challenge_method=S256} 을
         * <b>additionalParameters</b> 에 넣는다. 앞의 것이 서버에 남아야 하는 비밀이고,
         * 그래서 저장소가 Redis 여야 한다(쿠키에 담으면 브라우저로 나간다).
         *
         * <p>[JA] PKCE(RFC 7636)を有効にした認可リクエストリゾルバ。
         *
         * <p>Spring のデフォルトリゾルバは PKCE を<b>パブリッククライアントにのみ</b>自動適用する —
         * {@code DefaultOAuth2AuthorizationRequestResolver} は
         * {@code ClientAuthenticationMethod.NONE} のときにだけ PKCE を付ける。こちらの登録は三つとも
         * クライアントシークレットを使うコンフィデンシャルクライアントなので(CommonOAuth2Provider が Google・GitHub は
         * CLIENT_SECRET_BASIC、Facebook は CLIENT_SECRET_POST と指定している)その分岐に
         * 該当せず、結果として<b>PKCE がまったく適用されていなかった。</b>
         *
         * <p>コンフィデンシャルクライアントなので PKCE が無くてもシークレットと {@code state} で防御はできるが、
         * OAuth 2.1 と現行の Security BCP はコンフィデンシャルクライアントにも PKCE を推奨している —
         * <b>認可コード注入</b>(盗んだコードを攻撃者が自分のセッションに押し込む攻撃)を
         * もう一段防げ、リダイレクト URI の設定ミスやプロバイダ側のオープンリダイレクトで
         * コードが漏れた場合の被害も小さくなる。Google・GitHub・Facebook いずれもサポートしている。
         *
         * <p>{@code withPkce()} は {@code code_verifier} を認可リクエストの <b>attributes</b> に、
         * {@code code_challenge}/{@code code_challenge_method=S256} を
         * <b>additionalParameters</b> に入れる。前者はサーバーに残すべき秘密であり、
         * だからこそ保存先は Redis でなければならない(Cookie に入れるとブラウザに出ていく)。
         */
        @Bean
        public OAuth2AuthorizationRequestResolver pkceAuthorizationRequestResolver() {
                DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
                                customClientRegistrationRepository.clientRegistrationRepository(),
                                OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
                resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
                return resolver;
        }

        /**
         * ID 토큰 검증기 — 서명 알고리즘을 등록마다 고른다.
         *
         * <p>스프링의 기본은 RS256 하나다. 구글은 RS256 이지만 <b>LINE 웹 로그인의 ID 토큰은 ES256</b> 이라, 기본값으로는
         * 서명 검증이 실패해 LINE 로그인이 전부 {@code invalid_id_token} 으로 끝난다. {@code OAuth2LoginConfigurer} 는 이
         * 타입({@code JwtDecoderFactory<ClientRegistration>})의 빈을 찾아 쓴다.
         *
         * <p>[JA] ID トークン検証器 — 署名アルゴリズムを登録ごとに選ぶ。
         *
         * <p>Spring のデフォルトは RS256 のみである。Google は RS256 だが<b>LINE ウェブログインの ID トークンは ES256</b>
         * なので、デフォルトのままでは署名検証に失敗し、LINE ログインがすべて {@code invalid_id_token} で終わる。
         * {@code OAuth2LoginConfigurer} はこの型({@code JwtDecoderFactory<ClientRegistration>})の Bean を探して使う。
         */
        @Bean
        public org.springframework.security.oauth2.jwt.JwtDecoderFactory<org.springframework.security.oauth2.client.registration.ClientRegistration> idTokenDecoderFactory() {
                org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory factory =
                                new org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory();
                factory.setJwsAlgorithmResolver(SecurityConfig::idTokenAlgorithm);
                return factory;
        }

        static org.springframework.security.oauth2.jose.jws.SignatureAlgorithm idTokenAlgorithm(
                        org.springframework.security.oauth2.client.registration.ClientRegistration registration) {
                return "line".equals(registration.getRegistrationId())
                                ? org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.ES256
                                : org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256;
        }

        @Bean
        SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
                // http.csrf(csrf ->
                // csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                // .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                // .ignoringRequestMatchers("/api/user/**")
                // .ignoringRequestMatchers("/api/auth/public/**").ignoringRequestMatchers("/api/admin/**")
                // // .ignoringRequestMatchers("/api/order/**")
                // .ignoringRequestMatchers("/api/product/**")
                // );
                // http.cors(cors -> cors.configurationSource(corsConfigurationSource));
                http.cors(cors -> cors.disable());

                http.formLogin(login -> login.disable());
                http.logout((logout) -> logout.disable());

                http.csrf(AbstractHttpConfigurer::disable);
                http.authorizeHttpRequests((requests) -> requests
                                .requestMatchers(CorsUtils::isPreFlightRequest).permitAll()
                                // 컨테이너 프로브(liveness/readiness)와 Prometheus 스크레이프는 인증 없이 접근해야 한다.
                                // anyRequest().authenticated() 만 있던 시절엔 /actuator/health/readiness 가 401 을 돌려줘
                                // k8s 가 파드를 영원히 NotReady 로 두고 Prometheus 도 user-service 만 못 긁었다(docs/14 B-11).
                                // 게이트웨이는 /user-service/api/**, /sse 만 라우팅하므로 이 경로는 외부로 열리지 않는다.
                                // [JA] コンテナプローブ(liveness/readiness)と Prometheus のスクレイプは認証なしでアクセスできなければならない。
                                //      anyRequest().authenticated() だけだった頃は /actuator/health/readiness が 401 を返し、
                                //      k8s がポッドを永遠に NotReady のままにし、Prometheus も user-service だけ収集できなかった
                                //      (docs/14 B-11)。ゲートウェイは /user-service/api/**, /sse のみをルーティングするので、
                                //      このパスは外部に公開されない。
                                .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                                .requestMatchers(EndpointRequest.to("prometheus", "info")).permitAll()
                                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                                .requestMatchers("/api/product/**").permitAll()
                                .requestMatchers("/api/user/**").hasAnyRole("USER", "ADMIN", "SUPPORT")
                                .requestMatchers("/api/review/**").permitAll()
                                .requestMatchers("/api/search/**").permitAll()
                                // .requestMatchers("/api/csrf-token").permitAll()
                                .requestMatchers("/api/token/**").permitAll()
                                .requestMatchers("/api/auth/user/**").hasAnyRole("USER", "ADMIN", "SUPPORT")
                                .requestMatchers("/api/auth/logout").hasAnyRole("USER", "ADMIN", "SUPPORT")
                                .requestMatchers("/api/auth/public/**").permitAll()
                                .requestMatchers("/user-service/registrationConfirm").permitAll()  // later change to /registrationConfirm
                                // .requestMatchers("/registrationConfirm").permitAll()                                
                                //.requestMatchers("/api/sse").hasAnyRole("USER", "ADMIN")
                                // 상담원(ROLE_SUPPORT, docs/58)도 로그인한 사람이다 — 프로필·로그아웃·알림을 쓴다. 빠뜨리면
                                // 상담원 계정은 로그아웃조차 403 이다.
                                // [JA] 相談員(ROLE_SUPPORT, docs/58)もログインしたユーザーである — プロフィール・ログアウト・通知を使う。
                                //      漏らすと相談員アカウントはログアウトさえ 403 になる。
                                .requestMatchers("/api/sse").hasAnyRole("USER", "ADMIN", "SUPPORT")
                                .requestMatchers("/oauth2/**").permitAll()
                                // 프로바이더가 코드를 들고 돌아오는 콜백 경로. 인증 필터가
                                // 처리하므로 사실상 도달하지 않지만, 명시해 두면 경로 이름만
                                // 보고도 공개임을 알 수 있다.
                                // [JA] プロバイダがコードを持って戻ってくるコールバックパス。認証フィルタが処理するので
                                //      実際には到達しないが、明示しておけばパス名を見ただけで公開だと分かる。
                                .requestMatchers("/login/oauth2/**").permitAll()
                                .anyRequest().authenticated());

            

                http.oauth2Login((oauth2) -> oauth2
                                .loginPage("/signin")
                                .clientRegistrationRepository(
                                                customClientRegistrationRepository.clientRegistrationRepository())
                                .authorizationEndpoint(authorization -> authorization
                                                // PKCE 를 켠다 (§pkceAuthorizationRequestResolver)
                                                // [JA] PKCE を有効にする (§pkceAuthorizationRequestResolver)
                                                .authorizationRequestResolver(
                                                                pkceAuthorizationRequestResolver())
                                                // 진행 중인 인가 요청을 세션이 아니라 Redis 에 둔다
                                                // (§RedisOAuth2AuthorizationRequestRepository)
                                                // [JA] 進行中の認可リクエストをセッションではなく Redis に置く
                                                //      (§RedisOAuth2AuthorizationRequestRepository)
                                                .authorizationRequestRepository(
                                                                authorizationRequestRepository))
                                .userInfoEndpoint(
                                                (userInfoEndpointConfig) -> userInfoEndpointConfig
                                                                .userService(customOAuth2UserService)
                                                                // openid scope 가 있는 등록(구글·LINE)은 이쪽으로 온다
                                                                // — 빠지면 계정 연결이 통째로 건너뛰어진다(CustomOidcUserService)
                                                                // [JA] openid scope を持つ登録(Google・LINE)はこちらに来る
                                                                //      — 抜けるとアカウント連携が丸ごとスキップされる(CustomOidcUserService)
                                                                .oidcUserService(customOidcUserService))
                                .successHandler(oAuth2SuccessHandler)
                                // 실패 이유를 코드로 들고 로그인 화면으로 (OAuth2FailureHandler)
                                // [JA] 失敗理由をコードとして持たせてログイン画面へ (OAuth2FailureHandler)
                                .failureHandler(oAuth2FailureHandler));

                http.exceptionHandling(exception -> exception.authenticationEntryPoint(unauthorizedHandler));

                http.addFilterBefore(authenticationJwtTokenFilter(),
                                AuthLoginFilter.class);

                http.addFilterBefore(new AuthLogoutFilter(jwtUtils, refreshTokenService), LogoutFilter.class);

                AuthLoginFilter loginFilter = new AuthLoginFilter(authenticationManager(http), jwtUtils,
                                refreshTokenService, notificationService,
                                deviceRecognitionService, securityAlertProducer, redisSessionManager,
                                pendingTwoFactor);
                loginFilter.setFilterProcessesUrl("/api/auth/public/signin");
                http.addFilterAt(loginFilter, UsernamePasswordAuthenticationFilter.class);

                http.sessionManagement(
                                (sessionManagement) -> sessionManagement
                                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS));

                // STATELESS 를 선언했으면 세션을 만드는 곳이 없어야 한다.
                //
                // 요청 캐시가 마지막 하나였다. 인증이 필요한 경로에 미인증으로 들어오면
                // ExceptionTranslationFilter 가 엔트리포인트를 부르기 **전에**
                // HttpSessionRequestCache 로 원 요청을 세션에 저장한다 — 즉 401 을 낼 때마다
                // 서블릿 세션이 생겼다. 레플리카가 2개라 그 세션은 다음 요청에서 없을 수도
                // 있고, 무엇보다 아무도 그것을 읽지 않는다: 로그인 성공 경로는 JWT 를 발급하고
                // OAuth2SuccessHandler 는 저장된 요청이 아니라 고정된 프론트엔드 주소로 보낸다.
                //
                // 읽히지 않는 상태를 만드는 것은 비용만 있고 이득이 없다. 껐다.
                // [JA] STATELESS を宣言したなら、セッションを作る箇所があってはならない。
                //
                //      最後の一つがリクエストキャッシュだった。認証が必要なパスに未認証で入ってくると、
                //      ExceptionTranslationFilter はエントリポイントを呼ぶ**前に**
                //      HttpSessionRequestCache で元のリクエストをセッションに保存する — つまり 401 を返すたびに
                //      サーブレットセッションが作られていた。レプリカが 2 つあるのでそのセッションは次のリクエストでは
                //      存在しないかもしれず、何より誰もそれを読まない: ログイン成功経路は JWT を発行し、
                //      OAuth2SuccessHandler は保存されたリクエストではなく固定のフロントエンド URL に送る。
                //
                //      読まれない状態を作るのはコストだけで利益が無い。無効にした。
                http.requestCache(cache -> cache.disable());

                return http.build();
        }

        @Bean
        public AuthenticationManager authenticationManager(HttpSecurity http) throws Exception {
                AuthenticationManagerBuilder authenticationManagerBuilder = http
                                .getSharedObject(AuthenticationManagerBuilder.class);
                authenticationManagerBuilder.authenticationProvider(customAuthenticationProvider);
                return authenticationManagerBuilder.build();
        }


        // @Bean
        // public CommandLineRunner initData(RoleRepository roleRepository,
        //                 UserRepository userRepository,
        //                 PasswordEncoder passwordEncoder) {
        //         return args -> {
        //                 Role userRole = roleRepository.findByRoleName(AppRole.ROLE_USER)
        //                                 .orElseGet(() -> roleRepository.save(new Role(AppRole.ROLE_USER)));

        //                 Role adminRole = roleRepository.findByRoleName(AppRole.ROLE_ADMIN)
        //                                 .orElseGet(() -> roleRepository.save(new Role(AppRole.ROLE_ADMIN)));

        //                 if (!userRepository.existsByUsername("user1")) {
        //                         User user1 = new User("user1", "user1@example.com",
        //                                         passwordEncoder.encode("1234"));
        //                         user1.setName("name1");
        //                         user1.setAccountNonLocked(false);
        //                         user1.setAccountNonExpired(true);
        //                         user1.setCredentialsNonExpired(true);
        //                         user1.setEnabled(true);
        //                         user1.setCredentialsExpiryDate(LocalDate.now().plusYears(1));
        //                         user1.setAccountExpiryDate(LocalDate.now().plusYears(1));
        //                         user1.setTwoFactorEnabled(false);
        //                         user1.setSignUpMethod("email");
        //                         user1.setRole(userRole);
        //                         user1 = userRepository.save(user1);

        //                         UserCreatedRequest dto = UserCreatedRequest.builder()
        //                                         .userId(user1.getUserId())
        //                                         .email(user1.getEmail())
        //                                         .image(user1.getImage())
        //                                         .nickname(user1.getName())
        //                                         .username(user1.getUsername())
        //                                         .build();

        //                         // userCreatedProducer.publish(dto);
        //                 } else {
        //                         User user1 = userRepository.findByUsername("user1").get();
        //                         UserCreatedRequest dto = UserCreatedRequest.builder()
        //                                         .userId(user1.getUserId())
        //                                         .email(user1.getEmail())
        //                                         .image(user1.getImage())
        //                                         .nickname(user1.getName())
        //                                         .username(user1.getUsername())
        //                                         .build();

        //                      //   userCreatedProducer.publish(dto);
        //                 }

        //                 if (!userRepository.existsByUsername("user2")) {
        //                         User user1 = new User("user2", "user2@example.com",
        //                                         passwordEncoder.encode("1234"));
        //                         user1.setAccountNonLocked(false);
        //                         user1.setAccountNonExpired(true);
        //                         user1.setCredentialsNonExpired(true);
        //                         user1.setEnabled(true);
        //                         user1.setCredentialsExpiryDate(LocalDate.now().plusYears(1));
        //                         user1.setAccountExpiryDate(LocalDate.now().plusYears(1));
        //                         user1.setTwoFactorEnabled(false);
        //                         user1.setSignUpMethod("email");
        //                         user1.setRole(userRole);
        //                         user1.setName("name2");
        //                         user1 = userRepository.save(user1);

        //                         UserCreatedRequest dto = UserCreatedRequest.builder()
        //                                         .userId(user1.getUserId())
        //                                         .email(user1.getEmail())
        //                                         .image(user1.getImage())
        //                                         .nickname(user1.getName())
        //                                         .username(user1.getUsername())
        //                                         .build();

        //                         // userCreatedProducer.publish(dto);

        //                 } else {
        //                         User user1 = userRepository.findByUsername("user2").get();
        //                         UserCreatedRequest dto = UserCreatedRequest.builder()
        //                                         .userId(user1.getUserId())
        //                                         .email(user1.getEmail())
        //                                         .image(user1.getImage())
        //                                         .nickname(user1.getName())
        //                                         .username(user1.getUsername())
        //                                         .build();

        //                      //  userCreatedProducer.publish(dto);
        //                 }

        //                 if (!userRepository.existsByUsername("admin")) {
        //                 User admin = new User("admin", "admin@example.com",
        //                 passwordEncoder.encode("adminPass"));
        //                 admin.setAccountNonLocked(true);
        //                 admin.setAccountNonExpired(true);
        //                 admin.setCredentialsNonExpired(true);
        //                 admin.setEnabled(true);
        //                 admin.setCredentialsExpiryDate(LocalDate.now().plusYears(1));
        //                 admin.setAccountExpiryDate(LocalDate.now().plusYears(1));
        //                 admin.setTwoFactorEnabled(false);
        //                 admin.setSignUpMethod("email");
        //                 admin.setRole(adminRole);
        //                 userRepository.save(admin);
        //                 }
        //         };
        // }
}
