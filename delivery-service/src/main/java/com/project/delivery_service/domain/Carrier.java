package com.project.delivery_service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 택배사 마스터.
 *
 * <p>이 테이블이 있는 이유는 <b>택배사를 코드 수정 없이 늘리기 위해서</b>다(docs/25 §4).
 * 새 택배사를 붙일 때 필요한 것은 어댑터 클래스 하나와 이 테이블의 행 하나뿐이다 —
 * 스케줄러·상태 판정·알림·화면은 손대지 않는다.
 *
 * <p>[JA] 宅配業者マスター。
 * <p>このテーブルがある理由は<b>宅配業者をコード修正なしで増やすため</b>だ(docs/25 §4)。新しい宅配業者を付けるときに必要なのはアダプタークラス一つとこのテーブルの行一つだけ — スケジューラー・状態判定・通知・画面には手を付けない。
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "carrier", schema = "delivery")
public class Carrier implements Persistable<String> {

    /** 'KOREA_POST', 'CJ', 'SIMULATED' 등. 어댑터가 자기 코드로 자신을 등록한다. */
    // [JA] 'KOREA_POST'、'CJ'、'SIMULATED' など。アダプターが自分のコードで自身を登録する。
    @Id
    @Column(name = "carrier_code", length = 30)
    private String carrierCode;

    @Column(name = "name_ko", nullable = false, length = 60)
    private String nameKo;

    @Column(name = "name_en", length = 60)
    private String nameEn;

    /**
     * 고객이 직접 볼 수 있는 조회 페이지. {@code {trackingNumber}} 를 치환한다.
     *
     * <p>우리가 보여주지 못하는 정보(택배사 자체 화면의 지도·기사 연락처 등)를 사용자가
     * 직접 확인할 수 있게 한다. 추적 데이터가 늦거나 빠질 때의 안전판이기도 하다.
     *
     * <p>[JA] 顧客が直接見られる照会ページ。{@code {trackingNumber}} を置換する。
     * <p>こちらで見せられない情報(宅配業者自身の画面の地図・ドライバー連絡先など)をユーザーが直接確認できるようにする。追跡データが遅れたり抜けたりしたときの安全弁でもある。
     */
    @Column(name = "tracking_url", length = 300)
    private String trackingUrl;

    @Column(name = "active", nullable = false)
    private boolean active;

    /** 추적 API 를 제공하지 않는 택배사도 있다. 그런 곳은 송장번호만 보관한다. */
    // [JA] 追跡 API を提供しない宅配業者もある。そういう所は送り状番号だけを保管する。
    @Column(name = "supports_tracking", nullable = false)
    private boolean supportsTracking;

    public Carrier(String carrierCode, String nameKo, String nameEn,
                   String trackingUrl, boolean supportsTracking) {
        this.carrierCode = carrierCode;
        this.nameKo = nameKo;
        this.nameEn = nameEn;
        this.trackingUrl = trackingUrl;
        this.active = true;
        this.supportsTracking = supportsTracking;
    }

    /**
     * 송장번호를 끼운 조회 URL. 템플릿이 없으면 null.
     *
     * <p>송장번호는 퍼센트 인코딩해 끼운다(RFC 3986 의 비예약 문자 {@code A-Z a-z 0-9 - . _ ~} 밖은 모두, docs/57 §22).
     * 🐛 예전에는 글자 그대로 넣어, 관리자가 등록한 송장번호의 {@code &} · {@code #} · {@code /} · 공백이 고객 화면 링크의
     * 쿼리 · 경로를 바꿨다(예: {@code 123&sid2=…} 로 다른 매개변수를 끼우거나 {@code #} 뒤를 잘라 다른 화면으로).
     * <p>[JA] 送り状番号を埋め込んだ照会 URL。テンプレートがなければ null。
     * 送り状番号はパーセントエンコードして埋め込む(RFC 3986 の非予約文字 {@code A-Z a-z 0-9 - . _ ~} 以外はすべて、docs/57 §22)。
     * 🐛 以前は文字のまま入れ、管理者が登録した送り状番号の {@code &} · {@code #} · {@code /} · 空白が顧客画面のリンクのクエリ · パスを変えた
     * (例: {@code 123&sid2=…} で別のパラメータを差し込んだり、{@code #} の後ろを切って別の画面へ)。
     */
    public String trackingUrlFor(String trackingNumber) {
        if (trackingUrl == null || trackingNumber == null) {
            return null;
        }
        return trackingUrl.replace("{trackingNumber}", encodeUnreserved(trackingNumber));
    }

    private static String encodeUnreserved(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (byte b : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                out.append(c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
        }
        return out.toString();
    }

    // ── 왜 Persistable 을 구현하는가 ────────────────────────────────────────
    //
    // carrier_code('CJ', 'KOREA_POST' 등)는 어댑터가 정하는 자연 키다 — 코드가 직접 배정한다.
    // SimpleJpaRepository 는 save() 때 "@Id 가 null 인가"만 보고 신규 여부를 판단하므로,
    // id 가 이미 차 있는 이 엔티티는 persist() 가 아니라 merge() 로 간다. merge 는 붙일
    // 대상을 찾으려 SELECT 를 먼저 던지고(= INSERT 마다 헛조회), 같은 id 가 이미 커밋돼
    // 있으면 PK 충돌 대신 조용히 UPDATE 를 낸다.
    //
    // 플래그가 "저장됐으면 true" 방향인 이유는 기본값 false 가 곧 "새 것"이라 Lombok 이
    // 필드 초기화를 어떻게 다루든 안전한 쪽으로 떨어지기 때문이다. 불러온 엔티티는
    // @PostLoad 가 true 로 되돌리므로 수정 후 save() 는 그대로 UPDATE 로 간다.
    // [JA] ── なぜ Persistable を実装するのか ──
    //      carrier_code('CJ'、'KOREA_POST' など)はアダプターが決める自然キーだ — コードが直接割り当てる。
    //      SimpleJpaRepository は save() 時に「@Id が null か」だけを見て新規かどうかを判断するので、id が既に埋まっているこのエンティティは
    //      persist() ではなく merge() に行く。merge は付ける対象を探すため先に SELECT を投げ(= INSERT のたびに空振り照会)、同じ id が既にコミットされていれば
    //      PK 衝突の代わりに黙って UPDATE を出す。
    //      フラグが「保存済みなら true」の向きである理由は、デフォルト値 false がすなわち「新規」なので、Lombok がフィールド初期化をどう扱っても
    //      安全な側に落ちるからだ。読み込んだエンティティは @PostLoad が true に戻すので、修正後の save() はそのまま UPDATE に行く。
    @Transient
    private boolean persisted;

    @Override
    public String getId() {
        return carrierCode;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostPersist
    @PostLoad
    private void markPersisted() {
        this.persisted = true;
    }
}
