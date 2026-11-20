package com.project.notification_service.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.notification_service.domain.NotificationPreference;
import com.project.notification_service.domain.PhoneVerification;
import com.project.notification_service.repository.NotificationPreferenceRepository;
import com.project.notification_service.repository.PhoneVerificationRepository;
import com.project.notification_service.sms.PhoneNumbers;
import com.project.notification_service.sms.PhoneNumbers.Region;
import com.project.notification_service.sms.SmsResult;
import com.project.notification_service.sms.SmsSender;
import com.project.notification_service.sms.SmsTemplates;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * SMS 인증번호 발급·검증. 번호 등록과 로그인 2단계 인증이 같은 경로를 쓴다.
 *
 * <p>이 클래스는 대부분 <b>남용을 막는 코드</b>다. 인증 SMS 는 공격자에게 매력적인 표적이다:
 *
 * <ul>
 *   <li><b>문자 폭탄(SMS bombing).</b> 남의 번호를 넣고 발송을 반복하면 그 사람은 문자 폭탄을
 *       맞고 <b>요금은 우리가 낸다.</b> 세 겹으로 제한한다 — 번호당(재전송 60초·하루 10회), <b>계정당</b>(하루 10회, 번호를
 *       바꿔 가며 보내도), <b>서비스 전체</b>(하루 상한 — 요금의 천장).</li>
 *   <li><b>무차별 대입.</b> 6자리는 100만 가지뿐이다. 코드당 5회로 제한하고, 틀린 횟수가 차면
 *       코드를 폐기한다.</li>
 *   <li><b>타이밍 공격.</b> 코드 비교를 일반 문자열 비교로 하면 일치하는 앞자리 수에 따라 응답
 *       시간이 달라져 한 자리씩 알아낼 수 있다. 상수 시간 비교를 쓴다.</li>
 * </ul>
 *
 * <p>속도 제한을 Redis 에 두는 이유: 서비스 레플리카가 2개 이상이므로(docs/21) 인메모리
 * 카운터는 레플리카 수만큼 제한이 느슨해진다. 요금이 걸린 제한을 그렇게 둘 수는 없다.
 *
 * <p>[JA] SMS 認証番号の発行・検証。番号登録とログイン二段階認証が同じ経路を使う。
 * このクラスは大部分が<b>濫用を防ぐコード</b>だ。認証 SMS は攻撃者にとって魅力的な標的である: <b>SMS
 * 爆撃(SMS bombing)</b> — 他人の番号を入れて送信を繰り返すと、その人は SMS
 * 爆撃を受け<b>料金は我々が払う。</b>三重に制限する — 番号ごと(再送 60 秒・一日 10 回)、
 * <b>アカウントごと</b>(一日 10 回、番号を変えながら送っても)、<b>サービス全体</b>(一日の上限 —
 * 料金の天井)。<b>総当たり</b> — 6 桁は 100 万通りしかない。コードごとに 5 回に制限し、
 * 誤り回数が満ちたらコードを破棄する。<b>タイミング攻撃</b> — コード比較を通常の文字列比較で行うと、
 * 一致する先頭桁数によって応答時間が変わり一桁ずつ割り出せる。定数時間比較を使う。レート制限を Redis
 * に置く理由: サービスのレプリカが 2 つ以上なので(docs/21)、インメモリのカウンタはレプリカ数だけ制限が緩く
 * なる。料金のかかった制限をそうしておくことはできない。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationService {

    /** 같은 번호로 이 시간 안에 다시 발급받을 수 없다. 사용자의 "재전송" 연타도 여기서 걸린다. */
    // [JA] 同じ番号でこの時間内に再発行は受けられない。ユーザーの「再送」連打もここで引っかかる。
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);

    /** 하루에 같은 번호로 발급할 수 있는 최대 횟수. */
    // [JA] 一日に同じ番号で発行できる最大回数。
    private static final int DAILY_LIMIT_PER_PHONE = 10;

    /**
     * 🔒 한 계정이 하루에 받을 수 있는 발급 횟수 — <b>번호와 무관하게.</b>
     *
     * <p>🐛 번호 단위 제한뿐이었다(docs/38 §1.6). 번호를 바꿔 가며 요청하면 번호마다 하루 10회씩, 계정 하나로 사실상 무제한
     * 실발송을 할 수 있었고 요금은 우리가 낸다. 정상 사용(번호 등록 한두 번, 2단계 인증 몇 번)은 이 안에 들어온다.
     *
     * <p>[JA] 🔒 一アカウントが一日に受けられる発行回数 — <b>番号とは無関係に。</b>🐛
     * 番号単位の制限だけだった(docs/38 §1.6)。番号を変えながら要求すれば番号ごとに一日 10 回ずつ、
     * アカウント一つで事実上無制限の実送信ができ、料金は我々が払う。正常な使用(番号登録を一、二回、
     * 二段階認証を数回)はこの範囲に収まる。
     */
    static final int DAILY_LIMIT_PER_USER = 10;

    /**
     * 서비스 전체의 하루 발급 상한 — 요금의 천장. 계정을 여럿 만들어 도는 공격이나 버그로 인한 폭주가 청구서로 번지기 전에 멈춘다.
     * 걸리면 ERROR 로그(경보 대상). 0 이하면 끈다.
     *
     * <p>[JA] サービス全体の一日の発行上限 — 料金の天井。アカウントを多数作って回す攻撃やバグによる暴走が請求書に波
     * 及する前に止める。引っかかると ERROR ログ(警報対象)。0 以下なら無効。
     */
    @Value("${notification.verification.daily-global-limit:1000}")
    private int dailyGlobalLimit = 1000;

    private final PhoneVerificationRepository verificationRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final SmsSender smsSender;
    private final StringRedisTemplate redis;

    private final SecureRandom random = new SecureRandom();

    /**
     * 코드 해시에 섞는 값. 이것이 없으면 6자리 코드의 해시는 100만 개짜리 무지개 표로 즉시
     * 역산된다 — 해시로 저장한 의미가 사라진다.
     *
     * <p>[JA] コードハッシュに混ぜる値。これがないと 6 桁コードのハッシュは 100
     * 万件のレインボーテーブルで即座に逆算される — ハッシュで保存した意味がなくなる。
     */
    @Value("${notification.verification.pepper:}")
    private String pepper;

    public sealed interface IssueResult {
        record Sent(String maskedPhone, int ttlMinutes) implements IssueResult {}
        record Rejected(String reason) implements IssueResult {}
        record TooMany(String reason, long retryAfterSeconds) implements IssueResult {}
    }

    public sealed interface VerifyResult {
        record Verified(String e164) implements VerifyResult {}
        record Rejected(String reason, int attemptsLeft) implements VerifyResult {}
    }

    /**
     * 인증번호를 발급하고 SMS 로 보낸다.
     *
     * @param rawPhone      사용자 입력 번호
     * @param defaultRegion 국가코드가 없을 때 기준
     * @param userId        로그인 사용자면 그 id, 아니면 null
     *
     * <p>트랜잭션을 두지 않는다 — 코드 저장은 리포지토리의 짧은 트랜잭션으로 끝나고, SMS 발송(게이트웨이 타임아웃 5초)은 DB 커넥션
     * 없이 한다. 🐛 예전에는 메서드 전체가 트랜잭션이라 발송 동안 커넥션을 쥐었다(풀 5개 — 게이트웨이가 느리면 동시 요청 몇 개로
     * 리스너 · API 가 함께 멈췄다).
     * [JA] 認証番号を発行し SMS で送る。引数: rawPhone は利用者が入力した番号、defaultRegion は国番号がないときの基準、userId は
     * ログイン利用者ならその id、でなければ null。
     * トランザクションを置かない — コード保存はリポジトリの短いトランザクションで終わり、SMS 送信(ゲートウェイのタイムアウト 5 秒)は
     * DB 接続なしで行う。🐛 以前はメソッド全体がトランザクションで、送信の間ずっと接続を握っていた(プール 5 個 — ゲートウェイが遅いと
     * 同時リクエスト数個でリスナー · API が一緒に止まった)。
     */
    public IssueResult issue(String rawPhone, Region defaultRegion, Long userId) {

        Optional<String> normalized = PhoneNumbers.toE164(rawPhone, defaultRegion);
        if (normalized.isEmpty()) {
            // 어떤 이유로 거절됐는지 자세히 알려주지 않는다 — "이 번호는 존재한다/안 한다"를
            // 알려주는 것 자체가 번호 대역 탐색에 쓰일 수 있다.
            // [JA] どういう理由で拒否されたかを詳しく知らせない — 「この番号は存在する/しない」
            //      を知らせること自体が番号帯の探索に使われうる。
            return new IssueResult.Rejected("휴대폰 번호 형식이 올바르지 않습니다");
        }
        String e164 = normalized.get();
        Region region = PhoneNumbers.regionOfE164(e164).orElse(defaultRegion);
        return issueTo(e164, region, userId, PhoneVerification.Purpose.PHONE);
    }

    /**
     * 발급의 공통 몸통 — 속도 제한 · 코드 생성 · 저장 · 발송. 번호 등록과 로그인 2단계 인증이 같은 제한(번호 · 계정 · 전체)을 함께 쓴다:
     * 로그인 코드를 따로 세면 비밀번호를 아는 공격자가 그 칸으로 요금을 쓰게 된다.
     * [JA] 発行の共通部 — レート制限 · コード生成 · 保存 · 送信。番号登録とログイン二段階認証が同じ制限を共有する。
     */
    private IssueResult issueTo(String e164, Region region, Long userId, PhoneVerification.Purpose purpose) {

        // ── 속도 제한 ────────────────────────────────────────────────────────
        // [JA] ── レート制限 ──
        String cooldownKey = "sms:cooldown:" + e164;
        Long ttl = redis.getExpire(cooldownKey);
        if (ttl != null && ttl > 0) {
            return new IssueResult.TooMany("잠시 후 다시 시도해 주세요", ttl);
        }

        // 계정 한도를 번호 한도보다 먼저 본다 — 번호를 바꿔 가며 보내는 것이 이 한도의 대상이다.
        // [JA] アカウントの上限を番号の上限より先に見る — 番号を変えながら送ることがこの上限の対象だ。
        if (userId != null) {
            long perUser = countToday("sms:user-daily:" + userId);
            if (perUser > DAILY_LIMIT_PER_USER) {
                log.warn("인증번호 계정 일일 한도 초과: userId={} count={}", userId, perUser);
                return new IssueResult.TooMany("오늘 요청 가능한 횟수를 초과했습니다", 24 * 3600);
            }
        }

        long count = countToday("sms:daily:" + e164);
        if (count > DAILY_LIMIT_PER_PHONE) {
            log.warn("인증번호 일일 한도 초과: phone={} count={}", PhoneNumbers.mask(e164), count);
            return new IssueResult.TooMany("오늘 요청 가능한 횟수를 초과했습니다", 24 * 3600);
        }

        if (dailyGlobalLimit > 0) {
            String day = java.time.LocalDate.now(ZoneOffset.UTC).toString();
            long global = countToday("sms:global-daily:" + day);
            if (global > dailyGlobalLimit) {
                // 경보 대상 — 공격이거나 폭주다. 정상 트래픽이 닿았다면 상한을 올린다(notification.verification.daily-global-limit).
                // [JA] 警報対象 — 攻撃か暴走だ。正常トラフィックが達したなら上限を上げ
                //      る(notification.verification.daily-global-limit)。
                log.error("인증 SMS 전체 일일 상한 도달 — 발송을 멈췄다: count={} limit={}", global, dailyGlobalLimit);
                return new IssueResult.TooMany("잠시 후 다시 시도해 주세요", 3600);
            }
        }

        // 쿨다운을 **먼저 잡는다**(SET NX EX). 🐛 예전에는 위에서 남은 시간만 보고 발송 뒤에야 set 했다 — 동시 요청은 모두 위를
        // 통과해 같은 번호로 여러 통이 나갔다. 잡지 못했으면 다른 요청이 방금 보냈다. 발송이 실패하면 아래에서 푼다.
        // [JA] クールダウンを先に取る(SET NX EX)。以前は送信後に set しており、同時リクエストがすべて通って複数通送られていた。
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(cooldownKey, "1", RESEND_COOLDOWN))) {
            Long left = redis.getExpire(cooldownKey);
            return new IssueResult.TooMany("잠시 후 다시 시도해 주세요",
                    left != null && left > 0 ? left : RESEND_COOLDOWN.toSeconds());
        }

        // ── 코드 생성 ────────────────────────────────────────────────────────
        // SecureRandom 이어야 한다. java.util.Random 은 seed 를 알면 다음 값이 예측된다.
        // [JA] ── コード生成 ──
        //      SecureRandom でなければならない。java.util.Random は seed がわかれば次の値が予測される。
        String code = String.format("%06d", random.nextInt(1_000_000));

        verificationRepository.save(new PhoneVerification(e164, userId, hash(code), purpose));

        String text = SmsTemplates.verificationCode(region, code);
        SmsResult result = smsSender.send(e164, text);

        if (!result.isSent()) {
            // 발송이 실패했으면 쿨다운을 풀어 준다 — 사용자가 바로 다시 시도할 수 있어야 한다.
            // [JA] 送信が失敗したらクールダウンを解除する — ユーザーがすぐ再試行できるべきだ。
            redis.delete(cooldownKey);
            log.warn("인증 SMS 발송 실패: phone={} status={} detail={}",
                    PhoneNumbers.mask(e164), result.status(), result.detail());
            return new IssueResult.Rejected("인증번호를 보내지 못했습니다. 잠시 후 다시 시도해 주세요");
        }

        // ⚠️ 코드는 절대 로그에 남기지 않는다. 로그를 볼 수 있는 사람이 남의 인증을 통과할 수 있다.
        // [JA] ⚠️ コードは決してログに残さない。ログを見られる人が他人の認証を通過できてしまう。
        log.info("인증번호 발급: phone={} userId={} purpose={}", PhoneNumbers.mask(e164), userId, purpose);

        return new IssueResult.Sent(PhoneNumbers.mask(e164), PhoneVerification.TTL_MINUTES);
    }

    /**
     * 인증번호를 검증한다. 성공하면 코드는 소비되어 재사용할 수 없다.
     *
     * <p>[JA] 認証番号を検証する。成功するとコードは消費され再利用できない。
     */
    @Transactional
    public VerifyResult verify(String rawPhone, Region defaultRegion, String code, Long userId) {

        Optional<String> normalized = PhoneNumbers.toE164(rawPhone, defaultRegion);
        if (normalized.isEmpty()) {
            return new VerifyResult.Rejected("휴대폰 번호 형식이 올바르지 않습니다", 0);
        }
        String e164 = normalized.get();

        Optional<PhoneVerification> found = verificationRepository
                .findLatestForUpdate(e164, org.springframework.data.domain.PageRequest.of(0, 1)).stream().findFirst();

        if (found.isEmpty()) {
            return new VerifyResult.Rejected("인증번호를 다시 요청해 주세요", 0);
        }
        PhoneVerification verification = found.get();

        // 로그인한 사용자가 받은 코드는 그 사용자만 검증한다. 🐛 예전에는 번호만 보았다 — 남의 번호를 알면 일부러 5회 틀려 그 사람의
        // 코드를 계속 무효로 만들 수 있었다. 다른 사람의 시도는 횟수를 올리지 않는다.
        // [JA] ログインユーザーが受けたコードはそのユーザーだけが検証する。以前は番号だけを見ており、他人のコードを故意に 5 回
        //      間違えて無効にし続けられた。
        if (verification.getUserId() != null && !verification.getUserId().equals(userId)) {
            log.info("인증번호 검증 거부 — 발급받은 사용자가 아니다: phone={} userId={}", PhoneNumbers.mask(e164), userId);
            return new VerifyResult.Rejected("인증번호를 다시 요청해 주세요", 0);
        }

        if (!verification.isUsable()) {
            String reason = verification.isExpired() ? "인증번호가 만료되었습니다"
                    : "인증 시도 횟수를 초과했습니다. 다시 요청해 주세요";
            return new VerifyResult.Rejected(reason, 0);
        }

        if (!constantTimeEquals(hash(code), verification.getCodeHash())) {
            verification.recordFailedAttempt();
            int left = PhoneVerification.MAX_ATTEMPTS - verification.getAttempts();
            log.info("인증번호 불일치: phone={} 남은시도={}", PhoneNumbers.mask(e164), left);
            return new VerifyResult.Rejected("인증번호가 일치하지 않습니다", Math.max(0, left));
        }

        verification.consume();

        // 검증에 성공했으면 이 번호로 걸린 쿨다운은 풀어 준다 — 정상 사용자가 다음 단계에서
        // 막힐 이유가 없다.
        // [JA] 検証に成功したらこの番号にかかったクールダウンは解除する — 正常なユーザーが次の段階で詰まる理由がない。
        redis.delete("sms:cooldown:" + e164);

        log.info("인증 성공: phone={} userId={}", PhoneNumbers.mask(e164), verification.getUserId());
        return new VerifyResult.Verified(e164);
    }

    // ── 로그인 2단계 인증(SMS) — user-service 가 내부 호출로만 부른다(docs/24 §10) ─────────────────────
    // [JA] ── ログイン二段階認証(SMS) — user-service が内部呼び出しでのみ呼ぶ(docs/24 §10) ──

    /** 2단계 인증 코드 발송의 결과. user-service 는 {@code result} 로만 갈린다(응답은 늘 200 — 내부 호출의 실패와 섞이지 않게). */
    // [JA] 二段階認証コード送信の結果。user-service は {@code result} でのみ分岐する(応答は常に 200 —
    //      内部呼び出しの失敗と混ざらないように)。
    public enum TwoFactorResult {
        /** 보냈다. */
        // [JA] 送った。
        SENT,
        /** 맞았다 — 코드는 소비됐다. */
        // [JA] 合っていた — コードは消費された。
        VERIFIED,
        /** 틀렸다(남은 시도가 있으면 같은 코드로 다시). */
        // [JA] 誤り(残り試行があれば同じコードでもう一度)。
        INVALID,
        /** 살아 있는 코드가 없다(만료 · 소진 · 보낸 적 없음) — 다시 받는다. */
        // [JA] 生きているコードがない(期限切れ・使い切り・送ったことがない) — 受け取り直す。
        EXPIRED,
        /** 인증된 번호가 없다. */
        // [JA] 認証済みの番号がない。
        NO_PHONE,
        /** 등록된 번호가 2단계 인증을 켤 때의 번호와 다르다 — 그 번호로는 보내지도 받지도 않는다. */
        // [JA] 登録された番号が二段階認証を有効にしたときの番号と違う — その番号には送りも受けもしない。
        PHONE_CHANGED,
        /** 발송 제한(쿨다운 · 일일 한도). */
        // [JA] 送信制限(クールダウン・一日の上限)。
        TOO_MANY,
        /** 발송 실패(공급자 오류). */
        // [JA] 送信失敗(プロバイダーのエラー)。
        FAILED
    }

    /**
     * @param result      결과
     *                    [JA] 結果
     * @param maskedPhone 보낸 번호(마스킹) — 화면이 "어디로 갔는지" 보여 준다
     *                    [JA] 送った番号(マスキング) — 画面が「どこに届いたか」を見せる
     * @param binding     지금 등록된 번호의 결속값 — 켤 때 user-service 가 저장하고, 그 뒤로는 이것이 맞을 때만 보낸다
     *                    [JA] 今登録されている番号の結束値 — 有効化時に user-service が保存し、
     *                         以後はこれが合うときだけ送る
     * @param ttlMinutes  코드 유효 시간
     *                    [JA] コードの有効時間
     * @param retryAfterSeconds TOO_MANY 일 때 다시 요청할 수 있기까지
     *                          [JA] TOO_MANY のとき再要求できるまで
     * @param attemptsLeft INVALID 일 때 남은 시도
     *                     [JA] INVALID のときの残り試行
     */
    public record TwoFactorOutcome(TwoFactorResult result, String maskedPhone, String binding, int ttlMinutes,
            long retryAfterSeconds, int attemptsLeft) {

        static TwoFactorOutcome of(TwoFactorResult result) {
            return new TwoFactorOutcome(result, null, null, 0, 0, 0);
        }
    }

    /**
     * 로그인 2단계 인증 코드를 <b>등록된(인증을 마친) 번호로</b> 보낸다 — 번호를 요청에서 받지 않는다.
     *
     * <p>🔒 {@code binding} 이 있으면 지금 등록된 번호의 결속값과 같을 때만 보낸다. 2단계 인증을 켠 뒤 세션만 가진 사람(공용 PC ·
     * 탈취한 쿠키)이 보안 화면에서 번호를 <b>자기 번호로</b> 바꾸면, 로그인 코드가 그 사람에게 가 2단계 인증이 그 사람 것이 된다.
     * 켤 때의 번호에 묶어 두면 번호를 바꾸는 순간 SMS 수단은 멈추고(PHONE_CHANGED), 다시 켜려면 최근 로그인이 필요하다(user-service).
     * {@code binding} 이 없으면 켜는 중이다 — 지금 등록된 번호로 보내고 그 결속값을 돌려준다.
     *
     * <p>[JA] ログイン二段階認証のコードを登録済みの番号へ送る(番号はリクエストから受けない)。binding があれば今の番号の結束値と
     *      一致するときだけ送る — 有効化後にセッションだけを持つ者が番号を自分のものに変えると、コードがその人へ行くため。
     */
    public TwoFactorOutcome sendTwoFactor(Long userId, String binding) {
        Optional<NotificationPreference> preference = preferenceRepository.findByUserId(userId);
        if (preference.isEmpty() || !preference.get().isPhoneVerified() || preference.get().getPhoneE164() == null) {
            return TwoFactorOutcome.of(TwoFactorResult.NO_PHONE);
        }
        String e164 = preference.get().getPhoneE164();
        String current = bindingOf(e164);
        if (binding != null && !constantTimeEquals(current, binding)) {
            log.info("2단계 인증 SMS 거부 — 등록 번호가 켤 때와 다르다: userId={}", userId);
            return TwoFactorOutcome.of(TwoFactorResult.PHONE_CHANGED);
        }
        Region region = PhoneNumbers.regionOfE164(e164).orElse(Region.KR);
        IssueResult issued = issueTo(e164, region, userId, PhoneVerification.Purpose.LOGIN_2FA);
        if (issued instanceof IssueResult.Sent sent) {
            return new TwoFactorOutcome(TwoFactorResult.SENT, sent.maskedPhone(), current, sent.ttlMinutes(), 0, 0);
        }
        if (issued instanceof IssueResult.TooMany tooMany) {
            return new TwoFactorOutcome(TwoFactorResult.TOO_MANY, null, null, 0, tooMany.retryAfterSeconds(), 0);
        }
        return TwoFactorOutcome.of(TwoFactorResult.FAILED);
    }

    /**
     * 로그인 2단계 인증 코드를 확인한다 — 이 사용자에게 지금 등록된 번호로 보낸 가장 최근 LOGIN_2FA 코드만 본다(등록 코드는 아니다).
     * 맞으면 소비하고 결속값을 돌려준다(켤 때 user-service 가 저장한다). 시도 · 만료 · 1회용은 번호 등록 코드와 같다.
     *
     * <p>[JA] ログイン二段階認証のコードを確認する — このユーザーに今の番号で送った最新の LOGIN_2FA コードだけを見る。
     */
    @Transactional
    public TwoFactorOutcome verifyTwoFactor(Long userId, String binding, String code) {
        if (code == null || !code.matches("\\d{6}")) {
            return TwoFactorOutcome.of(TwoFactorResult.INVALID);
        }
        Optional<NotificationPreference> preference = preferenceRepository.findByUserId(userId);
        if (preference.isEmpty() || !preference.get().isPhoneVerified() || preference.get().getPhoneE164() == null) {
            return TwoFactorOutcome.of(TwoFactorResult.NO_PHONE);
        }
        String e164 = preference.get().getPhoneE164();
        String current = bindingOf(e164);
        if (binding != null && !constantTimeEquals(current, binding)) {
            return TwoFactorOutcome.of(TwoFactorResult.PHONE_CHANGED);
        }
        Optional<PhoneVerification> found = verificationRepository
                .findLatestTwoFactorForUpdate(userId, e164, org.springframework.data.domain.PageRequest.of(0, 1))
                .stream().findFirst();
        if (found.isEmpty() || !found.get().isUsable()) {
            return TwoFactorOutcome.of(TwoFactorResult.EXPIRED);
        }
        PhoneVerification verification = found.get();
        if (!constantTimeEquals(hash(code), verification.getCodeHash())) {
            verification.recordFailedAttempt();
            int left = Math.max(0, PhoneVerification.MAX_ATTEMPTS - verification.getAttempts());
            log.info("2단계 인증 SMS 코드 불일치: userId={} 남은시도={}", userId, left);
            return new TwoFactorOutcome(left > 0 ? TwoFactorResult.INVALID : TwoFactorResult.EXPIRED,
                    null, null, 0, 0, left);
        }
        verification.consume();
        log.info("2단계 인증 SMS 통과: userId={}", userId);
        return new TwoFactorOutcome(TwoFactorResult.VERIFIED, PhoneNumbers.mask(e164), current, 0, 0, 0);
    }

    /**
     * 번호의 결속값 — HMAC-SHA256(pepper, 번호). user-service 는 번호 대신 이것을 저장한다: 번호를 두 서비스에 복제하지 않고,
     * pepper 없이는 결속값에서 번호를 되짚지 못한다(번호는 경우의 수가 작아 그냥 SHA-256 이면 표로 역산된다).
     * [JA] 番号の結束値 — HMAC-SHA256(pepper, 番号)。user-service は番号の代わりにこれを保存する。
     */
    String bindingOf(String e164) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    ("2fa-phone:" + pepper).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(e164.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 을 사용할 수 없습니다", e);
        }
    }

    /** 하루짜리 카운터를 하나 올린다. 첫 증가에만 만료를 건다. null(파이프라인·트랜잭션 안에서만 온다)이면 0. */
    // [JA] 一日単位のカウンタを一つ上げる。最初の増加にだけ有効期限をかける。
    //      null(パイプライン・トランザクション内でのみ来る)なら 0。
    private long countToday(String key) {
        Long count = redis.opsForValue().increment(key);
        // 첫 증가 뒤 expire 전에 죽으면 TTL 없는 키가 남아 그 번호 · 계정이 영구히 막힌다 — 만료가 없으면(-1) 다시 건다.
        // [JA] 最初の増加の後 expire の前に落ちると TTL のないキーが残り永久に止まる — 期限がなければ(-1)掛け直す。
        if (count != null && (count == 1L || Long.valueOf(-1L).equals(redis.getExpire(key)))) {
            redis.expire(key, Duration.ofDays(1));
        }
        return count == null ? 0 : count;
    }

    /** 인증을 마친 번호를 사용자 설정에 등록한다. */
    // [JA] 認証を終えた番号をユーザー設定に登録する。
    @Transactional
    public NotificationPreference confirmPhoneForUser(Long userId, String e164) {
        NotificationPreference preference = preferenceRepository.findByUserId(userId)
                .orElseGet(() -> preferenceRepository.save(new NotificationPreference(userId)));

        Region region = PhoneNumbers.regionOfE164(e164).orElse(Region.KR);
        preference.confirmPhone(e164, region.code());
        return preference;
    }

    /**
     * 만료 코드 정리 — 매일. 개인정보(전화번호)를 필요 이상으로 보관하지 않는다.
     *
     * <p>🐛 예전에는 이 메서드를 부르는 곳이 없어(@Scheduled 없음) {@code phone_verification} 이 영구히 쌓였다. 삭제는 같은 조건이라
     * 파드가 여럿이어도 겹쳐서 해가 없다.
     * [JA] 期限切れコードの掃除 — 毎日。以前は呼ぶ所がなく phone_verification(電話番号)が永久に溜まっていた。
     */
    @Transactional
    @org.springframework.scheduling.annotation.Scheduled(cron = "${notification.verification.purge-cron:0 20 4 * * *}")
    public int purgeExpired() {
        return verificationRepository.deleteExpiredBefore(
                LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
    }

    /**
     * SHA-256(pepper + code). bcrypt 를 쓰지 않는 이유: 코드가 5분만 살고 시도가 5회로 제한되어
     * 있어 느린 해시의 이점이 없는 반면, 인증 요청마다 수백 ms 를 쓰게 된다. 대신 pepper 로
     * 무지개 표를 막는다 — pepper 없이 6자리를 해시하면 100만 개 표로 즉시 역산된다.
     *
     * <p>[JA] SHA-256(pepper + code)。bcrypt を使わない理由: コードは 5 分しか生きず試行も 5
     * 回に制限されているので遅いハッシュの利点がない一方、認証要求ごとに数百 ms を使うことになる。代わりに
     * pepper でレインボーテーブルを防ぐ — pepper なしで 6 桁をハッシュすると 100
     * 万件の表で即座に逆算される。
     */
    private String hash(String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(pepper.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다", e);
        }
    }

    /**
     * 상수 시간 비교. 일반 {@code equals} 는 첫 불일치에서 즉시 반환하므로, 응답 시간을 재면
     * 앞자리부터 한 글자씩 맞춰 나갈 수 있다.
     *
     * <p>[JA] 定数時間比較。通常の {@code equals} は最初の不一致で即座に返るので、
     * 応答時間を測ると先頭から一文字ずつ当てていける。
     */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
