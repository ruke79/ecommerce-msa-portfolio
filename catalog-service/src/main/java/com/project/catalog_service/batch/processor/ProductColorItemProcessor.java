    package com.project.catalog_service.batch.processor;

import com.project.catalog_service.dto.data.ProductColorCsvDto;
import com.project.catalog_service.model.ProductColor;
import com.project.catalog_service.service.ImageService;

import lombok.RequiredArgsConstructor;

import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductColorItemProcessor implements ItemProcessor<ProductColorCsvDto, ProductColor> {


    private final ImageService imageService;

    @Override
    public ProductColor process(ProductColorCsvDto item) throws Exception {
        
        // CSV 의 color_image 를 S3 로 올리고 URL 을 받는다. 실패는 삼키지 않는다 —
        // 예전에는 printStackTrace 후 null 을 그대로 저장해서, 자격 증명이 죽어도 잡이
        // 성공으로 끝나고 색상 견본이 전부 빈 상품이 남았다
        // (ImageService.uploadClasspathImage 주석 참조).
        // [JA] CSV の color_image を S3 に上げて URL を受け取る。失敗は飲み込まない —
        //      以前は printStackTrace の後 null をそのまま保存していたため、資格情報が死んでもジョブが
        //      成功で終わり、色見本がすべて空の商品が残った
        //      (ImageService.uploadClasspathImage のコメント参照)。
        String imageUrl = null;
        if (item.getColor_image() != null && !item.getColor_image().isBlank()) {
            imageUrl = imageService.uploadClasspathImage(item.getColor_image());
        }


        return ProductColor.builder()
        //.colorId(item.getColor_id())
                .color(item.getColor())
                .colorImage(imageUrl) // 업로드된 이미지 URL 설정 ／ [JA] アップロードされた画像 URL を設定
                .version(0L)
                .build();
    }
    
    
}