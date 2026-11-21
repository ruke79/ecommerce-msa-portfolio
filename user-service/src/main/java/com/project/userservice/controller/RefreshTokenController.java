package com.project.userservice.controller;

import java.io.IOException;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.common.constants.StatusMessages;
import com.project.common.constants.TokenStatus;
import com.project.userservice.constants.TokenType;
import com.project.userservice.exceptionHandling.TokenRefreshException;
import com.project.userservice.model.User;
import com.project.userservice.model.redis.RefreshToken;
import com.project.userservice.security.jwt.JwtUtils;
import com.project.userservice.security.response.LoginResponse;
import com.project.userservice.service.RefreshTokenService;
import com.project.userservice.service.impl.UserServiceImpl;

import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("api/")
public class RefreshTokenController {

    private final UserServiceImpl userService;

    private final JwtUtils jwtUtils;

    private final RefreshTokenService refreshTokenService;

    
    @Autowired
    public RefreshTokenController(UserServiceImpl userService, JwtUtils jwtUtils, RefreshTokenService refreshTokenService) {
        this.userService = userService;
        this.jwtUtils = jwtUtils;
        this.refreshTokenService = refreshTokenService;
    }

    /**
     * 액세스 토큰 재발급. 프론트는 <b>첫 화면을 그리기 전에 항상</b> 이것을 부른다
     * (TokenRefresher — 새로고침 뒤 세션 복구). 그래서 <b>로그인하지 않은 방문자</b>도 온다.
     *
     * <h3>🐛 방문자에게 400 "refresh token null" 을 돌려주고 있었다</h3>
     *
     * <p>"쿠키가 하나도 없으면 204, 있는데 리프레시 쿠키만 없으면 400" 이었다. 그런데
     * 운영에서는 쿠키가 하나도 없는 요청이 거의 없다 — Cloudflare 가 자기 쿠키를 심고,
     * 로그아웃한 뒤에도 다른 쿠키는 남는다. 결과: <b>로그인 전 첫 로딩마다</b> 콘솔에 400 이
     * 찍혔다. 화면은 (catch 에서 logout 으로) 정상이었지만, 붉은 오류 하나가 늘 떠 있으니
     * 진짜 오류가 묻힌다.
     *
     * <p>두 경우는 같은 사실이다: <b>세션이 없다.</b> 둘 다 204 다 — 프론트가 이미 "204 =
     * 세션 없음, 조용히 비로그인" 으로 다룬다. 빈 값의 쿠키도 같다.
     *
     * <p>만료·위조된 쿠키는 400 그대로다(프론트가 문구로 구분한다). 다만 그 쿠키를 <b>지운다</b>
     * — 안 지우면 만료된 뒤 처음 오는 방문자가 매 로딩마다 400 을 받는다. 지우고 나면 다음
     * 로딩부터 204 다.
     *
     * <p>[JA] アクセストークンの再発行。フロントは<b>最初の画面を描く前に必ず</b>これを呼ぶ(TokenRefresher — リロード後のセッション復旧)。
     * だから<b>ログインしていない訪問者</b>も来る。
     * 🐛 訪問者に 400 "refresh token null" を返していた: 「Cookie が一つもなければ 204、あるのにリフレッシュ Cookie だけないなら 400」だった。
     * ところが運用では Cookie が一つもないリクエストはほとんどない — Cloudflare が自分の Cookie を仕込み、
     * ログアウト後も他の Cookie は残る。結果: <b>ログイン前の初回ロードのたびに</b>コンソールに 400 が出た。画面は
     * (catch で logout して)正常だったが、赤いエラーが常に一つ出ていると本物のエラーが埋もれる。
     * 二つの場合は同じ事実だ: <b>セッションがない。</b>どちらも 204 — フロントは既に「204 = セッションなし、静かに未ログイン」
     * として扱っている。空の値の Cookie も同じ。
     * 期限切れ・偽造の Cookie は 400 のままだ(フロントが文言で区別する)。ただしその Cookie を<b>消す</b> —
     * 消さないと期限切れ後に初めて来る訪問者が毎回のロードで 400 を受ける。消せば次のロードから 204 になる。
     */
    @GetMapping("/token/refresh")
    public ResponseEntity<?> refreshtoken(HttpServletRequest request, HttpServletResponse response) throws IOException {

        String refresh = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie.getName().equals(TokenType.REFRESH.getType())) {
                    refresh = cookie.getValue();
                }
            }
        }

        if (refresh == null || refresh.isBlank()) {
            // 세션 없음 — 오류가 아니다. 로그 없이(방문자마다 한 줄씩 남기면 로그만 늘어난다).
            // [JA] セッションなし — エラーではない。ログなしで(訪問者ごとに一行残すとログが増えるだけだ)。
            return new ResponseEntity<>(null, HttpStatus.NO_CONTENT);
        }

        //expired check
        try {
            jwtUtils.isJwtTokenExpired(refresh);
        } catch (ExpiredJwtException e) {
            log.info("만료된 리프레시 토큰 — 쿠키를 지운다");
            expireRefreshCookie(response);
            return new ResponseEntity<>("refresh token expired", HttpStatus.BAD_REQUEST);
        } catch (io.jsonwebtoken.JwtException | IllegalArgumentException e) {
            // 🐛 예전: 서명이 안 맞는 토큰은 여기서 예외가 그대로 올라가 500 이었다. 키를 은퇴시킨 뒤(docs/68 §17) 옛 키의 리프레시 토큰을
            //    든 사용자는 400 을 받아야 화면이 로그아웃 상태로 정리된다 — 500 이면 "잠시 후 다시" 만 반복한다.
            // [JA] 以前は署名不一致のトークンの例外がそのまま上がり 500 だった。鍵の引退後、旧鍵のトークンは 400 で画面をログアウト状態に整理する。
            log.info("검증할 수 없는 리프레시 토큰({}) — 쿠키를 지운다", e.getClass().getSimpleName());
            expireRefreshCookie(response);
            return new ResponseEntity<>("invalid refresh token", HttpStatus.BAD_REQUEST);
        }

        // 🔒 액세스 토큰으로는 갱신하지 않는다 — 종류가 다른 토큰을 섞어 쓰는 창을 닫는다(JwtUtils.TOKEN_TYPE_CLAIM).
        // [JA] 🔒 アクセストークンでは更新しない — 種類の違うトークンを混用する隙間を閉じる(JwtUtils.TOKEN_TYPE_CLAIM)。
        if(jwtUtils.validateJwtToken(refresh) != TokenStatus.VALID || !jwtUtils.isRefreshTokenType(refresh)) {
            log.info("invalid refresh token");
            expireRefreshCookie(response);
            return new ResponseEntity<>("invalid refresh token", HttpStatus.BAD_REQUEST);
        }

        String email = jwtUtils.getIdFromJwtToken(refresh);


        // 🔒 비밀번호를 바꾸기 **전에** 발급된 리프레시 토큰은 여기서 끊는다.
        //
        // 이게 없으면 비밀번호 변경·재설정이 기존 세션에 아무 영향을 주지 않는다 —
        // 계정을 도둑맞아 비밀번호를 바꾼 경우에도 공격자는 최대 14일(리프레시 수명) 동안
        // 로그인 상태로 남는다. 비밀번호를 바꾼 의미가 사라진다.
        //
        // 토큰을 찾아 지우지 않고 발급 시각으로 거르는 이유: 리프레시 토큰은 Redis 에
        // sessionId 로만 저장돼 "이 사용자의 모든 세션" 을 뒤집어 찾을 수 없고,
        // RedisSessionManager 는 이름과 달리 인메모리 HashMap 이라 레플리카마다 따로 논다.
        // DB 한 곳만 보면 레플리카 수와 무관하게 동작한다.
        //
        // ⚠️ passwordChangedAt 이 null 이면(= 한 번도 바꾼 적 없음) 아무것도 거절하지 않는다.
        //    V10 이 기본값을 넣지 않는 것도 같은 이유다 — 넣었다면 배포 순간 전원이 로그아웃된다.
        // [JA] 🔒 パスワードを変える**前に**発行されたリフレッシュトークンはここで切る。
        //      これがないとパスワードの変更・再設定が既存のセッションに何の影響も与えない —
        //      アカウントを盗まれてパスワードを変えた場合でも、攻撃者は最大 14 日(リフレッシュの寿命)の間
        //      ログイン状態のまま残る。パスワードを変えた意味がなくなる。
        //      トークンを探して消さず発行時刻でふるい落とす理由: リフレッシュトークンは Redis に sessionId だけで
        //      保存されていて「このユーザーのすべてのセッション」を逆引きできず、RedisSessionManager は名前に反して
        //      インメモリの HashMap なのでレプリカごとにばらばらに動く。DB 一か所だけを見ればレプリカ数に関係なく動く。
        //      ⚠️ passwordChangedAt が null なら(= 一度も変えていない)何も拒否しない。
        //      V10 が既定値を入れないのも同じ理由だ — 入れていたらデプロイの瞬間に全員がログアウトされる。
        User tokenOwner = userService.findByEmail(email);
        if (tokenOwner != null && tokenOwner.getPasswordChangedAt() != null) {
            java.util.Date issuedAt = jwtUtils.getIssuedAtFromJwtToken(refresh);
            // 초 단위로 비교한다 — JWT 의 iat 는 초까지라, 비밀번호를 바꾼 바로 그 초에 이 기기에 새로 준 토큰(UserController)이
            // 밀리초 차이로 "이전" 이 되어 거절되지 않게(docs/68 §14). 같은 초의 옛 토큰은 변경 때 묶음을 끊어 막는다.
            // [JA] 秒単位で比較する — JWT の iat は秒までなので、パスワードを変えたまさにその秒にこの端末へ新たに渡したトークン(UserController)が
            //      ミリ秒差で「以前」になって拒否されないように(docs/68 §14)。同じ秒の古いトークンは変更時に束を切って防ぐ。
            java.util.Date changedAt = java.sql.Timestamp.valueOf(tokenOwner.getPasswordChangedAt().withNano(0));
            if (issuedAt == null || issuedAt.before(changedAt)) {
                log.info("비밀번호 변경 이전에 발급된 리프레시 토큰을 거절한다: userId={}",
                        tokenOwner.getUserId());
                // 🐛 예전: 쿠키를 남겨 둬, 화면의 갱신이 겹칠 때마다(알림 · 채팅 연결 · 401 재시도) 같은 쿠키로 다시 와 같은 거절을
                //    되풀이했다(2026-11-21 운영 — 0.26초 간격 4번). 다른 거절처럼 쿠키를 지우고 그 묶음도 끊는다 — 다음 시도는
                //    쿠키가 없어 바로 204(로그아웃)다.
                // [JA] 以前は Cookie を残し、画面の更新が重なるたびに同じ拒否を繰り返した(本番で 0.26 秒間隔 4 回)。他の拒否と同じく Cookie を消し、ファミリーも切る。
                refreshTokenService.revoke(refresh);
                expireRefreshCookie(response);
                return new ResponseEntity<>("password changed", HttpStatus.UNAUTHORIZED);
            }
        }

        if (tokenOwner == null) {
            expireRefreshCookie(response);
            return new ResponseEntity<>("invalid refresh token", HttpStatus.BAD_REQUEST);
        }

        // 🔒 정지 · 잠금 · 만료된 계정은 갱신하지 않는다(docs/68 §13). 🐛 예전에는 보지 않아, 관리자가 막아도 이미 로그인한 사용자는
        //    최대 30일 동안 계속 갱신했다. 막을 때 묶음도 끊지만(UserServiceImpl.endSessions) 여기가 마지막 방어선이다.
        //    로그인 5회 실패의 자동 잠금(Redis, LoginAttemptService)은 보지 않는다 — 공격자가 비밀번호를 틀려 남의 로그인을 끊게 된다.
        // [JA] 🔒 停止 · ロック · 期限切れのアカウントは更新しない(docs/68 §13)。🐛 以前は見ておらず、管理者が止めてもすでにログインした利用者は
        //      最大 30 日間更新し続けた。止めるときにまとめて切りもするが(UserServiceImpl.endSessions)ここが最後の防衛線だ。
        //      ログイン 5 回失敗の自動ロック(Redis, LoginAttemptService)は見ない — 攻撃者がパスワードを間違えて他人のログインを切れてしまう。
        // [JA] 停止 · ロック · 期限切れのアカウントは更新しない。自動ロック(Redis)は見ない — 攻撃者が他人のログインを切れてしまう。
        if (!tokenOwner.canSignIn()) {
            log.info("막힌 계정의 리프레시 토큰을 거절한다: userId={}", tokenOwner.getUserId());
            refreshTokenService.revoke(refresh);
            expireRefreshCookie(response);
            return new ResponseEntity<>("account disabled", HttpStatus.BAD_REQUEST);
        }

        // 🔴 회전 — 받은 토큰을 비교 후 삭제하고 같은 묶음의 새 토큰을 받는다(docs/68). 예전에는 세션 키가 "있는가" 만 보고
        //    새 토큰을 새 키에 저장했다 — 옛 토큰은 수명(14일)까지 계속 쓸 수 있었고, 재사용을 알아채지 못했다.
        //    [JA] ローテーション — 受け取ったトークンを比較して削除し、同じファミリーの新トークンを受け取る。以前はセッションキーが
        //         「あるか」だけを見て新しいキーに保存しており、古いトークンは寿命(14 日)まで使え、再利用にも気づかなかった。
        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(refresh, tokenOwner.getUserId());
        if (!rotation.ok()) {
            expireRefreshCookie(response);
            // 400 — 프론트의 갱신은 200 이 아니면 로그아웃으로 간다(TokenRefresher). 재사용이든 옛 토큰이든 다시 로그인이 답이다.
            // [JA] 400 — フロントの更新は 200 でなければログアウトへ行く(TokenRefresher)。再利用でも古いトークンでも再ログインが答えだ。
            return new ResponseEntity<>(switch (rotation.kind()) {
                case LEGACY, SESSION_ENDED -> "refresh token expired";   // 회전 이전 토큰 · 절대 수명 — 사용자에게는 "만료" 와 같다 ／ [JA] ローテーション以前のトークン · 絶対寿命 — ユーザーにとっては「期限切れ」と同じ
                default -> "invalid refresh token";
            }, HttpStatus.BAD_REQUEST);
        }

        User user = tokenOwner;

        ObjectMapper om = new ObjectMapper();

        LoginResponse loginInfo = userService.getLoginInfoByEmail(email);


        String data;
        try {
            data = om.writeValueAsString(loginInfo);
            response.setContentType("application/json");
            response.setCharacterEncoding("utf-8");


            String newAccess = jwtUtils.generateTokenFromUser(user);

            log.info("토큰 재발급 성공 ({})", rotation.kind());

            response.setHeader(TokenType.ACCESS.getType(), newAccess);
            // 쿠키도 토큰과 같이 끝나게 — 절대 수명이 가까우면 14일보다 짧다(docs/68 §12).
            // [JA] Cookie もトークンと一緒に終わるように — 絶対寿命が近ければ 14 日より短い(docs/68 §12)。
            response.addCookie(jwtUtils.createCookie(TokenType.REFRESH.getType(), rotation.token(), jwtUtils.remainingLifetime(rotation.token())));

         } catch (JsonProcessingException e) {
            // TODO Auto-generated catch block
            response.setStatus(401);
        }

        return new ResponseEntity<>(HttpStatus.OK);
    }

    /** 쓸 수 없는 리프레시 쿠키를 브라우저에서 지운다 — 같은 속성(Path·Secure·HttpOnly)에 Max-Age 0. */
    // [JA] 使えないリフレッシュ Cookie をブラウザから消す — 同じ属性(Path・Secure・HttpOnly)で Max-Age 0。
    private void expireRefreshCookie(HttpServletResponse response) {
        response.addCookie(jwtUtils.createCookie(TokenType.REFRESH.getType(), null, java.time.Duration.ZERO));
    }



    // @PostMapping("/token/delete")
    // public ResponseEntity<?> delete(HttpServletRequest request, HttpServletResponse response, 
    // @AuthenticationPrincipal UserDetails userDetails) {

    //     if (null != userDetails ) {
        
    //       Cookie cookie = jwtUtils.createCookie(TokenType.REFRESH.getType(), null, 0);

    //       response.addCookie(cookie);
          
    //       return new ResponseEntity<>(HttpStatus.OK);   
    //     }
    //     else {                    
    //         return ResponseEntity.status(HttpStatus.NOT_FOUND)
    //                 .body(StatusMessages.USER_NOT_FOUND);
    //     }            

    // }


}
