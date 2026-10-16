package com.project.userservice.repository.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import com.project.userservice.model.ShippingAddress;
import com.project.userservice.model.User;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    
    // --- 비관적 잠금 적용 ---
    // 사용자 정보를 수정하기 전에 락을 걸어 다른 트랜잭션의 접근을 막습니다.    
    //@Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<User> findByName(String name);  // 별명으로 검색
    
    // --- 낙관적 잠금 적용 ---
    // 엔티티의 @Version 필드를 사용하여 동시성 충돌을 감지합니다.    
    //@Lock(LockModeType.OPTIMISTIC)
    Optional<User> findByUsername(String name);  // 실명으로 검색

    Boolean existsByUsername(String username);

    Boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    // User와 Roles를 한 번의 쿼리로 가져오기 위해 JOIN FETCH 사용
    @Query("SELECT u FROM User u JOIN FETCH u.role WHERE u.email = :email")
    Optional<User> findByEmailWithRoles(@Param("email") String email);

    /** 관리자 회원 목록 — 역할을 함께(사용자마다 따로 읽지 않는다). */
    @Query("SELECT u FROM User u LEFT JOIN FETCH u.role")
    List<User> findAllWithRole();

    void delete(User user);

    // 🐛 지웠다: findAddressesByUserUserId — ON 조건 없는 엔티티 조인이라 모든 사용자의 주소를 돌려줄 수 있는 모양이었다(쓰는 곳 없음).
    //    배송지는 ShippingAddressRepository 의 소유자 조건 쿼리로만 읽는다.

    @Transactional
    @Modifying
    @Query(value = "UPDATE User u SET u.defaultPaymentMethod = :paymentMethod WHERE u.userId = :userId")
    int updateDefaultPaymentMethod(@Param("userId") Long userId, @Param("paymentMethod") String paymentMethod);

    /**
     * 유예 기간이 끝나 최종 처리할 계정. 식별자만 가져온다 — 처리는 계정마다 독립
     * 트랜잭션이라, 엔티티를 미리 붙들고 있으면 그 사이 바뀐 값을 덮어쓸 수 있다.
     *
     * <p>{@code withdrawnAt is null} 이 "아직 처리 안 됨"의 판정이다. 처리한 행에 값이
     * 찍히므로 배치가 같은 계정을 두 번 집지 않는다.
     */
    @Query("select u.userId from User u "
            + "where u.withdrawalRequestedAt is not null and u.withdrawnAt is null "
            + "and u.withdrawalRequestedAt <= :cutoff "
            + "order by u.withdrawalRequestedAt asc")
    List<Long> findDueForWithdrawal(@Param("cutoff") java.time.LocalDateTime cutoff,
                                    org.springframework.data.domain.Pageable pageable);

    /**
     * 한 사람의 등급과 구매 집계를 갱신한다 (V9).
     *
     * <p><b>왜 엔티티를 불러 고치지 않는가.</b> 등급 배치는 전 고객을 돈다. 엔티티로 하면
     * 고객 수만큼 SELECT 가 나가고, 영속성 컨텍스트에 전 회원이 쌓이며, 무엇보다
     * {@code User} 에는 낙관적 락 버전이 있어 사용자가 같은 순간 프로필을 저장하면
     * <b>배치가 예외로 죽는다</b>. 등급은 사용자가 만지지 않는 컬럼이라 충돌시킬 이유가 없다.
     *
     * <p>{@code tierComputedAt} 을 <b>반드시</b> 같이 찍는다. 이 시각이 강등 대상을 고르는
     * 유일한 근거다({@link #demoteStale}).
     *
     * <p>🔴 <b>더 새 회차가 찍은 행은 덮지 않는다</b>({@code tierComputedAt <= :computedAt}). 두 회차가 겹치면(임대가 만료된 뒤
     * 늦게 끝난 회차 등) 옛 회차가 새 회차의 등급 · 시각을 옛 값으로 되돌리고, 새 회차의 강등이 그 행을 "손대지 않은 행" 으로 읽어
     * BASIC 으로 내렸다. 0 을 돌려주는 것은 정상이다 — 새 회차가 이미 매겼다.
     */
    // [JA] より新しい回が付けた行は上書きしない。古い回が新しい回の値を戻し、新しい回の降格がそれを BASIC に下げていた。
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.tier = :tier, u.lifetimeOrderCount = :lifetimeOrderCount, "
            + "u.recentPaidAmount = :recentPaidAmount, u.tierComputedAt = :computedAt "
            + "where u.userId = :userId and (u.tierComputedAt is null or u.tierComputedAt <= :computedAt)")
    int updateTier(@Param("userId") Long userId,
                   @Param("tier") com.project.common.constants.event.CustomerTier tier,
                   @Param("lifetimeOrderCount") long lifetimeOrderCount,
                   @Param("recentPaidAmount") long recentPaidAmount,
                   @Param("computedAt") java.time.LocalDateTime computedAt);

    /**
     * 이번 회차에 손대지 않은 등급 보유자를 일반으로 내린다 (V9).
     *
     * <p>🔴 <b>강등은 여기서만 일어난다.</b> order-service 는 최근 구매가 있는 고객만
     * 돌려주므로, 창에서 빠진 사람은 응답에 <b>없다</b>. "없다" 를 감지할 방법이
     * {@code tierComputedAt} 이 회차 시작보다 오래됐는가 하나뿐이다.
     *
     * <p>{@code recentPaidAmount} 도 0 으로 되돌린다 — 등급은 내려갔는데 근거 금액이
     * 옛날 값으로 남아 있으면 문의에 답할 때 오히려 헷갈린다.
     *
     * <p>{@code lifetimeOrderCount} 는 <b>건드리지 않는다.</b> 평생 구매 이력은 시간이
     * 지난다고 사라지지 않는다. 여기서 0 으로 밀면 오래 쉰 단골이 {@code NEVER_PURCHASED}
     * 로 분류돼 첫 구매 쿠폰을 받는다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.tier = com.project.common.constants.event.CustomerTier.BASIC, "
            + "u.recentPaidAmount = 0, u.tierComputedAt = :computedAt "
            + "where u.tier <> com.project.common.constants.event.CustomerTier.BASIC "
            + "and (u.tierComputedAt is null or u.tierComputedAt < :startedAt)")
    int demoteStale(@Param("startedAt") java.time.LocalDateTime startedAt,
                    @Param("computedAt") java.time.LocalDateTime computedAt);

    /**
     * 복제 대사(UserReplicationReconciler)의 대상 — {@code since} 이후 가입·변경·탈퇴한 사용자, id 순 커서.
     */
    @Query("select u from User u where (u.createdAt >= :since or u.updatedAt >= :since or u.withdrawnAt >= :since) "
            + "and u.userId > :afterId order by u.userId")
    List<User> findTouchedSince(@Param("since") java.time.LocalDateTime since, @Param("afterId") long afterId,
            org.springframework.data.domain.Pageable page);
}
