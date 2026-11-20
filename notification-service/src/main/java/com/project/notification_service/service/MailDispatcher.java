package com.project.notification_service.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.project.notification_service.domain.MailMessage;
import com.project.notification_service.domain.MailStatus;
import com.project.notification_service.domain.NotificationPreference;
import com.project.notification_service.mail.EmailAddresses;
import com.project.notification_service.mail.MailResult;
import com.project.notification_service.mail.MailSender;
import com.project.notification_service.mail.MailTemplates;
import com.project.notification_service.repository.MailMessageRepository;
import com.project.notification_service.repository.NotificationPreferenceRepository;
import com.project.notification_service.sms.PhoneNumbers.Region;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 발송 대기 메일을 집어 내보낸다. {@link SmsDispatcher} 의 메일판이다.
 *
 * <p>구조가 같은 것은 우연이 아니다 — 둘 다 아웃박스 폴러이고, 같은 함정이 있다(docs/21):
 *
 * <ul>
 *   <li><b>선점.</b> {@code FOR UPDATE SKIP LOCKED}. 잠금이 없으면 레플리카 둘이 같은 행을 집어
 *       메일이 두 통 간다.</li>
 *   <li><b>동기 발송.</b> 트랜잭션 안에서 결과를 확정한다. 비동기 콜백으로 상태를 갱신하면
 *       잠금이 상태 확정 전에 풀려 창이 열린다.</li>
 *   <li><b>일시 실패를 영구 실패로 적지 않는다.</b> 메일 서버가 잠깐 흔들렸다고 FAILED 로 두면
 *       사용자는 가입 확인 메일을 영영 못 받는다.</li>
 * </ul>
 *
 * <p>SMS 와 다른 점이 둘 있다. 첫째, 재시도 상한이 더 크다(기본 5회) — 메일은 건당 과금이
 * 아니므로 몇 번 더 시도해도 비용이 아니고, SMTP 는 그레이리스팅 때문에 첫 시도가 실패하는 것이
 * 정상인 경우가 있다. 둘째, <b>오래된 행의 파라미터를 주기적으로 지운다</b> — 링크가 자격증명이기
 * 때문이다(§{@link #scrubStaleParams}).
 *
 * <p>[JA] 送信待ちメールを拾って送り出す。{@link SmsDispatcher} のメール版だ。構造が同じなのは偶然ではない —
 * どちらもアウトボックスポーラーで、同じ落とし穴がある(docs/21): <b>先取り</b> —
 * {@code FOR UPDATE SKIP LOCKED}。ロックがないとレプリカ二つが同じ行を拾いメールが二通届く。
 * <b>同期送信</b> — トランザクション内で結果を確定する。非同期コールバックで状態を更新すると、
 * 状態確定前にロックが外れて隙間が開く。<b>一時失敗を恒久失敗と記録しない</b> —
 * メールサーバーが少し揺れただけで FAILED にすると、ユーザーは登録確認メールを永遠に受け取れない。SMS
 * と違う点が二つある。第一に、再試行上限がより大きい(既定 5 回) —
 * メールは一通ごとの課金ではないので何度か多く試してもコストにならず、SMTP
 * はグレイリスティングのため初回が失敗するのが正常な場合がある。第二に、
 * <b>古い行のパラメータを定期的に消す</b> — リンクが資格情報だからだ(§{@link #scrubStaleParams})。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailDispatcher {

    private final MailMessageRepository mailMessageRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final MailService mailService;
    private final MailSender mailSender;

    @Value("${notification.mail.batch-size:50}")
    private int batchSize;

    /**
     * 이 횟수를 넘기면 포기한다.
     *
     * <p>SMS(3회)보다 큰 이유: 그레이리스팅을 쓰는 수신 서버는 <b>처음 온 발신자를 일부러 한 번
     * 거절</b>하고 재시도를 기다린다(스팸 봇은 재시도하지 않는다는 전제). 상한이 작으면 그런
     * 서버로는 첫 메일이 영영 안 간다.
     *
     * <p>[JA] この回数を超えたら諦める。SMS(3 回)より大きい理由: グレイリスティングを使う受信サーバーは<b>初めて来
     * た送信者をわざと一度拒否</b>し、再試行を待つ(スパムボットは再試行しないという前提)。
     * 上限が小さいとそうしたサーバーには最初のメールが永遠に届かない。
     */
    @Value("${notification.mail.max-attempts:5}")
    private int maxAttempts;

    /**
     * 첫 재시도까지 기다리는 시간. 시도할 때마다 두 배가 된다.
     *
     * <p>🔴 <b>기본값이 1분인 것은 상한 5회와 짝이다.</b> 이게 없던 동안 재시도는 이름뿐이었다 —
     * 폴링 주기가 5초라 5회가 20초 만에 소진됐고, 그레이리스팅(수 분 대기)을 쓰는 서버로는
     * 첫 메일이 영영 나가지 못했다. 1·2·4·8분이면 마지막 시도가 약 15분 뒤라, 그레이리스팅이
     * 풀리는 구간을 지난다.
     *
     * <p>[JA] 最初の再試行までの待ち時間。試すたびに倍になる。🔴 <b>既定値が 1 分なのは上限 5
     * 回と対になっている。</b>これがなかった間、再試行は名ばかりだった — ポーリング周期が 5 秒なので 5
     * 回が 20 秒で尽き、グレイリスティング(数分待ち)を使うサーバーには最初のメールが永遠に出せなかった。
     * 1・2・4・8 分なら最後の試行が約 15 分後になり、グレイリスティングが解ける区間を越える。
     */
    @Value("${notification.mail.retry-backoff-ms:60000}")
    private long retryBackoffMs;

    /** 간격 상한. 오래 걸리는 장애에서도 하루에 몇 번은 시도한다. */
    // [JA] 間隔の上限。長引く障害でも一日に何度かは試す。
    @Value("${notification.mail.retry-backoff-max-ms:1800000}")
    private long retryBackoffMaxMs;

    /** 이 시간이 지난 미발송 행의 링크는 지운다. 링크 자체가 24시간이면 만료된다. */
    // [JA] この時間を過ぎた未送信行のリンクは消す。リンク自体が 24 時間で期限切れになる。
    @Value("${notification.mail.params-retention-hours:24}")
    private int paramsRetentionHours;

    /** 끝난 메일 행의 보관 일수 — 문의 대응("왜 메일이 안 왔나")에는 충분히 길게, 주소를 무기한 두지는 않게. 30일 아래로는 안 내려간다. */
    // [JA] 終わったメール行の保管日数 — 問い合わせ対応(「なぜメールが来ないか」)には十分長く、
    //      アドレスを無期限には置かないように。30 日未満には下がらない。
    @Value("${notification.mail.retention-days:180}")
    private int retentionDays = 180;

    @Transactional
    @Scheduled(fixedDelayString = "${notification.mail.dispatch-interval-ms:5000}",
               initialDelayString = "${notification.mail.dispatch-initial-delay-ms:10000}")
    public void dispatch() {
        List<MailMessage> batch = mailMessageRepository.claimForSend(
                List.of(MailStatus.PENDING, MailStatus.RETRYABLE),
                maxAttempts,
                LocalDateTime.now(ZoneOffset.UTC),
                PageRequest.of(0, batchSize));

        if (batch.isEmpty()) {
            return;
        }
        log.debug("메일 발송 대상 {}건 선점", batch.size());

        for (int i = 0; i < batch.size(); i++) {
            if (!sendOne(batch.get(i))) {
                // 메일 서버가 흔들리는 중이다. 남은 배치를 계속 시도하면 타임아웃 × 배치 크기만큼
                // 행 잠금과 커넥션을 붙잡는다. 나머지는 다음 주기가 가져간다.
                // [JA] メールサーバーが揺れている最中だ。残りのバッチを試し続けるとタイムアウト × バッチサイズ分、
                //      行ロックとコネクションを掴む。残りは次の周期が持っていく。
                log.warn("일시적 실패로 배치 중단 — 남은 {}건은 다음 주기에", batch.size() - i - 1);
                break;
            }
        }
    }

    /** @return 상태가 확정됐으면 true, 일시적 실패라 배치를 멈춰야 하면 false */
    // [JA] @return 状態が確定したら true、一時失敗でバッチを止めるべきなら false
    private boolean sendOne(MailMessage message) {
        Region region = resolveRegion(message);

        MailTemplates.Rendered rendered =
                MailTemplates.render(region, message.getKind(), mailService.readParams(message));

        MailResult result = mailSender.send(
                message.getToEmail(), rendered.subject(), rendered.text(), rendered.html());

        switch (result.status()) {
            case SENT -> {
                // recordAttempt 가 종료 상태에서 params 를 지운다 — 링크를 발송 후까지 들고
                // 있지 않는다.
                // [JA] recordAttempt が終了状態で params を消す — リンクを送信後まで持たない。
                message.recordAttempt(MailStatus.SENT, null);
                return true;
            }
            case PERMANENT -> {
                // 주소가 없거나 도메인이 없다. 재시도하면 하드 바운스만 쌓인다.
                // [JA] アドレスがないかドメインがない。再試行してもハードバウンスが溜まるだけだ。
                log.error("메일 영구 실패: mailId={} to={} detail={}",
                        message.getMailId(), EmailAddresses.mask(message.getToEmail()), result.detail());
                message.recordAttempt(MailStatus.FAILED, result.detail());
                return true;
            }
            default -> {
                // 다음에 집어도 되는 시각을 함께 정한다. 이게 없으면 5초 뒤 같은 행을 또 집어
                // 상한이 20초 만에 소진된다 — 이 채널의 재시도를 실제로 만드는 한 줄이다.
                // [JA] 次に拾ってよい時刻を併せて決める。これがないと 5 秒後に同じ行をまた拾い、上限が 20 秒で尽きる —
                //      このチャネルの再試行を実際に作る一行だ。
                message.scheduleRetry(result.detail(), retryBackoffMs, retryBackoffMaxMs);

                if (!message.canRetry(maxAttempts)) {
                    log.warn("메일 재시도 상한 도달, 포기: mailId={} to={} attempts={}",
                            message.getMailId(), EmailAddresses.mask(message.getToEmail()),
                            message.getAttempts());
                    // giveUp 이다 — recordAttempt 를 부르면 방금의 한 번이 두 번으로 기록된다.
                    // [JA] giveUp である — recordAttempt を呼ぶと今の一回が二回と記録される。
                    message.giveUp("재시도 상한 초과");
                    return true;
                }
                log.debug("메일 재시도 예약: mailId={} attempts={} nextAttemptAt={}",
                        message.getMailId(), message.getAttempts(), message.getNextAttemptAt());
                return false;
            }
        }
    }

    /**
     * 문구 언어를 정한다. 사용자 설정이 있으면 그쪽이 우선한다.
     *
     * <p>설정이 없는 경우가 정상적으로 존재한다 — 가입 확인 메일은 사용자가 알림 설정을 만들기
     * 전에 나간다. 그때는 명령에 실려 온 로케일을 쓰고, 그것도 없으면 한국어다.
     *
     * <p>[JA] 文言の言語を決める。ユーザー設定があればそちらが優先する。設定がない場合は正常に存在する —
     * 登録確認メールはユーザーが通知設定を作る前に出る。そのときは命令に載ってきたロケールを使い、
     * それもなければ韓国語だ。
     */
    private Region resolveRegion(MailMessage message) {
        if (message.getRecipientUserId() != null) {
            NotificationPreference preference =
                    preferenceRepository.findByUserId(message.getRecipientUserId()).orElse(null);
            if (preference != null && preference.getRegion() != null) {
                try {
                    return Region.valueOf(preference.getRegion());
                } catch (IllegalArgumentException ignored) {
                    // 설정에 알 수 없는 값이 들어 있다. 명령의 로케일로 넘어간다.
                    // [JA] 設定に未知の値が入っている。命令のロケールにフォールバックする。
                }
            }
        }
        return MailTemplates.regionOf(message.getLocale());
    }

    /**
     * 끝내 발송되지 못한 오래된 행에서 링크를 지운다.
     *
     * <p>정상 경로에서는 발송 직후 지워지지만, 디스패처가 꺼져 있던 사이 쌓인 행이나 상한을
     * 넘기기 전에 방치된 행이 남을 수 있다. 링크는 24시간이면 어차피 만료되므로 그 뒤로 값을
     * 들고 있을 이유가 없다 — <b>DB 덤프 한 번이 곧 계정 탈취 수단이 되는 것을 막는다.</b>
     *
     * <p>행 자체는 남긴다. "왜 이 사용자에게 메일이 안 갔나"는 나중에도 볼 수 있어야 한다.
     *
     * <p>[JA] 最後まで送信されなかった古い行からリンクを消す。正常経路では送信直後に消えるが、
     * ディスパッチャが止まっていた間に溜まった行や、上限を越える前に放置された行が残りうる。リンクは 24
     * 時間でどうせ期限切れになるので、その後は値を持つ理由がない — <b>DB
     * ダンプ一回がそのままアカウント乗っ取り手段になるのを防ぐ。</b>行自体は残す。
     * 「なぜこのユーザーにメールが届かなかったか」は後でも見られる必要がある。
     */
    @Transactional
    @Scheduled(fixedDelayString = "${notification.mail.scrub-interval-ms:3600000}",
               initialDelayString = "${notification.mail.scrub-initial-delay-ms:60000}")
    public void scrubStaleParams() {
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC).minusHours(paramsRetentionHours);
        int scrubbed = mailMessageRepository.scrubParamsOlderThan(before);
        if (scrubbed > 0) {
            log.info("만료된 메일 링크 {}건을 지웠습니다(행은 남깁니다)", scrubbed);
        }
    }

    /**
     * 끝난 메일 행 정리 — 매일, 보관 일수({@code notification.mail.retention-days}, 기본 180일)가 지난 SENT · FAILED 행을 지운다.
     *
     * <p>🐛 예전에는 행이 영구히 남아 받는 주소({@code to_email}, 개인정보)가 무기한 쌓였다. 대기 중인 행(PENDING · RETRYABLE)은
     * 건드리지 않는다. 삭제는 같은 조건이라 파드가 여럿이어도 겹쳐서 해가 없다.
     * [JA] 終わったメール行の掃除 — 毎日、保存日数(既定 180 日)を過ぎた SENT・FAILED 行を削除する。以前は宛先(個人情報)が無期限に溜まっていた。
     */
    @Transactional
    @Scheduled(cron = "${notification.mail.purge-cron:0 25 4 * * *}")
    public int purgeFinished() {
        int days = Math.max(30, retentionDays);
        int deleted = mailMessageRepository.deleteFinishedOlderThan(LocalDateTime.now(ZoneOffset.UTC).minusDays(days));
        if (deleted > 0) {
            log.info("끝난 메일 {}건을 지웠습니다(보관 {}일)", deleted, days);
        }
        return deleted;
    }
}
