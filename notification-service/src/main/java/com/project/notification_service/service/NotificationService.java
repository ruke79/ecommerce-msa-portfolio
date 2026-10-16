package com.project.notification_service.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.notification_service.domain.MailKind;
import com.project.notification_service.domain.Notification;
import com.project.notification_service.domain.NotificationPreference;
import com.project.notification_service.domain.NotificationType;
import com.project.notification_service.domain.SmsStatus;
import com.project.notification_service.repository.NotificationPreferenceRepository;
import com.project.notification_service.repository.NotificationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 알림 생성·조회.
 *
 * <p>알림을 만드는 것과 SMS 를 보내는 것을 <b>분리</b>한다. 이벤트를 받은 자리에서 바로
 * 문자를 보내면 두 가지가 곤란해진다:
 *
 * <ul>
 *   <li>SMS 게이트웨이가 느리거나 죽으면 Kafka 컨슈머가 그만큼 붙잡힌다 — 알림 하나 때문에
 *       전체 소비가 밀린다.</li>
 *   <li>전송 실패를 컨슈머 재시도로 처리하면 <b>알림이 중복 생성</b>된다. 아웃박스에서 겪은
 *       것과 같은 구조다(docs/21).</li>
 * </ul>
 *
 * <p>그래서 컨슈머는 알림을 저장만 하고 {@code PENDING} 으로 표시한다. 실제 발송은
 * {@link SmsDispatcher} 가 별도로 집어 간다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final MailService mailService;

    /**
     * 알림을 만든다. 같은 {@code sourceEventId} 로는 한 번만 만들어진다.
     *
     * @return 새로 만들어졌으면 그 알림, 이미 있으면 비어 있음
     */
    @Transactional
    public Optional<Notification> create(Long recipientId, NotificationType type,
                                         String title, String content, String url,
                                         String sourceEventId, Map<String, String> smsParams) {

        // 1차 방어 — 이미 있으면 조용히 넘어간다.
        if (sourceEventId != null && notificationRepository.findBySourceEventId(sourceEventId).isPresent()) {
            log.debug("이미 처리된 이벤트, 건너뜀: {}", sourceEventId);
            return Optional.empty();
        }

        SmsStatus smsStatus = resolveSmsStatus(recipientId, type);

        Notification notification = Notification.builder()
                .recipientId(recipientId)
                .type(type)
                .title(title)
                .content(content)
                .url(url)
                .sourceEventId(sourceEventId)
                .smsStatus(smsStatus)
                .build();

        Notification saved;
        try {
            saved = notificationRepository.saveAndFlush(notification);
        } catch (DataIntegrityViolationException e) {
            // 2차 방어 — 위 조회와 저장 사이에 다른 레플리카가 먼저 넣었다. UNIQUE 제약이
            // 막아 준 것이므로 정상 흐름으로 취급한다. 이게 없으면 레플리카 2개에서
            // 같은 문자가 두 통 나간다.
            log.debug("동시 생성 감지, 건너뜀: {}", sourceEventId);
            return Optional.empty();
        }

        // 알림이 실제로 만들어진 경우에만 메일을 건다. 위 UNIQUE 방어를 통과했다는 것은
        // 이 이벤트를 우리가 처음 처리했다는 뜻이므로, 메일도 여기서 한 번만 걸린다.
        enqueueMail(recipientId, type, title, url, sourceEventId);

        return Optional.of(saved);
    }

    /**
     * 같은 알림을 메일로도 보낸다.
     *
     * <p>SMS 와 달리 <b>여기서 상태를 알림 행에 적지 않는다.</b> 메일은 별도 테이블
     * ({@code mail_message})을 쓰기 때문이다 — 가입 확인처럼 알림 행이 아예 없는 메일도 있어서
     * 알림에 딸린 컬럼으로는 표현할 수 없다(MailMessage 클래스 주석 참조).
     *
     * <p><b>같은 트랜잭션 안에서 돈다.</b> 아래 {@code catch} 는 설정 조회 같은 평범한
     * 런타임 예외로 알림까지 롤백되는 것을 막지만, <b>제약 위반은 막지 못한다</b> — JPA 가
     * 트랜잭션을 rollback-only 로 표시해 버리므로 잡아도 커밋되지 않는다.
     *
     * <p>그래도 별도 트랜잭션({@code REQUIRES_NEW})으로 떼지 않았다. 여기까지 왔다는 것은
     * 알림 행이 자기 UNIQUE 를 통과했다는 뜻이고, 메일 키는 그 값에서 파생되므로 실제로 부딪힐
     * 일이 사실상 없다. 반면 {@code REQUIRES_NEW} 는 그 구간 동안 커넥션을 하나 더 붙잡아
     * 풀 총합 가드레일(docs/19 §3, B-32)에 그대로 영향을 준다 — 일어나지 않는 경우를 위해
     * 치를 비용이 아니다.
     */
    private void enqueueMail(Long recipientId, NotificationType type,
                             String title, String url, String sourceEventId) {
        NotificationPreference preference = preferenceRepository.findByUserId(recipientId).orElse(null);
        if (preference == null || !preference.shouldSendEmail(type)) {
            return;
        }

        MailKind kind = MailKind.forNotification(type).orElse(null);
        if (kind == null) {
            return;   // shouldSendEmail 이 이미 걸렀지만, 두 판정이 어긋나도 안전하게.
        }

        String locale = localeOf(preference.getRegion());
        Map<String, String> params = new HashMap<>();
        if (title != null) {
            // 제목은 한국어로 저장된다(NotificationTexts) — 일본 지역이면 메일에 넣기 전에 바꾼다(docs/60 §9).
            params.put("subject", com.project.common.i18n.ClientText.localize(title, java.util.Locale.forLanguageTag(locale)));
        }
        if (url != null) {
            params.put("url", url);
        }

        try {
            // 알림과 다른 멱등 키를 쓴다. 같은 값을 쓰면 두 테이블의 UNIQUE 가 서로를 막지는
            // 않지만, 로그와 운영 조회에서 "알림 id"와 "메일 id"가 같은 문자열을 가리켜 혼동된다.
            mailService.enqueue(
                    sourceEventId == null ? null : "mail:" + sourceEventId,
                    preference.getEmail(),
                    recipientId,
                    kind,
                    params,
                    // 설정의 지역(KR/JP)을 로케일(ko/ja)로 바꿔 넘긴다. 디스패처가 설정을 다시
                    // 보긴 하지만, 그 사이 사용자가 지역을 바꿔도 이 메일은 등록 시점의 언어로
                    // 나가야 한다 — 주문 알림이 갑자기 다른 언어로 오면 그게 더 이상하다.
                    locale);
        } catch (RuntimeException e) {
            log.warn("알림 메일을 큐에 넣지 못했습니다(알림 자체는 생성됨): recipientId={} type={} {}",
                    recipientId, type, e.getMessage());
        }
    }

    /** 설정의 지역 코드(KR/JP)를 문구 로케일(ko/ja)로. 모르는 값이면 null — 기본값에 맡긴다. */
    private static String localeOf(String region) {
        if (region == null) {
            return null;
        }
        return switch (region.toUpperCase()) {
            case "JP" -> "ja";
            case "KR" -> "ko";
            default -> null;
        };
    }

    /** 이 사용자·이 종류가 SMS 대상인지 판정한다. 번호가 없거나 설정이 꺼져 있으면 아니다. */
    private SmsStatus resolveSmsStatus(Long recipientId, NotificationType type) {
        return preferenceRepository.findByUserId(recipientId)
                .filter(pref -> pref.shouldSendSms(type))
                .map(pref -> SmsStatus.PENDING)
                .orElse(SmsStatus.NOT_APPLICABLE);
    }

    @Transactional(readOnly = true)
    public List<Notification> recent(Long recipientId, int size) {
        return notificationRepository.findByRecipientIdAndDeletedFalseOrderByCreatedAtDesc(
                recipientId, PageRequest.of(0, Math.min(size, 100)));
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long recipientId) {
        return notificationRepository.countByRecipientIdAndReadFalseAndDeletedFalse(recipientId);
    }

    /**
     * 알림을 읽음 처리한다.
     *
     * <p>수신자 확인이 필수다. id 만으로 처리하면 남의 알림 id 를 넣어 상태를 바꿀 수 있다
     * (IDOR). 조회 결과의 수신자가 요청자와 다르면 없는 것처럼 취급한다 — "그 id 는 존재한다"는
     * 사실조차 알려주지 않는다.
     */
    @Transactional
    public boolean markRead(Long recipientId, Long notificationId) {
        return notificationRepository.findById(notificationId)
                .filter(n -> n.getRecipientId().equals(recipientId))
                .map(n -> { n.markRead(); return true; })
                .orElse(false);
    }

    @Transactional
    public int markAllRead(Long recipientId) {
        List<Notification> unread =
                notificationRepository.findByRecipientIdAndDeletedFalseOrderByCreatedAtDesc(
                        recipientId, PageRequest.of(0, 500));
        int changed = 0;
        for (Notification n : unread) {
            if (!n.isRead()) {
                n.markRead();
                changed++;
            }
        }
        return changed;
    }

    @Transactional
    public boolean delete(Long recipientId, Long notificationId) {
        return notificationRepository.findById(notificationId)
                .filter(n -> n.getRecipientId().equals(recipientId))
                .map(n -> { n.markDeleted(); return true; })
                .orElse(false);
    }

    @Transactional
    public NotificationPreference preferenceOf(Long userId) {
        return preferenceRepository.findByUserId(userId)
                .orElseGet(() -> preferenceRepository.save(new NotificationPreference(userId)));
    }

    /**
     * 설정을 가져오면서 계정 주소를 기록한다.
     *
     * <p><b>왜 이렇게 채우나.</b> 알림 서비스는 사용자 테이블을 갖고 있지 않다. 메일을 보내려면
     * 주소가 필요한데, 그 주소를 얻는 경로는 셋뿐이다: (1) user-service 에 매번 물어보기 —
     * 알림마다 동기 호출이 붙는다, (2) 사용자 생성 이벤트를 구독해 복제하기 — 정공법이지만
     * 기존 사용자는 채워지지 않는다, (3) <b>게이트웨이가 붙여 주는 {@code User-Email} 헤더</b>.
     *
     * <p>(3)을 쓴다. 그 헤더는 게이트웨이가 JWT 에서 꺼내 붙이고, 클라이언트가 보낸 같은 이름의
     * 헤더는 게이트웨이가 지운다(docs/27) — 즉 위조할 수 없다. 사용자가 알림 목록이나 설정
     * 화면을 한 번이라도 열면 채워진다.
     *
     * <p>한계는 분명하다: <b>로그인 후 알림 API 를 한 번도 부르지 않은 사용자에게는 알림 메일이
     * 나가지 않는다.</b> 가입 확인·비밀번호 재설정은 주소가 명령에 실려 오므로 영향받지 않는다.
     */
    @Transactional
    public NotificationPreference preferenceOf(Long userId, String email) {
        NotificationPreference preference = preferenceOf(userId);
        preference.rememberEmail(email);
        return preference;
    }

    /**
     * 알림 설정을 바꾼다 — 한 트랜잭션에서 읽고 고치고 저장한다. 들어온 키만 바꾼다.
     *
     * <p>🐛 예전: 컨트롤러가 {@link #preferenceOf(Long, String)} 로 받은 엔티티에 세터를 불렀다. 그 트랜잭션은 반환할 때 이미 커밋돼,
     * 세터는 트랜잭션 밖이었다 — open-in-view 는 트랜잭션 밖의 변경을 flush 하지 않으므로 응답은 200 과 새 값인데 DB 는 그대로였다
     * ("알림을 껐는데 계속 온다 · 새로 고치면 되돌아간다", 2단계 인증 켜기도). 테스트가 없었다(PreferenceUpdateTest).
     *
     * <p>2단계 인증을 켜려는데 인증된 번호가 없으면 아무것도 바꾸지 않고 빈 값(→ 409) — 일부만 저장된 채 실패를 돌려주지 않는다.
     */
    // [JA] 通知設定を変える — 一つのトランザクションで読み・直し・保存する。以前はコントローラーがトランザクションの外でセッターを呼び、
    //      200 と新しい値を返すのに DB は元のままだった。二段階認証を番号なしで有効にしようとしたら何も変えず空(409)。
    @Transactional
    public java.util.Optional<NotificationPreference> updatePreferences(Long userId, String email,
            java.util.Map<String, Boolean> changes) {
        NotificationPreference preference = preferenceOf(userId, email);
        boolean wantTwoFactor = Boolean.TRUE.equals(changes.get("twoFactorEnabled"));
        if (changes.containsKey("twoFactorEnabled") && wantTwoFactor && !preference.isPhoneVerified()) {
            return java.util.Optional.empty();
        }
        if (changes.containsKey("smsEnabled")) {
            preference.setSmsEnabled(Boolean.TRUE.equals(changes.get("smsEnabled")));
        }
        if (changes.containsKey("emailEnabled")) {
            // 메일 수신 거부. ⚠️ 가입 확인·비밀번호 재설정은 이 설정과 무관하게 나간다 — 본인이 방금 요청한 메일을 수신 거부로
            // 막으면 계정에 못 들어간다(MailKind#isTransactional).
            preference.setEmailEnabled(Boolean.TRUE.equals(changes.get("emailEnabled")));
        }
        if (changes.containsKey("twoFactorEnabled")) {
            preference.enableTwoFactor(wantTwoFactor);
        }
        return java.util.Optional.of(preference);
    }

    /**
     * 주소만 기록한다. 알림 목록 조회처럼 설정이 필요 없는 경로에서 쓴다.
     *
     * <p>값이 그대로면 {@code rememberEmail} 이 아무것도 하지 않으므로 UPDATE 가 나가지 않는다 —
     * 화면마다 불리는 경로에서 매번 쓰기가 발생하면 WAL 만 늘어난다.
     */
    @Transactional
    public void rememberEmail(Long userId, String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        preferenceOf(userId).rememberEmail(email);
    }
}
