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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationService {

    /** 같은 번호로 이 시간 안에 다시 발급받을 수 없다. 사용자의 "재전송" 연타도 여기서 걸린다. */
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);

    /** 하루에 같은 번호로 발급할 수 있는 최대 횟수. */
    private static final int DAILY_LIMIT_PER_PHONE = 10;

    /**
     * 🔒 한 계정이 하루에 받을 수 있는 발급 횟수 — <b>번호와 무관하게.</b>
     *
     * <p>🐛 번호 단위 제한뿐이었다(docs/38 §1.6). 번호를 바꿔 가며 요청하면 번호마다 하루 10회씩, 계정 하나로 사실상 무제한
     * 실발송을 할 수 있었고 요금은 우리가 낸다. 정상 사용(번호 등록 한두 번, 2단계 인증 몇 번)은 이 안에 들어온다.
     */
    static final int DAILY_LIMIT_PER_USER = 10;

    /**
     * 서비스 전체의 하루 발급 상한 — 요금의 천장. 계정을 여럿 만들어 도는 공격이나 버그로 인한 폭주가 청구서로 번지기 전에 멈춘다.
     * 걸리면 ERROR 로그(경보 대상). 0 이하면 끈다.
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
     */
    @Transactional
    public IssueResult issue(String rawPhone, Region defaultRegion, Long userId) {

        Optional<String> normalized = PhoneNumbers.toE164(rawPhone, defaultRegion);
        if (normalized.isEmpty()) {
            // 어떤 이유로 거절됐는지 자세히 알려주지 않는다 — "이 번호는 존재한다/안 한다"를
            // 알려주는 것 자체가 번호 대역 탐색에 쓰일 수 있다.
            return new IssueResult.Rejected("휴대폰 번호 형식이 올바르지 않습니다");
        }
        String e164 = normalized.get();
        Region region = PhoneNumbers.regionOfE164(e164).orElse(defaultRegion);

        // ── 속도 제한 ────────────────────────────────────────────────────────
        String cooldownKey = "sms:cooldown:" + e164;
        Long ttl = redis.getExpire(cooldownKey);
        if (ttl != null && ttl > 0) {
            return new IssueResult.TooMany("잠시 후 다시 시도해 주세요", ttl);
        }

        // 계정 한도를 번호 한도보다 먼저 본다 — 번호를 바꿔 가며 보내는 것이 이 한도의 대상이다.
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
                log.error("인증 SMS 전체 일일 상한 도달 — 발송을 멈췄다: count={} limit={}", global, dailyGlobalLimit);
                return new IssueResult.TooMany("잠시 후 다시 시도해 주세요", 3600);
            }
        }

        // ── 코드 생성 ────────────────────────────────────────────────────────
        // SecureRandom 이어야 한다. java.util.Random 은 seed 를 알면 다음 값이 예측된다.
        String code = String.format("%06d", random.nextInt(1_000_000));

        verificationRepository.save(new PhoneVerification(e164, userId, hash(code)));

        String text = SmsTemplates.verificationCode(region, code);
        SmsResult result = smsSender.send(e164, text);

        if (!result.isSent()) {
            // 발송이 실패했으면 쿨다운을 걸지 않는다 — 사용자가 바로 다시 시도할 수 있어야 한다.
            log.warn("인증 SMS 발송 실패: phone={} status={} detail={}",
                    PhoneNumbers.mask(e164), result.status(), result.detail());
            return new IssueResult.Rejected("인증번호를 보내지 못했습니다. 잠시 후 다시 시도해 주세요");
        }

        redis.opsForValue().set(cooldownKey, "1", RESEND_COOLDOWN);

        // ⚠️ 코드는 절대 로그에 남기지 않는다. 로그를 볼 수 있는 사람이 남의 인증을 통과할 수 있다.
        log.info("인증번호 발급: phone={} userId={}", PhoneNumbers.mask(e164), userId);

        return new IssueResult.Sent(PhoneNumbers.mask(e164), PhoneVerification.TTL_MINUTES);
    }

    /**
     * 인증번호를 검증한다. 성공하면 코드는 소비되어 재사용할 수 없다.
     */
    @Transactional
    public VerifyResult verify(String rawPhone, Region defaultRegion, String code) {

        Optional<String> normalized = PhoneNumbers.toE164(rawPhone, defaultRegion);
        if (normalized.isEmpty()) {
            return new VerifyResult.Rejected("휴대폰 번호 형식이 올바르지 않습니다", 0);
        }
        String e164 = normalized.get();

        Optional<PhoneVerification> found =
                verificationRepository.findFirstByPhoneE164AndConsumedFalseOrderByCreatedAtDesc(e164);

        if (found.isEmpty()) {
            return new VerifyResult.Rejected("인증번호를 다시 요청해 주세요", 0);
        }
        PhoneVerification verification = found.get();

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
        redis.delete("sms:cooldown:" + e164);

        log.info("인증 성공: phone={} userId={}", PhoneNumbers.mask(e164), verification.getUserId());
        return new VerifyResult.Verified(e164);
    }

    /** 하루짜리 카운터를 하나 올린다. 첫 증가에만 만료를 건다. null(파이프라인·트랜잭션 안에서만 온다)이면 0. */
    private long countToday(String key) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofDays(1));
        }
        return count == null ? 0 : count;
    }

    /** 인증을 마친 번호를 사용자 설정에 등록한다. */
    @Transactional
    public NotificationPreference confirmPhoneForUser(Long userId, String e164) {
        NotificationPreference preference = preferenceRepository.findByUserId(userId)
                .orElseGet(() -> preferenceRepository.save(new NotificationPreference(userId)));

        Region region = PhoneNumbers.regionOfE164(e164).orElse(Region.KR);
        preference.confirmPhone(e164, region.code());
        return preference;
    }

    /** 만료 코드 정리. 개인정보를 필요 이상으로 보관하지 않는다. */
    @Transactional
    public int purgeExpired() {
        return verificationRepository.deleteExpiredBefore(
                LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
    }

    /**
     * SHA-256(pepper + code). bcrypt 를 쓰지 않는 이유: 코드가 5분만 살고 시도가 5회로 제한되어
     * 있어 느린 해시의 이점이 없는 반면, 인증 요청마다 수백 ms 를 쓰게 된다. 대신 pepper 로
     * 무지개 표를 막는다 — pepper 없이 6자리를 해시하면 100만 개 표로 즉시 역산된다.
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
     */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
