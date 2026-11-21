package com.project.userservice.repository.jpa;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.project.userservice.model.PasskeyCredential;

/** 패스키(docs/68 §20). 남의 것은 id 로 찾지 않는다 — 언제나 userId 와 함께 찾는다(IDOR). */
// [JA] パスキー(docs/68 §20)。他人のものを id で探さない — 常に userId と一緒に探す(IDOR)。
@Repository
public interface PasskeyCredentialRepository extends JpaRepository<PasskeyCredential, Long> {

    Optional<PasskeyCredential> findByCredentialId(String credentialId);

    /**
     * 로그인 뒤의 기록 — 서명 카운터는 <b>줄지 않게</b>(GREATEST), 백업 상태 · 마지막 사용 시각과 함께 한 문장으로.
     * 🐛 2026-11-21 까지 엔티티에 새 카운터를 넣고 통째로 save 했다 — 같은 패스키로 동시에 로그인하면 늦게 저장한 쪽의 더 작은 값이
     * 덮어써 카운터가 줄 수 있었고, 그러면 카운터 감소로 잡던 복제(docs/68)가 그 값 위로 다시 통과했다(docs/93 ③ 3단계).
     * [JA] ログイン後の記録 — 署名カウンターは減らないように(GREATEST)。以前はエンティティごと save しており、同じパスキーで同時に
     *      ログインすると遅い方の小さい値が上書きしてカウンターが減り得た(カウンター減少で捕まえる複製が再び通る)。
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "UPDATE users.passkey_credential SET sign_count = GREATEST(sign_count, :signCount), "
            + "backup_state = :backupState, last_used_at = :now WHERE passkey_id = :passkeyId", nativeQuery = true)
    int recordLogin(@org.springframework.data.repository.query.Param("passkeyId") Long passkeyId,
            @org.springframework.data.repository.query.Param("signCount") long signCount,
            @org.springframework.data.repository.query.Param("backupState") boolean backupState,
            @org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now);

    List<PasskeyCredential> findByUserIdOrderByCreatedAtAsc(Long userId);

    long countByUserId(Long userId);

    Optional<PasskeyCredential> findByPasskeyIdAndUserId(Long passkeyId, Long userId);

    /** 회원 탈퇴 최종 처리(WithdrawalService) — 그 사용자의 행을 모두 지운다. */
    // [JA] 会員退会の最終処理(WithdrawalService) — そのユーザーの行をすべて消す。
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from PasskeyCredential x where x.userId = :userId")
    int deleteByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);
}
