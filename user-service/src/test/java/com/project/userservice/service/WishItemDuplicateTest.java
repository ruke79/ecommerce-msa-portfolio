package com.project.userservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.project.common.constants.OperationStatus;
import com.project.common.dto.ProductDto;
import com.project.userservice.client.CatalogServiceClient;
import com.project.userservice.model.User;
import com.project.userservice.model.WishItem;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.repository.jpa.WishiItemRepository;
import com.project.userservice.security.request.WishListRequest;

/**
 * 🔴 같은 상품 · 스타일을 다시 찜하면 '이미 있음' — 상품 id 가 128 이상이어도.
 *
 * <p>🐛 예전 {@code x.getProductId() == id} 는 박싱된 Long 의 <b>참조</b> 비교라 -128~127 에서만 같았다.
 */
@DisplayName("찜 — 중복 판정")
class WishItemDuplicateTest {

    @Test
    @DisplayName("🔴 상품 id 1000 을 다시 찜하면 OS_ALREADY_EXISTED — 새 행을 만들지 않는다")
    void largeProductIdIsDetectedAsDuplicate() {
        UserRepository users = mock(UserRepository.class);
        WishiItemRepository wishes = mock(WishiItemRepository.class);
        CatalogServiceClient catalog = mock(CatalogServiceClient.class);
        User user = new User("alice", "a@example.com", "x");
        user.getWishLists().add(new WishItem("0", Long.valueOf(1000L)));
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        when(catalog.getProductInfo("1000")).thenReturn(ResponseEntity.ok(mock(ProductDto.class)));

        WishListRequest request = new WishListRequest();
        request.setId("1000");
        request.setStyle("0");

        OperationStatus status = new WishItemService(users, wishes, catalog).addWishList("alice", request);

        assertThat(status).isEqualTo(OperationStatus.OS_ALREADY_EXISTED);
        verify(wishes, never()).save(any());
    }
}
