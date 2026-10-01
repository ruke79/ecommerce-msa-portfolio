package com.project.userservice.security.jwt;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

import com.project.common.constants.TokenStatus;
import com.project.userservice.model.User;
import com.project.userservice.security.RedisSessionManager;
import com.project.userservice.security.service.UserDetailsImpl;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
@Component
public class JwtUtils {
  private static final Logger logger = LoggerFactory.getLogger(JwtUtils.class);

  @Value("${spring.app.jwtSecret}")
  private String jwtSecret;

  @Value("${spring.app.jwtExpirationMs}")
  private int jwtExpirationMs;

  @Value("${spring.app.jwtRefreshExpirationMs}")
  private int jwtRefreshExpirationMs;

  /**
   * 로그인의 <b>절대 수명</b> — 최초 로그인({@code auth_time})부터 이 시간이 지나면 갱신해도 다시 로그인한다(docs/68 §12).
   * 기본 30일. 리프레시 토큰은 갱신마다 14일씩 늘어나므로, 이것이 없으면 가끔 쓰기만 해도 로그인이 끝나지 않는다.
   * 필드 기본값은 스프링 밖(단위 테스트)에서도 같은 값이 되게 둔다.
   */
  // [JA] ログインの絶対寿命 — 最初のログイン(auth_time)からこの時間が過ぎたら、更新しても再ログイン。既定 30 日。
  @Value("${spring.app.jwtRefreshAbsoluteMs:2592000000}")
  private long jwtRefreshAbsoluteMs = 2_592_000_000L;

  @Value("${spring.app.jwtCookieName}")
  private String jwtCookie;

  @Value("${spring.app.jwtRefreshCookieName}")
  private String jwtRefreshCookie;

  /**
   * ⚠️ 주입받는다. 예전에는 {@code RedisSessionManager.getInstance()} 로 정적 싱글턴을
   * 가져왔는데, 그 구현이 인메모리 HashMap 이라 <b>레플리카마다 다른 기억</b>을 갖고 있었다
   * (그 클래스 주석 참조). 이제 Redis 를 쓰므로 스프링 빈으로 받는다.
   *
   * <p>[JA] ⚠️ インジェクションで受け取る。以前は {@code RedisSessionManager.getInstance()} で静的シングルトンを
   * 取得していたが、その実装がインメモリ HashMap だったため<b>レプリカごとに異なる記憶</b>を持っていた
   * (そのクラスのコメント参照)。今は Redis を使うので Spring Bean として受け取る。
   */
  private final RedisSessionManager redisSessionMgr;

  public JwtUtils(RedisSessionManager redisSessionMgr) {
    this.redisSessionMgr = redisSessionMgr;
  }

  private SecretKey key() {
    return new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8),
        Jwts.SIG.HS256.key().build().getAlgorithm());
  }

  private String generateSessionId() {
    return UUID.randomUUID().toString();
  }
  
  public void removeSession(String id) {
    redisSessionMgr.removeSession(id);
  }

  public String getJwtFromHeader(HttpServletRequest request) {
    String bearerToken = request.getHeader("Authorization");
    logger.debug("Authorization Header: {}", bearerToken);
    if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
      return bearerToken.substring(7); // Remove Bearer prefix
    }
    return null;
  }

  /**
   * 안정적인 숫자 사용자 id 를 담는 클레임 이름.
   *
   * <p><b>왜 필요한가.</b> 예전에는 토큰의 subject(이메일)만 있었고, 게이트웨이는 그것을
   * {@code User-Id} 헤더로 하위 서비스에 넘겼다. 그런데 delivery-service·notification-service
   * 는 사용자를 <b>숫자 id</b> 로 저장한다. 그래서 이름은 User-Id 인데 값은 이메일 문자열인
   * 상태였고, 그 헤더를 {@code Long} 으로 받는 컨트롤러는 타입 변환에 실패해 실제 게이트웨이를
   * 거친 요청에 <b>전부 400</b> 을 돌려줬다.
   *
   * <p>이메일을 식별자로 쓰는 것 자체도 문제다. 이메일은 <b>바뀔 수 있는 값</b>이라 사용자가
   * 이메일을 변경하면 과거 데이터와의 연결이 끊긴다. 소유권 판정의 기준은 불변 id 여야 한다.
   *
   * <p>[JA] 安定した数値のユーザー id を入れるクレーム名。
   *
   * <p><b>なぜ必要か。</b> 以前はトークンの subject(メールアドレス)しか無く、ゲートウェイはそれを
   * {@code User-Id} ヘッダーとして下流サービスに渡していた。ところが delivery-service・notification-service
   * はユーザーを<b>数値 id</b> で保存している。そのため名前は User-Id なのに値はメールアドレス文字列という
   * 状態で、そのヘッダーを {@code Long} で受けるコントローラは型変換に失敗し、実際にゲートウェイを
   * 経由したリクエストに<b>すべて 400</b> を返していた。
   *
   * <p>メールアドレスを識別子として使うこと自体も問題である。メールアドレスは<b>変わり得る値</b>なので、ユーザーが
   * メールアドレスを変更すると過去データとの紐付けが切れる。所有権判定の基準は不変の id でなければならない。
   */
  public static final String UID_CLAIM = "uid";

  /**
   * 🔒 토큰 종류 클레임 — {@code access} · {@code refresh}.
   *
   * <p>🐛 예전에는 둘이 <b>같은 키·같은 클레임</b>으로 서명돼 구분할 수 없었다. 게이트웨이는 서명과 만료만 봤으므로 14일짜리
   * 리프레시 토큰을 {@code Authorization: Bearer} 에 넣으면 액세스 토큰으로 통과했다 — 그리고 로그아웃은 리프레시를 Redis 에서
   * 지울 뿐이라 <b>로그아웃한 뒤에도 14일 동안</b> 그 토큰으로 API 를 부를 수 있었다. 리프레시 쿠키는 경로 {@code /} 라 모든
   * 요청에 실려 다니므로, 한 번 새면 그 창이 그대로 열려 있었다.
   *
   * <p>이제 게이트웨이·user-service·chat-service 는 {@code access} 인 토큰만 신원으로 받고, 리프레시 경로는 {@code access} 를
   * 거절한다. 이 클레임이 없는 옛 액세스 토큰은 배포 뒤 한 번 401 을 받는다 — 화면은 401 이면 리프레시로 새 토큰을 받는다.
   * 옛 리프레시 토큰(클레임 없음)은 리프레시 경로에서만 계속 통한다(최대 14일, 그 뒤로 자연히 사라진다).
   *
   * <p>[JA] 🔒 トークン種別クレーム — {@code access} ・ {@code refresh}。
   *
   * <p>🐛 以前は両者が<b>同じ鍵・同じクレーム</b>で署名されていて区別できなかった。ゲートウェイは署名と有効期限しか
   * 見ていなかったので、14日有効のリフレッシュトークンを {@code Authorization: Bearer} に入れるとアクセストークンとして
   * 通ってしまった — しかもログアウトはリフレッシュトークンを Redis から消すだけなので、<b>ログアウト後も14日間</b>
   * そのトークンで API を呼べた。リフレッシュ Cookie はパスが {@code /} ですべてのリクエストに付いて回るため、
   * 一度漏れるとその窓がそのまま開きっぱなしだった。
   *
   * <p>今はゲートウェイ・user-service・chat-service は {@code access} のトークンだけを身元として受け入れ、リフレッシュ経路は
   * {@code access} を拒否する。このクレームの無い古いアクセストークンはデプロイ後に一度 401 を受ける — 画面は 401 なら
   * リフレッシュで新しいトークンを取得する。古いリフレッシュトークン(クレーム無し)はリフレッシュ経路でのみ引き続き
   * 通用する(最大14日、その後は自然に消える)。
   */
  public static final String TOKEN_TYPE_CLAIM = "tokenType";
  public static final String ACCESS = "access";
  public static final String REFRESH = "refresh";

  public String generateTokenFromUser(User user) {

    String email = user.getEmail();
    String role = user.getRole().getRoleName().name();

    String sessionId = generateSessionId();
    redisSessionMgr.addSession(email, sessionId);

    return Jwts.builder()
        .subject(email)
        .claim(UID_CLAIM, user.getUserId())
        .claim("role", role)
        .claim("is2faEnabled", user.isTwoFactorEnabled())
        .claim("sessionId", sessionId)
        .claim(TOKEN_TYPE_CLAIM, ACCESS)
        .issuedAt(new Date(System.currentTimeMillis()))
        .expiration(new Date(System.currentTimeMillis() + jwtExpirationMs))
        .signWith(key())
        .compact();
  }

  /**
   * 리프레시 토큰의 <b>묶음(family)</b> 클레임 — 한 번의 로그인에서 갱신으로 이어진 토큰들이 같은 값을 갖는다(docs/68).
   * 이미 갱신에 쓴 토큰이 다시 오면(재사용) 이 묶음 전체를 끊는다. 이 클레임이 없는 토큰은 회전 이전에 발급된 것이다.
   */
  // [JA] リフレッシュトークンの「ファミリー」クレーム — 一度のログインから更新でつながったトークンが同じ値を持つ。
  //      更新に使用済みのトークンが再び来たら(再利用)、このファミリー全体を切る。
  public static final String FAMILY_CLAIM = "fam";

  /**
   * 리프레시 토큰의 "최초 로그인 시각"(epoch 초, OIDC 의 {@code auth_time} 과 같은 뜻) — 회전해도 그대로 이어진다.
   * 절대 수명(30일)을 이것으로 잰다(docs/68 §12).
   */
  // [JA] リフレッシュトークンの「最初のログイン時刻」(epoch 秒)。ローテーションしてもそのまま引き継ぐ。絶対寿命をこれで測る。
  public static final String AUTH_TIME_CLAIM = "auth_time";

  /** 새 로그인 — 새 묶음으로 발급한다. */
  public String generatRefreshTokenFromUser(User user) {
    return generateRefreshToken(user, java.util.UUID.randomUUID().toString());
  }

  /** 새 로그인의 첫 토큰 — 최초 로그인 시각은 지금. */
  public String generateRefreshToken(User user, String familyId) {
    return generateRefreshToken(user, familyId, System.currentTimeMillis() / 1000);
  }

  /**
   * 갱신 — 같은 묶음으로, 새 세션 id 로, <b>최초 로그인 시각을 이어받아</b> 발급한다.
   *
   * <p>만료 = min(지금 + 리프레시 수명(14일), 최초 로그인 + 절대 수명(30일)). 절대 수명이 가까우면 14일보다 짧아진다.
   */
  // [JA] 更新 — 同じファミリー、新しいセッション id、最初のログイン時刻を引き継いで発行。期限 = min(今 + 14 日, 最初のログイン + 30 日)。
  public String generateRefreshToken(User user, String familyId, long authTimeEpochSec) {

    String email = user.getEmail();
    String role = user.getRole().getRoleName().name();

    String sessionId = generateSessionId();
    long now = System.currentTimeMillis();
    long expiresAt = Math.min(now + jwtRefreshExpirationMs, authTimeEpochSec * 1000 + jwtRefreshAbsoluteMs);

    return Jwts.builder()
        .subject(email)
        .claim(UID_CLAIM, user.getUserId())
        .claim("role", role)
        .claim("is2faEnabled", user.isTwoFactorEnabled())
        .claim("sessionId", sessionId)
        .claim(FAMILY_CLAIM, familyId)
        .claim(AUTH_TIME_CLAIM, authTimeEpochSec)
        .claim(TOKEN_TYPE_CLAIM, REFRESH)
        .issuedAt(new Date(now))
        .expiration(new Date(expiresAt))
        .signWith(key())
        .compact();
  }

  /**
   * @deprecated {@code uid} 클레임이 없는 토큰을 만든다. 게이트웨이가 안정적인 숫자
   *             사용자 id 를 하위 서비스로 넘기지 못하므로 새 코드에서는 쓰지 말 것.
   *             {@link #generateToken(Long, String, String, boolean, long)} 을 쓴다.
   *             [JA] {@code uid} クレームの無いトークンを作る。ゲートウェイが安定した数値のユーザー id を
   *             下流サービスに渡せないため、新しいコードでは使わないこと。
   *             {@link #generateToken(Long, String, String, boolean, long)} を使う。
   */
  @Deprecated
  public String generateToken(String id, String role, boolean isTwoFactorEnabled, long expirationMs) {
    return generateToken(null, id, role, isTwoFactorEnabled, expirationMs);
  }

  public String generateToken(Long userId, String email, String role,
                              boolean isTwoFactorEnabled, long expirationMs) {

    String sessionId = generateSessionId();
    redisSessionMgr.addSession(email, sessionId);

    var builder = Jwts.builder()
        .subject(email)
        .claim("role", role)
        .claim("is2faEnabled", isTwoFactorEnabled)
        .claim("sessionId", sessionId)
        .claim(TOKEN_TYPE_CLAIM, ACCESS);

    if (userId != null) {
      builder.claim(UID_CLAIM, userId);
    }

    return builder
        .issuedAt(new Date(System.currentTimeMillis()))
        .expiration(new Date(System.currentTimeMillis() + expirationMs))
        .signWith(key())
        .compact();
  }

  /** @deprecated {@code uid} 클레임이 없다. {@link #generateToken(Long, String, String, boolean)} 을 쓴다. */
  // [JA] @deprecated {@code uid} クレームが無い。{@link #generateToken(Long, String, String, boolean)} を使う。
  @Deprecated
  public String generateToken(String id, String role, boolean isTwoFactorEnabled) {
    return generateToken(null, id, role, isTwoFactorEnabled);
  }

  public String generateToken(Long userId, String email, String role, boolean isTwoFactorEnabled) {

    String sessionId = generateSessionId();
    redisSessionMgr.addSession(email, sessionId);

    var builder = Jwts.builder()
        .subject(email)
        .claim("role", role)
        .claim("is2faEnabled", isTwoFactorEnabled)
        .claim("sessionId", sessionId)
        .claim(TOKEN_TYPE_CLAIM, ACCESS);

    if (userId != null) {
      builder.claim(UID_CLAIM, userId);
    }

    return builder
        .issuedAt(new Date(System.currentTimeMillis()))
        .expiration(new Date(System.currentTimeMillis() + jwtExpirationMs))
        .signWith(key())
        .compact();
  }

  /**
   * 검증된 토큰의 subject(이메일).
   *
   * <p>🐛 예전에는 {@code ExpiredJwtException} 을 잡아 <b>만료된 토큰의 subject 도 돌려줬다.</b> 인증 필터가 이것만 불렀으므로
   * user-service 는 만료된 액세스 토큰을 유효로 취급했다(만료 검사는 주석 처리돼 있었다). 이제 만료면 던진다 — 부르는 쪽은
   * 모두 먼저 {@link #validateJwtToken} 을 거친다.
   *
   * <p>[JA] 検証済みトークンの subject(メールアドレス)。
   *
   * <p>🐛 以前は {@code ExpiredJwtException} を捕捉して<b>期限切れトークンの subject も返していた。</b> 認証フィルタは
   * これだけを呼んでいたので、user-service は期限切れのアクセストークンを有効として扱っていた(期限チェックは
   * コメントアウトされていた)。今は期限切れなら例外を投げる — 呼び出し側はすべて先に {@link #validateJwtToken} を通る。
   */
  public String getIdFromJwtToken(String token) {
    return Jwts.parser()
        .verifyWith(key())
        .build().parseSignedClaims(token)
        .getPayload().getSubject();
  }

  /** 서명·만료가 유효하고 종류가 {@code access} 인가. 클레임이 없는 옛 토큰은 아니다. */
  // [JA] 署名・有効期限が有効で、種別が {@code access} か。クレームの無い古いトークンは該当しない。
  public boolean isAccessToken(String token) {
    try {
      return ACCESS.equals(Jwts.parser().verifyWith(key()).build()
          .parseSignedClaims(token).getPayload().get(TOKEN_TYPE_CLAIM, String.class));
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** 리프레시 경로가 받아도 되는 토큰인가 — {@code refresh}, 또는 클레임이 없는 옛 리프레시. 액세스 토큰은 아니다. */
  // [JA] リフレッシュ経路が受け入れてよいトークンか — {@code refresh}、またはクレームの無い古いリフレッシュトークン。
  //      アクセストークンは該当しない。
  public boolean isRefreshTokenType(String token) {
    try {
      String type = Jwts.parser().verifyWith(key()).build()
          .parseSignedClaims(token).getPayload().get(TOKEN_TYPE_CLAIM, String.class);
      return type == null || REFRESH.equals(type);
    } catch (RuntimeException e) {
      return false;
    }
  }

  public String getRoleFromJwtToken(String token) {

    return Jwts.parser().verifyWith(key()).build()
        .parseSignedClaims(token).getPayload().get("role", String.class);
  }

  public String getSessionIdFromJwtToken(String token) {

    return Jwts.parser().verifyWith(key()).build()
        .parseSignedClaims(token).getPayload().get("sessionId", String.class);
  }

  /** 최초 로그인 시각(epoch 초). 이 클레임 이전에 발급된 토큰이면 발급 시각으로 대신한다 — 그 토큰부터 30일. */
  // [JA] 最初のログイン時刻(epoch 秒)。このクレーム以前に発行されたトークンなら発行時刻で代える。
  public long getAuthTimeFromJwtToken(String token) {
    var claims = Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
    Object v = claims.get(AUTH_TIME_CLAIM);
    if (v instanceof Number n) {
      return n.longValue();
    }
    return claims.getIssuedAt() != null ? claims.getIssuedAt().getTime() / 1000 : System.currentTimeMillis() / 1000;
  }

  /** 최초 로그인부터 절대 수명이 지났는가. */
  public boolean isPastAbsoluteLifetime(long authTimeEpochSec) {
    return System.currentTimeMillis() >= authTimeEpochSec * 1000 + jwtRefreshAbsoluteMs;
  }

  /** 토큰의 남은 수명 — 쿠키 Max-Age 를 토큰과 같이 끝나게. 이미 지났으면 0. */
  public java.time.Duration remainingLifetime(String token) {
    Date exp;
    try {
      exp = Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload().getExpiration();
    } catch (io.jsonwebtoken.ExpiredJwtException e) {
      return java.time.Duration.ZERO;
    }
    long ms = exp == null ? 0 : exp.getTime() - System.currentTimeMillis();
    return java.time.Duration.ofMillis(Math.max(0, ms));
  }

  /** 리프레시 토큰의 묶음 id — 회전 이전에 발급된 토큰이면 null. */
  public String getFamilyIdFromJwtToken(String token) {
    return Jwts.parser().verifyWith(key()).build()
        .parseSignedClaims(token).getPayload().get(FAMILY_CLAIM, String.class);
  }

  /**
   * 토큰이 발급된 시각.
   *
   * <p>비밀번호를 바꾼 시각과 견주어 <b>그보다 먼저 발급된 리프레시 토큰을 거절</b>하는 데
   * 쓴다. 리프레시 토큰은 Redis 에 sessionId 로만 저장돼 "이 사용자의 모든 세션" 을 뒤집어
   * 찾을 수 없고, {@code RedisSessionManager} 는 이름과 달리 인메모리라 레플리카마다 따로
   * 논다 — 발급 시각으로 거르면 DB 한 곳만 보면 된다.
   *
   * <p>[JA] トークンが発行された時刻。
   *
   * <p>パスワードを変更した時刻と比べ、<b>それより前に発行されたリフレッシュトークンを拒否</b>するのに
   * 使う。リフレッシュトークンは Redis に sessionId でしか保存されておらず「このユーザーの全セッション」を逆引きで
   * 探せないうえ、{@code RedisSessionManager} は名前に反してインメモリなのでレプリカごとにばらばらに
   * 動く — 発行時刻でふるい分ければ DB 一か所だけを見ればよい。
   *
   * @return 발급 시각. {@code iat} 가 없는 토큰이면 null
   *         [JA] 発行時刻。{@code iat} の無いトークンなら null
   */
  public Date getIssuedAtFromJwtToken(String token) {
    return Jwts.parser().verifyWith(key()).build()
        .parseSignedClaims(token).getPayload().getIssuedAt();
  }

  /** 긴 연결(SSE)이 필요한 클레임을 한 번에 — 서명 · 만료를 확인한다(docs/68 §9). */
  public record LiveClaims(Long userId, long issuedAtEpochSec, long expiresAtEpochMs) {
  }

  public LiveClaims liveClaimsOf(String token) {
    var claims = Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
    Number uid = claims.get(UID_CLAIM, Number.class);
    Date iat = claims.getIssuedAt();
    return new LiveClaims(uid == null ? null : uid.longValue(),
        iat == null ? 0 : iat.getTime() / 1000,
        claims.getExpiration().getTime());
  }

  public Boolean isJwtTokenExpired(String token) {
    return Jwts.parser()
        .verifyWith(key())
        .build().parseSignedClaims(token)
        .getPayload().getExpiration().before(new Date());
  }

  public Long getValidExpiration(String token) {

    Date now = new Date();

    return Jwts.parser()
        .verifyWith(key())
        .build().parseSignedClaims(token)
        .getPayload().getExpiration().getTime() - now.getTime();
  }

  public boolean isRequestAuthorized(String token) {
    try {

      Claims claims = Jwts.parser()
          .verifyWith(key())
          .build().parseSignedClaims(token)
          .getPayload();

      String userId = claims.getSubject();
      String sessionId = (String) claims.get("sessionId");

      return redisSessionMgr.isSessionActive(userId, sessionId);

    } catch (Exception e) {
      return false;
    }
  }

  public TokenStatus validateJwtToken(String authToken) {
    try {
      Jwts.parser().verifyWith(key())
          .build().parseSignedClaims(authToken);
      return TokenStatus.VALID;
    } catch (MalformedJwtException e) {
      logger.error("Invalid JWT token: {}", e.getMessage());
      return TokenStatus.INVALID;
    } catch (ExpiredJwtException e) {
      logger.error("JWT token is expired: {}", e.getMessage());
      return TokenStatus.EXPIRED;
    } catch (UnsupportedJwtException e) {
      logger.error("JWT token is unsupported: {}", e.getMessage());
      return TokenStatus.UNSUPPORTED;
    } catch (IllegalArgumentException e) {
      logger.error("JWT claims string is empty: {}", e.getMessage());
      return TokenStatus.ILLEGAL_ARGS;
    }
  }

  // Http only cookie + JWT
  //
  // 🗑️ 예전에는 여기 ResponseCookie 로 쿠키를 만드는 메서드 다섯 개가 있었고 SameSite=None 이었다. 부르는 곳이 없었다 — 실제 쿠키는
  //    전부 아래 createCookie 가 만든다. 코드를 읽는 사람이 "리프레시 쿠키가 SameSite=None" 이라고 오해하게만 했다.
  // [JA] 🗑️ 以前はここに ResponseCookie で Cookie を作るメソッドが5つあり SameSite=None だった。呼び出し元はなかった — 実際の Cookie は
  //    すべて下の createCookie が作る。読む人に「リフレッシュ Cookie は SameSite=None」と誤解させるだけだった。

  /**
   * 🐛 예전에는 {@code int expiry} 였고 단위가 호출하는 쪽마다 달랐다 — 2FA 대기 쿠키는 초를, 리프레시 쿠키는
   * {@code jwtRefreshExpirationMs}(밀리초)를 넘겼다. {@code Max-Age} 는 초라서 14일짜리 리프레시 쿠키가 약 38년짜리가
   * 됐다(1,209,600,000초). 단위를 타입으로 못 박는다(docs/57 §2).
   *
   * <p>[JA] 🐛 以前は {@code int expiry} で、単位が呼び出し側ごとに異なっていた — 2FA 待機 Cookie は秒を、リフレッシュ Cookie は
   * {@code jwtRefreshExpirationMs}(ミリ秒)を渡していた。{@code Max-Age} は秒なので、14日のリフレッシュ Cookie が約38年の
   * ものになっていた(1,209,600,000秒)。単位を型で固定する(docs/57 §2)。
   *
   * @param maxAge 쿠키 수명. {@link Duration#ZERO} 면 지운다
   *               [JA] Cookie の寿命。{@link Duration#ZERO} なら削除する
   */
  public Cookie createCookie(String key, String value, java.time.Duration maxAge) {

    Cookie cookie = new Cookie(key, value);
    cookie.setMaxAge((int) Math.min(Integer.MAX_VALUE, maxAge.toSeconds()));
    cookie.setSecure(true);
    cookie.setPath("/");
    cookie.setHttpOnly(true);
    // 🔒 SameSite=Lax — 다른 사이트에서 시작한 POST·fetch 에는 쿠키가 실리지 않는다. 쿠키로 신원을 싣는 요청(리프레시 회전,
    //    2FA 확인, 로그아웃)은 모두 POST 라, 남의 사이트가 사용자 브라우저로 그것들을 대신 보내는 CSRF 가 여기서 막힌다.
    //    액세스 토큰은 쿠키가 아니라 Authorization 헤더로 다니므로 애초에 CSRF 대상이 아니다.
    //
    //    🐛 예전에는 속성을 아예 적지 않았다. 브라우저마다 기본값이 다르다 — Chrome 은 Lax 로 보되 발급 후 2분 동안은 교차 사이트
    //    POST 에도 실어 보내고(Lax+POST), Safari·Firefox 는 None 으로 취급한다. 명시해야 모든 브라우저에서 같다.
    //
    //    Strict 가 아닌 이유: OAuth 제공자(구글·카카오·LINE)에서 돌아오는 것은 다른 사이트에서 시작한 최상위 GET 이동이다.
    //    Strict 면 그 이동에 기존 쿠키(기기 식별 등)가 실리지 않는다. Lax 는 최상위 GET 이동에는 싣고, POST 에는 싣지 않는다.
    //    프론트와 API 가 같은 사이트(같은 도메인 또는 그 하위 도메인)에 있다는 전제다 — 운영 구성(nginx 가 같은 호스트에서 프록시)이 그렇다.
    // [JA] 🔒 SameSite=Lax — 他サイトから始まった POST・fetch には Cookie が載らない。Cookie で身元を運ぶ要求(リフレッシュのローテーション、
    //    2FA 確認、ログアウト)はすべて POST なので、他サイトがユーザーのブラウザで代わりに送る CSRF がここで止まる。
    //    アクセストークンは Cookie ではなく Authorization ヘッダーで運ぶので、そもそも CSRF の対象ではない。
    //    🐛 以前は属性を書いていなかった。ブラウザごとに既定値が違う(Chrome は Lax 扱いだが発行後2分は他サイトの POST にも載せる、
    //    Safari・Firefox は None 扱い)。明示してすべてのブラウザで同じにする。
    //    Strict にしない理由: OAuth 提供者から戻るのは他サイト発のトップレベル GET 遷移で、Strict だと既存の Cookie が載らない。
    //    フロントと API が同じサイトにある前提 — 運用構成(nginx が同じホストでプロキシ)はそうなっている。
    cookie.setAttribute("SameSite", SAME_SITE);

    return cookie;
  }

  /** 모든 인증 쿠키의 SameSite 값. */
  // [JA] すべての認証 Cookie の SameSite 値。
  public static final String SAME_SITE = "Lax";

  /** 리프레시 토큰의 수명 — 쿠키 {@code Max-Age} 와 Redis TTL 이 같은 값을 쓴다. */
  // [JA] リフレッシュトークンの寿命 — Cookie の {@code Max-Age} と Redis の TTL が同じ値を使う。
  public java.time.Duration refreshTokenLifetime() {
    return java.time.Duration.ofMillis(jwtRefreshExpirationMs);
  }

  private String getCookieValueByName(HttpServletRequest request, String name) {
    Cookie cookie = WebUtils.getCookie(request, name);
    if (cookie != null) {
      return cookie.getValue();
    } else {
      return null;
    }
  }

  public String getJwtFromCookies(HttpServletRequest request) {
    return getCookieValueByName(request, jwtCookie);
  }

  public String getJwtRefreshFromCookies(HttpServletRequest request) {
    return getCookieValueByName(request, jwtRefreshCookie);
  }

}
