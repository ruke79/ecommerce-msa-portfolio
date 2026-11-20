package com.project.common.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ProductSkuDto {

    private String id;

    /**
     * 스타일(색상 변형) 코드. 이름은 역사적으로 {@code sku} 지만 재고 단위는 아니다 — 재고 단위의 SKU 는
     * 사이즈 한 줄마다 있는 {@link ProductSizeDto#getSkuCode()} 다(catalog V17).
     *
     * <p>[JA] スタイル(色バリエーション)コード。名前は歴史的に {@code sku} だが在庫単位ではない — 在庫単位の SKU はサイズ一行ごとにある
     * {@link ProductSizeDto#getSkuCode()} である(catalog V17)。
     */
    private String sku;

    private List<String> images;
    
    //private List<String> descriptionImages;

    private int discount;

    private int sold;

    private List<ProductSizeDto> sizes;
    private ProductColorDto color;
 /**
     * 해당 사이즈가 목록에 있는지 확인합니다.
     * @param size 확인할 사이즈 문자열
     * @return 사이즈 존재 여부
     *
     * <p>[JA] 該当サイズがリストにあるか確認する。size: 確認するサイズ文字列、戻り値: サイズの有無。
     */
    public boolean hasSize(String size) {
        // Stream의 anyMatch를 사용해 조건에 맞는 요소가 있는지 빠르게 확인합니다.
        // [JA] Stream の anyMatch を使い、条件に合う要素があるかを素早く確認する。
        return sizes.stream()
                .anyMatch(s -> s.getSize().equals(size));
    }

    /**
     * 해당 사이즈에 해당하는 ProductSizeDto를 가져옵니다.
     * @param size 가져올 사이즈 문자열
     * @return 해당 ProductSizeDto (없으면 null)
     *
     * <p>[JA] 該当サイズに対応する ProductSizeDto を取得する。size: 取得するサイズ文字列、戻り値: 該当 ProductSizeDto(なければ null)。
     */
    public ProductSizeDto getSize(String size) {
        // Stream의 filter와 findFirst를 사용해 첫 번째 일치하는 요소를 찾습니다.
        // Optional을 반환하므로 .orElse(null)을 사용해 null을 반환하도록 처리합니다.
        // [JA] Stream の filter と findFirst で最初に一致する要素を探す。
        //      Optional を返すので .orElse(null) で null を返すようにする。
        return sizes.stream()
                .filter(s -> s.getSize().equals(size))
                .findFirst()
                .orElse(null);
    }

    public boolean hasColor(String colorId) {
        return color.getId() == colorId;
    }

}
