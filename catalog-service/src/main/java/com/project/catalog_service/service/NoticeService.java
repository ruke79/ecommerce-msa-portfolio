package com.project.catalog_service.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.catalog_service.model.Notice;
import com.project.catalog_service.dto.ContentDtos.NoticeAdminSummary;
import com.project.catalog_service.dto.ContentDtos.NoticeAdminView;
import com.project.catalog_service.dto.ContentDtos.NoticeRequest;
import com.project.catalog_service.dto.ContentDtos.NoticeSummary;
import com.project.catalog_service.dto.ContentDtos.NoticeView;
import com.project.catalog_service.dto.ContentDtos.PagedAdminNotices;
import com.project.catalog_service.dto.ContentDtos.PagedNotices;
import com.project.catalog_service.message.producer.NoticePublishedProducer;
import com.project.catalog_service.repository.jpa.data.NoticeRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NoticeService {

    private final NoticeRepository noticeRepository;

    /**
     * 공지가 공개되면 전체 알림으로 나간다.
     *
     * <p>🐛 예전에는 공지를 공개해도 아무 일도 일어나지 않았다 — 사용자가 스스로 공지
     * 목록에 들어와야만 볼 수 있었다. 점검 예고처럼 <b>미리 봐야 의미가 있는</b> 공지가
     * 그대로 묻혔다.
     */
    private final NoticePublishedProducer noticePublishedProducer;

    /**
     * 페이지 크기 상한. 클라이언트가 보낸 size 는 이 값으로 잘린다 — {@code ?size=100000}
     * 으로 전체를 끌어가는 경로를 막는다.
     */
    @Value("${content.notice.max-page-size:50}")
    private int maxPageSize;

    public PagedNotices published(int page, int size) {
        Page<Notice> found = noticeRepository.findPublished(pageRequest(page, size));
        return toPaged(found);
    }

    public NoticeView publishedOne(Long id) {
        return noticeRepository.findPublishedById(id)
                .map(NoticeView::of)
                // 비공개 글을 404 로 돌려주는 것은 의도적이다. 403 이면 "그 번호에 글이
                // 있긴 하다"가 새어 나가, 번호를 훑어 미공개 공지의 존재를 알 수 있다.
                .orElseThrow(() -> new IllegalArgumentException("공지를 찾을 수 없습니다."));
    }

    public PagedAdminNotices all(int page, int size) {
        Page<Notice> found = noticeRepository.findAllByOrderByNoticeIdDesc(pageRequest(page, size));
        return new PagedAdminNotices(
                found.getContent().stream().map(NoticeAdminSummary::of).toList(),
                found.getNumber(),
                found.getSize(),
                found.getTotalElements(),
                found.getTotalPages());
    }

    public NoticeAdminView one(Long id) {
        return noticeRepository.findById(id)
                .map(NoticeAdminView::of)
                .orElseThrow(() -> new IllegalArgumentException("공지를 찾을 수 없습니다."));
    }

    @Transactional
    public NoticeAdminView create(NoticeRequest request) {
        Notice notice = Notice.builder()
                .title(request.title())
                .body(request.body())
                .pinned(Boolean.TRUE.equals(request.pinned()))
                .build();

        // published 는 setter 를 거쳐야 publishedAt 이 찍힌다. 빌더로 바로 넣으면 공개
        // 상태인데 공개 시각이 없는 행이 만들어진다.
        notice.setPublishedState(Boolean.TRUE.equals(request.published()));

        Notice saved = noticeRepository.save(notice);
        log.info("공지 생성: id={}, published={}", saved.getNoticeId(), saved.isPublished());

        // 초안으로 만들었으면 아무것도 나가지 않는다. 공개는 아래 update 에서 잡는다.
        if (saved.isPublished()) {
            noticePublishedProducer.publish(saved);
        }
        return NoticeAdminView.of(saved);
    }

    @Transactional
    public NoticeAdminView update(Long id, NoticeRequest request) {
        Notice notice = noticeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("공지를 찾을 수 없습니다."));

        // 전환을 잡으려면 바꾸기 **전** 값이 필요하다.
        boolean wasPublished = notice.isPublished();

        notice.setTitle(request.title());
        notice.setBody(request.body());
        if (request.pinned() != null) {
            notice.setPinned(request.pinned());
        }
        if (request.published() != null) {
            notice.setPublishedState(request.published());
        }

        log.info("공지 수정: id={}, published={}", id, notice.isPublished());

        // 비공개 → 공개로 **넘어간 순간에만** 알린다. 공개된 글의 오타를 고칠 때마다
        // 알림이 나가면 사용자에게는 같은 공지가 반복해서 뜬다.
        //
        // (내렸다 다시 올리는 경우는 이벤트가 한 번 더 나가지만, eventId 가
        // "notice:<id>" 로 같아서 알림 서비스가 중복으로 걸러 낸다.)
        if (!wasPublished && notice.isPublished()) {
            noticePublishedProducer.publish(notice);
        }
        return NoticeAdminView.of(notice);
    }

    @Transactional
    public void delete(Long id) {
        if (!noticeRepository.existsById(id)) {
            throw new IllegalArgumentException("공지를 찾을 수 없습니다.");
        }
        noticeRepository.deleteById(id);
        log.warn("공지 삭제: id={}", id);
    }

    /** 음수 페이지와 과도한 size 를 여기서 한 번에 막는다. */
    private PageRequest pageRequest(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), maxPageSize);
        return PageRequest.of(safePage, safeSize);
    }

    private static PagedNotices toPaged(Page<Notice> found) {
        return new PagedNotices(
                found.getContent().stream().map(NoticeSummary::of).toList(),
                found.getNumber(),
                found.getSize(),
                found.getTotalElements(),
                found.getTotalPages());
    }
}
