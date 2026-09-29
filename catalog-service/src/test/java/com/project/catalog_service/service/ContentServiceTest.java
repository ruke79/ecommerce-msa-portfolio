package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.project.catalog_service.model.Banner;
import com.project.catalog_service.model.Notice;
import com.project.catalog_service.dto.ContentDtos.NoticeAdminView;
import com.project.catalog_service.dto.ContentDtos.NoticeRequest;
import com.project.catalog_service.message.producer.NoticePublishedProducer;
import com.project.catalog_service.repository.jpa.data.BannerRepository;
import com.project.catalog_service.repository.jpa.data.NoticeRepository;

/**
 * 배너 노출 판정과 공지 공개 규칙.
 *
 * <p>여기서 검증되지 <b>않는</b> 것: {@code BannerRepository.findVisible} 의 JPQL 자체
 * (실제 DB 가 필요하다). 그 쿼리와 {@link Banner#isVisibleAt} 은 <b>같은 규칙을 두 번</b>
 * 적는 자리라, 아래 경계 테스트가 그 규칙의 정의를 고정하는 역할을 한다 — 쿼리를 고칠 때
 * 이 표를 보고 맞추면 된다.
 */
@DisplayName("콘텐츠")
class ContentServiceTest {

    @Nested
    @DisplayName("배너 노출 판정")
    class BannerVisibility {

        private final LocalDateTime now = LocalDateTime.of(2026, 9, 12, 12, 0);

        private Banner banner(boolean published, LocalDateTime from, LocalDateTime to) {
            return Banner.builder()
                    .title("t").imageUrl("i")
                    .published(published).startsAt(from).endsAt(to)
                    .build();
        }

        @Test
        @DisplayName("비공개면 기간과 무관하게 안 보인다")
        void unpublishedIsHiddenRegardlessOfPeriod() {
            assertThat(banner(false, null, null).isVisibleAt(now)).isFalse();
            assertThat(banner(false, now.minusDays(1), now.plusDays(1)).isVisibleAt(now)).isFalse();
        }

        @Test
        @DisplayName("기간이 없으면 공개 여부만 본다 — 상시 배너는 기간 개념을 몰라도 된다")
        void noPeriodMeansAlways() {
            assertThat(banner(true, null, null).isVisibleAt(now)).isTrue();
        }

        @Test
        @DisplayName("시작 전이면 안 보인다")
        void beforeStartIsHidden() {
            assertThat(banner(true, now.plusSeconds(1), null).isVisibleAt(now)).isFalse();
        }

        @Test
        @DisplayName("종료 후면 안 보인다")
        void afterEndIsHidden() {
            assertThat(banner(true, null, now.minusSeconds(1)).isVisibleAt(now)).isFalse();
        }

        @Test
        @DisplayName("경계는 양쪽 다 포함이다 — 종료 시각을 자정으로 넣으면 그 자정까지 보인다")
        void boundsAreInclusive() {
            assertThat(banner(true, now, now).isVisibleAt(now)).isTrue();
        }

        @Test
        @DisplayName("한쪽만 지정해도 동작한다")
        void openEndedPeriodsWork() {
            assertThat(banner(true, now.minusDays(1), null).isVisibleAt(now)).isTrue();
            assertThat(banner(true, null, now.plusDays(1)).isVisibleAt(now)).isTrue();
        }
    }

    @Nested
    @DisplayName("배너 목록")
    class BannerListing {

        @Test
        @DisplayName("공개 목록은 상한만큼만 요청한다 — 실수로 100개가 공개돼도 홈이 전부 받지 않는다")
        void visibleIsCapped() {
            BannerRepository repository = mock(BannerRepository.class);
            when(repository.findVisible(any(), any())).thenReturn(List.of());

            BannerService service = new BannerService(repository);
            ReflectionTestUtils.setField(service, "maxVisible", 10);

            service.visible();

            ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
            verify(repository).findVisible(any(), page.capture());
            assertThat(page.getValue().getPageSize()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("공지 공개")
    class NoticePublishing {

        private NoticeRepository repository;
        private NoticePublishedProducer producer;
        private NoticeService service;

        @BeforeEach
        void setUp() {
            repository = mock(NoticeRepository.class);
            when(repository.save(any(Notice.class))).thenAnswer(i -> i.getArgument(0));
            producer = mock(NoticePublishedProducer.class);
            service = new NoticeService(repository, producer);
            ReflectionTestUtils.setField(service, "maxPageSize", 50);
        }

        @Test
        @DisplayName("공개로 만들면 공개 시각이 찍힌다 — 빌더로 바로 넣으면 비는 자리")
        void createPublishedStampsTime() {
            NoticeAdminView view = service.create(
                    new NoticeRequest("점검 안내", "본문", false, true));

            assertThat(view.published()).isTrue();
            assertThat(view.publishedAt()).isNotNull();
        }

        @Test
        @DisplayName("비공개로 만들면 공개 시각이 없다")
        void createDraftHasNoPublishedAt() {
            NoticeAdminView view = service.create(
                    new NoticeRequest("초안", "본문", false, false));

            assertThat(view.published()).isFalse();
            assertThat(view.publishedAt()).isNull();
        }

        @Test
        @DisplayName("비공개↔공개를 반복해도 공개 시각은 처음 것을 유지한다 — 오타 수정 때마다 맨 위로 올라오면 안 된다")
        void republishKeepsTheOriginalTime() {
            Notice notice = Notice.builder().title("t").body("b").build();
            notice.setPublishedState(true);
            LocalDateTime first = notice.getPublishedAt();

            notice.setPublishedState(false);
            notice.setPublishedState(true);

            assertThat(notice.getPublishedAt()).isEqualTo(first);
        }

        // ── 전체 알림 발행 ────────────────────────────────────────────────────
        //
        // 🐛 회귀 대상: 공지를 공개해도 아무에게도 전달되지 않았다. 사용자가 스스로 공지
        // 목록 화면에 들어와야만 볼 수 있어서, 점검 예고처럼 미리 봐야 의미가 있는 공지가
        // 그대로 묻혔다.

        @Test
        @DisplayName("공개 상태로 만들면 전체 알림이 나간다")
        void createPublishedAnnounces() {
            service.create(new NoticeRequest("점검 안내", "본문", false, true));

            verify(producer).publish(any(Notice.class));
        }

        @Test
        @DisplayName("초안으로 만들면 아무것도 나가지 않는다 — 공개되지 않은 글을 알릴 수 없다")
        void createDraftDoesNotAnnounce() {
            service.create(new NoticeRequest("초안", "본문", false, false));

            verify(producer, never()).publish(any(Notice.class));
        }

        @Test
        @DisplayName("비공개 → 공개로 넘어가면 나간다")
        void publishingAnExistingDraftAnnounces() {
            Notice draft = Notice.builder().noticeId(7L).title("초안").body("본문").build();
            when(repository.findById(7L)).thenReturn(Optional.of(draft));

            service.update(7L, new NoticeRequest("초안", "본문", false, true));

            verify(producer).publish(draft);
        }

        /**
         * 공개된 글의 오타를 고칠 때마다 알림이 나가면 사용자에게는 같은 공지가 반복해서
         * 뜬다. 전환된 순간에만 알려야 한다.
         */
        @Test
        @DisplayName("이미 공개된 글을 수정하면 다시 나가지 않는다")
        void editingAPublishedNoticeDoesNotReannounce() {
            Notice published = Notice.builder().noticeId(7L).title("공지").body("본문").build();
            published.setPublishedState(true);
            when(repository.findById(7L)).thenReturn(Optional.of(published));

            service.update(7L, new NoticeRequest("공지(오타 수정)", "본문", false, true));

            verify(producer, never()).publish(any(Notice.class));
        }

        @Test
        @DisplayName("공개된 글을 내려도 나가지 않는다")
        void unpublishingDoesNotAnnounce() {
            Notice published = Notice.builder().noticeId(7L).title("공지").body("본문").build();
            published.setPublishedState(true);
            when(repository.findById(7L)).thenReturn(Optional.of(published));

            service.update(7L, new NoticeRequest("공지", "본문", false, false));

            verify(producer, never()).publish(any(Notice.class));
        }

        /**
         * published 를 건드리지 않는 수정(제목만 고치기)에서도 전환은 없다. request 의
         * published 가 null 이면 상태를 그대로 두므로 알림도 나가지 않아야 한다.
         */
        @Test
        @DisplayName("published 를 생략한 수정은 상태를 바꾸지 않고, 알림도 나가지 않는다")
        void updateWithoutPublishedFlagKeepsState() {
            Notice draft = Notice.builder().noticeId(7L).title("초안").body("본문").build();
            when(repository.findById(7L)).thenReturn(Optional.of(draft));

            service.update(7L, new NoticeRequest("제목만 수정", "본문", null, null));

            assertThat(draft.isPublished()).isFalse();
            verify(producer, never()).publish(any(Notice.class));
        }
    }

    @Nested
    @DisplayName("공지 게시 이벤트 본문 요약")
    class NoticeSummarising {

        /**
         * 공지 본문은 길이 제한이 없는 {@code text} 인데(Notice 주석) 알림에 보이는 것은
         * 두어 줄이다. 20KB 를 통째로 카프카에 흘려보낼 이유가 없고, 받는 쪽 컬럼도
         * varchar(1000) 이라 어차피 잘린다.
         */
        @Test
        @DisplayName("긴 본문은 잘리고 잘렸다는 표시가 남는다")
        void longBodyIsTruncated() {
            String summary = NoticePublishedProducer.summarize("가".repeat(5_000));

            assertThat(summary).hasSize(NoticePublishedProducer.SUMMARY_LIMIT + 1);
            assertThat(summary).endsWith("…");
        }

        @Test
        @DisplayName("짧은 본문은 그대로 둔다 — 멀쩡한 문장에 줄임표를 붙이지 않는다")
        void shortBodyIsKept() {
            assertThat(NoticePublishedProducer.summarize("짧은 본문")).isEqualTo("짧은 본문");
        }

        @Test
        @DisplayName("줄바꿈은 공백 하나로 눌린다 — 알림은 한 줄로 보인다")
        void newlinesAreFlattened() {
            assertThat(NoticePublishedProducer.summarize("첫 줄\n\n  둘째 줄  "))
                    .isEqualTo("첫 줄 둘째 줄");
        }

        @Test
        @DisplayName("본문이 없어도 터지지 않는다")
        void nullBodyIsSafe() {
            assertThat(NoticePublishedProducer.summarize(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("공지 목록 페이지")
    class NoticePaging {

        private NoticeRepository repository;
        private NoticeService service;

        @BeforeEach
        void setUp() {
            repository = mock(NoticeRepository.class);
            when(repository.findPublished(any())).thenReturn(emptyPage());
            service = new NoticeService(repository, mock(NoticePublishedProducer.class));
            ReflectionTestUtils.setField(service, "maxPageSize", 50);
        }

        private Page<Notice> emptyPage() {
            return new PageImpl<>(List.of());
        }

        private Pageable captured() {
            ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
            verify(repository).findPublished(page.capture());
            return page.getValue();
        }

        @Test
        @DisplayName("과도한 size 는 상한으로 잘린다 — ?size=100000 으로 전체를 끌어갈 수 없다")
        void oversizedPageIsClamped() {
            service.published(0, 100_000);
            assertThat(captured().getPageSize()).isEqualTo(50);
        }

        @Test
        @DisplayName("음수 페이지와 0 이하 size 는 정상값으로 바뀐다 — PageRequest 가 예외를 던진다")
        void negativeInputsAreNormalised() {
            service.published(-5, 0);
            assertThat(captured().getPageNumber()).isZero();
            assertThat(captured().getPageSize()).isEqualTo(1);
        }
    }
}
