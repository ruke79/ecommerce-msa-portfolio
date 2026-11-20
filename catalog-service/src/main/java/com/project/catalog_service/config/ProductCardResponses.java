package com.project.catalog_service.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.catalog_service.dto.EventDtos.EventDetailView;
import com.project.catalog_service.dto.HomeDtos.HomeView;
import com.project.catalog_service.dto.SearchResultDto;
import com.project.common.dto.ProductDto;
import com.project.common.dto.ProductSizeDto;
import com.project.common.dto.ProductSkuDto;

import lombok.RequiredArgsConstructor;

/**
 * 상품 <b>목록</b> 응답(검색 · 첫 화면 · 기획전 상세)은 카드가 쓰는 필드만 보낸다.
 *
 * <p>🐛 2026-10-24 CPU 프로파일(docs/83 §5, 게이트웨이 4 회차): catalog CPU 의 17.6% 가 응답 JSON 쓰기였고, 그중 41% 는 소켓 · TLS 로
 * 내보내는 몫, 34% 는 문자열 쓰기였다 — 쓰는 속도보다 <b>보내는 양</b>이 비용을 정한다. 목록은 상품 전체({@link ProductDto} — 설명 ·
 * 상세 · 문답 · 분류 · 환불 규정 · 배송비 · 사이즈 코드 …)를 실었지만 카드({@code ProductCard.jsx} · {@code util/stock.js} ·
 * {@code util/pricing.js})가 읽는 것은 아래 허용 목록뿐이다. 검색 결과 20개(데모 모양)에서 54KB → 33KB(−40%), 쓰는 시간 −44%
 * (로컬 측정). 게이트웨이도 그만큼 덜 옮긴다(TLS 두 번).
 *
 * <p>허용 목록(빼는 목록이 아니다): 상품에 새 필드가 생겨도 목록으로 새지 않는다. 카드가 새 필드를 읽게 되면 여기에 더한다
 * ({@code ProductCardResponsesTest} 가 화면 · k6 가 읽는 필드를 지킨다). 상품 상세({@code /api/product/…})와 다른 응답은 그대로다 —
 * 응답 <b>타입</b>으로 매퍼를 고른다({@link MappingJackson2HttpMessageConverter#registerObjectMappersForType}). 캐시(Redis)에 든 값은
 * 그대로 전체다 — 응답을 쓸 때만 줄인다.
 *
 * <p>[JA] 商品の<b>一覧</b>応答(検索 · トップ · 企画展詳細)はカードが使うフィールドだけを送る。
 *
 * <p>[JA] 2026-10-24 CPU プロファイル(docs/83 §5、ゲートウェイ 4 回目): catalog CPU の 17.6% が応答 JSON の書き出しで、そのうち 41% はソケット · TLS へ
 * 送り出す分、34% は文字列の書き込みだった — 書く速さより<b>送る量</b>がコストを決める。一覧は商品全体({@link ProductDto} — 説明 ·
 * 詳細 · Q&amp;A · 分類 · 返金規定 · 送料 · サイズコード …)を載せていたが、カード({@code ProductCard.jsx} · {@code util/stock.js} ·
 * {@code util/pricing.js})が読むのは下の許可リストだけである。検索結果 20 件(デモの形)で 54KB → 33KB(−40%)、書き出し時間 −44%
 * (ローカル測定)。ゲートウェイもその分運ぶ量が減る(TLS 二回)。
 *
 * <p>[JA] 許可リスト(除外リストではない): 商品に新しいフィールドができても一覧に漏れない。カードが新しいフィールドを読むようになればここに足す
 * ({@code ProductCardResponsesTest} が画面 · k6 の読むフィールドを守る)。商品詳細({@code /api/product/…})と他の応答はそのまま —
 * 応答の<b>型</b>でマッパーを選ぶ({@link MappingJackson2HttpMessageConverter#registerObjectMappersForType})。キャッシュ(Redis)に入った値は
 * そのまま全体 — 応答を書くときだけ減らす。
 */
@Configuration
@RequiredArgsConstructor
public class ProductCardResponses implements WebMvcConfigurer {

    /** 카드 매퍼를 쓰는 응답 타입 — 상품 목록을 담는 것만. ／ [JA] カード用マッパーを使う応答型。 */
    static final List<Class<?>> CARD_RESPONSES = List.of(SearchResultDto.class, HomeView.class, EventDetailView.class);

    /** 카드가 읽는 상품 필드 — {@code ProductCard.jsx}(slug · name · skus) · {@code stockStateOf}(status) · k6 {@code purchasable}(id). */
    // [JA] カードが読む商品フィールド — {@code ProductCard.jsx}(slug · name · skus) · {@code stockStateOf}(status) · k6 {@code purchasable}(id)。
    @JsonIncludeProperties({ "id", "name", "slug", "status", "skus" })
    abstract static class ProductCardFields {
    }

    /** 색상(스타일)마다 — 사진 · 색 · 할인율 · 사이즈. */
    // [JA] 色(スタイル)ごとに — 写真 · 色 · 割引率 · サイズ。
    @JsonIncludeProperties({ "id", "images", "discount", "color", "sizes" })
    abstract static class SkuCardFields {
    }

    /** 사이즈마다 — 가격 범위(pricing) · 재고 배지(stock: quantity · onHold). */
    // [JA] サイズごとに — 価格帯(pricing) · 在庫バッジ(stock: quantity · onHold)。
    @JsonIncludeProperties({ "size", "price", "quantity", "onHold" })
    abstract static class SizeCardFields {
    }

    /** Boot 가 만든 매퍼(모듈 · Page 를 PagedModel 로 쓰는 설정 포함) — 그것을 복사해 카드 규칙만 얹는다. */
    // [JA] Boot が作ったマッパー(モジュール · Page を PagedModel で書く設定を含む) — それをコピーしてカードの規則だけを載せる。
    private final ObjectMapper objectMapper;

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        ObjectMapper cards = cardMapper(objectMapper);
        for (HttpMessageConverter<?> converter : converters) {
            if (converter instanceof MappingJackson2HttpMessageConverter json) {
                register(json, cards);
            }
        }
    }

    /** 목록 응답 타입에 카드 매퍼를 건다 — 이 변환기가 쓰는 모든 미디어 타입에. ／ [JA] 一覧の応答型にカード用マッパーを登録する。 */
    static void register(MappingJackson2HttpMessageConverter converter, ObjectMapper cards) {
        for (Class<?> type : CARD_RESPONSES) {
            converter.registerObjectMappersForType(type, mappers -> mappers.put(MediaType.ALL, cards));
        }
    }

    /** 기본 매퍼의 복사본 + 카드 규칙. 기본 매퍼는 건드리지 않는다(상세 응답 · Feign 이 쓴다). */
    // [JA] 既定マッパーのコピー + カードの規則。既定マッパーには触れない(詳細応答 · Feign が使う)。
    static ObjectMapper cardMapper(ObjectMapper base) {
        return base.copy()
                .addMixIn(ProductDto.class, ProductCardFields.class)
                .addMixIn(ProductSkuDto.class, SkuCardFields.class)
                .addMixIn(ProductSizeDto.class, SizeCardFields.class);
    }
}
