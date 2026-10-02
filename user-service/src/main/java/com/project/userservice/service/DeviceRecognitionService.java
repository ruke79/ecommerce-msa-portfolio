package com.project.userservice.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.userservice.model.KnownDevice;
import com.project.userservice.repository.jpa.KnownDeviceRepository;
import com.project.userservice.security.DeviceIdentity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 이 로그인이 처음 보는 기기인가.
 *
 * <p>{@code SECURITY_ALERT} 를 만들어 내는 유일한 판단이다. 알림 자체는
 * {@code SecurityAlertProducer} 가 보내고, 여기서는 <b>보낼지 말지</b>만 정한다.
 *
 * <p><b>알림은 조용히 틀리기 쉽다.</b> 안 나가면 계정이 털려도 모르고, 너무 자주 나가면
 * 사용자가 무시하게 되어 진짜 경고까지 묻힌다. 그래서 두 가지를 못박았다:
 *
 * <ul>
 *   <li><b>첫 기기에는 알리지 않는다.</b> 가입하고 처음 로그인한 사람에게 "새로운 기기"
 *       는 말이 안 된다 — 비교할 기기가 없다. 첫인상이 쓸데없는 경고면 나중의 진짜
 *       경고도 무시한다.</li>
 *   <li><b>기기 수에 상한을 둔다.</b> 상한이 없으면 쿠키를 지우는 브라우저 하나가 이
 *       사용자의 행을 무한히 늘린다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceRecognitionService {

    /**
     * 한 사용자가 기억되는 기기 수.
     *
     * <p>넘으면 가장 오래 안 쓴 것부터 지운다. 지워진 기기로 다시 로그인하면 새 기기로
     * 잡혀 알림이 한 번 더 나간다 — 그게 맞는 동작이다. 20대나 되는 기기를 번갈아 쓰는
     * 계정이라면 그 자체가 살펴볼 만한 신호다.
     */
    @Value("${user.device.max-per-user:20}")
    private int maxPerUser;

    private final KnownDeviceRepository knownDeviceRepository;

    /**
     * 로그인한 기기를 기록하고, 처음 보는 기기면 알려야 한다고 답한다.
     *
     * @param presentedToken 브라우저가 보낸 기기 쿠키 값. 없으면 null.
     * @return 기록 결과 — 호출자가 쿠키를 심고 알림을 보낸다
     */
    @Transactional
    public Recognition recognize(Long userId, String presentedToken, String userAgent, String rawIp) {
        String label = DeviceIdentity.label(userAgent);
        String maskedIp = DeviceIdentity.maskIp(rawIp);
        LocalDateTime now = LocalDateTime.now();

        if (presentedToken != null) {
            Optional<KnownDevice> known = knownDeviceRepository
                    .findByUserIdAndTokenHash(userId, DeviceIdentity.hash(presentedToken));
            if (known.isPresent()) {
                KnownDevice device = known.get();
                device.touch(label, maskedIp, now);
                // 아는 기기다. 쿠키를 다시 심지 않는다 — 값이 그대로이고, 매 로그인마다
                // Set-Cookie 를 보낼 이유가 없다.
                return Recognition.known(device);
            }
        }

        // 쿠키가 없거나, 있어도 이 사용자에게는 처음인 값이다. 후자는 흔하다 —
        // 한 브라우저를 두 사람이 쓰면 두 번째 사람에게는 새 기기다.
        boolean firstEver = !knownDeviceRepository.existsByUserId(userId);
        String token = presentedToken != null ? presentedToken : DeviceIdentity.newToken();

        KnownDevice device = save(userId, token, label, maskedIp, now);
        if (device == null) {
            // 같은 사용자가 동시에 두 번 로그인해 UNIQUE 에 걸렸다. 다른 쪽이 이미
            // 기록했고 알림도 그쪽이 보낸다 — 여기서 또 보내면 알림이 두 개다.
            return Recognition.known(null);
        }

        enforceLimit(userId);

        // 🔴 첫 기기에는 알리지 않는다(클래스 주석).
        return firstEver ? Recognition.firstDevice(device, token) : Recognition.newDevice(device, token);
    }

    /**
     * 이 브라우저가 이 사용자의 <b>아는 기기</b>인가 — 기록하지 않고 보기만 한다(위험 기반 로그인, docs/68 §19).
     * 확인 코드를 통과하기 전에는 기기를 기록하지 않는다 — 기록하면 다음 시도부터 "아는 기기" 가 되어 확인을 건너뛴다.
     */
    // [JA] このブラウザがこのユーザーの既知の端末か — 記録せずに見るだけ。確認コードを通るまで端末を記録しない。
    @Transactional(readOnly = true)
    public boolean isKnown(Long userId, String presentedToken) {
        return presentedToken != null
                && knownDeviceRepository.findByUserIdAndTokenHash(userId, DeviceIdentity.hash(presentedToken)).isPresent();
    }

    private KnownDevice save(Long userId, String token, String label, String maskedIp, LocalDateTime now) {
        try {
            return knownDeviceRepository.save(KnownDevice.builder()
                    .userId(userId)
                    .tokenHash(DeviceIdentity.hash(token))
                    .label(label)
                    .lastIp(maskedIp)
                    .firstSeenAt(now)
                    .lastSeenAt(now)
                    .build());
        } catch (DataIntegrityViolationException e) {
            log.debug("같은 기기가 동시에 기록됐다 — 건너뛴다. userId={}", userId);
            return null;
        }
    }

    /** 상한을 넘으면 가장 오래 안 쓴 것부터 지운다. */
    private void enforceLimit(Long userId) {
        int limit = Math.max(1, maxPerUser);
        List<KnownDevice> devices = knownDeviceRepository.findByUserIdOrderByLastSeenAtDesc(userId);
        if (devices.size() <= limit) {
            return;
        }
        List<KnownDevice> stale = devices.subList(limit, devices.size());
        knownDeviceRepository.deleteAll(stale);
        log.info("기기 기록 상한 정리: userId={}, {}건 삭제", userId, stale.size());
    }

    /** 이 사용자가 로그인한 적 있는 기기들 — 화면 목록. */
    @Transactional(readOnly = true)
    public List<KnownDevice> devicesOf(Long userId) {
        return knownDeviceRepository.findByUserIdOrderByLastSeenAtDesc(userId);
    }

    /**
     * 기기 기억을 지운다.
     *
     * <p><b>세션을 끊지는 않는다.</b> 그 기기에 로그인된 상태가 있으면 그대로 남는다 —
     * 이 동작의 뜻은 "다음에 그 기기에서 로그인하면 새 기기로 보고 알린다" 뿐이다.
     * 화면 문구도 그렇게 적어야 한다. "로그아웃시킨다"로 읽히면 사용자는 조치를 했다고
     * 믿고 더 이상 아무것도 하지 않는다.
     *
     * <p>🔒 남의 기기를 지울 수 없도록 <b>소유자까지 함께</b> 조회한다. id 만으로 지우면
     * 번호를 훑어 남의 기기 기록을 지울 수 있다.
     *
     * @return 그런 기기가 있었으면 true
     */
    @Transactional
    public boolean forget(Long userId, Long deviceId) {
        Optional<KnownDevice> device = knownDeviceRepository.findByDeviceIdAndUserId(deviceId, userId);
        if (device.isEmpty()) {
            return false;
        }
        knownDeviceRepository.delete(device.get());
        log.info("기기 기억 삭제: userId={}, deviceId={}", userId, deviceId);
        return true;
    }

    /**
     * 기기 인식 결과.
     *
     * @param device     기록된 기기. 동시 로그인으로 다른 쪽이 먼저 기록했으면 null
     * @param cookieToken 브라우저에 심어야 할 값. 이미 아는 기기면 null(다시 심지 않는다)
     * @param shouldAlert 새 기기 알림을 보내야 하는가
     */
    public record Recognition(KnownDevice device, String cookieToken, boolean shouldAlert) {

        static Recognition known(KnownDevice device) {
            return new Recognition(device, null, false);
        }

        /** 이 사용자의 첫 기기 — 기록은 하되 알리지 않는다. */
        static Recognition firstDevice(KnownDevice device, String token) {
            return new Recognition(device, token, false);
        }

        static Recognition newDevice(KnownDevice device, String token) {
            return new Recognition(device, token, true);
        }
    }
}
