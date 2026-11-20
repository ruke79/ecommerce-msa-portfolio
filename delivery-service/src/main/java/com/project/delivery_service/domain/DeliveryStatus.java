package com.project.delivery_service.domain;

import java.time.Duration;

/**
 * 배송 상태 — <b>내부 표준</b>. 택배사 문자열을 그대로 쓰지 않는다(docs/25 §3.2).
 *
 * <p>택배사마다 상태 표현이 다르다. 우체국은 {@code 접수/발송/도착/배달준비/배달완료},
 * CJ는 {@code 집화처리/간선상차/간선하차/배달출발/배달완료} 식이다. 그대로 저장하면
 * 화면과 로직이 택배사마다 갈라지고, 택배사를 하나 추가할 때마다 둘 다 고쳐야 한다.
 *
 * <p>각 상태가 스스로 두 가지를 안다:
 * <ul>
 *   <li><b>다음 폴링까지의 간격</b> — 전 건을 같은 주기로 조회하면 공공 API 일일 한도를
 *       금방 넘긴다. 끝난 배송을 계속 조회하는 것이 쿼터 낭비의 주범이다.</li>
 *   <li><b>고객에게 보여줄 단계</b> — 상태를 그대로 노출하면 사용자는 이해하지 못한다.
 *       아마존처럼 소수의 단계로 접는다.</li>
 * </ul>
 *
 * <p><b>순서가 곧 진행도다.</b> {@link #isProgressFrom} 이 {@code ordinal()} 을 쓰므로
 * 상수 선언 순서를 바꾸면 의미가 바뀐다. 새 상태는 흐름상 맞는 자리에 넣어야 한다.
 *
 * <p>[JA] 配送状態 — <b>内部標準</b>。宅配業者の文字列をそのまま使わない(docs/25 §3.2)。
 * 宅配業者ごとに状態の表現が異なる(郵便局は 접수/발송/도착/배달준비/배달완료、CJ は 집화처리/간선상차/간선하차/배달출발/배달완료 といった具合)。
 * そのまま保存すると画面とロジックが業者ごとに分岐し、業者を一つ追加するたびに両方を直すことになる。
 * 各状態は自ら二つを知っている: <b>次のポーリングまでの間隔</b>(全件を同じ周期で照会すると公共 API の日次上限をすぐ超える。
 * 終わった配送を照会し続けるのがクォータ浪費の主因である)と、<b>顧客に見せる段階</b>(状態をそのまま出してもユーザーは理解できない。Amazon のように少数の段階に畳む)。
 * <b>順序がそのまま進捗度である。</b> {@link #isProgressFrom} が {@code ordinal()} を使うため、定数の宣言順を変えると意味が変わる。
 * 新しい状態は流れ上ふさわしい位置に入れること。
 */
public enum DeliveryStatus {

    /** 배송 건은 만들어졌지만 송장번호가 없다. 조회할 대상 자체가 없다. */
    // [JA] 配送は作られたが送り状番号がない。照会する対象そのものがない。
    PENDING(null, Stage.ORDERED, false),

    /**
     * 택배사가 접수 정보만 받았다 — 실물은 아직 판매자에게 있다.
     *
     * <p>🐛 단계가 {@code ORDERED} 였다. 송장을 등록하면 주문은 {@code DISPATCHED} 가 되고
     * (F-2) "발송되었습니다" 알림까지 나가는데, 배송 추적의 진행 바는 여전히 <b>주문 완료</b>에
     * 서 있었다 — 같은 화면에서 주문 상태와 진행 바가 서로 다른 말을 했다. 송장 등록이 곧
     * 발송 처리라는 것이 이 시스템의 정의이므로 여기도 {@code SHIPPED} 다.
     *
     * <p>[JA] 宅配業者が受付情報だけを受け取った — 現物はまだ販売者の手元にある。
     * 🐛 段階が {@code ORDERED} だった。送り状を登録すると注文は {@code DISPATCHED} になり(F-2)「発送されました」通知まで出るのに、
     * 配送追跡の進捗バーは依然として<b>注文完了</b>に立っていた — 同じ画面で注文状態と進捗バーが食い違っていた。
     * 送り状登録がすなわち発送処理というのがこのシステムの定義なので、ここも {@code SHIPPED} である。
     */
    INFO_RECEIVED(Duration.ofHours(6), Stage.SHIPPED, false),

    /** 집화 완료. 실물이 택배사 손에 들어갔다. */
    // [JA] 集荷完了。現物が宅配業者の手に渡った。
    PICKED_UP(Duration.ofHours(3), Stage.SHIPPED, false),

    /** 간선 이동 중. 하루 몇 번 스캔된다. */
    // [JA] 幹線輸送中。一日に数回スキャンされる。
    IN_TRANSIT(Duration.ofHours(3), Stage.IN_TRANSIT, false),

    /**
     * 통관 보류 — <b>국제 배송 전용</b>.
     *
     * <p>국내 배송에는 없는 단계이고, 사용자가 <b>직접 조치해야 하는 경우가 있다</b>
     * (개인통관고유부호 제출, 관세 납부). 그래서 알림 대상이고, 진행 바에서도 별도로
     * 보여준다. 이걸 IN_TRANSIT 에 뭉뚱그리면 사용자는 왜 며칠째 멈춰 있는지 알 수 없다.
     *
     * <p>[JA] 通関保留 — <b>国際配送専用</b>。
     * 国内配送にはない段階で、ユーザーが<b>自ら対応しなければならない場合がある</b>(個人通関固有符号の提出、関税納付)。
     * そのため通知対象であり、進捗バーでも別に見せる。IN_TRANSIT にまとめてしまうと、ユーザーはなぜ何日も止まっているのか分からない。
     */
    CUSTOMS_HOLD(Duration.ofHours(6), Stage.CUSTOMS, false),

    /** 통관 완료. 국내 배송망으로 넘어간다. */
    // [JA] 通関完了。国内配送網に引き渡される。
    CUSTOMS_CLEARED(Duration.ofHours(3), Stage.CUSTOMS, false),

    /**
     * 배달 출발 — <b>오늘 도착한다.</b>
     *
     * <p>별도 상태로 둔 이유는 알림 가치가 가장 높은 순간이기 때문이다. 아마존이
     * "Out for delivery" 를 강조하는 이유와 같다. 폴링도 가장 자주 한다.
     *
     * <p>[JA] 配達出発 — <b>今日届く。</b>
     * 別の状態にした理由は、通知の価値が最も高い瞬間だからである。Amazon が "Out for delivery" を強調するのと同じ理由。ポーリングも最も頻繁に行う。
     */
    OUT_FOR_DELIVERY(Duration.ofMinutes(30), Stage.IN_TRANSIT, false),

    /** 부재 등으로 배달 시도 실패. 택배사가 다시 시도한다. */
    // [JA] 不在などで配達試行に失敗。宅配業者が再試行する。
    ATTEMPT_FAILED(Duration.ofHours(3), Stage.IN_TRANSIT, false),

    /** 배달 완료. 폴링을 멈춘다. */
    // [JA] 配達完了。ポーリングを止める。
    DELIVERED(null, Stage.DELIVERED, true),

    /** 파손·분실·주소불명 등 사람이 봐야 하는 상태. */
    // [JA] 破損・紛失・住所不明など、人が確認すべき状態。
    EXCEPTION(Duration.ofHours(12), Stage.EXCEPTION, false),

    /** 반송 완료. 폴링을 멈춘다. */
    // [JA] 返送完了。ポーリングを止める。
    RETURNED(null, Stage.EXCEPTION, true),

    /**
     * 발송 전 취소됨.
     *
     * <p>발송 이후에는 이 상태로 갈 수 없다 — 이미 움직이는 물건을 "취소"로 표시하면
     * 실물과 기록이 어긋난다. 그 경우는 반품(회수) 절차이고 {@link #RETURNED} 로 끝난다.
     *
     * <p>[JA] 発送前にキャンセルされた。
     * 発送後はこの状態には行けない — すでに動いている品物を「キャンセル」と表示すると現物と記録がずれる。
     * その場合は返品(回収)手続きであり、{@link #RETURNED} で終わる。
     */
    CANCELLED(null, Stage.CANCELLED, true);

    /** 고객 화면의 진행 단계. 내부 상태를 소수의 단계로 접는다. */
    // [JA] 顧客画面の進捗段階。内部状態を少数の段階に畳む。
    public enum Stage { ORDERED, SHIPPED, IN_TRANSIT, CUSTOMS, DELIVERED, EXCEPTION, CANCELLED }

    private final Duration pollInterval;
    private final Stage stage;
    private final boolean terminal;

    DeliveryStatus(Duration pollInterval, Stage stage, boolean terminal) {
        this.pollInterval = pollInterval;
        this.stage = stage;
        this.terminal = terminal;
    }

    /** 다음 폴링까지의 간격. {@code null} 이면 폴링하지 않는다. */
    // [JA] 次のポーリングまでの間隔。{@code null} ならポーリングしない。
    public Duration pollInterval() {
        return pollInterval;
    }

    public Stage stage() {
        return stage;
    }

    /** 더 이상 변하지 않는 상태인가. 폴링을 멈추는 기준이다. */
    // [JA] もう変化しない状態か。ポーリングを止める基準である。
    public boolean isTerminal() {
        return terminal;
    }

    /**
     * 아직 발송되지 않았는가 — <b>주소 변경과 취소가 가능한 구간</b>.
     *
     * <p>집화(PICKED_UP)가 되는 순간 실물이 택배사 손에 있으므로 우리가 목적지를 바꿀 수
     * 없다. 이 경계를 코드로 못박아 두지 않으면 "취소했는데 물건이 왔다"가 생긴다.
     *
     * <p>[JA] まだ発送されていないか — <b>住所変更とキャンセルが可能な区間</b>。
     * 集荷(PICKED_UP)された瞬間に現物は宅配業者の手にあるため、こちらで宛先を変えられない。
     * この境界をコードで固定しておかないと「キャンセルしたのに品物が届いた」が起きる。
     */
    public boolean isBeforeDispatch() {
        return this == PENDING || this == INFO_RECEIVED;
    }

    /**
     * 고객에게 SMS 를 보낼 만한 상태인가.
     *
     * <p>모든 전이에 알리면 한 건의 배송에 대여섯 번이 나간다 — 소음이다. 실제로 사람이
     * 알고 싶어 하거나 행동이 달라지는 순간만 고른다: <b>출발했다</b>, 오늘 받는다,
     * 도착했다, 문제가 생겼다, 그리고 <b>통관 보류</b>(사용자가 서류를 내야 풀린다).
     *
     * <p>{@link #INFO_RECEIVED}(송장 등록 = 발송)는 나중에 더했다. "상품이 발송되었습니다" 는
     * 대부분의 쇼핑몰이 보내는 알림이고, 받는 사람이 가장 먼저 기다리는 소식이다. 빼 두었던
     * 이유는 문자 요금이었는데, 배송 알림({@code NotificationType.SHIPPING_UPDATED})은
     * 애초에 {@code smsWorthy = false} 라 문자로 나가지 않는다 — 앱 알림과 메일뿐이다.
     * 그래서 요금 문제가 아니었다.
     *
     * <p>여기에 없는 것들(집화·간선 이동·통관 완료·배달 재시도)은 알아도 할 일이 없고,
     * 그 이력은 주문 상세의 배송 추적이 이미 보여준다.
     *
     * <p>[JA] 顧客に SMS を送るに値する状態か。
     * すべての遷移で知らせると一件の配送で五、六回送られる — ノイズである。実際に人が知りたい、あるいは行動が変わる瞬間だけを選ぶ:
     * <b>出発した</b>、今日受け取る、届いた、問題が起きた、そして<b>通関保留</b>(ユーザーが書類を出さないと解けない)。
     * {@link #INFO_RECEIVED}(送り状登録 = 発送)は後から加えた。「商品が発送されました」はほとんどのショッピングモールが送る通知で、
     * 受取人が真っ先に待つ知らせである。外していた理由は SMS 料金だったが、配送通知({@code NotificationType.SHIPPING_UPDATED})は
     * もともと {@code smsWorthy = false} で SMS では送られない — アプリ通知とメールだけである。だから料金の問題ではなかった。
     * ここにないもの(集荷・幹線輸送・通関完了・配達再試行)は知ってもすることがなく、その履歴は注文詳細の配送追跡がすでに見せている。
     */
    public boolean isWorthNotifying() {
        return this == INFO_RECEIVED || this == OUT_FOR_DELIVERY || this == DELIVERED
                || this == EXCEPTION || this == RETURNED || this == CUSTOMS_HOLD || this == CANCELLED;
    }

    /**
     * 이 상태가 {@code other} 보다 뒤 단계인가.
     *
     * <p>택배사 이력은 순서가 뒤바뀌어 오는 경우가 있다(스캔 시각과 등록 시각이 다르다).
     * 뒤로 가는 전이를 그대로 반영하면 "배달완료" 였던 배송이 "배송중" 으로 되돌아간다.
     * 진행은 앞으로만 한다 — 단, 예외·반송은 언제든 될 수 있다(실제로 사고가 났으므로).
     * 취소는 발송 전에만 가능하므로 여기서 특별 취급하지 않는다.
     *
     * <p>순서만으로는 안 되는 두 갈래를 따로 둔다(🐛 예전에는 {@code ordinal} 비교뿐이었다):
     * <ul>
     *   <li><b>예외 뒤의 정상 스캔</b> — {@code EXCEPTION} 은 {@code DELIVERED} 보다 뒤에 선언돼 있어, '파손' 스캔 뒤에 정상
     *       배달된 상자가 EXCEPTION 에 고정됐다(12시간마다 영원히 폴링, 주문은 DISPATCHED 에 멈춤). 예외에서는 이동 · 배달 출발 ·
     *       배달 완료로 갈 수 있다.</li>
     *   <li><b>재배달 출발</b> — 부재({@code ATTEMPT_FAILED}) 다음의 배달 출발({@code OUT_FOR_DELIVERY})이 "뒤로 가는 전이" 로
     *       무시돼 배달 출발 알림이 빠지고 폴링 주기가 3시간으로 남았다.</li>
     * </ul>
     *
     * <p>[JA] この状態が {@code other} より後の段階か。
     *
     * <p>[JA] 宅配業者の履歴は順序が入れ替わって来ることがある(スキャン時刻と登録時刻が違う)。
     * 後ろへ戻る遷移をそのまま反映すると「配達完了」だった配送が「配送中」に戻る。
     * 進行は前にだけ進む — ただし例外 · 返送はいつでも起こり得る(実際に事故が起きたので)。
     * キャンセルは発送前にだけ可能なので、ここでは特別扱いしない。
     *
     * <p>[JA] 順序だけでは扱えない二つの分岐を別に置く(以前は {@code ordinal} 比較だけだった):
     * <ul>
     *   <li>[JA] <b>例外の後の正常スキャン</b> — {@code EXCEPTION} は {@code DELIVERED} より後に宣言されているので、「破損」スキャンの後に正常に
     *       配達された箱が EXCEPTION に固定された(12 時間ごとに永遠にポーリング、注文は DISPATCHED で止まる)。例外からは移動 · 配達出発 ·
     *       配達完了へ進める。</li>
     *   <li>[JA] <b>再配達出発</b> — 不在({@code ATTEMPT_FAILED})の次の配達出発({@code OUT_FOR_DELIVERY})が「後ろへ戻る遷移」として
     *       無視され、配達出発の通知が抜けてポーリング周期が 3 時間のまま残った。</li>
     * </ul>
     */
    public boolean isProgressFrom(DeliveryStatus other) {
        if (other == null) {
            return true;
        }
        if (other.isTerminal() || this == other) {
            return false;   // 끝난 배송은 되돌리지 않는다 ／ [JA] 終わった配送は戻さない
        }
        if (this == EXCEPTION || this == RETURNED) {
            return true;
        }
        if (other == EXCEPTION) {
            // 사고 뒤에 다시 움직이기 시작했다 — 집화 전 단계로는 돌아가지 않는다.
            // [JA] 事故の後に再び動き始めた — 集荷前の段階には戻らない。
            return ordinal() >= IN_TRANSIT.ordinal() && ordinal() <= DELIVERED.ordinal();
        }
        if (other == ATTEMPT_FAILED && this == OUT_FOR_DELIVERY) {
            return true;    // 재배달 출발 ／ [JA] 再配達出発
        }
        return ordinal() > other.ordinal();
    }
}
