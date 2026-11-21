package com.project.delivery_service.domain;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.project.common.i18n.CountryCodes;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 1건의 배송 전체 — <b>상자들을 담는 컨테이너</b>.
 *
 * <p>추적 정보(송장번호·상태·이력)는 여기가 아니라 {@link Shipment} 에 있다. 한 주문이 여러
 * 상자로 나뉘면 송장번호도 택배사도 도착일도 상자마다 다르기 때문이다(분할 배송).
 * 이 엔티티가 소유하는 것은 <b>주문 단위로 하나뿐인 것</b>들이다: 수취인, 배송지, 그리고
 * 상자들의 상태에서 파생되는 전체 진행도.
 *
 * <p>배송지가 여기 있는 것이 중요하다 — 상자가 나뉘어도 목적지는 하나이므로, 주소 변경은
 * 한 번에 모든 상자에 적용된다.
 *
 * <p>[JA] 注文 1 件の配送全体 — <b>箱をまとめるコンテナ</b>。
 * <p>追跡情報(送り状番号・状態・履歴)はここではなく {@link Shipment} にある。一つの注文が複数の箱に分かれると送り状番号も宅配業者も到着日も箱ごとに違うからだ(分割配送)。このエンティティが所有するのは<b>注文単位で一つしかないもの</b>: 受取人、配送先、そして箱の状態から派生する全体の進捗。
 * <p>配送先がここにあることが重要だ — 箱が分かれても目的地は一つなので、住所変更は一度にすべての箱に適用される。
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "delivery", schema = "delivery",
       indexes = {
           @Index(name = "idx_delivery_customer", columnList = "customer_id, created_at"),
           @Index(name = "idx_delivery_order", columnList = "order_id")
       })
public class Delivery {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(Delivery.class);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "delivery_id")
    private Long deliveryId;

    /**
     * 주문당 배송 1건. 분할은 {@link Shipment} 로 표현하므로 이 제약은 유지된다.
     *
     * <p>재주문은 <b>새 주문</b>이므로 새 Delivery 가 생긴다 — 취소된 배송을 재사용하지
     * 않는다. 재사용하면 취소 이력과 새 배송 이력이 한 행에 뒤섞인다.
     *
     * <p>[JA] 注文あたり配送 1 件。分割は {@link Shipment} で表現するのでこの制約は維持される。
     * <p>再注文は<b>新しい注文</b>なので新しい Delivery ができる — 取り消された配送を再利用しない。再利用すると取消履歴と新しい配送履歴が一行に混ざる。
     */
    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /**
     * 이 주문이 어떤 주문의 재주문인가. 취소 후 재주문 흐름에서 채워진다.
     *
     * <p>고객 화면에 "취소하신 주문 #123 의 재주문입니다"를 보여줄 수 있고, 같은 주소로
     * 재배송하려다 또 실패하는 패턴(주소 오류 등)을 찾을 수 있다.
     *
     * <p>[JA] この注文がどの注文の再注文か。取消後の再注文フローで埋まる。
     * <p>顧客画面に「取り消された注文 #123 の再注文です」と表示でき、同じ住所へ再配送しようとしてまた失敗するパターン(住所誤りなど)を見つけられる。
     */
    @Column(name = "reordered_from_order_id")
    private Long reorderedFromOrderId;

    // ── 수취인 스냅샷 ────────────────────────────────────────────────────────
    // OrderAddress 를 참조하지 않고 **복사**한다. 사용자가 배송 중에 프로필 주소를 바꿔도
    // 이미 출발한 택배의 목적지는 바뀌지 않는다. 참조로 두면 나중에 조회했을 때 현재 주소가
    // 나와 사실과 달라지고, 분쟁 자료로서의 가치가 사라진다(docs/25 §3.1).
    //
    // 필드 구성은 국제 배송을 염두에 둔다. 한국식 (시/도 + 시/군/구) 만 가정하면 미국·일본
    // 주소가 들어오지 못한다. line1/line2 + city + state + postalCode + country 는
    // 대부분의 국가를 담을 수 있는 최소 공통 형태다.
    // [JA] ── 受取人スナップショット ──
    //      OrderAddress を参照せず**コピー**する。ユーザーが配送中にプロフィールの住所を変えても、既に出発した荷物の目的地は変わらない。
    //      参照にすると後で照会したとき現在の住所が出て事実と食い違い、紛争資料としての価値が失われる(docs/25 §3.1)。
    //      フィールド構成は国際配送を念頭に置く。韓国式(市/道 + 市/郡/区)だけを想定すると米国・日本の住所が入らない。
    //      line1/line2 + city + state + postalCode + country は大半の国を収められる最小共通形だ。

    @Column(name = "recipient_name", nullable = false, length = 80)
    private String recipientName;

    @Column(name = "recipient_phone", length = 20)
    private String recipientPhone;

    @Column(name = "postal_code", length = 12)
    private String postalCode;

    @Column(name = "address_line1", nullable = false, length = 200)
    private String addressLine1;

    @Column(name = "address_line2", length = 200)
    private String addressLine2;

    @Column(name = "city", length = 80)
    private String city;

    @Column(name = "state", length = 80)
    private String state;

    /** ISO 3166-1 alpha-2. 국제 배송 판정과 통관 필요 여부의 근거다. */
    // [JA] ISO 3166-1 alpha-2。国際配送判定と通関要否の根拠。
    @Column(name = "country", nullable = false, length = 2)
    private String country;

    @Column(name = "delivery_memo", length = 200)
    private String deliveryMemo;

    @OneToMany(mappedBy = "delivery", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("sequenceNo ASC")
    private List<Shipment> shipments = new ArrayList<>();

    @OneToMany(mappedBy = "delivery", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("changedAt DESC")
    private List<AddressChange> addressChanges = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private Long version;

    @Builder
    public Delivery(Long orderId, Long customerId, Long reorderedFromOrderId,
                    String recipientName, String recipientPhone,
                    String postalCode, String addressLine1, String addressLine2,
                    String city, String state, String country, String deliveryMemo) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.reorderedFromOrderId = reorderedFromOrderId;
        this.recipientName = fit("recipientName", recipientName, 80);
        this.recipientPhone = fit("recipientPhone", recipientPhone, 20);
        // ⚠️ 폼은 우편번호를 30자까지 받는데 이 컬럼은 12자다 — country 와 **같은 종류**의
        //    사고가 여기 하나 더 남아 있었다. 길이를 맞추는 쪽을 택한다: 잘린 우편번호는
        //    관리자 화면에 보이고 발송 전에 고칠 수 있지만, 배송이 안 생기면 아무도 모른다.
        // [JA] ⚠️ フォームは郵便番号を 30 文字まで受け付けるがこのカラムは 12 文字だ — country と**同じ種類**の
        //      事故がここにもう一つ残っていた。長さを合わせる方を選ぶ: 切られた郵便番号は
        //      管理画面に見えて発送前に直せるが、配送ができなければ誰も気づかない。
        this.postalCode = fit("postalCode", postalCode, 12);
        this.addressLine1 = fit("addressLine1", addressLine1, 200);
        this.addressLine2 = fit("addressLine2", addressLine2, 200);
        this.city = fit("city", city, 80);
        this.state = fit("state", state, 80);
        // 🐛 예전에는 `country.toUpperCase()` 였다. 주소 폼은 나라를 **이름**으로 저장하는데
        //    (SingularSelect 가 option.name 을 값으로 쓴다) 이 컬럼은 varchar(2) 라,
        //    "Korea, Republic of" 가 들어오는 순간 배송 생성이 통째로 죽었다:
        //      ERROR: value too long for type character varying(2)
        //    그 예외는 배송 하나가 아니라 **결제 이벤트**를 DLT 로 보냈고, 화면에는
        //    "배송 준비 중" 만 남아 원인이 보이지 않았다(docs/25 §11.5).
        //
        //    이름이든 코드든 여기서 코드로 바꾼다. 알아보지 못하면 발송국으로 본다 —
        //    통관 단계가 어긋나는 것이 배송이 아예 안 생기는 것보다 낫다.
        // [JA] 🐛 以前は `country.toUpperCase()` だった。住所フォームは国を**名前**で保存するが
        //      (SingularSelect が option.name を値に使う)このカラムは varchar(2) なので、
        //      "Korea, Republic of" が入った瞬間に配送作成が丸ごと落ちた(value too long for type character varying(2))。
        //      その例外は配送一つではなく**決済イベント**を DLT へ送り、画面には
        //      「配送準備中」だけが残って原因が見えなかった(docs/25 §11.5)。
        //      名前でもコードでもここでコードに変える。判別できなければ発送国とみなす —
        //      通関段階がずれる方が配送がまったくできないよりましだ。
        this.country = CountryCodes.toAlpha2OrDefault(country, "KR");
        this.deliveryMemo = fit("deliveryMemo", deliveryMemo, 200);
        // UTC 로 통일한다 — Asia/Seoul 하드코딩이 아웃박스 파티션 키를 어긋나게 했던 것과
        // 같은 실수를 반복하지 않는다(B-21). 표시 시점의 변환은 프론트엔드가 한다.
        // [JA] UTC に統一する — Asia/Seoul のハードコードがアウトボックスのパーティションキーをずらしたのと
        //      同じ過ちを繰り返さない(B-21)。表示時点の変換はフロントエンドが行う。
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
        this.updatedAt = this.createdAt;
    }

    /**
     * 컬럼 길이에 맞춘다. 넘치면 <b>자르고 경고를 남긴다</b>.
     *
     * <h3>왜 자르나 — 예외를 던지지 않는 이유</h3>
     *
     * <p>이 값들은 결제 이벤트를 처리하는 도중에 저장된다. 한 칸이 길어서 예외가 나면 그
     * 레코드는 재시도 끝에 DLT 로 가고, <b>배송은 영영 만들어지지 않는다.</b> 화면에는
     * "배송 준비 중" 만 남아 아무도 원인을 모른다 — 나라 이름 하나로 실제로 그랬다.
     *
     * <p>잘린 값은 관리자 배송 화면에 그대로 보이고, 발송 전이면 주소 변경으로 고칠 수 있다.
     * <b>보이는 잘못이 보이지 않는 누락보다 낫다.</b> 경고 로그에 어느 칸인지 남는다.
     *
     * <p>값 자체는 찍지 않는다 — 수취인 이름·연락처·주소는 개인정보다.
     *
     * <p>[JA] カラム長に合わせる。溢れたら<b>切り詰めて警告を残す</b>。<b>なぜ切るのか — 例外を投げない理由。</b>
     * <p>これらの値は決済イベントの処理中に保存される。一つの欄が長くて例外になると、そのレコードはリトライの末に DLT へ行き<b>配送は永遠に作られない。</b>画面には「配送準備中」だけが残り誰も原因が分からない — 国名一つで実際にそうなった。
     * <p>切られた値は管理者の配送画面にそのまま見え、発送前なら住所変更で直せる。<b>見える誤りの方が見えない欠落よりましだ。</b>警告ログにどの欄かが残る。
     * <p>値自体は出力しない — 受取人の名前・連絡先・住所は個人情報だ。
     */
    private static String fit(String field, String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        LOG.warn("배송 {} 가 컬럼 길이({})를 넘어 잘라서 저장한다: 길이={}", field, max, value.length());
        return value.substring(0, max);
    }

    /** 판매국 기준 국제 배송인가. 통관 단계가 끼는지를 가른다. */
    // [JA] 販売国基準で国際配送か。通関段階が挟まるかを分ける。
    public boolean isInternational(String originCountry) {
        return !this.country.equalsIgnoreCase(originCountry);
    }

    public Shipment addShipment(boolean international) {
        Shipment shipment = new Shipment(this, shipments.size() + 1, international);
        shipments.add(shipment);
        touch();
        return shipment;
    }

    /**
     * 전체 진행도 — <b>가장 뒤처진 상자</b>를 따른다.
     *
     * <p>3개 중 2개가 도착했어도 주문은 아직 완료가 아니다. 반대로 하면(가장 앞선 상자를
     * 따르면) 아직 안 온 물건이 있는데 "배달완료"가 되어 사용자가 신고할 수 없게 된다.
     *
     * <p>단, 예외 상태인 상자가 하나라도 있으면 그걸 우선한다 — 문제는 묻히면 안 된다.
     *
     * <p>[JA] 全体の進捗 — <b>最も遅れている箱</b>に従う。
     * <p>3 個中 2 個が到着しても注文はまだ完了ではない。逆にすると(最も進んだ箱に従うと)まだ届いていない品物があるのに「配達完了」になり、ユーザーが申告できなくなる。
     * <p>ただし例外状態の箱が一つでもあればそれを優先する — 問題は埋もれてはならない。
     */
    public DeliveryStatus overallStatus() {
        if (shipments.isEmpty()) {
            return DeliveryStatus.PENDING;
        }
        boolean allCancelled = shipments.stream()
                .allMatch(s -> s.getStatus() == DeliveryStatus.CANCELLED);
        if (allCancelled) {
            return DeliveryStatus.CANCELLED;
        }
        // 예외를 먼저 본다.
        // [JA] 例外を先に見る。
        for (Shipment s : shipments) {
            if (s.getStatus().stage() == DeliveryStatus.Stage.EXCEPTION) {
                return s.getStatus();
            }
        }
        return shipments.stream()
                .map(Shipment::getStatus)
                // 취소된 상자는 진행도 계산에서 뺀다 (재주문으로 대체됐을 수 있다).
                // [JA] 取り消された箱は進捗計算から外す (再注文で置き換えられた可能性がある)。
                .filter(s -> s != DeliveryStatus.CANCELLED)
                .min(Comparator.comparingInt(Enum::ordinal))
                .orElse(DeliveryStatus.CANCELLED);
    }

    /** 전체 예상 도착일 — 가장 늦게 오는 상자 기준. 사용자가 알고 싶은 것은 "언제 다 오나"다. */
    // [JA] 全体の到着予定日 — 最も遅く届く箱基準。ユーザーが知りたいのは「いつ全部届くか」だ。
    public LocalDateTime overallEstimatedAt() {
        return shipments.stream()
                .map(Shipment::getEstimatedAt)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    /**
     * 배송지를 바꿀 수 있는 상태인가.
     *
     * <p><b>상자가 하나라도 발송됐으면 불가</b>다. 일부만 바꾸면 같은 주문의 물건이 서로 다른
     * 곳으로 가고, 그 상태는 어떤 화면으로도 설명할 수 없다.
     *
     * <p>[JA] 配送先を変えられる状態か。
     * <p><b>箱が一つでも発送されていれば不可</b>だ。一部だけ変えると同じ注文の品物が別々の場所へ行き、その状態はどの画面でも説明できない。
     */
    public boolean canChangeAddress() {
        // 빈 스트림의 allMatch 는 true 다 — 상자가 아직 없으면(송장 등록 전) 당연히 변경 가능하다.
        // [JA] 空ストリームの allMatch は true だ — 箱がまだなければ(送り状登録前)当然変更可能だ。
        return shipments.stream().allMatch(s -> s.getStatus().isBeforeDispatch());
    }

    /**
     * 배송지를 변경한다. 이력을 남기고 새 주소를 적용한다.
     *
     * @return 변경 이력. 변경할 수 없는 상태면 null
     *
     * <p>[JA] 配送先を変更する。履歴を残して新しい住所を適用する。@return 変更履歴。変更できない状態なら null
     */
    public AddressChange changeAddress(Long changedBy, String recipientName, String recipientPhone,
                                       String postalCode, String addressLine1, String addressLine2,
                                       String city, String state, String country,
                                       String deliveryMemo, String reason) {
        if (!canChangeAddress()) {
            return null;
        }
        String before = formattedAddress();

        this.recipientName = recipientName != null ? recipientName : this.recipientName;
        this.recipientPhone = recipientPhone != null ? recipientPhone : this.recipientPhone;
        this.postalCode = postalCode;
        this.addressLine1 = addressLine1;
        this.addressLine2 = addressLine2;
        this.city = city;
        this.state = state;
        if (country != null) {
            // 생성 때와 같은 규칙이다. 주소 변경도 폼에서 오므로 같은 이름이 들어온다.
            // [JA] 作成時と同じ規則だ。住所変更もフォームから来るので同じ名前が入ってくる。
            this.country = CountryCodes.toAlpha2OrDefault(country, this.country);
        }
        this.deliveryMemo = deliveryMemo;
        touch();

        AddressChange change = new AddressChange(this, changedBy, before, formattedAddress(), reason);
        addressChanges.add(change);
        return change;
    }

    /** 발송 전 취소. 모든 상자가 발송 전이어야 한다. */
    // [JA] 発送前の取消。すべての箱が発送前でなければならない。
    /**
     * 다시 맞춘 결과. {@code dispatchedExcess} 는 이미 송장이 붙은 상자에 있어 줄이지 못한 수(품목 id → 수) — 사람이 실물을 본다.
     * [JA] 合わせ直しの結果。dispatchedExcess は送り状のある箱にあって減らせなかった数。
     */
    public record QuantitySync(int reducedUnits, java.util.List<Integer> cancelledShipments, java.util.Map<Long, Integer> dispatchedExcess) {
        public boolean changed() {
            return reducedUnits > 0;
        }
    }

    /**
     * 보낼 수(주문의 배정된 수, 품목 id 마다)에 맞춰 상자의 수량을 <b>줄인다</b> — 5단계 15, 대형 EC 의 '있는 것만 보내고 없는 것은 취소':
     * <ul>
     *   <li>고칠 수 있는 상자(송장 없음 · 대기)만 — 나중 상자부터(분할 배송의 마지막 상자가 아직 안 집혔을 가능성이 크다).</li>
     *   <li>0 이 된 품목은 빼고, 그래서 빈 상자는 취소한다.</li>
     *   <li>늘리지 않는다 · 목록에 없는 품목은 그대로(옛 주문 · 다른 경로로 더한 줄).</li>
     *   <li>송장이 붙은 상자에 남은 초과분은 줄이지 않고 돌려준다(이미 집었다 — 사람이 확인).</li>
     * </ul>
     * 멱등이다 — 같은 목표로 다시 불러도 아무것도 줄지 않는다.
     * [JA] 送る数(注文の割り当て数)に合わせて箱の数量を減らす — 直せる箱だけ、後の箱から。0 の品目は外し、空の箱は取り消す。冪等。
     */
    public QuantitySync syncShippableQuantities(java.util.Map<Long, Integer> shippable) {
        java.util.Map<Long, Integer> current = new java.util.LinkedHashMap<>();
        for (Shipment s : shipments) {
            if (s.getStatus() == DeliveryStatus.CANCELLED) {
                continue;
            }
            for (ShipmentItem item : s.getItems()) {
                if (item.getOrderProductId() != null) {
                    current.merge(item.getOrderProductId(), item.getQuantity(), Integer::sum);
                }
            }
        }
        java.util.List<Shipment> editable = shipments.stream().filter(Shipment::isEditable)
                .sorted(Comparator.comparingInt(Shipment::getSequenceNo).reversed()).toList();
        int reduced = 0;
        java.util.Set<Shipment> touched = new java.util.HashSet<>();
        java.util.Map<Long, Integer> late = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<Long, Integer> e : current.entrySet()) {
            Integer target = shippable.get(e.getKey());
            if (target == null) {
                continue;
            }
            int excess = e.getValue() - Math.max(0, target);
            for (Shipment s : editable) {
                if (excess <= 0) {
                    break;
                }
                for (ShipmentItem item : new java.util.ArrayList<>(s.getItems())) {
                    if (excess <= 0) {
                        break;
                    }
                    if (!e.getKey().equals(item.getOrderProductId()) || item.getQuantity() <= 0) {
                        continue;
                    }
                    int cut = Math.min(excess, item.getQuantity());
                    item.reduceBy(cut);
                    excess -= cut;
                    reduced += cut;
                    touched.add(s);
                    if (item.getQuantity() == 0) {
                        s.removeItem(item);
                    }
                }
            }
            if (excess > 0) {
                late.put(e.getKey(), excess);
            }
        }
        java.util.List<Integer> cancelled = new java.util.ArrayList<>();
        for (Shipment s : touched) {
            if (s.getItems().isEmpty() && s.cancel()) {
                cancelled.add(s.getSequenceNo());
            }
        }
        if (reduced > 0) {
            touch();
        }
        java.util.Collections.sort(cancelled);
        return new QuantitySync(reduced, cancelled, late);
    }

    public boolean cancel() {
        if (shipments.isEmpty()) {
            touch();
            return true;
        }
        if (!shipments.stream().allMatch(s -> s.getStatus().isBeforeDispatch())) {
            return false;
        }
        shipments.forEach(Shipment::cancel);
        touch();
        return true;
    }

    /** 이력·표시용 한 줄 주소. */
    // [JA] 履歴・表示用の一行住所。
    public String formattedAddress() {
        StringBuilder sb = new StringBuilder();
        if (postalCode != null) {
            sb.append('[').append(postalCode).append("] ");
        }
        sb.append(addressLine1);
        if (addressLine2 != null && !addressLine2.isBlank()) {
            sb.append(' ').append(addressLine2);
        }
        if (city != null) {
            sb.append(", ").append(city);
        }
        if (state != null) {
            sb.append(", ").append(state);
        }
        sb.append(", ").append(country);
        return sb.toString();
    }

    private void touch() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
