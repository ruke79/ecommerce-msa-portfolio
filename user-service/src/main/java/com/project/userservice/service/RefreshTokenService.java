package com.project.userservice.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.common.constants.StatusMessages;
import com.project.userservice.message.producer.SecurityAlertProducer;
import com.project.userservice.model.User;
import com.project.userservice.model.redis.RefreshToken;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.repository.redis.RefreshTokenRepository;
import com.project.userservice.security.jwt.JwtUtils;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 리프레시 토큰 — 발급 · <b>회전</b> · 재사용 감지 · 묶음 끊기(docs/68).
 *
 * <h3>🐛 예전</h3>
 *
 * <p>갱신은 "받은 토큰의 세션 키가 Redis 에 <b>있는가</b>" 만 보고 새 토큰을 새 키에 저장했다 — <b>옛 키는 지우지 않았다.</b> 옛 토큰은
 * 수명(14일)이 끝날 때까지 계속 새 토큰을 받아 냈다. 한 번 로그인하고 열 번 갱신하면 살아 있는 리프레시 토큰이 열한 개였다. 이미 쓴 토큰이
 * 다시 와도 알아채지 못했고(재사용 감지 없음), 로그아웃은 <b>지금 낸 토큰 하나</b>의 키만 지워서 탈취된 옛 토큰은 로그아웃 뒤에도 살아 있었다.
 *
 * <h3>지금</h3>
 *
 * <ul>
 *   <li><b>회전</b> — 갱신할 때 옛 토큰을 비교 후 삭제한다(Redis Lua, 원자적). 한 토큰은 한 번만 쓸 수 있다.</li>
 *   <li><b>묶음</b> — 로그인할 때 묶음 id 를 만들고, 갱신한 토큰은 같은 묶음을 잇는다(JWT {@code fam}).</li>
 *   <li><b>재사용 감지</b> — 서명 · 만료가 멀쩡한데 이미 회전된 토큰이 오면, 누군가 토큰을 복사해 간 것이다. <b>묶음 전체를 끊는다</b>
 *       — 탈취한 쪽도 정상 사용자도 다시 로그인해야 한다. 어느 쪽이 도둑인지 서버는 모르므로, 둘 다 끊는 것이 표준이다(RFC 9700 §4.14.2).</li>
 *   <li><b>동시 갱신 유예</b> — 탭 두 개가 같은 토큰으로 거의 동시에 갱신하면 두 번째는 재사용처럼 보인다. 회전 직후 {@code grace-ms}
 *       동안은 옛 토큰에 <b>방금 발급한 새 토큰</b>을 돌려준다(새로 만들지 않는다).</li>
 *   <li><b>로그아웃 = 묶음 끊기.</b></li>
 *   <li><b>회전 이전 토큰(fam 없음)은 받지 않는다</b> — 배포 전의 옛 토큰들이 바로 문제의 그것이다. 배포 뒤 모두 한 번 다시 로그인한다.</li>
 * </ul>
 */
// [JA] リフレッシュトークン — 発行・ローテーション・再利用検知・ファミリー切断。以前は古いキーを消さず、古いトークンが寿命(14 日)まで
//      新トークンを受け取り続け、再利用も検知せず、ログアウトは提示したトークン一つのキーしか消さなかった。今は比較して削除(Lua・原子的)、
//      ファミリー(JWT fam)、再利用なら家族ごと切断(RFC 9700 §4.14.2)、同時更新の猶予、ログアウト = ファミリー切断、fam のない旧トークンは拒否。
@Transactional
@Slf4j
@Service
public class RefreshTokenService {

    /** 회전의 결과. 새 토큰은 ROTATED · GRACE 일 때만 있다. */
    public enum Kind {
        /** 이 요청이 회전했다 — 새 토큰 */
        ROTATED,
        /** 동시 갱신 — 다른 요청이 방금 발급한 새 토큰을 돌려준다 */
        GRACE,
        /** 이미 회전된 토큰이 다시 왔다 — 묶음을 끊었다 */
        REUSED,
        /** 끊긴 묶음의 토큰 */
        REVOKED,
        /** 회전 이전에 발급된 토큰(fam 없음) — 다시 로그인 */
        LEGACY,
        /** 저장된 값과 다르다 · 다른 요청이 회전 중인데 기다려도 끝나지 않았다 */
        REJECTED
    }

    /** 계수기 — Prometheus 에서는 {@code auth_refresh_total{result}}. {@code reused} 는 경보(RefreshTokenReuseDetected). */
    static final String METRIC = "auth.refresh";
    /** 모든 기기에서 로그아웃 횟수 — Prometheus {@code auth_logout_all_total}. */
    static final String LOGOUT_ALL_METRIC = "auth.logout.all";

    public record Rotation(Kind kind, String token) {
        public boolean ok() {
            return kind == Kind.ROTATED || kind == Kind.GRACE;
        }
    }

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtUtils jwtUtils;
    private final MeterRegistry meters;
    private final SecurityAlertProducer alerts;
    private final com.project.userservice.security.jwt.GraceTokenCipher graceCipher;
    private final long graceMs;
    private final long pendingWaitMs;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, UserRepository userRepository,
            JwtUtils jwtUtils, MeterRegistry meters, SecurityAlertProducer alerts,
            com.project.userservice.security.jwt.GraceTokenCipher graceCipher,
            @Value("${auth.refresh.grace-ms:20000}") long graceMs,
            @Value("${auth.refresh.pending-wait-ms:2000}") long pendingWaitMs) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.jwtUtils = jwtUtils;
        this.meters = meters;
        this.alerts = alerts;
        this.graceCipher = graceCipher;
        this.graceMs = graceMs;
        this.pendingWaitMs = pendingWaitMs;
    }

    @Transactional(readOnly = true)
    public Optional<RefreshToken> findByUserId(String sessionId, String userId) {
        return refreshTokenRepository.findByUserId(sessionId, userId);
    }

    public void deleteByKey(String userId) {
        refreshTokenRepository.delete(userId);
    }

    /** 새 로그인 — 새 묶음. 끝난 묶음을 사용자 색인에서 치운다(로그인은 드물어 여기서 해도 싸다). */
    public RefreshToken createRefreshToken(Long userId) {
        refreshTokenRepository.pruneUserFamilies(userId);
        return createRefreshToken(userId, UUID.randomUUID().toString());
    }

    /** 같은 묶음으로 발급하고, 묶음의 지금 세션으로 적는다. */
    public RefreshToken createRefreshToken(Long userId, String familyId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException(StatusMessages.USER_NOT_FOUND));
        String token = jwtUtils.generateRefreshToken(user, familyId);
        String sessionId = jwtUtils.getSessionIdFromJwtToken(token);

        RefreshToken refreshToken = new RefreshToken(sessionId, user.getEmail(), token);
        refreshTokenRepository.save(refreshToken);
        refreshTokenRepository.setFamilyCurrent(familyId, sessionId);
        refreshTokenRepository.addUserFamily(userId, familyId);
        return refreshToken;
    }

    /**
     * 갱신 — 받은 토큰을 회전한다. 서명 · 만료 · 종류 · 비밀번호 변경 확인은 호출자(컨트롤러)가 먼저 했다.
     *
     * @param presented 쿠키로 받은 리프레시 토큰(서명 확인됨)
     * @param userId    토큰 주인
     */
    // 트랜잭션 밖에서 — 유예를 기다리는 동안 DB 커넥션을 붙잡지 않는다(회전 판단은 Redis 에서 끝난다).
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Rotation rotate(String presented, Long userId) {
        String familyId = jwtUtils.getFamilyIdFromJwtToken(presented);
        String sessionId = jwtUtils.getSessionIdFromJwtToken(presented);
        if (familyId == null || familyId.isBlank()) {
            return outcome(Kind.LEGACY, null, userId);
        }
        if (refreshTokenRepository.isFamilyRevoked(familyId)) {
            return outcome(Kind.REVOKED, null, userId);
        }

        long r = refreshTokenRepository.rotate(sessionId, presented, graceMs);
        if (r == 1L) {
            RefreshToken next = createRefreshToken(userId, familyId);
            // 유예 키에는 새 토큰의 암호문을 둔다 — 해시는 되돌릴 수 없어 두 번째 탭에 줄 수 없고, 원문은 두지 않는다(§10).
            refreshTokenRepository.completeGrace(sessionId, graceCipher.seal(sessionId, next.getToken()), graceMs);
            return outcome(Kind.ROTATED, next.getToken(), userId);
        }
        if (r == -1L) {
            // 같은 세션 키에 다른 값 — 정상 흐름에서는 생기지 않는다(세션 id 는 토큰마다 새로 만든다). 끊지 않고 거절만.
            return outcome(Kind.REJECTED, null, userId);
        }

        // 키가 없다 — 방금 다른 요청이 회전했거나(유예), 이미 쓴 토큰이 다시 왔다(재사용).
        String grace = awaitGrace(sessionId);
        if (grace != null && !RefreshTokenRepository.PENDING.equals(grace)) {
            String same = graceCipher.open(sessionId, grace);
            // 풀리지 않는다 = 위변조 · 다른 세션의 값 · 키가 바뀌었다. 재사용 증거는 아니다 — 끊지 않고 거절만.
            return same == null ? outcome(Kind.REJECTED, null, userId) : outcome(Kind.GRACE, same, userId);
        }
        if (RefreshTokenRepository.PENDING.equals(grace)) {
            // 다른 요청이 회전 중인데 기다려도 끝나지 않았다 — 도둑이라고 단정할 근거가 없다. 끊지 않는다.
            return outcome(Kind.REJECTED, null, userId);
        }
        // "모든 기기에서 로그아웃" 과 부딪힌 경우 — 그쪽이 방금 이 묶음을 끊어 키가 사라진 것이다. 재사용이 아니다(알림 없음).
        // [JA] 「すべての端末からログアウト」と重なった場合 — 向こうがこのファミリーを切ったのでキーが消えた。再利用ではない。
        if (refreshTokenRepository.isFamilyRevoked(familyId)) {
            return outcome(Kind.REVOKED, null, userId);
        }
        refreshTokenRepository.revokeFamily(familyId);
        // 열려 있는 채팅 · 알림 연결도 끊는다 — 도둑이 이미 붙어 있으면 토큰 만료(최대 15분)까지 메시지를 받는다. 사용자 단위라 같은
        // 사람의 다른 기기도 닫히지만, 그 기기는 로그인이 살아 있어 갱신 뒤 조용히 다시 붙는다(docs/68 §9).
        refreshTokenRepository.cutoffLiveSessions(userId);
        // 계정 주인에게 알린다(앱 · 메일 · SMS, 수신 설정과 무관) — "로그인이 풀렸다" 를 보고 비밀번호를 바꾸게.
        // 주소는 DB 에서 — 토큰의 subject 를 믿지 않는다. 발행 실패는 응답을 바꾸지 않는다(프로듀서가 삼킨다).
        // [JA] アカウントの持ち主に知らせる(アプリ・メール・SMS、受信設定に関係なく)。宛先は DB から。
        userRepository.findById(userId)
                .ifPresent(u -> alerts.publishRefreshReuse(userId, u.getEmail(), familyId));
        return outcome(Kind.REUSED, null, userId);
    }

    /** 로그아웃 — 그 토큰의 묶음 전체를 끊는다(회전 이전 토큰이면 그 키만). */
    public void revoke(String presented) {
        String familyId = jwtUtils.getFamilyIdFromJwtToken(presented);
        if (familyId != null && !familyId.isBlank()) {
            refreshTokenRepository.revokeFamily(familyId);
        }
        refreshTokenRepository.delete(jwtUtils.getSessionIdFromJwtToken(presented));
    }

    /**
     * 모든 기기에서 로그아웃 — 이 사용자의 모든 로그인 묶음을 끊는다(docs/68 §8).
     *
     * <p>끊긴 기기는 <b>다음 갱신에서</b> 로그인 화면으로 간다. 이미 가진 액세스 토큰은 수명(15분)까지 유효하다 —
     * 게이트웨이는 서명만 보고 저장소를 보지 않는다(무상태 JWT 의 값). 그래서 "최대 15분 안에" 가 정직한 약속이다.
     *
     * @return 끊은 묶음 수(이미 끝난 것은 세지 않는다)
     */
    // [JA] すべての端末からログアウト — このユーザーの全ファミリーを切る。切られた端末は次の更新でログイン画面へ。
    //      手元のアクセストークンは寿命(15 分)まで有効(ゲートウェイは署名だけを見る)。
    public int revokeAll(Long userId) {
        int revoked = 0;
        for (String familyId : refreshTokenRepository.userFamilies(userId)) {
            if (!refreshTokenRepository.isFamilyRevoked(familyId)) {
                refreshTokenRepository.revokeFamily(familyId);
                revoked++;
            }
        }
        refreshTokenRepository.deleteUserFamilies(userId);
        // 열려 있는 채팅 · 알림 연결은 지금 닫는다(docs/68 §9) — HTTP 는 최대 15분, 긴 연결은 즉시.
        refreshTokenRepository.cutoffLiveSessions(userId);
        meters.counter(LOGOUT_ALL_METRIC).increment();
        log.info("[auth] 모든 기기에서 로그아웃 — userId={} 끊은 로그인={}", userId, revoked);
        return revoked;
    }

    /** 유예 키가 PENDING 이면 새 토큰이 적힐 때까지 잠깐 기다린다(회전한 요청은 보통 수십 ms 안에 끝난다). */
    private String awaitGrace(String sessionId) {
        String grace = refreshTokenRepository.grace(sessionId);
        long deadline = System.currentTimeMillis() + pendingWaitMs;
        while (RefreshTokenRepository.PENDING.equals(grace) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            grace = refreshTokenRepository.grace(sessionId);
        }
        return grace;
    }

    private Rotation outcome(Kind kind, String token, Long userId) {
        meters.counter(METRIC, "result", kind.name().toLowerCase(java.util.Locale.ROOT)).increment();
        if (kind == Kind.REUSED) {
            // 토큰 · 이메일은 남기지 않는다 — 사용자 id 만(docs/51).
            log.warn("[auth] 리프레시 토큰 재사용 감지 — 묶음을 끊었다(탈취 의심). userId={}", userId);
        } else if (kind == Kind.REVOKED || kind == Kind.REJECTED) {
            log.info("[auth] 리프레시 토큰 거절 — {} userId={}", kind, userId);
        }
        return new Rotation(kind, token);
    }
}
