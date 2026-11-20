package com.project.notification_service.domain;

/**
 * 알림 종류. 사용자가 종류별로 켜고 끌 수 있으므로 화면의 설정 항목과 1:1 대응한다.
 *
 * <p>{@code smsWorthy} 가 이 설계의 핵심이다. <b>모든 알림을 SMS 로 보내면 안 된다</b> —
 * 건당 과금이고, 사용자에게도 소음이다. 결제·보안처럼 놓치면 곤란한 것만 기본으로 SMS 를 태우고
 * 나머지는 앱 안에서만 보여 준다. 사용자가 원하면 개별로 켤 수 있다.
 *
 * <p>[JA] 通知の種類。ユーザーが種類別にオン・オフできるので画面の設定項目と 1:1 で対応する。
 * {@code smsWorthy} がこの設計の核心である。<b>すべての通知を SMS で送ってはならない</b> —
 * 件数課金で、ユーザーにとってもノイズである。決済・セキュリティのように見逃すと困るものだけ既定で SMS に乗せ、
 * 残りはアプリ内でだけ見せる。ユーザーが望めば個別に有効にできる。
 */
public enum NotificationType {

    /** 주문 완료. 금액이 오간 사건이라 기본 SMS. */
    // [JA] 注文完了。お金が動いた出来事なので既定で SMS。
    ORDER_PLACED("order/", true),

    /** 결제 성공. 위와 같다. */
    // [JA] 決済成功。上と同じ。
    PAYMENT_COMPLETED("order/", true),

    /** 결제 실패 — 사용자가 바로 알아야 조치할 수 있다. */
    // [JA] 決済失敗 — ユーザーがすぐ知ってこそ対処できる。
    PAYMENT_FAILED("order/", true),
    /**
     * 환불 완료 — 돈이 돌아갔다. 결제 완료와 같은 무게라 기본 SMS.
     *
     * <p>🐛 예전에는 이 종류가 없어서 pay-service 의 CANCELED 응답이 "결제 완료" 로 분류됐고,
     * 그마저 같은 주문의 결제 완료 알림과 중복 키가 겹쳐 아무것도 만들어지지 않았다.
     *
     * <p>[JA] 返金完了 — お金が戻った。決済完了と同じ重みなので既定で SMS。
     * 以前はこの種類がなく、pay-service の CANCELED 応答が「決済完了」に分類され、
     * それさえ同じ注文の決済完了通知と重複キーがぶつかって何も作られなかった。
     */
    PAYMENT_REFUNDED("order/", true),

    /** 배송 상태 변경. */
    // [JA] 配送状態の変更。
    SHIPPING_UPDATED("order/", false),

    /**
     * 구매 확정(docs/25 §12) — 주문의 마지막 전이.
     *
     * <p>대부분은 손님이 누른 것이 아니라 배달 완료 뒤 기한이 지나 <b>자동으로</b> 된 것이다.
     * 아무것도 안 했는데 주문 상태가 바뀌는 일이라 알리지 않으면 주문 내역을 열고서야 안다.
     * 돈이 오가지는 않으므로 문자는 아니다 — 앱 알림과 메일뿐.
     *
     * <p>[JA] 購入確定(docs/25 §12) — 注文の最後の遷移。
     * ほとんどは客が押したのではなく、配達完了後に期限が過ぎて<b>自動で</b>なったものである。
     * 何もしていないのに注文状態が変わることなので、知らせなければ注文履歴を開いて初めて分かる。
     * お金は動かないので SMS ではない — アプリ通知とメールのみ。
     */
    ORDER_COMPLETED("order/", false),

    /**
     * 품절 옵션이 다시 입고됐다 — 손님이 신청한 것이다(docs/53). 판촉이 아니라 본인이 부탁한 소식이라
     * 앱과 메일로 알린다. 문자는 아니다 — 건당 과금을 태울 만큼 급하지 않고, 놓쳐도 손해가 없다.
     *
     * <p>[JA] 品切れのオプションが再入荷した — 客が申し込んだものである(docs/53)。販促ではなく本人が頼んだ知らせなので
     * アプリとメールで知らせる。SMS ではない — 件数課金を使うほど急ぎではなく、見逃しても損はない。
     */
    RESTOCK_AVAILABLE("product/", false),

    /** 채팅 메시지 도착. 앱 안에서 보는 것으로 충분하고, SMS 로 태우면 폭탄이 된다. */
    // [JA] チャットメッセージ到着。アプリ内で見れば十分で、SMS に乗せると爆弾になる。
    CHAT_MESSAGE("chatroom/", false),

    /** 친구 접속. 실시간성만 있으면 되고 사후 통지는 의미가 없다 — SMS 부적합. */
    // [JA] 友達の接続。リアルタイム性さえあればよく事後通知は意味がない — SMS に不適。
    FRIEND_ONLINE("chatroom/", false),

    /** 쿠폰 발급·만료 임박. */
    // [JA] クーポン発行・期限切れ間近。
    COUPON_ISSUED("coupon/", false),

    /** 보안: 새 기기 로그인 등. 놓치면 계정이 털린다 — 항상 SMS. */
    // [JA] セキュリティ: 新しい端末からのログインなど。見逃すとアカウントが乗っ取られる — 常に SMS。
    SECURITY_ALERT("account/", true),

    /**
     * 공지사항이 게시됐다.
     *
     * <p><b>전체 사용자 대상</b>이라 개인 알림 테이블에 넣지 않는다 —
     * {@code broadcast_notification} 에 행 하나를 만들고 개인별로는 읽음 표시만 남긴다
     * (BroadcastNotification 주석). SMS 로는 절대 보내지 않는다: 사용자 수만큼 문자가
     * 나가는 것은 요금 사고다.
     *
     * <p>[JA] お知らせが掲示された。
     * <b>全ユーザー対象</b>なので個人通知テーブルに入れない — {@code broadcast_notification} に行を一つ作り、
     * 個人別には既読の印だけを残す(BroadcastNotification のコメント)。SMS では絶対に送らない: ユーザー数分の SMS が出るのは料金事故である。
     */
    NOTICE_PUBLISHED("notice/", false),

    /**
     * 관리자가 직접 보낸 공지성 알림.
     *
     * <p>{@link #NOTICE_PUBLISHED} 와 같은 구조를 쓰지만, 공지사항 게시물이 아니라
     * 관리자가 그 자리에서 쓴 안내다(점검 예고 등).
     *
     * <p>[JA] 管理者が直接送ったお知らせ的な通知。
     * {@link #NOTICE_PUBLISHED} と同じ構造を使うが、お知らせの掲示物ではなく管理者がその場で書いた案内である(メンテナンス予告など)。
     */
    ANNOUNCEMENT("", false),

    /**
     * 기획전이 시작됐다.
     *
     * <p>{@link #NOTICE_PUBLISHED} 와 같은 <b>전체 알림</b>이다 — 모든 사용자에게 같은
     * 내용이므로 사용자 수만큼 알림 행을 만들지 않는다(BroadcastNotification 주석).
     *
     * <p>SMS 로는 보내지 않는다. 판촉이라 놓쳐도 사용자에게 손해가 없고, 전체 사용자에게
     * 문자를 태우는 것은 요금 사고다.
     *
     * <p>[JA] 企画展が始まった。
     * {@link #NOTICE_PUBLISHED} と同じ<b>全体通知</b>である — 全ユーザーに同じ内容なのでユーザー数分の通知行を作らない(BroadcastNotification のコメント)。
     * SMS では送らない。販促なので見逃してもユーザーに損はなく、全ユーザーに SMS を送るのは料金事故である。
     */
    EVENT_STARTED("event/", false);

    private final String path;
    private final boolean smsWorthy;

    NotificationType(String path, boolean smsWorthy) {
        this.path = path;
        this.smsWorthy = smsWorthy;
    }

    public String path() {
        return path;
    }

    /** 기본적으로 SMS 까지 보낼 만한 종류인가. 사용자 설정이 이를 덮어쓸 수 있다. */
    // [JA] 既定で SMS まで送るに値する種類か。ユーザー設定がこれを上書きできる。
    public boolean isSmsWorthyByDefault() {
        return smsWorthy;
    }
}
