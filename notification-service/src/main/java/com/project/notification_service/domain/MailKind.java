package com.project.notification_service.domain;

import java.util.Optional;

/**
 * 보낼 메일의 종류. 문구({@code MailTemplates})와 1:1 대응한다.
 *
 * <p>{@link NotificationType} 과 따로 두는 이유가 두 가지다.
 *
 * <p><b>1. 모든 메일이 인앱 알림은 아니다.</b> 가입 확인·비밀번호 재설정은 <b>로그인하기 전</b>에
 * 보내는 메일이라 보여줄 인앱 알림도, 참조할 수신 설정도 없다. 이것들을 {@code NotificationType}
 * 에 끼워 넣으면 "알림 목록"에 로그인 전 사용자의 알림이 섞이고, 수신 거부 설정이 적용돼
 * <b>비밀번호 재설정 메일이 사용자 설정으로 차단되는</b> 일이 생긴다.
 *
 * <p><b>2. 모든 알림이 메일감은 아니다.</b> 채팅 메시지마다 메일을 보내면 그건 스팸이다.
 * {@link #forNotification} 이 그 판단을 한곳에 모은다.
 *
 * <p>[JA] 送るメールの種類。文言({@code MailTemplates})と 1:1 で対応する。
 * {@link NotificationType} と別にする理由は二つある。
 * <b>1. すべてのメールがアプリ内通知ではない。</b> 登録確認・パスワード再設定は<b>ログイン前</b>に送るメールなので、
 * 見せるアプリ内通知も参照する受信設定もない。これらを {@code NotificationType} に押し込むと「通知一覧」にログイン前ユーザーの
 * 通知が混ざり、受信拒否設定が適用されて<b>パスワード再設定メールがユーザー設定で遮断される</b>ことが起きる。
 * <b>2. すべての通知がメール向きではない。</b> チャットメッセージごとにメールを送ればそれはスパムである。
 * {@link #forNotification} がその判断を一か所に集める。
 */
public enum MailKind {

    /** 가입 확인 링크. params: {@code confirmationUrl} */
    // [JA] 登録確認リンク。params: {@code confirmationUrl}
    REGISTRATION_CONFIRM(true),

    /** 비밀번호 재설정 링크. params: {@code resetUrl} */
    // [JA] パスワード再設定リンク。params: {@code resetUrl}
    PASSWORD_RESET(true),

    /** 위험 기반 로그인의 확인 코드(user-service docs/68 §19). params: {@code code}(6자리). 로그인 전 메일이라 수신 설정과 무관. */
    // [JA] リスクベースログインの確認コード(user-service docs/68 §19)。params: {@code code}(6 桁)。ログイン前のメールなので受信設定と無関係。
    LOGIN_CHALLENGE(true),

    /** 주문 접수. params: {@code subject}, {@code url} */
    // [JA] 注文受付。params: {@code subject}, {@code url}
    ORDER_PLACED(false),

    /** 결제 완료. */
    // [JA] 決済完了。
    PAYMENT_COMPLETED(false),

    /** 결제 실패 — 사용자가 조치해야 한다. */
    // [JA] 決済失敗 — ユーザーが対処する必要がある。
    PAYMENT_FAILED(false),
    PAYMENT_REFUNDED(false),

    /** 배송 상태 변경. */
    // [JA] 配送状態の変更。
    SHIPPING_UPDATED(false),

    /** 구매 확정 — 손님이 눌렀든 기한이 지나 자동으로 됐든. */
    // [JA] 購入確定 — 客が押したか期限が過ぎて自動でなったかを問わない。
    ORDER_COMPLETED(false),

    /** 재입고 — 손님이 신청한 옵션이 다시 들어왔다(docs/53). */
    // [JA] 再入荷 — 客が申し込んだオプションが再入荷した(docs/53)。
    RESTOCK_AVAILABLE(false),

    /** 쿠폰 발급. */
    // [JA] クーポン発行。
    COUPON_ISSUED(false),

    /** 보안 경고: 새 기기 로그인 등. */
    // [JA] セキュリティ警告: 新しい端末からのログインなど。
    SECURITY_ALERT(false);

    private final boolean transactional;

    MailKind(boolean transactional) {
        this.transactional = transactional;
    }

    /**
     * 사용자 설정으로 <b>끌 수 없는</b> 메일인가.
     *
     * <p>가입 확인과 비밀번호 재설정은 사용자가 방금 직접 요청한 결과다. 이걸 수신 거부로 막으면
     * 사용자가 스스로 계정에 못 들어가게 된다 — 수신 거부의 취지(원치 않는 메일을 줄인다)와
     * 정반대다. 한국 정보통신망법·일본 특정전자메일법이 요구하는 수신 동의도 <b>광고성</b> 메일에
     * 대한 것이지, 본인이 요청한 정보성 메일에 대한 것이 아니다.
     *
     * <p>{@link NotificationType#SECURITY_ALERT} 가 SMS 에서 설정을 무시하는 것과 같은 이유가
     * 하나 더 있다: 공격자가 먼저 수신 거부를 켜 두고 작업할 수 있어서는 안 된다.
     *
     * <p>[JA] ユーザー設定で<b>切れない</b>メールか。
     * 登録確認とパスワード再設定はユーザーが今まさに自分で要求した結果である。これを受信拒否で止めると
     * ユーザーが自分でアカウントに入れなくなる — 受信拒否の趣旨(望まないメールを減らす)と正反対である。
     * 韓国の情報通信網法・日本の特定電子メール法が求める受信同意も<b>広告</b>メールに対するもので、本人が要求した情報メールに対するものではない。
     * {@link NotificationType#SECURITY_ALERT} が SMS で設定を無視するのと同じ理由がもう一つある:
     * 攻撃者が先に受信拒否を有効にしておいて作業できてはならない。
     */
    public boolean isTransactional() {
        return transactional;
    }

    /**
     * 사용자의 메일 수신 거부를 무시하고 보내는가 — 사용자가 요청한 메일({@link #isTransactional()})과 보안 경고.
     *
     * <p>🐛 예전에는 큐에 넣을 때 {@link #isTransactional()} 만 봐서 {@link #SECURITY_ALERT} 가 수신 거부에 막혔다
     * ({@code NotificationPreference.shouldSendEmail} 은 보안 경고를 예외로 판정하는데도). 세션을 얻은 공격자가 메일 수신을 끈
     * 뒤 비밀번호 재설정 · 소셜 연결 · 패스키 등록을 해도 메일이 나가지 않았다 — 바로 막으려던 경우다.
     *
     * <p>[JA] ユーザーのメール受信拒否を無視して送るか — 本人が要求したメールとセキュリティ警告。以前はキュー投入時に
     * isTransactional() だけを見ており、セキュリティ警告が受信拒否で止まっていた(攻撃者が先に受信を切れば警告が届かない)。
     */
    public boolean ignoresOptOut() {
        return transactional || this == SECURITY_ALERT;
    }

    /**
     * 이 알림을 메일로도 보낼 것인가.
     *
     * <p>[JA] この通知をメールでも送るか。
     *
     * @return 보낼 종류면 대응하는 {@code MailKind}, 메일로 보내지 않을 종류면 비어 있음
     *   [JA] 送る種類なら対応する {@code MailKind}、メールで送らない種類なら空
     */
    public static Optional<MailKind> forNotification(NotificationType type) {
        return switch (type) {
            case ORDER_PLACED      -> Optional.of(ORDER_PLACED);
            case PAYMENT_COMPLETED -> Optional.of(PAYMENT_COMPLETED);
            case PAYMENT_FAILED    -> Optional.of(PAYMENT_FAILED);
            case PAYMENT_REFUNDED  -> Optional.of(PAYMENT_REFUNDED);
            case SHIPPING_UPDATED  -> Optional.of(SHIPPING_UPDATED);
            case ORDER_COMPLETED   -> Optional.of(ORDER_COMPLETED);
            case RESTOCK_AVAILABLE -> Optional.of(RESTOCK_AVAILABLE);
            case COUPON_ISSUED     -> Optional.of(COUPON_ISSUED);
            case SECURITY_ALERT    -> Optional.of(SECURITY_ALERT);
            // 채팅 메시지·친구 접속은 메일로 보내지 않는다. 활발한 대화 하나가 메일함을
            // 수십 통으로 채운다 — 사용자에게는 스팸이고, 발신 도메인 평판에도 해롭다
            // (수신 서버가 우리를 스팸 발신자로 학습한다).
            // [JA] チャットメッセージ・友達の接続はメールで送らない。活発な会話一つでメールボックスが
            //      数十通で埋まる — ユーザーにはスパムで、送信ドメインの評判にも有害である
            //      (受信サーバーが我々をスパム送信者として学習する)。
            case CHAT_MESSAGE, FRIEND_ONLINE -> Optional.empty();

            // 공지·관리자 안내도 메일로 보내지 않는다. 이유가 둘이다:
            //
            //  1. **전체 발송이다.** 공지 하나에 전 사용자에게 메일이 나가면 발신 도메인이
            //     대량 발송으로 분류된다. 수신 서버가 우리를 학습하고 나면, 정작 중요한
            //     주문·결제 메일이 스팸함으로 간다.
            //  2. **광고성에 가깝다.** 사용자가 직접 요청한 결과가 아니므로 수신 동의가
            //     필요한 범주다(MailKind.isTransactional 주석의 법령 참조). 동의를 받는
            //     구조를 먼저 갖춰야 보낼 수 있다.
            //
            // 앱 안에서 보여 주는 것으로 충분하다.
            //
            // 기획전 시작도 같다. 그쪽은 판촉이라 2번(광고성)에 더 정확히 해당한다 —
            // 세일 안내 메일은 수신 동의 없이 보낼 수 없는 전형적인 형태다.
            // [JA] お知らせ・管理者案内もメールで送らない。理由は二つ:
            //      
            //       1. **一斉送信である。** お知らせ一つで全ユーザーにメールが出ると送信ドメインが
            //          大量送信に分類される。受信サーバーが我々を学習すると、肝心の
            //          注文・決済メールが迷惑メールフォルダに行く。
            //       2. **広告に近い。** ユーザーが直接要求した結果ではないので受信同意が
            //          必要な範疇である(MailKind.isTransactional のコメントの法令参照)。同意を受ける
            //          仕組みを先に整えてこそ送れる。
            //      
            //      アプリ内で見せるだけで十分である。
            //      
            //      企画展の開始も同じ。そちらは販促なので 2 番(広告)により正確に当たる —
            //      セール案内メールは受信同意なしに送れない典型的な形である。
            case NOTICE_PUBLISHED, ANNOUNCEMENT, EVENT_STARTED -> Optional.empty();
        };
    }
}
