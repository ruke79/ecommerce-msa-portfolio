package com.project.cart_service.controller;

import com.project.common.exception.ClientMessage;

import java.util.List;


import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;


import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.project.common.dto.CartDto;
import com.project.common.dto.ProductInfoDto;
import com.project.common.dto.cart.request.CartRequest;

import com.project.common.dto.cart.response.CartResponse;
import com.project.common.dto.catalog.request.ProductInfoRequest;
import com.project.common.response.MessageResponse;
import com.project.cart_service.model.Cart;


import com.project.cart_service.service.CartService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("api/cart/")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    
    


    /**
     * 🔒 장바구니 저장. 예전에는 <b>본문의 {@code email}</b> 로 사용자를 찾았다 — 로그인만 하면 본문에 남의 이메일을
     * 넣어 <b>그 사람의 장바구니를 지우고 덮어쓸</b> 수 있었다(docs/38 §1.6). 신원은 게이트웨이가 검증한 토큰의 uid
     * ({@code User-Id} 헤더) 하나다. 본문의 email 은 읽지 않는다.
     */
    @PostMapping("/savecart")
    ResponseEntity<?> saveCart(@Valid @RequestBody CartRequest request,
            @RequestHeader(value = "User-Id", required = false) Long userId) {

                 if (userId == null) {
                     return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                             .body(new MessageResponse("로그인이 필요합니다."));
                 }

                 Cart cart;
                 try {
                     cart = cartService.saveCart(request, userId);
                 } catch (IllegalArgumentException e) {
                     // 수량이 범위 밖이다(CartService.checkedQty) — 고칠 수 있는 요청 오류다.
                     return ResponseEntity.badRequest().body(new MessageResponse(ClientMessage.of(e)));
                 }

                 if (null != cart ) {

                    CartResponse response = new CartResponse(Long.toString(cart.getCartId()), cart.getCartTotal());

                    return new ResponseEntity<>(response, HttpStatus.OK);
                }
                else {
                    return new ResponseEntity<>(null, HttpStatus.OK);
                }

            
    }
    /**
     * 🔒 예전에는 {@code ?userId=} 로 <b>남의 이메일을 넣으면 그 사람의 장바구니</b>가
     * 그대로 나왔다. 신원은 게이트웨이가 검증한 토큰에서만 온다 — {@code User-Id}(토큰의 uid),
     * 클라이언트가 보낸 동명 헤더는 게이트웨이가 지운다. 쿼리 파라미터는 무시한다.
     *
     * <p>예전에는 {@code User-Email} 을 받아 user-service 에 이메일 → id 를 물었다. 장바구니는 id 로 저장되고 토큰이
     * 이미 id 를 나르므로 그 왕복은 필요 없었다(docs/48 §6-①, order 와 같다).
     */
    @GetMapping("/loadcart")
    ResponseEntity<?> loadCart(@RequestHeader(value = "User-Id", required = false) Long userId) {

            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new MessageResponse("로그인이 필요합니다."));
            }

            try {
                List<ProductInfoDto> response = cartService.loadCart(userId);

                return new ResponseEntity<>(response, HttpStatus.OK);
            } 
            catch(RuntimeException e) {
                // 🐛 예전에는 200 + 빈 본문(null)이었다 — "장바구니 없음" 과 구별되지 않아, 서버 것으로 바꾸는 화면이 장바구니를
                // 조용히 비웠다. 실패는 실패로 알리고(화면은 가진 것을 그대로 둔다) 원인을 남긴다.
                log.warn("장바구니 읽기 실패 userId={}", userId, e);
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(new MessageResponse(ClientMessage.of(e)));
            }
        
    }

    // ── 서버가 장바구니의 기준 — 담기·수량·삭제 (docs/40 0-b 2단계) ─────────────────────────────
    // 예전에는 이 셋이 브라우저(redux)에만 있었고 서버는 마지막 체크아웃의 사본만 가졌다 — 다른 기기에서는 옛 장바구니, 체크아웃에서
    // 고르지 않은 줄은 서버에서 사라졌다. 응답은 언제나 지금 장바구니 전체(loadcart 와 같은 모양) — 화면은 그것을 그대로 쓴다.

    /** 담기 — 같은 줄(상품·색상·사이즈)이면 수량을 이 값으로. 본문은 catalog 의 담기 응답 + qty·_uid 다. */
    @PutMapping("/items")
    ResponseEntity<?> putItem(@RequestHeader(value = "User-Id", required = false) Long userId,
            @RequestBody ProductInfoDto item) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new MessageResponse("로그인이 필요합니다."));
        }
        try {
            return ResponseEntity.ok(cartService.putItem(userId, item));
        } catch (RuntimeException e) {
            // 원인을 남긴다 — 일반 문구("요청을 처리하지 못했습니다")만으로는 무엇이 실패했는지 알 수 없었다.
            log.warn("장바구니 putItem 실패 userId={}", userId, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new MessageResponse(ClientMessage.of(e)));
        }
    }

    /** 수량 — 0 이하면 뺀다. */
    @org.springframework.web.bind.annotation.PatchMapping("/items")
    ResponseEntity<?> setItemQty(@RequestHeader(value = "User-Id", required = false) Long userId,
            @RequestParam("productId") Long productId, @RequestParam("colorId") Long colorId,
            @RequestParam(value = "size", required = false, defaultValue = "") String size,
            @RequestParam("qty") int qty) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new MessageResponse("로그인이 필요합니다."));
        }
        try {
            return ResponseEntity.ok(cartService.setItemQty(userId, productId, colorId, size, qty));
        } catch (RuntimeException e) {
            // 원인을 남긴다 — 일반 문구("요청을 처리하지 못했습니다")만으로는 무엇이 실패했는지 알 수 없었다.
            log.warn("장바구니 setItemQty 실패 userId={}", userId, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new MessageResponse(ClientMessage.of(e)));
        }
    }

    /** 빼기. */
    @org.springframework.web.bind.annotation.DeleteMapping("/items")
    ResponseEntity<?> removeItem(@RequestHeader(value = "User-Id", required = false) Long userId,
            @RequestParam("productId") Long productId, @RequestParam("colorId") Long colorId,
            @RequestParam(value = "size", required = false, defaultValue = "") String size) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new MessageResponse("로그인이 필요합니다."));
        }
        try {
            return ResponseEntity.ok(cartService.removeItem(userId, productId, colorId, size));
        } catch (RuntimeException e) {
            // 원인을 남긴다 — 일반 문구("요청을 처리하지 못했습니다")만으로는 무엇이 실패했는지 알 수 없었다.
            log.warn("장바구니 removeItem 실패 userId={}", userId, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new MessageResponse(ClientMessage.of(e)));
        }
    }

    @PutMapping("/updatecart")
    ResponseEntity<?> updateCart(@RequestBody ProductInfoRequest products) {
        

        try {
            List<ProductInfoDto> result = cartService.updateCart(products);

            return new ResponseEntity<>(result, HttpStatus.OK);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(ClientMessage.of(e)));
        }

    }

    /**
     * 🔒 loadCart 와 같은 이유로 쿼리 파라미터 대신 검증된 헤더를 쓴다. order·coupon 이 서비스 간 호출로도 부른다 —
     * 그때도 {@code User-Id} 헤더에 그 사용자의 id 를 싣는다(각자의 CartServiceClient).
     */
    @GetMapping("/checkout")
    ResponseEntity<?> getCart(@RequestHeader(value = "User-Id", required = false) Long userId) {

            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new MessageResponse("로그인이 필요합니다."));
            }

            try {
                // 주문의 근거 — 고른 줄의 가격을 catalog 의 지금 값으로 맞춰 돌려준다(CartService.getCartForCheckout)
                CartDto response = cartService.getCartForCheckout(userId);

                return new ResponseEntity<>(response, HttpStatus.OK);
            } catch (RuntimeException e) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(new MessageResponse(ClientMessage.of(e)));
            }

    }
        
    /**
     * 장바구니 줄의 상품 id(order-service 가 옛 줄의 빈 상품 id 를 채울 때 부른다).
     *
     * <p>🐛 줄에 상품 id 가 없으면(옛 스키마의 줄) {@code Long.toString(null)} 에서 NPE — 전역 처리기가 500 대신 일반 문구를 냈고
     * 로그에만 "productId is null" 이 남았다. 없으면 404 다.
     */
    @GetMapping("/{cartProductId}") 
    ResponseEntity<String> getProductId(@PathVariable("cartProductId") String cartProdcutId) {
          Long productId;
          try {
              productId = cartService.getProductId(cartProdcutId);
          } catch (RuntimeException e) {
              return ResponseEntity.notFound().build();
          }
          return productId == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(Long.toString(productId));
    }

}
