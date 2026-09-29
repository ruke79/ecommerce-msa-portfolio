package com.project.catalog_service.repository.jpa.data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.project.catalog_service.model.Event;
import com.project.catalog_service.model.EventStatus;

@Repository
public interface EventRepository extends JpaRepository<Event, Long> {

    Optional<Event> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<Event> findAllByOrderByEventIdDesc();

    List<Event> findByStatusOrderByStartsAtDesc(EventStatus status);

    /**
     * 진행 중인 기획전의 대상 상품과 할인율.
     *
     * <p>기획전 엔티티를 통째로 읽지 않는다 — 할인 합성에 필요한 것은 두 숫자뿐이고,
     * 이 결과는 1분마다 갱신된다({@code EventPricing}). 제목·설명·배너 이미지까지 끌고
     * 오면 그만큼이 매분 낭비다.
     */
    @Query("""
            select p.productId, p.discountRate
              from Event e join e.products p
             where e.status = com.project.catalog_service.model.EventStatus.ACTIVE
            """)
    List<Object[]> activeProductDiscounts();

    /**
     * 진행 중인 기획전의 대상 <b>카테고리</b>와 할인율.
     *
     * <p>카테고리를 상품으로 펼치는 것은 {@code EventPricing} 이 한다 — 여기서 조인해
     * 한 번에 가져오면 스키마를 가로지르는 조인이 된다({@code content.event_category} 와
     * {@code product.product}). 두 스키마를 나눠 둔 경계를 쿼리로 굳히지 않는다.
     */
    @Query("""
            select c.categoryId, c.discountRate
              from Event e join e.categories c
             where e.status = com.project.catalog_service.model.EventStatus.ACTIVE
            """)
    List<Object[]> activeCategoryDiscounts();

    /** 시작할 때가 된 것. 스케줄러가 1분마다 부른다. */
    @Query("""
            select e from Event e
             where e.status = com.project.catalog_service.model.EventStatus.SCHEDULED
               and e.startsAt is not null
               and e.startsAt <= :now
            """)
    List<Event> dueToStart(@Param("now") LocalDateTime now);

    /** 끝낼 때가 된 것. 종료 시각은 <b>포함</b>이라 지난 뒤에야 끝난다. */
    @Query("""
            select e from Event e
             where e.status = com.project.catalog_service.model.EventStatus.ACTIVE
               and e.endsAt is not null
               and e.endsAt < :now
            """)
    List<Event> dueToEnd(@Param("now") LocalDateTime now);

    /**
     * 상태를 <b>조건부로</b> 바꾼다.
     *
     * <p>왜 엔티티를 읽어 세터로 바꾸지 않는가: 인스턴스가 둘 이상이면 스케줄러도 둘이다.
     * 둘 다 "시작할 때가 됐다"를 보고 둘 다 시작 알림을 발행한다. 여기서 {@code where
     * status = :from} 을 걸면 <b>먼저 도착한 하나만</b> 행을 바꾸고, 나머지는 0 을 받는다.
     * 바꾼 쪽만 발행하면 알림이 하나다.
     *
     * <p>(알림 자체도 {@code eventId} 로 한 번 더 걸러지지만, 그건 최종 방어선이다.
     * 발행 자체를 한 번만 하는 편이 낫다 — 카프카에 나간 뒤에 거르는 것보다 싸다.)
     *
     * @return 실제로 바뀐 행 수(0 또는 1)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Event e set e.status = :to where e.eventId = :id and e.status = :from")
    int transition(@Param("id") Long id,
                   @Param("from") EventStatus from,
                   @Param("to") EventStatus to);
}
