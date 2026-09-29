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

  /** 새 로그인 — 새 묶음으로 발급한다. */
  public String generatRefreshTokenFromUser(User user) {
    return generateRefreshToken(user, java.util.UUID.randomUUID().toString());
  }

  /** 갱신 — 같은 묶음으로, 새 세션 id 로 발급한다. */
  public String generateRefreshToken(User user, String familyId) {

    String email = user.getEmail();
    String role = user.getRole().getRoleName().name();

    String sessionId = generateSessionId();

    return Jwts.builder()
        .subject(email)
        .claim(UID_CLAIM, user.getUserId())
        .claim("role", role)
        .claim("is2faEnabled", user.isTwoFactorEnabled())
        .claim("sessionId", sessionId)
        .claim(FAMILY_CLAIM, familyId)
        .claim(TOKEN_TYPE_CLAIM, REFRESH)
        .issuedAt(new Date(System.currentTimeMillis()))
        .expiration(new Date(System.currentTimeMillis() + jwtRefreshExpirationMs))
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

  public ResponseCookie generateJwtCookie(User user) {
    String jwt = generateTokenFromUser(user);
    return generateCookie(jwtCookie, jwt, "/api");
  }

  public ResponseCookie generateRefreshJwtCookie(String refreshToken) {
    return generateCookie(jwtRefreshCookie, refreshToken, "/");
  }

  public ResponseCookie getCleanJwtCookie() {
    ResponseCookie cookie = ResponseCookie.from(jwtCookie,
        null).path("/api").build();
    return cookie;
  }

  public ResponseCookie getCleanJwtRefreshCookie() {
    ResponseCookie cookie = ResponseCookie.from(jwtRefreshCookie,
        null).path("/api").build();
    return cookie;
  }

  private ResponseCookie generateCookie(String name, String value, String path) {
    ResponseCookie cookie = ResponseCookie.from(name, value)
        .path(path).maxAge(24 * 60 * 60)
        .sameSite("None")
        .secure(true)
        .httpOnly(true).build();
    return cookie;
  }

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

    return cookie;
  }

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
