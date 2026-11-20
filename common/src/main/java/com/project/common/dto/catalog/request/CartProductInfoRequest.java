package com.project.common.dto.catalog.request;

import java.util.List;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 장바구니 항목 여러 개의 (상품, 스타일, 사이즈) 정보를 <b>한 번에</b> 묻는 요청 —
 * {@code GET /api/cart/info?items=1:0:0&items=2:1:0}. 항목마다
 * {@code GET /api/cart/{productId}?style=&size=} 를 부르던 것의 대체다(docs/38 §1.13).
 *
 * <p><b>왜 GET 인가.</b> catalog 의 {@code AdminWriteInterceptor} 는 POST/PUT/DELETE 에
 * 게이트웨이가 붙이는 {@code User-Id}/{@code User-Role} 를 요구한다. 서비스 간 Feign 호출에는
 * 그 헤더가 없으므로(Authorization 만 복사) POST 로 만들면 운영에서 401 이다 — 읽기 요청이니
 * GET 이 맞기도 하다. 항목은 {@link Item#key()} 형식 문자열로 실린다.
 *
 * <p>응답은 {@code Map<String, ProductInfoDto>} 이고 키는 {@link Item#key()} 다. 요청 순서에
 * 기대지 않는 이유: 못 찾은 항목(없는 상품, 품절, 잘못된 인덱스)은 맵에서 빠질 뿐 실패가
 * 아니어서, 목록으로 돌려주면 순서 대조가 깨진다.
 *
 * <p>[JA] カート項目複数の(商品、スタイル、サイズ)情報を<b>一度に</b>問い合わせるリクエスト — {@code GET /api/cart/info?items=1:0:0&items=2:1:0}。
 * 項目ごとに {@code GET /api/cart/{productId}?style=&size=} を呼んでいたものの置き換えである(docs/38 §1.13)。
 * <b>なぜ GET か。</b>catalog の {@code AdminWriteInterceptor} は POST/PUT/DELETE にゲートウェイが付ける {@code User-Id}/{@code User-Role} を要求する。
 * サービス間の Feign 呼び出しにはそのヘッダーがない(Authorization だけコピー)ので、POST にすると運用で 401 になる — 読み取りなので GET が正しくもある。
 * 項目は {@link Item#key()} 形式の文字列で載る。
 * 応答は {@code Map<String, ProductInfoDto>} でキーは {@link Item#key()} である。リクエスト順に頼らない理由: 見つからない項目(ない商品、品切れ、
 * 誤ったインデックス)はマップから抜けるだけで失敗ではないので、リストで返すと順序の突き合わせが崩れる。
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class CartProductInfoRequest {

    private List<Item> items;

    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @AllArgsConstructor
    public static class Item {
        private Long productId;
        /** 상품의 SKU 목록에서의 위치(색상). ／ [JA] 商品の SKU リスト内での位置(色)。 */
        private int style;
        /** 그 SKU 의 사이즈 목록에서의 위치. ／ [JA] その SKU のサイズリスト内での位置。 */
        private int size;

        /** 응답 맵의 키. 양쪽(catalog·cart)이 같은 규칙을 써야 하므로 여기 한 곳에 둔다. ／ [JA] 応答マップのキー。両側(catalog · cart)が同じ規則を使う必要があるので、ここ一か所に置く。 */
        public String key() {
            return key(productId, style, size);
        }

        public static String key(Long productId, int style, int size) {
            return productId + ":" + style + ":" + size;
        }

        /** {@link #key()} 의 역 — 쿼리 파라미터로 실린 문자열을 되돌린다. 형식이 틀리면 예외. ／ [JA] {@link #key()} の逆 — クエリパラメータで載った文字列を戻す。形式が誤っていれば例外。 */
        public static Item parse(String key) {
            String[] parts = key == null ? new String[0] : key.split(":", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException("cart item key must be productId:style:size — " + key);
            }
            try {
                return new Item(Long.parseLong(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("cart item key must be numeric — " + key, e);
            }
        }
    }
}
