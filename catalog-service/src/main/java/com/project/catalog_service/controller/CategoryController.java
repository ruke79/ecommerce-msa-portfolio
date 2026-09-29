package com.project.catalog_service.controller;

import com.project.common.exception.ClientMessage;
 
import org.hibernate.mapping.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.project.common.constants.StatusMessages;
import com.project.common.dto.CategoryDto;
import com.project.common.dto.ProductColorDto;
import com.project.common.dto.ProductDto;

import com.project.common.dto.SubCategoryDto;
import com.project.common.dto.catalog.request.CategoryRequest;
import com.project.common.dto.catalog.request.SubCategoryRequest;
import com.project.common.dto.catalog.response.CategoryResponse;
import com.project.common.dto.catalog.response.SubCategoryResponse;
import com.project.common.response.MessageResponse;
import com.project.catalog_service.model.Subcategory;
import com.project.catalog_service.repository.jpa.data.CategoryRepository;
import com.project.catalog_service.repository.jpa.data.SubcategoryRepository;
import com.project.catalog_service.model.Category;

import com.project.catalog_service.service.CategoryService;



//import jakarta.mail.Multipart;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.ObjectInputFilter.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/category")
// @PreAuthorize("hasRole('ROLE_ADMIN')")
@RequiredArgsConstructor
public class CategoryController {

    
    private final CategoryRepository categoryRepository;

    private final SubcategoryRepository subCategoryRepository;

    private final CategoryService categoryService;

     @PostMapping("/category")
    ResponseEntity<?> addCategory(@RequestBody CategoryRequest request) {

        try {

            boolean exist = categoryService.findCategory(request.getName());

            if (!exist) {

                // Category category = new Category();
                // category.setCategoryName(categoryRequest.getName());
                // category.setSlug(categoryRequest.getSlug());

                // categoryRepository.save(category);

                CategoryDto dto = categoryService.createCategory(request.getName(), request.getSlug());

                CategoryResponse response = new CategoryResponse(
                        dto.getId(),
                        dto.getName()
                // category.getSlug()
                );

                return new ResponseEntity<>(response, HttpStatus.OK);
            } else {
                return new ResponseEntity<>(StatusMessages.CATEGORY_IS_EXISTED, HttpStatus.BAD_REQUEST);
            }
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(ClientMessage.of(e)));
        }

    }

    @PostMapping("/subcategory")
    ResponseEntity<?> addSubCategory(@RequestBody SubCategoryRequest request) {

        try {
            boolean exist = categoryService.findSubCategory(request.getSubcategoryName(), request.getParent());

            if (!exist) {

                SubCategoryDto subcategory = categoryService.createSubCategory(request.getSubcategoryName(),
                        request.getParent(), request.getSlug());

                SubCategoryResponse response = new SubCategoryResponse(
                        subcategory.getId(),
                        subcategory.getName(), subcategory.getParent().getName());

                return new ResponseEntity<>(response, HttpStatus.OK);

            } else
                return new ResponseEntity<>(StatusMessages.SUBCATEGORY_IS_EXISTED, HttpStatus.BAD_REQUEST);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(ClientMessage.of(e)));
        }
    }

       
    @GetMapping("/categories")
    ResponseEntity<?> getCategories() {

        List<Category> category = categoryRepository.findAll();

        List<CategoryResponse> responses = new ArrayList<>();
        category.forEach(item -> {
            responses.add(new CategoryResponse(Long.toString(item.getCategoryId()), item.getCategoryName()));
        });

        return new ResponseEntity<>(responses, HttpStatus.OK);

    }

    /**
     * 전체 하위 카테고리 목록.
     *
     * <p><b>⚠️ 결과가 비었다고 400 을 돌려주지 말 것.</b> 예전에는 이랬다:
     *
     * <pre>
     *   if (!subcategories.isEmpty()) { ... 200 ... }
     *   else return new ResponseEntity&lt;&gt;(SUBCATEGORY_NOT_FOUND, HttpStatus.BAD_REQUEST);
     * </pre>
     *
     * <p>목록 조회에서 <b>0건은 오류가 아니다.</b> 요청은 완벽히 정상이었고 답이 빈 목록일
     * 뿐이다. 400 은 "당신의 요청이 잘못됐다" 는 뜻이라 클라이언트가 고칠 것이 없다.
     *
     * <p>그리고 이건 <b>갓 설치한 시스템을 잠가 버린다.</b> 관리자가 하위 카테고리를 만들려면
     * /admin/category 에 들어가야 하는데, 그 화면이 열리면서 이 API 를 부르고 400 을 받아
     * "Faild to load subcategories" 로 끝난다 — 하나도 없기 때문에 하나도 만들 수 없는
     * 닭과 달걀이 된다. 실제로 빈 DB 에서 그 상태가 됐다.
     *
     * <p>바로 옆 {@code /categories} 는 빈 검사가 없어 {@code 200 []} 를 준다. 그 비대칭이
     * "카테고리 토스트는 안 뜨는데 하위 카테고리 토스트만 뜬다" 로 나타났다.
     */
    @GetMapping("/product/allsubcategories")
    ResponseEntity<?> loadAllSubcategories() {

        try {

            List<Subcategory> subcategories = subCategoryRepository.findAll();

            ArrayList<SubCategoryResponse> subCategoryList = new ArrayList<>();

            subcategories.forEach(item -> {
                Category category = categoryRepository.findById(item.getCategory().getCategoryId())
                        .orElseThrow(() -> new RuntimeException("Category not found"));

                subCategoryList
                        .add(new SubCategoryResponse(Long.toString(item.getSubcategoryId()),
                                item.getSubcategoryName(), category.getCategoryName()));
            });

            // 0건이면 빈 배열. 화면은 "아직 없음" 을 그리면 된다.
            return new ResponseEntity<>(subCategoryList, HttpStatus.OK);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(ClientMessage.of(e)));
        }
    }

    @GetMapping("/product/subcategories")
    ResponseEntity<?> getSubcategories(@RequestParam("category") String category) {

        try {

            List<Subcategory> subcategories = categoryRepository
                    .findSubCategoriesByCategoryId(Long.parseLong(category));

            ArrayList<SubCategoryResponse> subCategoryList = new ArrayList<>();

            subcategories.forEach(item -> subCategoryList
                    .add(new SubCategoryResponse(Long.toString(item.getSubcategoryId()),
                            item.getSubcategoryName(), item.getCategory().getCategoryName())));

            // 위 메서드와 같은 이유로 0건도 200 이다. 하위 카테고리가 아직 없는 카테고리는
            // 정상이며, 그걸 400 으로 돌려주면 상품 등록 폼이 열리다 만다
            // (admin/Index.jsx 의 getSubs 는 catch 조차 없어 콘솔에 처리되지 않은 거절이 남았다).
            return new ResponseEntity<>(subCategoryList, HttpStatus.OK);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(ClientMessage.of(e)));
        }
    }

    
}
