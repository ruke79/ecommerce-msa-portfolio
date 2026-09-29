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
                        .filter(x -> x.getProductId() == id && x.getStyle().equals(request.getStyle()))
                        .findFirst();

                if (existed.isPresent()) {

                    return OperationStatus.OS_ALREADY_EXISTED;

                } else {

                    WishItem wish = new WishItem(request.getStyle(), id);
                    
                    user.getWishLists().add(wish);

                    wish.setUser(user);

                    wishItemRepository.save(wish);

                    return OperationStatus.OS_SUCCESS;
                }

            }
        }

        return OperationStatus.OS_FAILED;
    }

}
