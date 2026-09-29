package com.project.catalog_service.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.project.catalog_service.model.Category;
import com.project.catalog_service.model.Subcategory;
import com.project.catalog_service.repository.jpa.data.CategoryRepository;
import com.project.catalog_service.repository.jpa.data.SubcategoryRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductWarmUpService {

    private final ProductService productService;
    private final CategoryRepository categoryRepository;
    private final SubcategoryRepository subcategoryRepository;
    private final RedisTemplate<String, Object> redisTemplate; // RedisTemplate 주입 추가

    // 캐시 생성 여부를 확인하기 위한 고유한 플래그 키
    private static final String PRODUCT_CACHE_INITIALIZED_KEY = "product:cache:initialized";

    /**
     * 모든 카테고리/서브카테고리별 상품 목록을 순회하며 캐시를 미리 채웁니다 (Warm-up).
     * Redis에 플래그 키가 없으면 캐시를 생성하고, 있으면 건너뜁니다.
     */
    @PostConstruct
    @Transactional(readOnly = true)
    public void warmUpProductCachesOnStartup() {
        // 1. Redis에 캐시 초기화 플래그가 있는지 확인
        Boolean isCacheInitialized = redisTemplate.hasKey(PRODUCT_CACHE_INITIALIZED_KEY);
        
        if (Boolean.TRUE.equals(isCacheInitialized)) {
            log.info("Product cache already exists in Redis. Skipping warm-up.");
            return; // 캐시가 있으면 메서드 실행을 중단
        }

        log.info("Starting to warm up product caches...");

        // 2. 캐시가 없으므로 캐시 생성 로직 실행
        List<Category> allCategories = categoryRepository.findAll();
        for (Category category : allCategories) {
            List<Subcategory> allSubcategories = subcategoryRepository.findByCategory(category);
            for (Subcategory subcategory : allSubcategories) {
                productService.getProductsByCategoryAndSubcategory(
                    category.getCategoryName(), subcategory.getSubcategoryName(), 0, 20);
            }
        }
        
        // 3. 캐시 생성 완료 후, 다음 재시작을 위해 플래그 키 저장
        redisTemplate.opsForValue().set(PRODUCT_CACHE_INITIALIZED_KEY, "true");
        
        log.info("Product cache warm-up completed and flag set in Redis.");
    }

}