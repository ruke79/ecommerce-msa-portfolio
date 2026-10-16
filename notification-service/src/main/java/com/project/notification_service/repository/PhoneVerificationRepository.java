package com.project.notification_service.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.project.notification_service.domain.PhoneVerification;

public interface PhoneVerificationRepository extends JpaRepository<PhoneVerification, Long> {

    /**
     * 검증용 — 이 번호로 발급된 <b>가장 최근의</b> 살아 있는 코드를 <b>잠그고</b> 읽는다({@code FOR UPDATE}).
     *
     * <p>최신 것만 보는 이유: 사용자가 "재전송"을 누르면 코드가 여러 개 살아 있게 되는데,
     * 옛 코드도 통과시키면 유효한 코드의 개수가 늘어나 무차별 대입이 그만큼 쉬워진다.
     *
     * <p>🐛 예전에는 잠금 없이 읽고 {@code attempts+1} 을 썼다. 동시 요청 N개가 모두 같은 attempts 를 읽어 5회 제한이 사실상
     * 사라졌다. 잠그면 같은 코드의 검증은 한 줄로 서고, 뒤의 요청은 앞이 올린 횟수를 본다.
     * [JA] 検証用 — 最新の有効なコードをロックして読む。以前はロックなしで読み attempts+1 を書いたため、同時リクエストで 5 回制限が消えていた。
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from PhoneVerification v where v.phoneE164 = :phone and v.consumed = false order by v.createdAt desc")
    java.util.List<PhoneVerification> findLatestForUpdate(@Param("phone") String phoneE164,
            org.springframework.data.domain.Pageable page);

    /** 만료된 코드 정리. 개인정보를 필요 이상으로 들고 있지 않기 위해서이기도 하다. */
    @Modifying
    @Query("delete from PhoneVerification v where v.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") LocalDateTime before);
}
