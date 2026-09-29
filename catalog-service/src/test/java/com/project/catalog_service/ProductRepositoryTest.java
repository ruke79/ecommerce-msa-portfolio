package com.project.catalog_service;
import com.project.catalog_service.repository.jpa.data.CategoryRepository;
import com.project.catalog_service.repository.jpa.data.ProductDetailsRepository;
import com.project.catalog_service.repository.jpa.data.ProductRepository;
import com.project.catalog_service.repository.jpa.data.SubcategoryRepository;
import com.project.catalog_service.service.ProductService;
import com.project.catalog_service.service.SearchService;
import com.project.catalog_service.model.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class ProductRepositoryTest {

    @Autowired
    private SearchService searchService;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private SubcategoryRepository subcategoryRepository;
    @Autowired
    private ProductDetailsRepository productDetailRepository;

    // 테스트에 사용할 공통 엔티티 변수
    private Category category1, category2;
    private Subcategory subcategory1, subcategory2,subcategory3;
    private Product product1, product2;

    @BeforeEach
    void setUp() {
        // 테스트 데이터 정리
        productRepository.deleteAllInBatch();
        categoryRepository.deleteAllInBatch();
        subcategoryRepository.deleteAllInBatch();
        productDetailRepository.deleteAllInBatch();

        // 카테고리, 서브카테고리 저장
        category1 = categoryRepository.save(Category.builder().categoryName("fashion").slug("fashion").build());
        category2 = categoryRepository.save(Category.builder().categoryName("electronics").slug("electronics").build());
        subcategory1 = subcategoryRepository.save(Subcategory.builder().subcategoryName("men's clothing")
                .slug("men-clothing").category(category1).build());
        subcategory2 = subcategoryRepository
                .save(Subcategory.builder().subcategoryName("notebook").slug("laptop").category(category2).build());
        subcategory3 = subcategoryRepository
                .save(Subcategory.builder().subcategoryName("phones").slug("phones").category(category2).build());


        // 상품 데이터 생성 및 저장
        product1 = Product.builder()
                .name("Laptop Pro")
                .description("Powerful laptop for professionals.")
                .brand("BrandX")
                .slug("laptop-pro")
                .category(category2)
                .rating(4.5f)
                .build();

        product2 = Product.builder()
                .name("Smartphone Z")
                .description("Latest model smartphone.")
                .brand("BrandY")
                .slug("smartphone-z")
                .category(category2)
                .rating(3.8f)
                .build();

        // 상품에 서브카테고리 추가
        Set<ProductSubcategory> subcategories2 = new HashSet<>();
        subcategories2.add(ProductSubcategory.builder().product(product1).subcategory(subcategory2).build());
        Set<ProductSubcategory> subcategories3 = new HashSet<>();
        subcategories3.add(ProductSubcategory.builder().product(product2).subcategory(subcategory3).build());
        
        

        // 상품 저장
        product1 = productRepository.save(product1);
        product2 = productRepository.save(product2);

        // 상품 상세 정보 저장 (양방향 관계 설정)
        // ProductDetails를 Product의 details Set에 추가하고, ProductDetails에 Product 참조 설정
        ProductDetails detail1 = ProductDetails.builder().name("material").value("aluminum").product(product1).build();
        ProductDetails detail2 = ProductDetails.builder().name("style").value("sleek").product(product2).build();
        ProductDetails detail3 = ProductDetails.builder().name("gender").value("unisex").product(product2).build();

        // Product 엔티티의 details Set에 ProductDetails 추가
        product1.setDetails(new HashSet<>(Arrays.asList(detail1)));
        product2.setDetails(new HashSet<>(Arrays.asList(detail2, detail3)));

        // ProductDetails 저장
        productDetailRepository.saveAll(Arrays.asList(detail1, detail2, detail3));
    }

    // @Test
    // @DisplayName("상품 ID 목록으로 상품을 조회하는 테스트")
    // void findProductBySearchParams_상품ID_목록_검색_테스트() {
    //     // Given
    //     List<Long> productIds = new ArrayList<>();
    //     for (long i = 1; i <= 200; i++) {
    //         productIds.add(i);
    //     }

    //     float rating = 4.0f;

    //     // When
    //     List<Product> products = searchService.(
    //             null, null, null, null, null, null, rating, productIds);

    //     // Then
    //     assertThat(products).isNotNull();
    //     assertEquals(2, products.size());
    //     assertThat(products).extracting(Product::getProductId).containsExactlyInAnyOrder(product1.getProductId(),
    //             product2.getProductId());
    // }

    // @Test
    // @DisplayName("이름과 평점으로 상품을 조회하는 테스트")
    // void findProductBySearchParams_이름과_평점_검색_테스트() {
    //     // Given
    //     String name = "Laptop";
    //     Float rating = 4.0f;

    //     List<Long> productIds = new ArrayList<>();
    //     for (long i = 1; i <= 200; i++) {
    //         productIds.add(i);
    //     }

    //     // When
    //     List<Product> products = productRepository.findProductBySearchParams(
    //             name, null, null, null, null, null, rating, productIds);

    //     // Then
    //     assertThat(products).isNotNull();
    //     assertEquals(1, products.size());
    //     assertEquals(product1.getProductId(), products.get(0).getProductId());
    // }

    // @Test
    // @DisplayName("총 상품 개수를 조회하는 테스트")
    // void countProductsBySearchParams_총_개수_검색_테스트() {
    //     // Given
    //     String name = "Laptop";
    //     Long categoryId = category2.getCategoryId();

    //     float rating = 4.0f;

    //     List<Long> productIds = new ArrayList<>();
    //     for (long i = 1; i <= 200; i++) {
    //         productIds.add(i);
    //     }

    //     // When
    //     int count = productRepository.countProductsBySearchParams(
    //             name, categoryId, null, null, null, null, rating, productIds);

    //     // Then
    //     assertEquals(1, count);
    // }
}