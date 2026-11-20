package com.project.userservice.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.project.common.constants.event.AudienceSegment;
import com.project.userservice.model.User;

import lombok.extern.slf4j.Slf4j;

/**
 * 사용자가 어떤 고객군에 속하는가.
 *
 * <p><b>왜 여기인가.</b> "신규가 며칠인가" 는 우리 서비스의 정책이고, 그 판단에 필요한
 * 가입일은 user-service 만 갖고 있다. 가입일을 다른 서비스에 넘겨 각자 계산하게 하면
 * 같은 규칙이 여러 곳에 생기고, 기준을 30일에서 14일로 바꿀 때 한 곳을 빠뜨린다.
 *
 * <p>쿠폰 서비스는 "이 사람의 세그먼트가 무엇인가" 만 물어보고, 기획전이 요구하는
 * 세그먼트와 대조한다.
 *
 * <p>[JA] ユーザーがどの顧客層に属するか。
 * <p><b>なぜここか。</b>「新規は何日までか」は当サービスのポリシーで、その判断に必要な登録日は user-service だけが持っている。登録日を他のサービスに渡して各自計算させると
 * 同じ規則が複数箇所にでき、基準を 30 日から 14 日に変えるとき一か所を漏らす。
 * <p>クーポンサービスは「この人のセグメントは何か」だけを尋ね、企画展が要求するセグメントと照合する。
 */
@Slf4j
@Service
public class SegmentService {

    /**
     * 가입 후 며칠까지를 신규로 보는가.
     *
     * <p>설정으로 두는 이유: 판촉 정책은 코드가 아니다. 가입 쿠폰의 할인율을 설정으로
     * 뺀 것과 같은 이유다({@code WelcomeCouponProperties}).
     *
     * <p>[JA] 登録後何日までを新規とみなすか。
     * <p>設定にする理由: 販促ポリシーはコードではない。登録クーポンの割引率を設定に出したのと同じ理由である(WelcomeCouponProperties)。
     */
    @Value("${user.segment.new-user-days:30}")
    private int newUserDays;

    /**
     * 이 사용자가 지금 속한 세그먼트.
     *
     * <p>{@link AudienceSegment#ALL} 은 <b>담지 않는다.</b> 모두가 항상 속하므로 목록에
     * 넣어 봤자 정보가 없고, 받는 쪽은 {@code ALL} 을 "조건 없음" 으로 따로 처리한다
     * ({@link AudienceSegment#matches}).
     *
     * <p>{@link AudienceSegment#NEW_USER} 와 {@link AudienceSegment#EXISTING_USER} 는
     * 언제나 <b>정확히 하나</b>가 붙는다 — 둘을 합치면 로그인 사용자 전체다. 구매 이력
     * ({@code PURCHASED}/{@code NEVER_PURCHASED})도 마찬가지다.
     *
     * <p>등급은 다르다. 일반 등급이면 <b>아무것도 붙지 않고</b>, 골드면 {@code TIER_SILVER}
     * 와 {@code TIER_GOLD} 가 <b>둘 다</b> 붙는다 — "실버 이상" 을 받는 쪽에서 단순 포함
     * 검사로 판정하게 하려는 것이다({@link com.project.common.constants.event.CustomerTier#segments()}).
     *
     * <p>🔴 등급과 구매 이력은 <b>여기서 계산하지 않는다.</b> 저장된 값을 읽을 뿐이다.
     * 배치({@link CustomerTierService})가 하루 한 번 채운다. 이 메서드는 쿠폰 발급
     * 경로에 있어서, 여기서 order-service 를 동기 호출하면 주문 서비스 장애가 쿠폰
     * 발급 장애로 번진다.
     *
     * <p>[JA] このユーザーが今属するセグメント。
     * <p>AudienceSegment#ALL は<b>入れない。</b> 全員が常に属するので一覧に入れても情報がなく、受け取る側は ALL を「条件なし」として別に扱う(AudienceSegment#matches)。
     * <p>NEW_USER と EXISTING_USER は常に<b>ちょうど一つ</b>が付く — 二つを合わせるとログインユーザー全体。購入履歴(PURCHASED/NEVER_PURCHASED)も同様。
     * <p>等級は違う。一般等級なら<b>何も付かず</b>、ゴールドなら TIER_SILVER と TIER_GOLD が<b>両方</b>付く — 「シルバー以上」を受け取る側で単純な包含検査で判定できるようにするため(CustomerTier#segments())。
     * <p>🔴 等級と購入履歴は<b>ここで計算しない。</b> 保存された値を読むだけ。バッチ(CustomerTierService)が一日一回埋める。このメソッドはクーポン発行経路にあり、
     * ここで order-service を同期呼び出しすると注文サービスの障害がクーポン発行の障害に広がる。
     */
    public List<AudienceSegment> segmentsOf(User user) {
        if (user == null) {
            return List.of();
        }

        List<AudienceSegment> segments = new ArrayList<>();

        // 축 1 — 가입 시점. 둘 중 정확히 하나.
        // [JA] 軸 1 — 登録時点。二つのうちちょうど一つ。
        segments.add(isNew(user) ? AudienceSegment.NEW_USER : AudienceSegment.EXISTING_USER);

        // 축 2 — 구매 이력. 둘 중 정확히 하나.
        // [JA] 軸 2 — 購入履歴。二つのうちちょうど一つ。
        segments.add(user.hasPurchased() ? AudienceSegment.PURCHASED : AudienceSegment.NEVER_PURCHASED);

        // 축 3 — 등급. 없을 수도(일반), 여러 개일 수도(골드면 실버·골드 둘 다) 있다.
        // "실버 이상" 을 받는 쪽에서 단순 포함 검사로 판정할 수 있게 누적으로 준다.
        // [JA] 軸 3 — 等級。ないことも(一般)、複数のことも(ゴールドならシルバー・ゴールド両方)ある。「シルバー以上」を受け取る側で単純な包含検査で判定できるよう累積で渡す。
        segments.addAll(user.tierOrBasic().segments());

        return List.copyOf(segments);
    }

    /**
     * 신규인가.
     *
     * <p>가입일이 없으면 <b>신규가 아니다.</b> createdAt 은 감사 필드라 비어 있을 수
     * 있는데(옛 데이터·직접 삽입), 그때 신규로 치면 <b>모든 옛 계정이 신규 전용 혜택을
     * 받는다</b> — 혜택을 잘못 주는 쪽이 못 주는 쪽보다 되돌리기 어렵다.
     *
     * <p>[JA] 新規か。
     * <p>登録日がなければ<b>新規ではない。</b> createdAt は監査フィールドなので空のことがあり(古いデータ・直接挿入)、そのとき新規とみなすと<b>すべての古いアカウントが新規専用特典を受け取る</b> —
     * 特典を誤って与える方が与えそこなう方より戻しにくい。
     */
    boolean isNew(User user) {
        LocalDateTime joinedAt = user.getCreatedAt();
        if (joinedAt == null) {
            log.debug("가입일이 없는 계정 — 신규로 보지 않는다. userId={}", user.getUserId());
            return false;
        }
        return joinedAt.isAfter(LocalDateTime.now().minus(Duration.ofDays(Math.max(0, newUserDays))));
    }
}
