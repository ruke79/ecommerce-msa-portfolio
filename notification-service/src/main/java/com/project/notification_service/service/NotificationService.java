package com.project.notification_service.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.notification_service.domain.MailKind;
import com.project.notification_service.domain.Notification;
import com.project.notification_service.domain.NotificationPreference;
import com.project.notification_service.domain.NotificationType;
import com.project.notification_service.domain.SmsStatus;
import com.project.notification_service.repository.NotificationPreferenceRepository;
import com.project.notification_service.repository.NotificationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 알림 생성·조회.
 *
 * <p>알림을 만드는 것과 SMS 를 보내는 것을 <b>분리</b>한다. 이벤트를 받은 자리에서 바로
 * 문자를 보내면 두 가지가 곤란해진다:
 *
 * <ul>
 *   <li>SMS 게이트웨이가 느리거나 죽으면 Kafka 컨슈머가 그만큼 붙잡힌다 — 알림 하나 때문에
 *       전체 소비가 밀린다.</li>
 *   <li>전송 실패를 컨슈머 재시도로 처리하면 <b>알림이 중복 생성</b>된다. 아웃박스에서 겪은
 *       것과 같은 구조다(docs/21).</li>
 * </ul>
 *
 * <p>그래서 컨슈머는 알림을 저장만 하고 {@code PENDING} 으로 표시한다. 실제 발송은
 * {@link SmsDispatcher} 가 별도로 집어 간다.
 *
 * <p>[JA] 通知の作成・照会。通知を作ることと SMS を送ることを<b>分離</b>する。イベントを受けたその場ですぐ SMS
 * を送ると二つ困る: SMS ゲートウェイが遅いか死んでいると Kafka コンシューマがその分掴まれる —
 * 通知一つのために全体の消費が遅れる。送信失敗をコンシューマの再試行で処理すると<b>通知が重複作成</b>され
 * る。アウトボックスで経験したのと同じ構造だ(docs/21)。そのためコンシューマは通知を保存して
 * {@code PENDING} と印を付けるだけにする。実際の送信は {@link SmsDispatcher} が別に拾っていく。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final MailService mailService;

    /**
     * 알림을 만든다. 같은 {@code sourceEventId} 로는 한 번만 만들어진다.
     *
     * <p>[JA] 通知を作る。同じ {@code sourceEventId} では一度だけ作られる。
     *
     * @return 새로 만들어졌으면 그 알림, 이미 있으면 비어 있음
     *         [JA] 新しく作られたらその通知、すでにあれば空
     */
    @Transactional
    public Optional<Notification> create(Long recipientId, NotificationType type,
                                         String title, String content, String url,
                                         String sourceEventId, Map<String, String> smsParams) {

        // 1차 방어 — 이미 있으면 조용히 넘어간다.
        // [JA] 一次防御 — すでにあれば静かに通り過ぎる。
        if (sourceEventId != null && notificationRepository.findBySourceEventId(sourceEventId).isPresent()) {
            log.debug("이미 처리된 이벤트, 건너뜀: {}", sourceEventId);
            return Optional.empty();
        }

        SmsStatus smsStatus = resolveSmsStatus(recipientId, type);

        Notification notification = Notification.builder()
                .recipientId(recipientId)
                .type(type)
                .title(title)
                .content(content)
                .url(url)
                .sourceEventId(sourceEventId)
                .smsStatus(smsStatus)
                .build();

        Notification saved;
        try {
            saved = notificationRepository.saveAndFlush(notification);
        } catch (DataIntegrityViolationException e) {
            // 2차 방어 — 위 조회와 저장 사이에 다른 레플리카가 먼저 넣었다. UNIQUE 제약이
            // 막아 준 것이므로 정상 흐름으로 취급한다. 이게 없으면 레플리카 2개에서
            // 같은 문자가 두 통 나간다.
            // [JA] 二次防御 — 上の照会と保存の間に別のレプリカが先に入れた。UNIQUE
            //      制約が防いでくれたものなので正常な流れとして扱う。これがないとレプリカ 2 つで同じ SMS が二通出る。
            log.debug("동시 생성 감지, 건너뜀: {}", sourceEventId);
            return Optional.empty();
        }

        // 알림이 실제로 만들어진 경우에만 메일을 건다. 위 UNIQUE 방어를 통과했다는 것은
        // 이 이벤트를 우리가 처음 처리했다는 뜻이므로, 메일도 여기서 한 번만 걸린다.
        // [JA] 通知が実際に作られた場合にだけメールをかける。上の UNIQUE 防御を通ったということはこのイベントを我々が初
        //      めて処理したという意味なので、メールもここで一度だけかかる。
        enqueueMail(recipientId, type, title, url, sourceEventId);

        return Optional.of(saved);
    }

    /**
     * 같은 알림을 메일로도 보낸다.
     *
     * <p>SMS 와 달리 <b>여기서 상태를 알림 행에 적지 않는다.</b> 메일은 별도 테이블
     * ({@code mail_message})을 쓰기 때문이다 — 가입 확인처럼 알림 행이 아예 없는 메일도 있어서
     * 알림에 딸린 컬럼으로는 표현할 수 없다(MailMessage 클래스 주석 참조).
     *
     * <p><b>같은 트랜잭션 안에서 돈다.</b> 아래 {@code catch} 는 설정 조회 같은 평범한
     * 런타임 예외로 알림까지 롤백되는 것을 막지만, <b>제약 위반은 막지 못한다</b> — JPA 가
     * 트랜잭션을 rollback-only 로 표시해 버리므로 잡아도 커밋되지 않는다.
     *
     * <p>그래도 별도 트랜잭션({@code REQUIRES_NEW})으로 떼지 않았다. 여기까지 왔다는 것은
     * 알림 행이 자기 UNIQUE 를 통과했다는 뜻이고, 메일 키는 그 값에서 파생되므로 실제로 부딪힐
     * 일이 사실상 없다. 반면 {@code REQUIRES_NEW} 는 그 구간 동안 커넥션을 하나 더 붙잡아
     * 풀 총합 가드레일(docs/19 §3, B-32)에 그대로 영향을 준다 — 일어나지 않는 경우를 위해
     * 치를 비용이 아니다.
     *
     * <p>[JA] 同じ通知をメールでも送る。SMS と違い<b>ここで状態を通知行に書かない。</b>メールは別テーブ
     * ル({@code mail_message})を使うからだ — 登録確認のように通知行がまったくないメールもあり、
     * 通知に付属するカラムでは表現できない(MailMessage クラスのコメント参照)。
     * <b>同じトランザクション内で動く。</b>下の {@code catch} は設定照会のような普通のランタイム例外で通知
     * までロールバックされるのを防ぐが、<b>制約違反は防げない</b> — JPA がトランザクションを rollback-only
     * にしてしまうので、捕まえてもコミットされない。それでも別トランザクション({@code REQUIRES_NEW})
     * に切り離さなかった。ここまで来たということは通知行が自分の UNIQUE を通ったという意味で、
     * メールキーはその値から派生するので実際にぶつかることは事実上ない。一方 {@code REQUIRES_NEW}
     * はその区間の間コネクションをもう一つ掴み、プール総和のガードレール(docs/19 §3, B-32)
     * にそのまま影響する — 起きないケースのために払うコストではない。
     */
    private void enqueueMail(Long recipientId, NotificationType type,
                             String title, String url, String sourceEventId) {
        NotificationPreference preference = preferenceRepository.findByUserId(recipientId).orElse(null);
        if (preference == null || !preference.shouldSendEmail(type)) {
            return;
        }

        MailKind kind = MailKind.forNotification(type).orElse(null);
        if (kind == null) {
            return;   // shouldSendEmail 이 이미 걸렀지만, 두 판정이 어긋나도 안전하게. ／ [JA] shouldSendEmail がすでに絞ったが、二つの判定がずれても安全に。
        }

        // 🐛 2026-10-24 운영: 지역(region)은 전화번호를 인증해야 채워진다 — 인증하지 않은 사용자는 locale 이 null 이고,
        //    예전에는 Locale.forLanguageTag(null) 이 NPE 를 던졌다. 이 줄이 아래 try 밖이라 알림 생성 자체가 실패해 주문 · 결제 ·
        //    배송 · 쿠폰 안내가 모두 DLT 로 갔다(10-16 부터 order.DLT · payment.DLT 의 notification 그룹 8쌍). 지역을 모르면
        //    제목은 그대로(한국어) — 메일 언어는 디스패처의 기본값. 메일 준비 전부를 try 안에 둔다(알림은 메일과 상관없이 만든다).
        // [JA] 地域は電話番号の認証で入る — 未認証なら locale が null で Locale.forLanguageTag(null) が NPE、try の外だったので
        //      通知の作成ごと失敗して注文 · 決済などの案内がすべて DLT に行った。地域が分からなければ件名はそのまま。
        String locale = localeOf(preference.getRegion());
        try {
            Map<String, String> params = new HashMap<>();
            if (title != null) {
                // 제목은 한국어로 저장된다(NotificationTexts) — 일본 지역이면 메일에 넣기 전에 바꾼다(docs/60 §9).
                // [JA] 件名は韓国語で保存される(NotificationTexts) — 日本地域ならメールに入れる前に置き換える(docs/60
                //      §9)。
                params.put("subject", com.project.common.i18n.ClientText.localize(title,
                        locale == null ? null : java.util.Locale.forLanguageTag(locale)));
            }
            if (url != null) {
                params.put("url", url);
            }

            // 알림과 다른 멱등 키를 쓴다. 같은 값을 쓰면 두 테이블의 UNIQUE 가 서로를 막지는
            // 않지만, 로그와 운영 조회에서 "알림 id"와 "메일 id"가 같은 문자열을 가리켜 혼동된다.
            // [JA] 通知とは別の冪等キーを使う。同じ値を使っても二つのテーブルの UNIQUE が互いを妨げはしないが、
            //      ログや運用照会で「通知 id」と「メール id」が同じ文字列を指して混乱する。
            mailService.enqueue(
                    sourceEventId == null ? null : "mail:" + sourceEventId,
                    preference.getEmail(),
                    recipientId,
                    kind,
                    params,
                    // 설정의 지역(KR/JP)을 로케일(ko/ja)로 바꿔 넘긴다. 디스패처가 설정을 다시
                    // 보긴 하지만, 그 사이 사용자가 지역을 바꿔도 이 메일은 등록 시점의 언어로
                    // 나가야 한다 — 주문 알림이 갑자기 다른 언어로 오면 그게 더 이상하다.
                    // [JA] 設定の地域(KR/JP)をロケール(ko/ja)に変えて渡す。ディスパッチャが設定を再度見はするが、
                    //      その間にユーザーが地域を変えてもこのメールは登録時点の言語で出るべきだ —
                    //      注文通知が突然別の言語で来るほうがよほどおかしい。
                    locale);
        } catch (RuntimeException e) {
            log.warn("알림 메일을 큐에 넣지 못했습니다(알림 자체는 생성됨): recipientId={} type={} {}",
                    recipientId, type, e.getClass().getSimpleName());   // 메시지에는 받는 주소가 섞일 수 있다(N-H2) ／ [JA] メッセージには宛先アドレスが混じりうる(N-H2)
        }
    }

    /** 설정의 지역 코드(KR/JP)를 문구 로케일(ko/ja)로. 모르는 값이면 null — 기본값에 맡긴다. */
    // [JA] 設定の地域コード(KR/JP)を文言ロケール(ko/ja)に。未知の値なら null — 既定値に任せる。
    private static String localeOf(String region) {
        if (region == null) {
            return null;
        }
        return switch (region.toUpperCase()) {
            case "JP" -> "ja";
            case "KR" -> "ko";
            default -> null;
        };
    }

    /** 이 사용자·이 종류가 SMS 대상인지 판정한다. 번호가 없거나 설정이 꺼져 있으면 아니다. */
    // [JA] このユーザー・この種類が SMS 対象かを判定する。番号がないか設定がオフなら対象外。
    private SmsStatus resolveSmsStatus(Long recipientId, NotificationType type) {
        return preferenceRepository.findByUserId(recipientId)
                .filter(pref -> pref.shouldSendSms(type))
                .map(pref -> SmsStatus.PENDING)
                .orElse(SmsStatus.NOT_APPLICABLE);
    }

    @Transactional(readOnly = true)
    public List<Notification> recent(Long recipientId, int size) {
        return notificationRepository.findByRecipientIdAndDeletedFalseOrderByCreatedAtDesc(
                recipientId, PageRequest.of(0, Math.min(size, 100)));
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long recipientId) {
        return notificationRepository.countByRecipientIdAndReadFalseAndDeletedFalse(recipientId);
    }

    /**
     * 알림을 읽음 처리한다.
     *
     * <p>수신자 확인이 필수다. id 만으로 처리하면 남의 알림 id 를 넣어 상태를 바꿀 수 있다
     * (IDOR). 조회 결과의 수신자가 요청자와 다르면 없는 것처럼 취급한다 — "그 id 는 존재한다"는
     * 사실조차 알려주지 않는다.
     *
     * <p>[JA] 通知を既読にする。受信者の確認が必須だ。id だけで処理すると他人の通知 id
     * を入れて状態を変えられる(IDOR)。照会結果の受信者が要求者と違えば存在しないものとして扱う — 「その id
     * は存在する」という事実すら知らせない。
     */
    @Transactional
    public boolean markRead(Long recipientId, Long notificationId) {
        return notificationRepository.findById(notificationId)
                .filter(n -> n.getRecipientId().equals(recipientId))
                .map(n -> { n.markRead(); return true; })
                .orElse(false);
    }

    @Transactional
    public int markAllRead(Long recipientId) {
        List<Notification> unread =
                notificationRepository.findByRecipientIdAndDeletedFalseOrderByCreatedAtDesc(
                        recipientId, PageRequest.of(0, 500));
        int changed = 0;
        for (Notification n : unread) {
            if (!n.isRead()) {
                n.markRead();
                changed++;
            }
        }
        return changed;
    }

    @Transactional
    public boolean delete(Long recipientId, Long notificationId) {
        return notificationRepository.findById(notificationId)
                .filter(n -> n.getRecipientId().equals(recipientId))
                .map(n -> { n.markDeleted(); return true; })
                .orElse(false);
    }

    @Transactional
    public NotificationPreference preferenceOf(Long userId) {
        return preferenceRepository.findByUserId(userId)
                .orElseGet(() -> preferenceRepository.save(new NotificationPreference(userId)));
    }

    /**
     * 설정을 가져오면서 계정 주소를 기록한다.
     *
     * <p><b>왜 이렇게 채우나.</b> 알림 서비스는 사용자 테이블을 갖고 있지 않다. 메일을 보내려면
     * 주소가 필요한데, 그 주소를 얻는 경로는 셋뿐이다: (1) user-service 에 매번 물어보기 —
     * 알림마다 동기 호출이 붙는다, (2) 사용자 생성 이벤트를 구독해 복제하기 — 정공법이지만
     * 기존 사용자는 채워지지 않는다, (3) <b>게이트웨이가 붙여 주는 {@code User-Email} 헤더</b>.
     *
     * <p>(3)을 쓴다. 그 헤더는 게이트웨이가 JWT 에서 꺼내 붙이고, 클라이언트가 보낸 같은 이름의
     * 헤더는 게이트웨이가 지운다(docs/27) — 즉 위조할 수 없다. 사용자가 알림 목록이나 설정
     * 화면을 한 번이라도 열면 채워진다.
     *
     * <p>한계는 분명하다: <b>로그인 후 알림 API 를 한 번도 부르지 않은 사용자에게는 알림 메일이
     * 나가지 않는다.</b> 가입 확인·비밀번호 재설정은 주소가 명령에 실려 오므로 영향받지 않는다.
     *
     * <p>[JA] 設定を取得しつつアカウントのアドレスを記録する。<b>なぜこう埋めるのか。</b>通知サービスはユーザーテー
     * ブルを持っていない。メールを送るにはアドレスが必要だが、それを得る経路は三つしかない: (1)
     * user-service に毎回問い合わせる — 通知ごとに同期呼び出しが付く、(2)
     * ユーザー作成イベントを購読して複製する — 正攻法だが既存ユーザーは埋まらない、(3)
     * <b>ゲートウェイが付けてくれる {@code User-Email} ヘッダー</b>。(3) を使う。
     * そのヘッダーはゲートウェイが JWT から取り出して付け、クライアントが送った同名ヘッダーはゲートウェイが
     * 消す(docs/27) — つまり偽造できない。ユーザーが通知一覧か設定画面を一度でも開けば埋まる。
     * 限界ははっきりしている: <b>ログイン後に通知 API を一度も呼んでいないユーザーには通知メールが出な
     * い。</b>登録確認・パスワード再設定はアドレスが命令に載ってくるので影響を受けない。
     */
    @Transactional
    public NotificationPreference preferenceOf(Long userId, String email) {
        NotificationPreference preference = preferenceOf(userId);
        preference.rememberEmail(email);
        return preference;
    }

    /** 설정 변경의 결과 — 바꿨거나, 이 경로가 받지 않는 키가 있어 아무것도 바꾸지 않았다. */
    // [JA] 設定変更の結果 — 変えたか、この経路が受け付けないキーがあって何も変えなかったか。
    public sealed interface PreferenceUpdate {
        record Updated(NotificationPreference preference) implements PreferenceUpdate {}
        record Rejected(String reason) implements PreferenceUpdate {}
    }

    /**
     * 알림 설정을 바꾼다 — 한 트랜잭션에서 읽고 고치고 저장한다. 들어온 키만 바꾼다.
     *
     * <p>🐛 예전: 컨트롤러가 {@link #preferenceOf(Long, String)} 로 받은 엔티티에 세터를 불렀다. 그 트랜잭션은 반환할 때 이미 커밋돼,
     * 세터는 트랜잭션 밖이었다 — open-in-view 는 트랜잭션 밖의 변경을 flush 하지 않으므로 응답은 200 과 새 값인데 DB 는 그대로였다
     * ("알림을 껐는데 계속 온다 · 새로 고치면 되돌아간다", 2단계 인증 켜기도). 테스트가 없었다(PreferenceUpdateTest).
     *
     * <p>🐛 {@code twoFactorEnabled} 는 받지 않는다(docs/24 §10). 예전에는 여기서 켜면 저장은 됐지만 <b>로그인은 그 값을 읽지 않았다</b> —
     * 로그인 2단계는 user-service 의 것이라, 켠 사용자는 보호받는다고 믿었지만 비밀번호만으로 들어올 수 있었다. 이제 SMS 2단계 인증은
     * user-service 의 {@code /api/auth/sms-2fa/*} 가 켜고 끈다(코드 확인 · 최근 로그인 · 번호 결속). 그 키가 오면 아무것도 바꾸지 않고
     * 거절한다 — 일부만 저장한 채 성공처럼 보이지 않게.
     */
    // [JA] 通知設定を変える — 一つのトランザクションで読み・直し・保存する。twoFactorEnabled は受けない — 以前はここで有効にしても
    //      ログインがその値を読まず、守られていると信じた利用者がパスワードだけで入られた。SMS 二段階認証は user-service が持つ。
    @Transactional
    public PreferenceUpdate updatePreferences(Long userId, String email, java.util.Map<String, Boolean> changes) {
        if (changes.containsKey("twoFactorEnabled")) {
            return new PreferenceUpdate.Rejected("2단계 인증은 보안 설정에서 켜고 끕니다.");
        }
        NotificationPreference preference = preferenceOf(userId, email);
        if (changes.containsKey("smsEnabled")) {
            preference.setSmsEnabled(Boolean.TRUE.equals(changes.get("smsEnabled")));
        }
        if (changes.containsKey("emailEnabled")) {
            // 메일 수신 거부. ⚠️ 가입 확인·비밀번호 재설정은 이 설정과 무관하게 나간다 — 본인이 방금 요청한 메일을 수신 거부로
            // 막으면 계정에 못 들어간다(MailKind#isTransactional).
            // [JA] メール受信拒否。⚠️ 登録確認・パスワード再設定はこの設定と無関係に出る —
            //      本人がたった今要求したメールを受信拒否で止めるとアカウントに入れなくなる(MailKind#isTransactional)。
            preference.setEmailEnabled(Boolean.TRUE.equals(changes.get("emailEnabled")));
        }
        return new PreferenceUpdate.Updated(preference);
    }

    /**
     * 주소만 기록한다. 알림 목록 조회처럼 설정이 필요 없는 경로에서 쓴다.
     *
     * <p>값이 그대로면 {@code rememberEmail} 이 아무것도 하지 않으므로 UPDATE 가 나가지 않는다 —
     * 화면마다 불리는 경로에서 매번 쓰기가 발생하면 WAL 만 늘어난다.
     *
     * <p>[JA] アドレスだけ記録する。通知一覧の照会のように設定が不要な経路で使う。値がそのままなら
     * {@code rememberEmail} は何もしないので UPDATE は出ない — 画面ごとに呼ばれる経路で毎回書き込みが起きる
     * と WAL が増えるだけだ。
     */
    @Transactional
    public void rememberEmail(Long userId, String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        preferenceOf(userId).rememberEmail(email);
    }
}
