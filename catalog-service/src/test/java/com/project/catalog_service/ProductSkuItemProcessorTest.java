package com.project.catalog_service;
import com.project.catalog_service.batch.processor.ProductSkuItemProcessor;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.project.catalog_service.dto.data.ProductSkuCsvDto;
import com.project.catalog_service.model.Product;
import com.project.catalog_service.model.ProductColor;
import com.project.catalog_service.repository.jpa.data.ProductColorRepository;
import com.project.catalog_service.repository.jpa.data.ProductRepository;
import com.project.catalog_service.service.ImageService;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductSkuItemProcessorTest {

    @InjectMocks
    private ProductSkuItemProcessor processor;

    @Mock
    private ProductRepository productRepository;
    @Mock
    private ProductColorRepository productColorRepository;
    @Mock
    private ImageService imageService;

    private ProductSkuCsvDto csvDto;
    private Product product;
    private ProductColor color;

    // Helper method to create Product instance for testing
    private Product createProduct(Long id) {
        Product p = null;
        try {
            var constructor = Product.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            p = constructor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create Product instance for test", e);
        }
        return p;
    }

    @BeforeEach
    void setUp() throws Exception {
        csvDto = new ProductSkuCsvDto();
        csvDto.setProduct_id(1L);
        csvDto.setColor_id(1L);
        csvDto.setImages("images\\food\\Image_2.jpg,images\\food\\Image_9.jpg");

        product = createProduct(1L);

        try {
            var colorConstructor = ProductColor.class.getDeclaredConstructor();
            colorConstructor.setAccessible(true);
            color = colorConstructor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create ProductColor instance for test", e);
        }
        color.setColorId(1L);

        // 공통 스텁이라 테스트마다 쓰이는 것이 다르다(예: 잘못된 product_id 케이스는
        // color 조회·이미지 업로드까지 가지 않는다). 엄격 모드에서는 이런 미사용 스텁이
        // UnnecessaryStubbingException 으로 테스트를 깨뜨리므로 이 세 건만 lenient 로 둔다.
        // 개별 테스트에서 추가하는 스텁에는 엄격 검사가 그대로 적용된다.
        lenient().when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        lenient().when(productColorRepository.findById(1L)).thenReturn(Optional.of(color));
        lenient().when(imageService.uploadClasspathImage(anyString()))
                .thenReturn("http://s3.url/uploaded-image.jpg");
    }

    @Test
    void process_should_upload_images_with_correct_paths() throws Exception {
        // When
        processor.process(csvDto);

        // Then — 역슬래시 정규화는 ImageService.uploadClasspathImage 안에서 한다.
        verify(imageService).uploadClasspathImage("images\\food\\Image_2.jpg");
        verify(imageService).uploadClasspathImage("images\\food\\Image_9.jpg");
    }

    /**
     * 업로드가 실패하면 <b>그대로 터져야 한다.</b>
     *
     * <p>예전에는 프로세서가 예외를 삼키고 null 을 걸러 냈다. 그래서 S3 자격 증명이
     * 폐기된 상태에서도 임포트 잡이 성공으로 끝났고, images 가 빈 SKU 가 DB 에 남았다.
     * 그 상품이 첫 화면에 올라오면 카드가 깨진다.
     */
    @Test
    void process_should_fail_when_image_upload_fails() {
        when(imageService.uploadClasspathImage(anyString()))
                .thenThrow(new ImageService.ImageUploadException("이미지 업로드 실패", null));

        assertThrows(ImageService.ImageUploadException.class, () -> processor.process(csvDto));
    }

    @Test
    void process_should_return_product_sku_with_image_urls() throws Exception {
        // When
        var result = processor.process(csvDto);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getImages()).hasSize(2);
        assertThat(result.getImages()).containsOnly("http://s3.url/uploaded-image.jpg");
    }

    @Test
    void process_should_handle_null_images_column() throws Exception {
        // Given
        csvDto.setImages(null);
        
        // When
        var result = processor.process(csvDto);

        // Then
        assertThat(result.getImages()).isNotNull();
        assertThat(result.getImages()).isEmpty();
    }

    @Test
    void process_should_throw_exception_for_invalid_product_id() {
        // Given
        csvDto.setProduct_id(99L);
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> processor.process(csvDto));
    }

    @Test
    void process_should_throw_exception_for_invalid_color_id() {
        // Given
        csvDto.setColor_id(99L);
        when(productColorRepository.findById(99L)).thenReturn(Optional.empty());

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> processor.process(csvDto));
    }
}