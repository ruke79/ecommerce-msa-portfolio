package com.project.notification_service.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.notification_service.domain.BroadcastNotification;
import com.project.notification_service.domain.BroadcastRead;
import com.project.notification_service.domain.NotificationType;
import com.project.notification_service.repository.BroadcastNotificationRepository;
import com.project.notification_service.repository.BroadcastReadRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 전체 사용자 대상 알림.
 *
 * <p>개인 알림({@link NotificationService})과 <b>저장 구조가 다르다</b> — 공지 하나에
 * 사용자 수만큼 행을 만들지 않고 공지당 한 행만 둔다(BroadcastNotification 주석).
 * 그래서 별도 서비스로 나눴다: 같은 클래스에 넣으면 "어떤 것은 개인 행을 만들고 어떤 것은
 * 안 만든다"가 메서드마다 섞여 읽기 어려워진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastService {

    /**
     * 목록에 한 번에 실을 수 있는 전체 알림 수.
     *
     * <p>상한이 필요한 이유: 목록 API 는 개인 알림과 전체 알림을 합쳐서 돌려주는데, 전체
     * 알림이 무제한이면 개인 알림이 밀려 보이지 않는다. 공지는 원래 몇 건 수준이고,
     * 그보다 많다면 목록이 아니라 공지사항 화면에서 볼 일이다.
     */
    public static final int MAX_VISIBLE = 20;

    /**
     * 관리자 목록 한 번에 읽는 최대 건수.
     *
     * <p>사용자 목록보다는 크지만 무제한은 아니다 — {@code ?limit=100000} 으로 전체를
     * 끌어가는 경로를 막는다(catalog 의 NoticeService·ProductService 와 같은 이유).
     */
    public static final int MAX_ADMIN_PAGE_SIZE = 100;

    /** {@code broadcast_notification.title} 의 컬럼 길이(V4). */
    private static final int TITLE_LIMIT = 255;

    /** {@code broadcast_notification.content} 의 컬럼 길이(V4). */
    private static final int CONTENT_LIMIT = 1000;

    private final BroadcastNotificationRepository broadcastRepository;
    private final BroadcastReadRepository readRepository;

    /**
     * 전체 알림을 만든다.
     *
     * <p>{@code sourceEventId} 가 같은 것이 이미 있으면 만들지 않는다. 카프카는
     * at-least-once 라 공지 게시 이벤트가 재전달될 수 있는데, 그때마다 공지가 하나씩 더
     * 생기면 사용자 화면에 같은 공지가 여러 줄로 쌓인다.
     *
     * @return 새로 만들었으면 그 공지, 이미 있었으면 비어 있음
     */
    @Transactional
    public Optional<BroadcastNotification> create(NotificationType type, String title, String content,
            String url, String sourceEventId, LocalDateTime startsAt, LocalDateTime endsAt) {

        if (sourceEventId != null) {
            Optional<BroadcastNotification> existing =
                    broadcastRepository.findBySourceEventId(sourceEventId);
            if (existing.isPresent()) {
                log.debug("이미 처리된 공지 이벤트, 건너뜀: {}", sourceEventId);
                return Optional.empty();
            }
        }

        BroadcastNotification broadcast = BroadcastNotification.builder()
                .type(type)
                // 길이를 여기서 자른다. 공지 본문은 길이 제한이 없는 text 라(catalog 의
                // Notice 주석) 발행자가 줄이지 못한 채 넘어오면 insert 가 통째로 실패하고,
                // 그 레코드는 DLT 로 간다 — 알림이 안 나가는 것으로 끝나지 않고 컨슈머가
                // 같은 레코드를 세 번 재시도한 뒤에야 넘어간다.
                .title(clip(title, TITLE_LIMIT))
                .content(clip(content, CONTENT_LIMIT))
                .url(url)
                .sourceEventId(sourceEventId)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .build();

        try {
            return Optional.of(broadcastRepository.save(broadcast));
        } catch (DataIntegrityViolationException e) {
            // 위의 조회와 저장 사이에 다른 인스턴스가 먼저 넣었다. UNIQUE 제약이
            // 최종 방어선이고, 여기서는 "이미 있다"와 같은 결과로 취급한다.
            log.debug("공지 이벤트가 동시에 처리됐다, 건너뜀: {}", sourceEventId);
            return Optional.empty();
        }
    }

    /** 이 사람에게 지금 보여야 하는 전체 알림과, 각각의 읽음 여부. */
    @Transactional(readOnly = true)
    public List<Visible> visibleFor(Long recipientId, int limit) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int size = Math.max(1, Math.min(limit, MAX_VISIBLE));

        List<BroadcastNotification> broadcasts =
                broadcastRepository.visibleFor(recipientId, now, PageRequest.of(0, size));

        if (broadcasts.isEmpty()) {
            return List.of();
        }

        // 읽음 여부는 **한 번에** 가져온다. 공지마다 조회하면 목록 하나에 N 번이다.
        Map<Long, BroadcastRead> reads = readRepository
                .findByRecipientIdAndBroadcastIdIn(recipientId,
                        broadcasts.stream().map(BroadcastNotification::getBroadcastId).toList())
                .stream()
                .collect(Collectors.toMap(BroadcastRead::getBroadcastId, Function.identity(), (a, b) -> a));

        return broadcasts.stream()
                .map(b -> new Visible(b, reads.get(b.getBroadcastId()) != null
                        && reads.get(b.getBroadcastId()).getReadAt() != null))
                .toList();
    }

    /**
     * 관리자 목록 — 예약분·만료분까지.
     *
     * <p>{@link #visibleFor} 와 달리 기간으로 거르지 않는다: 안 보이는 것을 고칠 수는 없다.
     */
    @Transactional(readOnly = true)
    public List<BroadcastNotification> all(int limit) {
        int size = Math.max(1, Math.min(limit, MAX_ADMIN_PAGE_SIZE));
        return broadcastRepository.findAllByOrderByStartsAtDesc(PageRequest.of(0, size));
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long recipientId) {
        return broadcastRepository.countUnreadFor(recipientId, LocalDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 하나를 읽음 처리한다.
     *
     * <p>이미 읽었으면 아무 일도 하지 않는다 — 같은 요청을 두 번 보내도 결과가 같다.
     *
     * @return 그런 공지가 있으면 true. 없는 id 면 false(화면은 404 로 옮긴다).
     */
    @Transactional
    public boolean markRead(Long recipientId, Long broadcastId) {
        if (broadcastRepository.findById(broadcastId).isEmpty()) {
            return false;
        }
        upsert(recipientId, broadcastId, read -> read.setReadAt(LocalDateTime.now(ZoneOffset.UTC)));
        return true;
    }

    /** 유효한 전체 알림을 모두 읽음 처리한다. @return 새로 읽음이 된 건수 */
    @Transactional
    public int markAllRead(Long recipientId) {
        List<Long> ids = broadcastRepository.unreadIdsFor(recipientId, LocalDateTime.now(ZoneOffset.UTC));
        for (Long id : ids) {
            upsert(recipientId, id, read -> read.setReadAt(LocalDateTime.now(ZoneOffset.UTC)));
        }
        return ids.size();
    }

    /**
     * 목록에서 치운다.
     *
     * <p>공지 자체는 지우지 않는다 — 모두가 공유하는 행이다. "이 사람에게는 보이지 않는다"
     * 로만 표현한다. 한 사람의 삭제가 다른 사람의 화면을 바꾸면 안 된다.
     */
    @Transactional
    public boolean dismiss(Long recipientId, Long broadcastId) {
        if (broadcastRepository.findById(broadcastId).isEmpty()) {
            return false;
        }
        upsert(recipientId, broadcastId, read -> {
            read.setDismissed(true);
            if (read.getReadAt() == null) {
                // 치웠다는 것은 봤다는 뜻이다. 안읽음 배지에 남아 있으면 안 된다.
                read.setReadAt(LocalDateTime.now(ZoneOffset.UTC));
            }
        });
        return true;
    }

    /** 관리자가 공지를 내린다. 읽음 행도 함께 정리한다(V4 주석 — FK 가 없다). */
    @Transactional
    public boolean remove(Long broadcastId) {
        if (broadcastRepository.findById(broadcastId).isEmpty()) {
            return false;
        }
        readRepository.deleteByBroadcastId(broadcastId);
        broadcastRepository.deleteById(broadcastId);
        return true;
    }

    /** 컬럼 길이에 맞춘다. 마지막 한 글자를 줄임표로 바꿔 잘렸다는 것을 드러낸다. */
    private static String clip(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit - 1) + "…";
    }

    private void upsert(Long recipientId, Long broadcastId, java.util.function.Consumer<BroadcastRead> mutate) {
        BroadcastRead read = readRepository
                .findByBroadcastIdAndRecipientId(broadcastId, recipientId)
                .orElseGet(() -> BroadcastRead.builder()
                        .broadcastId(broadcastId)
                        .recipientId(recipientId)
                        .dismissed(false)
                        .build());
        mutate.accept(read);
        readRepository.save(read);
    }

    /** 공지 한 건 + 이 사람이 읽었는지. */
    public record Visible(BroadcastNotification broadcast, boolean read) {
    }
}
