package com.project.catalog_service.dto.response;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.project.common.dto.ProductDto;
import com.project.catalog_service.model.Product;
import com.project.catalog_service.util.CursorPagenation;
import com.project.catalog_service.mapper.ProductMapper;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;


@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductResponse {

     private static final long LAST_CURSOR = -1L;

    private List<ProductDto> contents = new ArrayList<>();
    private long totalElements;
    private long nextCursor;

    private ProductResponse(List<ProductDto> contents, long totalElements, long nextCursor) {
        this.contents = contents;
        this.totalElements = totalElements;
        this.nextCursor = nextCursor;
    }

    public static ProductResponse of(CursorPagenation<Product> productCursor, long totalElements) {
        if (productCursor.isLastScroll()) {
            return ProductResponse.newLastScroll(productCursor.getCurrentScrollItems(),  totalElements);
        }
        return ProductResponse.newScrollHasNext(productCursor.getCurrentScrollItems(), totalElements, productCursor.getNextCursor().getProductId());
    }

    private static ProductResponse newLastScroll(List<Product> feedsScroll, long totalElements) {
        return newScrollHasNext(feedsScroll, totalElements, LAST_CURSOR);
    }

    private static ProductResponse newScrollHasNext(List<Product> feedsScroll, long totalElements, long nextCursor) {
        return new ProductResponse(getContents(feedsScroll), totalElements, nextCursor);
    }

    private static List<ProductDto> getContents(List<Product> products) {
        return products.stream()
                .map(ProductMapper::toDto)
                .collect(Collectors.toList());
    }

}
