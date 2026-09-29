package com.project.notification_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;

import com.project.notification_service.domain.BroadcastNotification;
import com.project.notification_service.domain.BroadcastRead;
import com.project.notification_service.domain.NotificationType;
import com.project.notification_service.repository.BroadcastNotificationRepository;
import com.project.notification_service.repository.BroadcastReadRepository;
import com.project.notification_service.service.BroadcastService.Visible;

/**
 * 전체 알림의 저장 규칙.
 *
 * <p>핵심은 <b>공지 하나에 행 하나</b>라는 것이다 — 사용자 수만큼 알림 행을 만들지 않는다.
 * 그래서 "안 읽음"은 행이 없는 상태이고, 개인별로 남는 것은 읽었을 때뿐이다. 이 비대칭이
 * 아래 테스트들이 지키는 계약이다.
 */
@DisplayName("전체 알림")
class BroadcastServiceTest {

    private BroadcastNotificationRepository broadcasts;
    private BroadcastReadRepository reads;
    private BroadcastService service;

    @BeforeEach
    void setUp() {
        broadcasts = mock(BroadcastNotificationRepository.class);
        reads = mock(BroadcastReadRepository.class);
        when(broadcasts.save(any(BroadcastNotification.class))).thenAnswer(i -> i.getArgument(0));
        when(reads.save(any(BroadcastRead.class))).thenAnswer(i -> i.getArgument(0));
        service = new BroadcastService(broadcasts, reads);
    }

    private static BroadcastNotification broadcast(long id) {
        BroadcastNotification b = BroadcastNotification.builder()
                .broadcastId(id)
                .type(NotificationType.NOTICE_PUBLISHED)
                .title("공지 " + id)
                .startsAt(LocalDateTime.now().minusDays(1))
                .createdAt(LocalDateTime.now().minusDays(1))
                .build();
        return b;
    }

    @Nested
    @DisplayName("생성")
    class Creation {

        private Optional<BroadcastNotification> create(String eventId) {
            return service.create(NotificationType.NOTICE_PUBLISHED, "제목", "내용",
                    "notice/1", eventId, null, null);
        }

        @Test
        @DisplayName("새 이벤트면 만든다")
        void createsForNewEvent() {
            when(broadcasts.findBySourceEventId("notice:1")).thenReturn(Optional.empty());

            assertThat(create("notice:1")).isPresent();
            verify(broadcasts).save(any(BroadcastNotification.class));
        }

        /**
         * 카프카는 at-least-once 다. 재전달될 때마다 공지가 하나씩 더 생기면 사용자 화면에
         * 같은 줄이 쌓인다.
         */
        @Test
        @DisplayName("같은 이벤트가 다시 오면 만들지 않는다")
        void skipsDuplicateEvent() {
            when(broadcasts.findBySourceEventId("notice:1"))
                    .thenReturn(Optional.of(broadcast(1)));

            assertThat(create("notice:1")).isEmpty();
            verify(broadcasts, never()).save(any(BroadcastNotification.class));
        }

        /**
         * 조회와 저장 사이에 다른 인스턴스가 먼저 넣을 수 있다. UNIQUE 제약이 최종
         * 방어선이고, 그때는 "이미 있다"와 같은 결과여야 한다 — 예외가 올라가면 그 레코드가
         * DLT 로 가고, 실제로는 아무 문제도 없었는데 운영자가 원인을 찾게 된다.
         */
        @Test
        @DisplayName("동시에 들어와 UNIQUE 에 걸리면 조용히 넘어간다 — DLT 로 보내지 않는다")
        void concurrentInsertIsNotAnError() {
            when(broadcasts.findBySourceEventId("notice:1")).thenReturn(Optional.empty());
            when(broadcasts.save(any(BroadcastNotification.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_broadcast_source_event"));

            assertThat(create("notice:1")).isEmpty();
        }

        /**
         * 공지 본문은 길이 제한이 없는 text 다(catalog 의 Notice 주석). 발행자가 줄이지 못한
         * 채 넘어오면 insert 가 통째로 실패하고, 그 레코드는 세 번 재시도한 뒤 DLT 로 간다.
         */
        @Test
        @DisplayName("컬럼 길이를 넘는 제목·내용은 잘라서 넣는다 — insert 가 실패하면 DLT 로 간다")
        void clipsOversizedText() {
            when(broadcasts.findBySourceEventId(anyString())).thenReturn(Optional.empty());

            service.create(NotificationType.ANNOUNCEMENT, "가".repeat(400), "나".repeat(2_000),
                    null, "admin:1", null, null);

            ArgumentCaptor<BroadcastNotification> saved =
                    ArgumentCaptor.forClass(BroadcastNotification.class);
            verify(broadcasts).save(saved.capture());

            assertThat(saved.getValue().getTitle()).hasSize(255);
            assertThat(saved.getValue().getContent()).hasSize(1000);
            assertThat(saved.getValue().getTitle()).endsWith("…");
        }

        @Test
        @DisplayName("이벤트 키가 없으면 중복 검사 없이 만든다 — 관리자 발송 등")
        void nullEventIdSkipsLookup() {
            assertThat(create(null)).isPresent();
            verify(broadcasts, never()).findBySourceEventId(any());
        }
    }

    @Nested
    @DisplayName("목록")
    class Listing {

        @Test
        @DisplayName("읽음 여부를 한 번에 가져온다 — 공지마다 조회하면 목록 하나에 N 번이다")
        void readsAreFetchedInOneQuery() {
            when(broadcasts.visibleFor(anyLong(), any(), any()))
                    .thenReturn(List.of(broadcast(1), broadcast(2), broadcast(3)));
            when(reads.findByRecipientIdAndBroadcastIdIn(anyLong(), any()))
                    .thenReturn(List.of());

            service.visibleFor(7L, 20);

            verify(reads).findByRecipientIdAndBroadcastIdIn(anyLong(), any());
        }

        @Test
        @DisplayName("읽음 행이 있고 읽은 시각이 있으면 읽은 것이다")
        void readFlagComesFromTheReadRow() {
            when(broadcasts.visibleFor(anyLong(), any(), any()))
                    .thenReturn(List.of(broadcast(1), broadcast(2)));
            when(reads.findByRecipientIdAndBroadcastIdIn(anyLong(), any()))
                    .thenReturn(List.of(BroadcastRead.builder()
                            .broadcastId(1L).recipientId(7L)
                            .readAt(LocalDateTime.now()).dismissed(false).build()));

            List<Visible> visible = service.visibleFor(7L, 20);

            assertThat(visible).extracting(Visible::read).containsExactly(true, false);
        }

        @Test
        @DisplayName("공지가 없으면 읽음 조회를 하지 않는다")
        void emptyListSkipsTheReadQuery() {
            when(broadcasts.visibleFor(anyLong(), any(), any())).thenReturn(List.of());

            assertThat(service.visibleFor(7L, 20)).isEmpty();
            verify(reads, never()).findByRecipientIdAndBroadcastIdIn(anyLong(), any());
        }

        /**
         * 목록은 개인 알림과 합쳐서 내려간다. 전체 알림이 무제한이면 개인 알림이 밀려
         * 보이지 않는다.
         */
        @Test
        @DisplayName("요청 개수는 상한으로 잘린다")
        void oversizedLimitIsClamped() {
            when(broadcasts.visibleFor(anyLong(), any(), any())).thenReturn(List.of());

            service.visibleFor(7L, 100_000);

            ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
            verify(broadcasts).visibleFor(anyLong(), any(), page.capture());
            assertThat(page.getValue().getPageSize()).isEqualTo(BroadcastService.MAX_VISIBLE);
        }

        @Test
        @DisplayName("0 이하도 정상값으로 바뀐다 — PageRequest 가 예외를 던진다")
        void zeroLimitIsNormalised() {
            when(broadcasts.visibleFor(anyLong(), any(), any())).thenReturn(List.of());

            service.visibleFor(7L, 0);

            ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
            verify(broadcasts).visibleFor(anyLong(), any(), page.capture());
            assertThat(page.getValue().getPageSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("관리자 목록도 상한이 있다 — ?limit=100000 으로 전체를 끌어갈 수 없다")
        void adminListingIsAlsoClamped() {
            when(broadcasts.findAllByOrderByStartsAtDesc(any())).thenReturn(List.of());

            service.all(100_000);

            ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
            verify(broadcasts).findAllByOrderByStartsAtDesc(page.capture());
            assertThat(page.getValue().getPageSize()).isEqualTo(BroadcastService.MAX_ADMIN_PAGE_SIZE);
        }
    }

    @Nested
    @DisplayName("읽음·치움")
    class ReadAndDismiss {

        @Test
        @DisplayName("없는 공지를 읽음 처리하면 false — 화면은 404 로 옮긴다")
        void unknownBroadcastIsNotFound() {
            when(broadcasts.findById(9L)).thenReturn(Optional.empty());

            assertThat(service.markRead(7L, 9L)).isFalse();
            verify(reads, never()).save(any());
        }

        @Test
        @DisplayName("처음 읽으면 읽음 행을 만든다 — 안 읽은 상태는 행이 없는 것이다")
        void firstReadCreatesTheRow() {
            when(broadcasts.findById(1L)).thenReturn(Optional.of(broadcast(1)));
            when(reads.findByBroadcastIdAndRecipientId(1L, 7L)).thenReturn(Optional.empty());

            assertThat(service.markRead(7L, 1L)).isTrue();

            ArgumentCaptor<BroadcastRead> saved = ArgumentCaptor.forClass(BroadcastRead.class);
            verify(reads).save(saved.capture());
            assertThat(saved.getValue().getBroadcastId()).isEqualTo(1L);
            assertThat(saved.getValue().getRecipientId()).isEqualTo(7L);
            assertThat(saved.getValue().getReadAt()).isNotNull();
        }

        /**
         * 치웠다는 것은 봤다는 뜻이다. 읽음 시각을 같이 찍지 않으면 목록에서 사라진 공지가
         * 배지 숫자에는 남아, 사용자가 안 읽은 알림을 찾지 못한 채 배지만 보게 된다.
         */
        @Test
        @DisplayName("치우면 읽음으로도 표시된다 — 배지에 유령 숫자가 남으면 안 된다")
        void dismissAlsoMarksRead() {
            when(broadcasts.findById(1L)).thenReturn(Optional.of(broadcast(1)));
            when(reads.findByBroadcastIdAndRecipientId(1L, 7L)).thenReturn(Optional.empty());

            assertThat(service.dismiss(7L, 1L)).isTrue();

            ArgumentCaptor<BroadcastRead> saved = ArgumentCaptor.forClass(BroadcastRead.class);
            verify(reads).save(saved.capture());
            assertThat(saved.getValue().isDismissed()).isTrue();
            assertThat(saved.getValue().getReadAt()).isNotNull();
        }

        /** 이미 읽은 것을 치울 때 읽은 시각을 덮어쓰면 "언제 봤는지"가 흐트러진다. */
        @Test
        @DisplayName("이미 읽은 것을 치워도 읽은 시각은 그대로다")
        void dismissKeepsTheOriginalReadTime() {
            LocalDateTime first = LocalDateTime.now().minusDays(3);
            when(broadcasts.findById(1L)).thenReturn(Optional.of(broadcast(1)));
            when(reads.findByBroadcastIdAndRecipientId(1L, 7L))
                    .thenReturn(Optional.of(BroadcastRead.builder()
                            .broadcastId(1L).recipientId(7L).readAt(first).dismissed(false).build()));

            service.dismiss(7L, 1L);

            ArgumentCaptor<BroadcastRead> saved = ArgumentCaptor.forClass(BroadcastRead.class);
            verify(reads).save(saved.capture());
            assertThat(saved.getValue().getReadAt()).isEqualTo(first);
        }

        /**
         * 한 사람의 "치움"이 공지 자체를 지우면 다른 모든 사용자의 화면이 바뀐다 — 공지는
         * 모두가 공유하는 행이다.
         */
        @Test
        @DisplayName("치워도 공지 자체는 지우지 않는다")
        void dismissNeverDeletesTheBroadcast() {
            when(broadcasts.findById(1L)).thenReturn(Optional.of(broadcast(1)));
            when(reads.findByBroadcastIdAndRecipientId(1L, 7L)).thenReturn(Optional.empty());

            service.dismiss(7L, 1L);

            verify(broadcasts, never()).deleteById(anyLong());
        }

        @Test
        @DisplayName("모두 읽음은 유효한 것만 처리하고 건수를 돌려준다")
        void markAllReadCountsWhatItTouched() {
            when(broadcasts.unreadIdsFor(anyLong(), any())).thenReturn(List.of(1L, 2L, 3L));
            when(reads.findByBroadcastIdAndRecipientId(anyLong(), anyLong()))
                    .thenReturn(Optional.empty());

            assertThat(service.markAllRead(7L)).isEqualTo(3);
            verify(reads, org.mockito.Mockito.times(3)).save(any(BroadcastRead.class));
        }
    }

    @Nested
    @DisplayName("관리자 삭제")
    class AdminRemoval {

        /**
         * FK 가 없다(V4 주석). 읽음 행을 서비스가 직접 정리하지 않으면 고아 행이 계속
         * 쌓인다 — 조회에는 영향이 없지만 테이블이 줄지 않는다.
         */
        @Test
        @DisplayName("공지를 지우면 읽음 행도 함께 지운다 — FK 가 없다")
        void removingAlsoClearsReadRows() {
            when(broadcasts.findById(1L)).thenReturn(Optional.of(broadcast(1)));

            assertThat(service.remove(1L)).isTrue();

            verify(reads).deleteByBroadcastId(1L);
            verify(broadcasts).deleteById(1L);
        }

        @Test
        @DisplayName("없는 공지를 지우면 false")
        void removingUnknownIsNotFound() {
            when(broadcasts.findById(9L)).thenReturn(Optional.empty());

            assertThat(service.remove(9L)).isFalse();
            verify(broadcasts, never()).deleteById(anyLong());
        }
    }
}
