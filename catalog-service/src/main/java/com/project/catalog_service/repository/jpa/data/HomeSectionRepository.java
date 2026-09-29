package com.project.catalog_service.repository.jpa.data;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.project.catalog_service.model.HomeSection;

@Repository
public interface HomeSectionRepository extends JpaRepository<HomeSection, Long> {

    /**
     * 지금 노출되는 섹션.
     *
     * <p>기간 판정을 쿼리에서 하는 이유는 배너와 같다 — 지난 기획전 섹션이 쌓였을 때
     * 전부 읽어 와서 거르지 않기 위해서다. {@link HomeSection#isVisibleAt} 과 <b>같은
     * 판정</b>이어야 하며, 두 곳에 있는 규칙이라 테스트가 그 정의를 고정한다.
     */
    @Query("select s from HomeSection s where s.published = true "
            + "and (s.startsAt is null or s.startsAt <= :now) "
            + "and (s.endsAt is null or s.endsAt >= :now) "
            + "order by s.sortOrder asc, s.sectionId asc")
    List<HomeSection> findVisible(@Param("now") LocalDateTime now);

    /** 관리자 목록 — 비공개·기간 지난 것까지 전부. */
    List<HomeSection> findAllByOrderBySortOrderAscSectionIdAsc();
}
