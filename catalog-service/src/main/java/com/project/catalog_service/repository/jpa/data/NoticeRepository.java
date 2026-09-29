package com.project.catalog_service.repository.jpa.data;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.project.catalog_service.model.Notice;

@Repository
public interface NoticeRepository extends JpaRepository<Notice, Long> {

    /**
     * 공개된 공지 — 고정 먼저, 그다음 최신순.
     *
     * <p>정렬을 {@code Pageable} 의 sort 로 받지 않고 쿼리에 고정한 이유: 클라이언트가
     * 정렬 필드를 문자열로 보내면 그게 곧 JPQL 에 들어가는 입력이 된다. 공지 목록의
     * 정렬은 협상 대상이 아니므로 서버가 정한다.
     *
     * <p>{@code publishedAt} 이 null 인 공개 글은 있을 수 없지만(공개 전환 시 찍는다),
     * 과거 데이터를 대비해 {@code nulls last} 로 둔다.
     */
    @Query("select n from Notice n where n.published = true "
            + "order by n.pinned desc, n.publishedAt desc nulls last, n.noticeId desc")
    Page<Notice> findPublished(Pageable pageable);

    @Query("select n from Notice n where n.published = true and n.noticeId = :id")
    Optional<Notice> findPublishedById(Long id);

    Page<Notice> findAllByOrderByNoticeIdDesc(Pageable pageable);
}
