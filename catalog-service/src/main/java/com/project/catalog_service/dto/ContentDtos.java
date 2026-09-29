package com.project.catalog_service.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.project.catalog_service.model.Banner;
import com.project.catalog_service.model.Notice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 배너·공지의 입출력 형태.
 *
 * <p>한 파일에 모은 이유: 전부 필드 서너 개짜리 record 라 파일을 나누면 읽는 사람이
 * 여섯 번 점프해야 한다. 늘어나면 그때 나눈다.
 *
 * <p><b>공개 응답과 관리자 응답을 나눈다.</b> 공개 응답에 {@code published} 나
 * {@code startsAt} 을 실으면 아직 공개하지 않은 기획전의 존재와 시작 시각이 새어 나간다 —
 * 목록에서 걸러도 필드가 있으면 언젠가 누가 그 필드로 필터를 만든다.
 */
public final class ContentDtos {

    private ContentDtos() {
    }

    // ── 배너 ────────────────────────────────────────────────────────────

    /** 공개 응답. 화면이 그리는 데 필요한 것만. */
    public record BannerView(Long id, String title, String imageUrl, String linkUrl) {

        public static BannerView of(Banner b) {
            return new BannerView(b.getBannerId(), b.getTitle(), b.getImageUrl(), b.getLinkUrl());
        }
    }

    /** 관리자 응답. 노출 판정에 쓰인 값까지 전부 보여 줘야 왜 안 보이는지 알 수 있다. */
    public record BannerAdminView(Long id, String title, String imageUrl, String linkUrl,
                                  int sortOrder, boolean published,
                                  LocalDateTime startsAt, LocalDateTime endsAt,
                                  boolean visibleNow) {

        public static BannerAdminView of(Banner b, LocalDateTime now) {
            return new BannerAdminView(b.getBannerId(), b.getTitle(), b.getImageUrl(),
                    b.getLinkUrl(), b.getSortOrder(), b.isPublished(),
                    b.getStartsAt(), b.getEndsAt(), b.isVisibleAt(now));
        }
    }

    public record BannerRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 1000) String imageUrl,
            @Size(max = 1000) String linkUrl,
            Integer sortOrder,
            Boolean published,
            LocalDateTime startsAt,
            LocalDateTime endsAt) {
    }

    // ── 공지 ────────────────────────────────────────────────────────────

    /** 목록용. 본문은 싣지 않는다 — 20건짜리 목록이 본문 20개를 끌고 오면 안 된다. */
    public record NoticeSummary(Long id, String title, boolean pinned, LocalDateTime publishedAt) {

        public static NoticeSummary of(Notice n) {
            return new NoticeSummary(n.getNoticeId(), n.getTitle(), n.isPinned(), n.getPublishedAt());
        }
    }

    public record NoticeView(Long id, String title, String body, boolean pinned,
                             LocalDateTime publishedAt) {

        public static NoticeView of(Notice n) {
            return new NoticeView(n.getNoticeId(), n.getTitle(), n.getBody(), n.isPinned(),
                    n.getPublishedAt());
        }
    }

    public record NoticeAdminView(Long id, String title, String body, boolean pinned,
                                  boolean published, LocalDateTime publishedAt,
                                  LocalDateTime updatedAt) {

        public static NoticeAdminView of(Notice n) {
            return new NoticeAdminView(n.getNoticeId(), n.getTitle(), n.getBody(), n.isPinned(),
                    n.isPublished(), n.getPublishedAt(), n.getUpdatedAt());
        }
    }

    public record NoticeRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 20000) String body,
            Boolean pinned,
            Boolean published) {
    }

    /**
     * 관리자 목록용 요약.
     *
     * <p>{@link NoticeSummary} 와 나눈 이유: 관리자는 <b>공개 여부</b>를 봐야 한다(초안이
     * 목록에 섞여 있다). 그렇다고 공개 응답에 그 필드를 실을 수는 없다 — 공개 목록에는
     * 공개된 것만 있으므로 항상 true 인 필드가 되고, 언젠가 누가 그걸로 필터를 만든다.
     *
     * <p>{@code publishedAt} 만으로 판정할 수 없다는 점이 핵심이다. 공개했다가 내린 글은
     * 공개 시각이 남아 있어서, 그 값만 보면 공개 중인 것처럼 보인다.
     */
    public record NoticeAdminSummary(Long id, String title, boolean pinned, boolean published,
                                     LocalDateTime publishedAt) {

        public static NoticeAdminSummary of(Notice n) {
            return new NoticeAdminSummary(n.getNoticeId(), n.getTitle(), n.isPinned(),
                    n.isPublished(), n.getPublishedAt());
        }
    }

    /** 목록 응답. 페이지 정보를 함께 준다 — 프론트가 총 페이지 수를 직접 계산하지 않도록. */
    public record PagedNotices(List<NoticeSummary> items, int page, int size, long total,
                               int totalPages) {
    }

    public record PagedAdminNotices(List<NoticeAdminSummary> items, int page, int size, long total,
                                    int totalPages) {
    }
}
