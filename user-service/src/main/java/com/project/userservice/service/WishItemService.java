package com.project.userservice.service;

import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.project.common.constants.OperationStatus;
import com.project.common.dto.ProductDto;
import com.project.userservice.client.CatalogServiceClient;
import com.project.userservice.model.User;
import com.project.userservice.model.WishItem;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.repository.jpa.WishiItemRepository;
import com.project.userservice.security.request.WishListRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class WishItemService {

    private final UserRepository userRepository;
    private final WishiItemRepository wishItemRepository;
    private final CatalogServiceClient catalogServiceClient;

    public OperationStatus addWishList(String username, WishListRequest request) {

        Long id = Long.parseLong(request.getId());

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (null != user) {

            // Product product = productRepository.findById(id)
            //         .orElseThrow(() -> new RuntimeException("Product not found"));
            ResponseEntity<ProductDto> product = catalogServiceClient.getProductInfo(request.getId());

            if (null != product) {

                // for (WishList w : user.getWishLists()) {
                //     log.info(Long.toString(w.getProduct().getProductId()) +
                //             w.getStyle());
                // }

                Optional<WishItem> existed = user.getWishLists().stream()
                        // 🐛 예전 `==` — 박싱된 Long 끼리는 참조 비교라 -128~127 만 같았다. 상품 id 128 부터는 중복을 못 잡아 같은 찜이 계속 쌓였다.
                        // 🐛 style 이 NULL 인 옛 행이 있으면 x.getStyle().equals 에서 NPE → 400 이었다 — Objects.equals.
                        .filter(x -> id.equals(x.getProductId()) && java.util.Objects.equals(x.getStyle(), request.getStyle()))
                        .findFirst();

                if (existed.isPresent()) {

                    return OperationStatus.OS_ALREADY_EXISTED;

                } else {

                    WishItem wish = new WishItem(request.getStyle(), id);
                    
                    user.getWishLists().add(wish);

                    wish.setUser(user);

                    try {
                        wishItemRepository.saveAndFlush(wish);
                    } catch (org.springframework.dao.DataIntegrityViolationException e) {
                        // 동시에 온 두 번째 요청 — 위의 확인은 둘 다 "없음" 을 읽는다. DB 의 유니크(V18)가 막은 것이니 "이미 있음" 이다.
                        // [JA] 同時に来た二つ目のリクエスト — DB のユニーク(V18)が防いだので「既にある」。
                        return OperationStatus.OS_ALREADY_EXISTED;
                    }

                    return OperationStatus.OS_SUCCESS;
                }

            }
        }

        return OperationStatus.OS_FAILED;
    }

}
