package com.project.common.dto.order;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 주문의 배송지 — 주문 생성 요청({@code OrderRequest.shippingAddress})과 주문 조회 응답이 함께 쓴다.
 *
 * <p>금액 · SKU 와 달리 주소는 <b>손님이 적은 값을 그대로 쓸 수밖에 없다</b> — 그래서 서버가 최소한을 검증한다. 예전에는 검증이 하나도
 * 걸려 있지 않아(어노테이션은 import 만, 컨트롤러에 {@code @Valid} 없음) 기본 주소 · 도시 · 시/도 · 우편번호가 없으면 DB 의
 * NOT NULL 위반으로 500 이 났고, 빈 문자열이나 수취인 없는 주소는 그대로 저장돼 delivery-service 까지 흘러갔다.
 *
 * <p>필수는 DB 가 NOT NULL 로 요구하는 넷과 수취인 이름(성 · 이름 중 하나)뿐이다. 길이는 DB 폭(255)까지만 막는다 — 화면(AddShipping.jsx)이
 * 더 좁게 막고, 그보다 엄격하면 예전에 저장한 주소록의 주소로 주문하지 못하게 된다.
 *
 * <p>[JA] 注文の配送先 — 注文作成リクエストと注文照会レスポンスが共に使う。金額・SKU と違い住所は<b>客が書いた値をそのまま使うしかない</b>
 * — だからサーバーが最低限を検証する。以前は検証が一つも掛かっておらず、基本住所・市区町村・都道府県・郵便番号がないと DB の NOT NULL
 * 違反で 500 になり、空文字や受取人のない住所はそのまま保存されていた。必須は DB が NOT NULL で求める四つと受取人名(姓・名のどちらか)だけ。
 * 長さは DB の幅(255)までだけ — 画面がもっと狭く止めており、それより厳しくすると以前保存した住所録の住所で注文できなくなる。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class OrderAddressDto {

    /** 컬럼 폭 — orders.order_address 의 varchar(255). ／ [JA] カラム幅。 */
    static final int MAX = 255;

    private String id;

    @Size(max = MAX)
    private String firstname;

    @Size(max = MAX)
    private String lastname;

    @NotBlank
    @Size(max = MAX)
    private String address1;

    @Size(max = MAX)
    private String address2;

    @NotBlank
    @Size(max = MAX)
    private String city;

    @NotBlank
    @Size(max = MAX)
    private String state;

    @NotBlank
    @Size(max = MAX)
    private String zipCode;

    @Size(max = MAX)
    private String country;

    @Size(max = MAX)
    private String phoneNumber;

    private boolean active;

    /** 받는 사람이 있다 — 성 · 이름 중 하나는 있어야 한다(한국 이름은 한쪽에 다 적기도 한다). JSON 에는 싣지 않는다. */
    // [JA] 受取人がいる — 姓・名のどちらかは必要(片方にまとめて書くこともある)。JSON には載せない。
    @JsonIgnore
    @AssertTrue
    public boolean isRecipientNamed() {
        return (firstname != null && !firstname.isBlank()) || (lastname != null && !lastname.isBlank());
    }
}
