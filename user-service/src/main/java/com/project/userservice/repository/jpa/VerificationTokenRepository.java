package com.project.userservice.repository.jpa;

import com.project.userservice.model.VerificationToken;
import com.project.userservice.model.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

public interface VerificationTokenRepository extends JpaRepository<VerificationToken, Long> {

    // --- 비관적 잠금 적용 ---
    // 토큰의 유효성을 검증하고 사용 상태를 변경할 때, 락을 걸어 이중 사용을 막습니다.
    //@Lock(LockModeType.PESSIMISTIC_WRITE)
    // [JA] --- 悲観ロックの適用 ---
    //      トークンの有効性を検証して使用状態を変えるとき、ロックをかけて二重使用を防ぐ。
    VerificationToken findByToken(String token);
    
    // --- 낙관적 잠금 적용 ---
    //@Lock(LockModeType.OPTIMISTIC)
    // [JA] --- 楽観ロックの適用 ---
    VerificationToken findByUser(User user);

    Stream<VerificationToken> findAllByExpiryDateLessThan(Date now);

    void deleteByExpiryDateLessThan(Date now);

    @Modifying
    @Query("delete from VerificationToken t where t.expiryDate <= ?1")
    void deleteAllExpiredSince(Date now);

    /**
     * 끝내 인증하지 않은 계정 — 정리 대상 후보.
     *
     * <p>인증에 성공하면 {@code validateAndGetUser} 가 계정을 활성화하고 <b>토큰을 지운다</b>.
     * 그래서 "비활성 계정 + 남아 있는 토큰"은 곧 "한 번도 인증하지 않았다"는 뜻이다.
     * 만료 시각을 기준으로 삼는 이유는 재발송이 같은 행의 만료를 갱신하기 때문이다
     * ({@code createVerificationTokenForUser}) — 최근에 재발송을 요청한 사람은 자동으로 빠진다.
     *
     * <p>[JA] 最後まで認証しなかったアカウント — 整理対象の候補。
     * 認証に成功すると {@code validateAndGetUser} がアカウントを有効化しトークンを消す。だから「無効アカウント + 残っている
     * トークン」はそのまま「一度も認証していない」という意味である。有効期限を基準にする理由は、再送が同じ行の有効期限を
     * 更新するからである({@code createVerificationTokenForUser}) — 最近再送を求めた人は自動的に外れる。
     */
    @Query("select t from VerificationToken t join t.user u "
            + "where u.enabled = false and t.expiryDate < :cutoff "
            + "order by t.expiryDate asc")
    List<VerificationToken> findStaleUnverified(@Param("cutoff") Date cutoff, Pageable pageable);

    /**
     * 만료됐지만 <b>이미 활성화된</b> 계정의 토큰만 지운다.
     *
     * <p>⚠️ 만료된 토큰을 전부 지우면 안 된다. 비활성 계정의 토큰은 위 {@link #findStaleUnverified}
     * 의 <b>유일한 판단 근거</b>라서, 유예 기간이 끝나기 전에 지워 버리면 그 계정은 영원히
     * 후보에 오르지 않고 남는다. 지금 대부분의 만료 토큰은 인증 게이트가 꺼져 있던 동안
     * 만들어진 것들이다(계정은 활성, 링크는 안 누름).
     *
     * <p>[JA] 期限切れで、かつすでに有効化されたアカウントのトークンだけを消す。
     * ⚠️ 期限切れトークンを全部消してはならない。無効アカウントのトークンは上の {@link #findStaleUnverified} の唯一の判断
     * 根拠なので、猶予期間が終わる前に消してしまうとそのアカウントは永遠に候補に上がらず残る。今ある期限切れトークンの
     * 大半は、認証ゲートがオフだった間に作られたものである(アカウントは有効、リンクは押していない)。
     */
    @Modifying
    @Query("delete from VerificationToken t where t.expiryDate <= :now "
            + "and exists (select 1 from User u where u = t.user and u.enabled = true)")
    int deleteExpiredOfEnabledUsers(@Param("now") Date now);

    /** 회원 탈퇴 최종 처리용. */
    // [JA] 会員退会の最終処理用。
    @org.springframework.transaction.annotation.Transactional
    @Modifying(clearAutomatically = true)
    @Query("delete from VerificationToken t where t.user.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}