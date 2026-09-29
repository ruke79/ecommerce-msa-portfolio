package com.project.catalog_service.repository.jpa.data;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.project.catalog_service.model.Banner;

@Repository
public interface BannerRepository extends JpaRepository<Banner, Long> {

    /**
     * 지금 노출되는 배너.
     *
     * <p>기간 판정을 애플리케이션이 아니라 <b>쿼리</b>에서 하는 이유: 전부 읽어 와서
     * 거르면 종료된 기획전 배너가 수백 개 쌓였을 때 그걸 전부 메모리에 올린다. 노출
     * 개수 상한({@code content.banner.max-visible})도 DB 가 잘라 주는 편이 싸다.
     *
     * <p>{@link Banner#isVisibleAt} 과 <b>같은 판정</b>이어야 한다. 두 곳에 있는 규칙이라
     * 테스트로 묶어 두었다(ContentServiceTest).
     */
    @Query("select b from Banner b where b.published = true "
            + "and (b.startsAt is null or b.startsAt <= :now) "
            + "and (b.endsAt is null or b.endsAt >= :now) "
            + "order by b.sortOrder asc, b.bannerId asc")
    List<Banner> findVisible(@Param("now") LocalDateTime now, Pageable pageable);

    /** 관리자 목록 — 비공개·기간 지난 것까지 전부. */
    List<Banner> findAllByOrderBySortOrderAscBannerIdAsc();
}
