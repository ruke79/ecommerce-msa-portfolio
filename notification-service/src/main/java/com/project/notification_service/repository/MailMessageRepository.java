package com.project.notification_service.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.project.notification_service.domain.MailMessage;
import com.project.notification_service.domain.MailStatus;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface MailMessageRepository extends JpaRepository<MailMessage, Long> {

    Optional<MailMessage> findBySourceEventId(String sourceEventId);

    /**
     * 발송 대기·재시도 대상을 <b>선점</b>한다.
     *
     * <p>{@code NotificationRepository.claimForSms} 와 같은 이유로
     * {@code FOR UPDATE SKIP LOCKED} 다(docs/21). 잠금이 없으면 레플리카 둘이 같은 행을 집어
     * <b>같은 메일이 두 통</b> 간다. 확인 메일 두 통은 성가신 정도지만, 비밀번호 재설정 메일이
     * 두 통이면 유효한 재설정 링크가 두 개 살아 있게 된다.
     *
     * <p>{@code lock.timeout = -2} 는 하이버네이트에서 {@code SKIP LOCKED} 를 뜻한다. 그냥
     * {@code FOR UPDATE} 로 두면 잠금을 기다리며 두 레플리카가 직렬화되어, 잠금은 맞지만
     * 처리량이 레플리카 하나짜리로 떨어진다.
     *
     * <p>🔴 <b>{@code next_attempt_at} 조건이 이 채널의 재시도를 실제로 만든다.</b> 예전에는
     * 상한에 닿지 않은 행을 무조건 다시 집었고, 폴링 주기가 5초라 5회 재시도가 20초 만에
     * 소진됐다 — 상한을 5로 둔 이유(그레이리스팅)가 통째로 무효가 됐다. 경위는
     * {@link com.project.notification_service.domain.RetryBackoff} 에 있다.
     *
     * <p>{@code is null} 을 함께 보는 것은 생략할 수 없다. 새로 만들어진 행과 이 컬럼이 없던
     * 시절의 행은 모두 null 인데, 조건에서 빠뜨리면 <b>그 행들이 영영 선택되지 않는다.</b>
     *
     * <p>[JA] 送信待ち・再試行対象を<b>先取り</b>する。{@code NotificationRepository.claimForSms} と同じ理由で
     * {@code FOR UPDATE SKIP LOCKED} だ(docs/21)。ロックがないとレプリカ二つが同じ行を拾い<b>同じメールが二
     * 通</b>届く。確認メール二通は煩わしい程度だが、パスワード再設定メールが二通なら有効な再設定リンクが二
     * つ生きていることになる。{@code lock.timeout = -2} は Hibernate で {@code SKIP LOCKED} を意味する。
     * 単に {@code FOR UPDATE} にするとロック待ちで二つのレプリカが直列化され、
     * ロックは正しいがスループットがレプリカ一つ分に落ちる。🔴 <b>{@code next_attempt_at}
     * の条件がこのチャネルの再試行を実際に作る。</b>以前は上限に達していない行を無条件に拾い直し、
     * ポーリング周期が 5 秒なので 5 回の再試行が 20 秒で尽きた — 上限を 5 にした理由(グレイリスティング)
     * がまるごと無効になった。経緯は {@link com.project.notification_service.domain.RetryBackoff} にある。
     * {@code is null} を併せて見るのは省けない。新しく作られた行とこのカラムがなかった頃の行はどちらも
     * null で、条件から漏らすと<b>それらの行が永遠に選ばれない。</b>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
           select m from MailMessage m
            where m.status in :statuses
              and m.attempts < :maxAttempts
              and (m.nextAttemptAt is null or m.nextAttemptAt <= :now)
            order by m.createdAt asc
           """)
    List<MailMessage> claimForSend(@Param("statuses") List<MailStatus> statuses,
                                   @Param("maxAttempts") int maxAttempts,
                                   @Param("now") LocalDateTime now,
                                   Pageable pageable);

    /**
     * 오래된 미발송 메일의 파라미터를 지운다.
     *
     * <p><b>왜 필요한가.</b> {@code params} 에는 가입 확인 링크·비밀번호 재설정 링크가 들어 있다 —
     * 그 자체로 계정을 활성화하거나 비밀번호를 바꿀 수 있는 자격증명이다. 정상 경로에서는 발송이
     * 끝나는 즉시 {@code MailMessage#clearParams} 가 지우지만, <b>끝내 종료 상태에 도달하지 못한
     * 행</b>이 남을 수 있다(디스패처가 꺼져 있던 사이 쌓인 것 등).
     *
     * <p>링크 자체는 24시간이면 만료되므로 그 뒤로는 값이 남아 있을 이유가 없다. 행은 남긴다 —
     * "왜 이 사용자에게 메일이 안 갔나"를 나중에 볼 수 있어야 하기 때문이다. 지우는 것은
     * 자격증명뿐이다.
     *
     * <p>[JA] 古い未送信メールのパラメータを消す。<b>なぜ必要か。</b>{@code params}
     * には登録確認リンク・パスワード再設定リンクが入っている — それ自体でアカウントを有効化したりパスワード
     * を変えたりできる資格情報だ。正常経路では送信が終わった直後に {@code MailMessage#clearParams}
     * が消すが、<b>最後まで終了状態に至らなかった行</b>が残りうる(ディスパッチャが止まっていた間に溜まった
     * ものなど)。リンク自体は 24 時間で期限切れになるので、その後は値を残す理由がない。行は残す —
     * 「なぜこのユーザーにメールが届かなかったか」を後で見られる必要があるからだ。消すのは資格情報だけだ。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           update MailMessage m
              set m.params = null
            where m.params is not null
              and m.createdAt < :before
           """)
    int scrubParamsOlderThan(@Param("before") LocalDateTime before);

    /** 끝난(보냈거나 영구 실패한) 오래된 메일 행을 지운다 — 받는 주소(개인정보)를 무기한 들고 있지 않는다. */
    // [JA] 終わった(送ったか恒久失敗した)古いメール行を消す — 宛先アドレス(個人情報)を無期限に持たない。
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           delete from MailMessage m
            where m.status in (com.project.notification_service.domain.MailStatus.SENT,
                               com.project.notification_service.domain.MailStatus.FAILED)
              and m.createdAt < :before
           """)
    int deleteFinishedOlderThan(@Param("before") LocalDateTime before);
}
