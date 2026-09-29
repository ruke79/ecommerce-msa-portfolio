package com.project.catalog_service.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.catalog_service.model.Banner;
import com.project.catalog_service.dto.ContentDtos.BannerAdminView;
import com.project.catalog_service.dto.ContentDtos.BannerRequest;
import com.project.catalog_service.dto.ContentDtos.BannerView;
import com.project.catalog_service.repository.jpa.data.BannerRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BannerService {

    private final BannerRepository bannerRepository;

    /**
     * 한 번에 내려보내는 최대 배너 수. 실수로 100개가 공개돼도 홈 화면이 그걸 전부
     * 받아 오지는 않게 한다.
     */
    @Value("${content.banner.max-visible:10}")
    private int maxVisible;

    public List<BannerView> visible() {
        return bannerRepository.findVisible(LocalDateTime.now(), PageRequest.of(0, maxVisible))
                .stream()
                .map(BannerView::of)
                .toList();
    }

    public List<BannerAdminView> all() {
        LocalDateTime now = LocalDateTime.now();
        return bannerRepository.findAllByOrderBySortOrderAscBannerIdAsc().stream()
                .map(b -> BannerAdminView.of(b, now))
                .toList();
    }

    @Transactional
    public BannerAdminView create(BannerRequest request) {
        Banner banner = Banner.builder()
                .title(request.title())
                .imageUrl(request.imageUrl())
                .linkUrl(blankToNull(request.linkUrl()))
                .sortOrder(request.sortOrder() == null ? 0 : request.sortOrder())
                // 기본은 비공개다. 만들자마자 첫 화면에 뜨면 작업 중인 배너가 노출된다.
                .published(Boolean.TRUE.equals(request.published()))
                .startsAt(request.startsAt())
                .endsAt(request.endsAt())
                .build();

        Banner saved = bannerRepository.save(banner);
        log.info("배너 생성: id={}, published={}", saved.getBannerId(), saved.isPublished());
        return BannerAdminView.of(saved, LocalDateTime.now());
    }

    @Transactional
    public BannerAdminView update(Long id, BannerRequest request) {
        Banner banner = bannerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("배너를 찾을 수 없습니다."));

        banner.setTitle(request.title());
        banner.setImageUrl(request.imageUrl());
        banner.setLinkUrl(blankToNull(request.linkUrl()));
        if (request.sortOrder() != null) {
            banner.setSortOrder(request.sortOrder());
        }
        if (request.published() != null) {
            banner.setPublished(request.published());
        }
        // 기간은 null 을 "제한 없음"으로 되돌리는 의미가 있으므로 그대로 덮어쓴다.
        banner.setStartsAt(request.startsAt());
        banner.setEndsAt(request.endsAt());

        log.info("배너 수정: id={}, published={}", id, banner.isPublished());
        return BannerAdminView.of(banner, LocalDateTime.now());
    }

    @Transactional
    public void delete(Long id) {
        if (!bannerRepository.existsById(id)) {
            throw new IllegalArgumentException("배너를 찾을 수 없습니다.");
        }
        bannerRepository.deleteById(id);
        log.warn("배너 삭제: id={}", id);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
