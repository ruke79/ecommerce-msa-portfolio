package com.project.userservice.security;

import org.springframework.stereotype.Component;

import com.project.userservice.client.NotificationServiceClient;
import com.project.userservice.client.NotificationServiceClient.TwoFactorSmsRequest;
import com.project.userservice.client.NotificationServiceClient.TwoFactorSmsResponse;
import com.project.userservice.message.producer.SecurityAlertProducer;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.security.totp.TotpReplayGuard;
import com.project.userservice.service.UserService.TwoFactorChange;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 로그인 2단계 인증의 <b>두 번째 수단 — SMS</b>(docs/24 §10).
 *
 * <h3>무엇이 어디에 있나</h3>
 * <ul>
 *   <li>켜 둠 여부 · 켤 때의 번호 결속값 — 여기(users V20). 로그인이 이 서비스에 있으므로 로그인마다 다른 서비스에 묻지 않는다.</li>
 *   <li>번호 · 코드(해시 · 5분 · 5회 · 1회용) · 발송 제한 — notification(등록 번호로만 보낸다, 번호를 요청에서 받지 않는다).</li>
 * </ul>
 *
 * <h3>지키는 것</h3>
 * <ul>
 *   <li><b>시도는 TOTP 와 같은 사용자별 칸에서 센다</b>({@link TotpReplayGuard#reserveAttempt}) — 수단을 바꿔 가며 한도를 두 배로
 *       쓰지 못한다. 한도에 닿으면 TOTP 처럼 주인에게 알린다(비밀번호가 샜다는 신호).</li>
 *   <li><b>켤 때의 번호에 묶는다</b> — 그 뒤 보안 화면에서 번호를 바꾸면(세션만 가진 사람도 할 수 있다) SMS 수단은 멈춘다
 *       ({@link TwoFactorChange#PHONE_CHANGED}). 다시 켜려면 최근 로그인 + 새 번호로 받은 코드가 필요하다.</li>
 *   <li><b>notification 이 닿지 않으면 막는다</b>({@link TwoFactorChange#UNAVAILABLE}) — 통과시키지 않는다. 다른 계정의 로그인은 이
 *       호출이 없으므로 영향이 없고, TOTP 도 켜 둔 계정은 그것으로 들어온다.</li>
 * </ul>
 *
 * <p>트랜잭션을 두지 않는다 — 원격 호출(타임아웃 수 초) 동안 DB 커넥션을 쥐지 않게. 쓰기는 리포지토리의 짧은 트랜잭션이다.
 *
 * <p>[JA] ログイン二段階認証の<b>二つ目の手段 — SMS</b>(docs/24 §10)。
 *
 * <h3>[JA] 何がどこにあるか</h3>
 * <ul>
 *   <li>有効かどうか · 有効化時の番号の結束値 — ここ(users V20)。ログインがこのサービスにあるので、ログインのたびに他のサービスに問い合わせない。</li>
 *   <li>番号 · コード(ハッシュ · 5 分 · 5 回 · 1 回限り)· 送信制限 — notification(登録番号にだけ送る、番号をリクエストから受け取らない)。</li>
 * </ul>
 *
 * <h3>[JA] 守るもの</h3>
 * <ul>
 *   <li><b>試行は TOTP と同じユーザー単位の枠で数える</b>({@link TotpReplayGuard#reserveAttempt})— 手段を替えながら上限を二倍に
 *       使えない。上限に達すると TOTP と同様に持ち主に知らせる(パスワードが漏れた兆候)。</li>
 *   <li><b>有効化時の番号に結束する</b> — その後セキュリティ画面で番号を変えると(セッションだけ持つ人でもできる)SMS 手段は止まる
 *       ({@link TwoFactorChange#PHONE_CHANGED})。再び有効にするには最近のログイン + 新しい番号で受け取ったコードが必要だ。</li>
 *   <li><b>notification に届かなければ止める</b>({@link TwoFactorChange#UNAVAILABLE})— 通さない。他のアカウントのログインはこの
 *       呼び出しがないので影響がなく、TOTP も有効にしたアカウントはそれで入れる。</li>
 * </ul>
 *
 * <p>[JA] トランザクションを置かない — 遠隔呼び出し(タイムアウト数秒)の間 DB 接続を握らないように。書き込みはリポジトリの短いトランザクションだ。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmsTwoFactor {

    private final NotificationServiceClient notificationClient;
    private final UserRepository userRepository;
    private final TotpReplayGuard attempts;
    private final SecurityAlertProducer securityAlertProducer;

    /** 발송의 결과. */
    // [JA] 送信の結果。
    public enum SendStatus { SENT, NO_PHONE, PHONE_CHANGED, TOO_MANY, UNAVAILABLE }

    /**
     * @param maskedPhone       보낸 번호(마스킹)
     *     [JA] 送った番号(マスキング)
     * @param ttlMinutes        코드 유효 시간
     *     [JA] コードの有効時間
     * @param retryAfterSeconds TOO_MANY 일 때 다시 요청할 수 있기까지
     *     [JA] TOO_MANY のとき再要求できるまで
     */
    public record Sent(SendStatus status, String maskedPhone, int ttlMinutes, long retryAfterSeconds) {

        static Sent of(SendStatus status) {
            return new Sent(status, null, 0, 0);
        }
    }

    /**
     * 켜져 있는 SMS 수단으로 코드를 보낸다 — 로그인 2단계 · 끄기 확인. 켤 때의 번호 결속값을 싣는다.
     * [JA] 有効な SMS 手段でコードを送る — ログイン二段階目 · 無効化の確認。有効化時の結束値を載せる。
     */
    public Sent send(User user) {
        if (!user.isSmsTwoFactorEnabled()) {
            return Sent.of(SendStatus.NO_PHONE);
        }
        return send(user.getUserId(), user.getSmsTwoFactorBinding());
    }

    /**
     * 켜기 전 — 지금 등록된(인증을 마친) 번호로 보낸다. 부르는 쪽이 최근 로그인을 확인한다.
     * [JA] 有効化の前 — 今の登録番号へ送る。呼ぶ側が直近のログインを確認する。
     */
    public Sent sendForEnrollment(User user) {
        return send(user.getUserId(), null);
    }

    private Sent send(Long userId, String binding) {
        TwoFactorSmsResponse response;
        try {
            response = notificationClient.sendTwoFactorSms(new TwoFactorSmsRequest(userId, binding, null));
        } catch (RuntimeException e) {
            log.warn("2단계 인증 SMS 발송 요청이 notification 에 닿지 못했다: userId={} ({})", userId, e.getClass().getSimpleName());
            return Sent.of(SendStatus.UNAVAILABLE);
        }
        String result = response == null ? "" : String.valueOf(response.result());
        return switch (result) {
            case "SENT" -> new Sent(SendStatus.SENT, response.maskedPhone(), response.ttlMinutes(), 0);
            case "NO_PHONE" -> Sent.of(SendStatus.NO_PHONE);
            case "PHONE_CHANGED" -> Sent.of(SendStatus.PHONE_CHANGED);
            case "TOO_MANY" -> new Sent(SendStatus.TOO_MANY, null, 0, response.retryAfterSeconds());
            default -> Sent.of(SendStatus.UNAVAILABLE);   // FAILED(공급자 오류) · 모르는 값 ／ [JA] FAILED(プロバイダーエラー)・未知の値
        };
    }

    /**
     * 로그인 2단계 — 받은 코드를 확인한다. 시도는 확인 <b>전에</b> 사용자별 칸에서 센다.
     * [JA] ログイン二段階目 — 受け取ったコードを確認する。試行は確認の前にユーザー単位の枠で数える。
     */
    public TwoFactorChange verifyLogin(User user, String code) {
        if (!user.isSmsTwoFactorEnabled()) {
            return TwoFactorChange.NOT_ENABLED;
        }
        return check(user, user.getSmsTwoFactorBinding(), code).change();
    }

    /**
     * 켠다 — 지금 등록 번호로 받은 코드가 맞으면 그 번호의 결속값과 함께 저장하고 주인에게 알린다. 부르는 쪽이 최근 로그인을 확인한다.
     * [JA] 有効化 — 今の登録番号で受けたコードが合えば、その番号の結束値と共に保存し持ち主に知らせる。
     */
    public TwoFactorChange enable(Long userId, String code) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return TwoFactorChange.NOT_ENABLED;
        }
        if (user.isSmsTwoFactorEnabled()) {
            return TwoFactorChange.ALREADY_ENABLED;
        }
        Checked checked = check(user, null, code);
        if (checked.change() != TwoFactorChange.OK) {
            return checked.change();
        }
        if (checked.binding() == null || checked.binding().isBlank()) {
            // 맞았다는데 결속값이 없다 — 켜면 그 뒤로 번호 검사 없이 보내게 된다. 켜지 않는다.
            // [JA] 合ったというのに結束値がない — 有効にするとその後は番号チェックなしで送ることになる。有効にしない。
            log.error("2단계 인증 SMS 켜기 — 결속값 없는 통과 응답: userId={}", userId);
            return TwoFactorChange.UNAVAILABLE;
        }
        user.setSmsTwoFactorEnabled(true);
        user.setSmsTwoFactorBinding(checked.binding());
        userRepository.save(user);
        securityAlertProducer.publishTwoFactorChanged(userId, user.getEmail(), true);
        log.info("2단계 인증 SMS 켜짐: userId={}", userId);
        return TwoFactorChange.OK;
    }

    /**
     * 끈다 — 켤 때의 번호로 받은 지금 코드가 맞아야 한다(TOTP 끄기와 같은 생각, docs/68 §14). 번호가 바뀌어 받을 수 없으면 끌 수도 없다 —
     * 그 경우 SMS 수단은 이미 멈춰 있다(로그인에도 쓰이지 않는다).
     * [JA] 無効化 — 有効化時の番号で受けた今のコードが合う必要がある。
     */
    public TwoFactorChange disable(Long userId, String code) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || !user.isSmsTwoFactorEnabled()) {
            return TwoFactorChange.NOT_ENABLED;
        }
        TwoFactorChange checked = check(user, user.getSmsTwoFactorBinding(), code).change();
        if (checked != TwoFactorChange.OK) {
            return checked;
        }
        user.setSmsTwoFactorEnabled(false);
        user.setSmsTwoFactorBinding(null);
        userRepository.save(user);
        securityAlertProducer.publishTwoFactorChanged(userId, user.getEmail(), false);
        log.info("2단계 인증 SMS 꺼짐: userId={}", userId);
        return TwoFactorChange.OK;
    }

    private record Checked(TwoFactorChange change, String binding) {
    }

    private Checked check(User user, String binding, String code) {
        Long userId = user.getUserId();
        // 형식이 틀린 입력도 시도로 센다 — 세지 않으면 형식 검사가 한도 밖의 오라클이 된다(그럴 일은 없지만 셈을 한 곳에 둔다).
        // [JA] 形式の誤った入力も試行として数える — 数えないと形式チェックが上限外のオラクルになる(そうなることはないが、数えるのは一か所に置く)。
        long attempt = attempts.reserveAttempt(userId);
        if (attempt > attempts.maxFailures()) {
            return new Checked(TwoFactorChange.TOO_MANY_ATTEMPTS, null);
        }
        if (code == null || !code.matches("\\d{6}")) {
            return failed(user, attempt);
        }
        TwoFactorSmsResponse response;
        try {
            response = notificationClient.verifyTwoFactorSms(new TwoFactorSmsRequest(userId, binding, code));
        } catch (RuntimeException e) {
            log.warn("2단계 인증 SMS 확인 요청이 notification 에 닿지 못했다: userId={} ({})", userId, e.getClass().getSimpleName());
            return new Checked(TwoFactorChange.UNAVAILABLE, null);
        }
        String result = response == null ? "" : String.valueOf(response.result());
        switch (result) {
            case "VERIFIED":
                attempts.clearFailures(userId);
                return new Checked(TwoFactorChange.OK, response.binding());
            case "INVALID":
            case "EXPIRED":
                return failed(user, attempt);
            case "NO_PHONE":
                return new Checked(TwoFactorChange.NO_PHONE, null);
            case "PHONE_CHANGED":
                return new Checked(TwoFactorChange.PHONE_CHANGED, null);
            default:
                return new Checked(TwoFactorChange.UNAVAILABLE, null);
        }
    }

    private Checked failed(User user, long attempt) {
        log.info("2단계 인증 SMS 코드 확인 실패: userId={}, 실패={}", user.getUserId(), attempt);
        if (attempt == attempts.maxFailures()) {
            securityAlertProducer.publishAccountEvent(user.getUserId(), user.getEmail(),
                    SecurityAlertProducer.ALERT_2FA_FAILURES, null);
        }
        return new Checked(TwoFactorChange.INVALID_CODE, null);
    }
}
