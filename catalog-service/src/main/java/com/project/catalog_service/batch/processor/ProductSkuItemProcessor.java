package com.project.catalog_service.batch.processor;

import com.project.catalog_service.dto.data.ProductSkuCsvDto;
import com.project.catalog_service.model.Product;
import com.project.catalog_service.model.ProductColor;
import com.project.catalog_service.model.ProductSku;
import com.project.catalog_service.repository.jpa.data.ProductColorRepository;
import com.project.catalog_service.repository.jpa.data.ProductRepository;
import com.project.catalog_service.service.ImageService;

import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ProductSkuItemProcessor implements ItemProcessor<ProductSkuCsvDto, ProductSku> {

    private final ProductRepository productRepository;
    private final ProductColorRepository productColorRepository;
    private final ImageService imageService;

    @Override
    public ProductSku process(ProductSkuCsvDto item) throws Exception {
        Product product = productRepository.findById(item.getProduct_id())
            .orElseThrow(() -> new IllegalArgumentException("Invalid product_id: " + item.getProduct_id()));
        ProductColor color = productColorRepository.findById(item.getColor_id())
            .orElseThrow(() -> new IllegalArgumentException("Invalid color_id: " + item.getColor_id()));

        // ⚠️ 업로드 실패를 삼키지 않는다. 예전에는 catch 에서 printStackTrace 후 null 을
        //    돌려주고 걸러 냈다. 그래서 자격 증명이 폐기된 상태에서도 잡이 **성공으로**
        //    끝났고, images 가 빈 SKU 가 그대로 저장됐다. 증상은 등록이 아니라 첫 화면에서
        //    나타난다 — 이미지가 없는 상품 카드는 깨지고, 예전 프론트는 거기서 TypeError 를
        //    내 홈 전체를 백지로 만들었다. 자세한 이유는 ImageService.uploadClasspathImage.
        List<String> imageUrls = new ArrayList<>();
        if (item.getImages() != null && !item.getImages().isEmpty()) {
            imageUrls = Arrays.stream(item.getImages().split(","))
                    .map(String::trim)
                    .filter(path -> !path.isEmpty())
                    .map(imageService::uploadClasspathImage)
                    .collect(Collectors.toList());
        }

        return ProductSku.builder()
            //.skuproductId(item.getSkuproduct_id())
            .sku(item.getSku())
            .images(imageUrls)
            // CSV DTO 는 Integer(널 가능)인데 엔티티는 primitive int 라, 빈 셀 하나가
            // 언박싱 NPE 를 일으켜 임포트 잡 전체를 죽였다. 엔티티 기본값과 동일하게 0 으로 채운다
            // (docs/14 B-15).
            .discount(item.getDiscount() != null ? item.getDiscount() : 0)
            .sold(item.getSold() != null ? item.getSold() : 0)
            .color(color)
            .product(product)
            .version(0L) // 초기 버전 설정
            .build();
    }
}
