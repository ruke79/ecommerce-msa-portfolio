package com.project.userservice.dto;

import java.time.LocalDateTime;

import com.project.common.constants.event.CustomerTier;

import lombok.Builder;
import lombok.Getter;

/**
 * 내 등급 — 화면에 보여 줄 형태.
 *
 * <h3>🔴 무엇을 담지 <b>않는지</b>가 설계다</h3>
 *
 * <p>여기에는 <b>"다음 등급까지 남은 금액"이 없다.</b> 계산해서 넣기는 쉽지만, 그 한
 * 필드가 화면의 성격을 바꾼다 — "당신의 혜택" 이 "당신이 더 써야 할 금액" 이 된다.
 * 진행 게이지도 같은 이유로 없다.
 *
 * <p>대신 <b>근거는 전부 준다</b>: 지금 등급, 그 판단에 쓰인 금액, 기준 금액, 마지막
 * 계산 시각. "왜 내가 실버인가" 에 화면이 답할 수 있어야 문의가 CS 로 가지 않는다.
 * 사용자가 그 숫자로 직접 뺄셈을 하는 것은 막지 않는다 — 알고 싶은 사람에게 감추는 것과,
 * 우리가 먼저 들이미는 것은 다르다.
 *
 * <p>이 구분이 아마존 톤이다. 등급은 혜택이 도착하는 형태로만 드러나고, 등급 자체를
 * 점수처럼 전시하지 않는다({@link CustomerTier} 주석).
 *
 * <p>[JA] 自分の等級 — 画面に見せる形。
 * 🔴 何を<b>入れないか</b>が設計である。ここには<b>「次の等級までの残り金額」がない。</b>計算して入れるのは簡単だが、
 * その一フィールドが画面の性格を変える — 「あなたの特典」が「あなたがもっと使うべき金額」になる。進捗ゲージも同じ理由でない。
 * 代わりに<b>根拠はすべて渡す</b>: 今の等級、その判断に使われた金額、基準金額、最後の計算時刻。「なぜ自分はシルバーなのか」に
 * 画面が答えられてこそ問い合わせが CS に行かない。ユーザーがその数字で自分で引き算するのは止めない — 知りたい人に隠すことと、
 * こちらから先に突きつけることは違う。
 * この区別が Amazon のトーンだ。等級は特典が届く形でだけ現れ、等級自体を点数のように展示しない({@link CustomerTier} のコメント)。
 */
@Getter
@Builder
public class TierStatusDto {

    /** 등급 이름({@code BASIC}/{@code SILVER}/…). 화면이 조건 분기에 쓴다. */
    // [JA] 等級名({@code BASIC}/{@code SILVER}/…)。画面が条件分岐に使う。
    private final String tier;

    /** 화면에 보일 한국어 이름. 서버가 정한다 — 두 곳에서 번역하면 어긋난다. */
    // [JA] 画面に表示する韓国語名。サーバーが決める — 二か所で翻訳すると食い違う。
    private final String displayName;

    /**
     * 등급을 마지막으로 계산한 시각.
     *
     * <p>이 값이 있어야 "오늘 큰 주문을 했는데 왜 그대로냐" 에 답할 수 있다. 배치는
     * 하루 한 번 돌고, 그 사실을 화면이 말해야 한다. 아직 한 번도 계산되지 않았으면
     * {@code null} 이다(가입 직후).
     *
     * <p>[JA] 等級を最後に計算した時刻。
     * この値があってこそ「今日大きな注文をしたのになぜそのままか」に答えられる。バッチは一日一回回り、
     * その事実を画面が伝えなければならない。まだ一度も計算されていなければ {@code null} である(登録直後)。
     */
    private final LocalDateTime computedAt;

    /** 등급 판단에 쓰인 최근 기간의 실결제액. */
    // [JA] 等級判断に使われた直近期間の実決済額。
    private final long recentPaidAmount;

    /** 가입 이후 결제된 주문 수. */
    // [JA] 登録以降に決済された注文数。
    private final long lifetimeOrderCount;

    /** 몇 개월을 보는가. 기준 금액과 함께 있어야 숫자가 뜻을 갖는다. */
    // [JA] 何か月を見るか。基準金額と一緒にあってこそ数字が意味を持つ。
    private final int months;

    private final long silverAmount;
    private final long goldAmount;
    private final long vipAmount;
}
