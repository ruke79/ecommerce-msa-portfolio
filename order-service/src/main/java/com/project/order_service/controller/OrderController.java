package com.project.order_service.controller;

import com.project.common.exception.ClientMessage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;

import com.project.order_service.service.OrderDataService;

import org.springframework.beans.factory.annotation.Autowired;
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

import com.project.common.constants.PaymentProvider;
import com.project.common.constants.StatusMessages;
import com.project.common.domain.exception.DomainException;
import com.project.common.dto.order.OrderDto;
import com.project.common.dto.request.PaypalPaymentRequest;
import com.project.common.response.MessageResponse;
import com.project.common.security.InternalCallGuard;
import com.project.common.dto.order.request.OrderRequest;
import com.project.order_service.domain.exception.OrderDomainException;
import com.project.order_service.model.Order;
import com.project.order_service.payment.CheckoutSession;
import com.project.order_service.service.OrderService;
import com.project.order_service.service.PurchaseStatsService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("api/order/")
public class OrderController {

    @Autowired
    OrderService orderService;

    @Autowired
    PurchaseStatsService purchaseStatsService;

    /** 리뷰 작성 자격(받은 상품인가) — user-service 가 묻는다. */
    @Autowired
    com.project.order_service.repository.jpa.OrderProductRepository orderProductRepository;

    /** 배송 서비스가 묻는 주문의 배송 단면(수취인·주소·품목). */
    @Autowired
    com.project.order_service.service.OrderShippingInfoService orderShippingInfoService;

    /**
     * Stripe 경로. {@code stripe.enabled=false} 면 빈이 없으므로 <b>null 이다</b> —
     * 그때는 해당 엔드포인트가 503 을 돌려준다. required=false 로 둔 이유는 Stripe 를
     * 쓰지 않는 환경에서 order-service 가 기동조차 못 하는 일을 막기 위해서다.
     */
    @Autowired(required = false)
    com.project.order_service.service.StripePaymentService stripePaymentService;

    /** 포트원 경로. {@code portone.enabled=false} 면 null 이고, 해당 엔드포인트는 503 이다. */
    @Autowired(required = false)
    com.project.order_service.service.PortOnePaymentService portOnePaymentService;

    /**
     * 주문 생성.
     *
     * <h3>🐛 실패가 보이지 않았다</h3>
     *
     * <p>예전에는 이랬다:
     *
     * <pre>{@code
     * } catch (RuntimeException e) {
     *     return ResponseEntity.status(HttpStatus.BAD_REQUEST)
     *             .body(new MessageResponse(ClientMessage.of(e)));
     * }
     * }</pre>
     *
     * <p>세 가지가 한꺼번에 잘못돼 있었다.
     *
     * <ul>
     *   <li><b>로그가 없다.</b> 어떤 예외가 나도 조용히 400 으로 바뀐다. 실제로 사용자가
     *       400 을 받았을 때 order-service 로그에는 <b>그 시각의 줄이 한 줄도 없었다</b> —
     *       무엇이 실패했는지 알아낼 방법이 없다.</li>
     *   <li><b>서비스가 null 을 돌려줬다.</b> 장바구니가 비었을 때·고객이 없을 때 모두
     *       null 이라, 바로 아래 {@code newOrder.getOrderId()} 가 NPE 를 냈다. 그 NPE 가
     *       이 catch 에 걸려 <b>본문이 JVM 의 NPE 문구인 400</b> 이 됐다
     *       ({@code "Cannot invoke ... because \"newOrder\" is null"}). 사용자 화면에
     *       그대로 떴다 — 내부 구현이 새는 것이기도 하다. 이제 각 갈래가 이유를 달고
     *       {@link OrderDomainException} 을 던진다.</li>
     *   <li><b>전부 400 이었다.</b> cart-service 가 죽어도, 파싱이 깨져도 "네 요청이
     *       잘못됐다" 가 된다. 사용자는 고칠 것이 없는데 고치라는 말을 듣는다.</li>
     * </ul>
     *
     * <p>이제 <b>도메인 실패(사용자가 고칠 수 있음)</b>는 400 + 그 문구, <b>그 밖의 예외</b>는
     * 500 + 일반 문구 + 스택트레이스 로그로 나눈다. 예외 메시지를 그대로 내보내지 않는
     * 이유는 그것이 대개 내부 사정이기 때문이다.
     */
    @PostMapping("/create")
    // @Valid — 배송지 검증(OrderAddressDto). 틀리면 서비스에 닿기 전에 400(CustomRestExceptionHandler).
    // [JA] @Valid — 配送先の検証。誤りがあればサービスに届く前に 400。
    ResponseEntity<?> createOrder(@jakarta.validation.Valid @RequestBody OrderRequest request,
            @RequestHeader(value = "User-Id", required = false) Long userId) {

        // 🔒 주문자는 **게이트웨이가 검증한 토큰**에서만 온다.
        //
        //    예전에는 `request.getEmail()` — 즉 **요청 본문**을 그대로 썼다. 그 값이
        //    장바구니 조회·쿠폰 견적·주문자 조회에 모두 쓰이므로, 남의 이메일을 넣어
        //    보내면 **그 사람의 장바구니로 주문이 만들어지고 배송지는 내 것**이 된다.
        //    이 저장소의 다른 엔드포인트들이 쿼리 파라미터 email 을 버리고 헤더로 옮긴
        //    것과 같은 이유다(아래 getOrder 주석 참조).
        //    주문자 id 는 토큰의 uid(User-Id)다 — 이메일로 고객 사본을 찾지 않는다(docs/48 §6-①). 장바구니·쿠폰도
        //    같은 id 로 묻는다 — 이메일은 이 흐름 어디에도 필요 없다.
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("로그인이 필요합니다."));
        }

        try {
            Order newOrder = orderService.createOrder(request, userId);

            Map<String, String> data = new HashMap<>();
            data.put("orderId", Long.toString(newOrder.getOrderId()));

            return new ResponseEntity<>(data, HttpStatus.OK);

        } catch (DomainException e) {
            // 사용자가 고칠 수 있는 실패다. 문구는 이미 사용자를 향해 쓰여 있다.
            log.warn("주문 생성 거절: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(ClientMessage.of(e)));

        } catch (RuntimeException e) {
            // 여기까지 온 것은 우리 잘못이다. 스택트레이스를 남긴다 — 이게 없어서
            // 지난번 400 의 원인을 로그에서 찾을 수 없었다.
            log.error("주문 생성 실패(예상치 못한 오류)", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("주문을 생성하지 못했습니다. 잠시 후 다시 시도해 주세요."));
        }

    }

    /**
     * 주문 상세.
     *
     * <p>🔒 <b>예전에는 아무나 아무 주문을 볼 수 있었다.</b> 이 메서드는 {@code email} 을
     * 쿼리 파라미터로 받았는데, 그 값은 <b>아무 데도 쓰이지 않았고</b>
     * ({@code OrderDataService.getOrder} 는 인자로 받은 email 을 무시하고
     * {@code orderRepository.findById(orderId)} 만 한다) 소유권 검사도 없었다. 즉
     * {@code GET /api/order/1}, {@code /2}, {@code /3} ... 으로 주문 번호만 훑으면
     * <b>모든 고객의 주문 내역·배송 주소·전화번호·결제 금액</b>을 읽을 수 있었다.
     *
     * <p>이제 신원은 <b>게이트웨이가 검증한 토큰</b>에서만 온다({@code User-Id} 헤더 — 토큰의 uid).
     * 클라이언트가 보낸 값은 게이트웨이가 지우므로 사칭할 수 없다. 쿼리 파라미터 email 은
     * 더 이상 신뢰하지 않는다 — 하위 호환을 위해 받기만 하고 무시한다.
     */
    @GetMapping("/{orderId}")
    ResponseEntity<?> getOrder(@PathVariable("orderId") String orderId,
            @RequestHeader(value = "User-Id", required = false) Long userId) {

        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("로그인이 필요합니다."));
        }

        try {
            // 소유권 검사는 OrderDataService.getOrder 안에서 한다(엔티티의 customerId 를
            // 봐야 하는데 OrderDto 에는 소유자 필드가 없다). 남의 주문이면 null 이 온다.
            OrderDto response = orderService.getOrder(Long.parseLong(orderId), userId, "");

            if (response == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new MessageResponse("주문을 찾을 수 없습니다."));
            }

            return new ResponseEntity<>(response, HttpStatus.OK);
        } catch (NumberFormatException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse("잘못된 주문 번호입니다."));
        } catch (RuntimeException e) {
            // 예전에는 로그 없이 400 이었다 — 화면에는 400 만, 서버에는 아무것도 남지 않아 원인을 찾을 수 없었다.
            log.warn("주문 조회 실패: orderId={} ({})", orderId, e.toString());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(ClientMessage.of(e)));
        }

    }

    /**
     * 내 주문 목록.
     *
     * <p>🔒 예전에는 {@code ?email=} 로 <b>남의 이메일을 넣으면 그 사람의 주문 목록</b>이
     * 그대로 나왔다. 이메일만 알면 되므로 사실상 전체 주문 조회다.
     */
    @GetMapping("/orders")
    public ResponseEntity<?> getOrders(@RequestParam(value="filter", required = false) String filter,
            @RequestHeader(value = "User-Id", required = false) Long userId) {

        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("로그인이 필요합니다."));
        }

        List<OrderDto> response = orderService.getOrders(userId, filter);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /**
     * 주문 목록 — 커서 페이징(점진적 로딩).
     *
     * <p>🐛 위의 {@code /orders} 는 고객의 주문을 <b>전부</b> 돌려준다. 주문이 쌓이는 만큼
     * 응답이 커지고, 게다가 주문마다 DB 조회 + user-service Feign + catalog 호출이 다시
     * 일어나며(N+1), 상태로 거를 때는 조건에 맞지 않는 주문이 <b>null 로 목록에 섞여
     * 나온다.</b> 자세한 것은 {@code OrderDataService.getOrdersPage} 주석에 있다.
     *
     * <p>이쪽은 상태를 SQL 에서 거르고 커서로 끊어 준다. 화면은 스크롤할 때마다 다음
     * 묶음을 받는다.
     *
     * <p>{@code /orders} 는 지우지 않았다 — 다른 호출부가 있는지 확인되지 않았고, 화면을
     * 옮긴 뒤에 정리하는 편이 안전하다.
     *
     * @param cursor 마지막으로 받은 주문 id. 첫 페이지는 생략하거나 0.
     * @param filter 비어 있으면 상태로 거르지 않는다(화면의 "All Orders").
     */
    @GetMapping("/orders/page")
    public ResponseEntity<?> getOrdersPage(
            @RequestParam(value = "filter", required = false, defaultValue = "") String filter,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "size", required = false, defaultValue = "10") int size,
            @RequestHeader(value = "User-Id", required = false) Long userId) {

        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("로그인이 필요합니다."));
        }

        try {
            Page<OrderDto> page = orderService.getOrdersPage(userId, filter, cursor, size);
            List<OrderDto> contents = page.getContent();

            // 다음 커서는 이번 묶음의 마지막 주문 id 다. 덜 받았으면 끝이다.
            Long nextCursor = null;
            if (!contents.isEmpty() && contents.size() >= Math.min(size, OrderDataService.MAX_ORDER_PAGE_SIZE)) {
                nextCursor = Long.parseLong(contents.get(contents.size() - 1).getId());
            }

            return ResponseEntity.ok(Map.of(
                    "contents", contents,
                    // Map.of 는 null 을 담지 못한다. 없음은 -1 로 나타낸다(카탈로그 쪽과 같은 규칙).
                    "nextCursor", nextCursor == null ? -1L : nextCursor));

        } catch (IllegalStateException e) {
            // OrderStatus.getStatus 가 모르는 상태 문자열에 던진다. 잘못된 요청이지
            // 서버 오류가 아니다.
            return ResponseEntity.badRequest()
                    .body(new MessageResponse("알 수 없는 주문 상태입니다: " + filter));
        }
    }

    /**
     * 결제 <b>취소</b> — PayPal 창을 승인 없이 닫은 경우.
     *
     * <h3>🐛 취소가 결제와 같은 문으로 들어왔다</h3>
     *
     * <p>프론트의 {@code onApproveHandler} 와 {@code onCancelHandler} 가 <b>둘 다</b>
     * {@code /payment/paypal} 을 불렀고, 구분은 본문의 {@code paymentStatus: "CANCELED"}
     * 한 필드였다. 그런데 서버는 그 필드를 <b>일부러 읽지 않는다</b> — 브라우저가 결제
     * 상태를 정하게 두는 것이 docs/35 결함의 출발점이었다.
     *
     * <p>그래서 취소도 결제로 취급돼 캡처 검증에 걸렸다. 승인조차 안 한 주문은 PayPal
     * 에서 {@code CREATED} 이므로 결과는 언제나 400 이었다:
     *
     * <pre>
     * {"message":"Payment transaction failed: PayPal 결제가 완료되지 않았습니다:
     *   status=CREATED, paypalOrderId=..."}
     * </pre>
     *
     * <p><b>두 수정이 부딪힌 자리다.</b> 둘 다 각자는 옳았고, 같은 엔드포인트에 있었기
     * 때문에 한쪽을 고치자 다른 쪽이 조용히 깨졌다. 연산이 다르면 문도 달라야 한다.
     */
    @PostMapping("/payment/paypal/cancel")
    public ResponseEntity<?> cancelPaypalPayment(@RequestBody PaypalPaymentRequest request,
            @RequestHeader(value = "User-Id", required = false) Long userId) {

        // 🔒 요청자는 게이트웨이가 검증한 헤더에서만 온다. 본문의 userId 은 믿지 않는다 —
        //    취소는 남의 주문 상태를 바꿀 수 있는 연산이다.
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("로그인이 필요합니다."));
        }

        try {
            orderService.cancelPaypalPayment(request, userId);
            return new ResponseEntity<>(new MessageResponse("order canceled"), HttpStatus.OK);

        } catch (RuntimeException e) {
            log.warn("주문 취소 거절: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(ClientMessage.of(e)));
        }
    }

    /**
     * 환불.
     *
     * <p>본문은 어느 주문·왜 뿐이다. 요청자는 {@code User-Id}, 관리자 여부는
     * {@code User-Role} — 둘 다 게이트웨이가 토큰에서 뽑아 넣고 클라이언트가 보낸 사본은
     * 지운다. 관리자면 남의 주문도, 발송된 주문도 환불할 수 있다.
     *
     * <p>역할 판정은 {@link InternalCallGuard#isAdmin} 하나를 쓴다 — ADMIN 과 ROLE_ADMIN
     * 을 둘 다 받는 규칙이 한 곳에 있어야 한다.
     */
    /**
     * 관리자 주문 목록 — 환불 화면용.
     *
     * <p>🔒 <b>관리자만.</b> 소유권 검사가 없는 유일한 목록이라 역할 판정이 곧 접근 통제다.
     * {@code User-Role} 은 게이트웨이가 토큰에서 뽑아 넣고 클라이언트 사본은 지운다.
     * 403 이 아니라 404 를 돌려주지 않는 이유: 이 경로가 존재한다는 사실은 비밀이 아니다.
     */
    @GetMapping("/admin/orders")
    public ResponseEntity<?> adminOrders(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "trackingId", required = false) String trackingId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestHeader(value = "User-Role", required = false) String userRole) {

        if (!InternalCallGuard.isAdmin(userRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new MessageResponse("관리자만 볼 수 있습니다."));
        }
        try {
            return ResponseEntity.ok(orderService.listOrdersForAdmin(status, trackingId, page, size));
        } catch (IllegalStateException e) {
            // OrderStatus.getStatus — 모르는 상태 이름.
            return ResponseEntity.badRequest().body(new MessageResponse("알 수 없는 주문 상태입니다: " + status));
        }
    }

    @PostMapping("/refund")
    public ResponseEntity<?> refund(@RequestBody com.project.common.dto.request.RefundRequest request,
            @RequestHeader(value = "User-Id", required = false) Long userId,
            @RequestHeader(value = "User-Role", required = false) String userRole) {

        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("로그인이 필요합니다."));
        }
        if (request == null || request.getTrackingId() == null || request.getTrackingId().isBlank()) {
            return ResponseEntity.badRequest().body(new MessageResponse("trackingId 가 필요합니다."));
        }

        boolean admin = InternalCallGuard.isAdmin(userRole);
        try {
            Order refunded = orderService.refundOrder(request.getTrackingId(), userId, admin,
                    request.getReason(), request.getItems());
            Map<String, Object> body = new HashMap<>();
            body.put("message", refunded.getOrderStatus() == com.project.common.constants.OrderStatus.REFUNDED
                    ? "환불이 처리되었습니다." : "선택한 품목이 환불되었습니다.");
            body.put("orderStatus", refunded.getOrderStatus());
            body.put("refundId", refunded.getRefundId());
            body.put("refundedAmount", refunded.getRefundedAmount());
            return ResponseEntity.ok(body);

        } catch (DomainException e) {
            // 규칙에 걸린 거절(결제 전 · 배송 후 · 남의 주문). 예상된 경로라 warn 이다.
            log.warn("환불 거절: {}", e.getMessage());
            return ResponseEntity.badRequest().body(new MessageResponse(ClientMessage.of(e)));
        } catch (RuntimeException e) {
            // 제공자 호출 실패 등. 원인이 로그에 남아야 한다(docs/47).
            log.error("환불 처리 실패: trackingId={}", request.getTrackingId(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("환불을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."));
        }
    }

    @Autowired
    private com.project.order_service.service.OrderPurchaseConfirmationService purchaseConfirmationService;

    /**
     * 구매 확정 — {@code DELIVERED → COMPLETED} (docs/25 §12).
     *
     * <p>본문이 없다. 어느 주문인지는 경로, 누가인지는 게이트웨이가 토큰에서 뽑아 넣은
     * {@code User-Id} 뿐이다. 남의 주문이면 404 — 403 은 "그 주문이 존재한다" 를 알려
     * 준다({@code GET /{orderId}} 와 같은 규칙). 이미 확정된 주문이면 200 으로 그대로
     * 돌려준다(멱등).
     */
    @PostMapping("/{orderId}/confirm")
    public ResponseEntity<?> confirmPurchase(@PathVariable("orderId") String orderId,
            @RequestHeader(value = "User-Id", required = false) Long userId) {

        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("로그인이 필요합니다."));
        }
        long id;
        try {
            id = Long.parseLong(orderId);
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(new MessageResponse("잘못된 주문 번호입니다."));
        }
        try {
            Order completed = purchaseConfirmationService.confirmByCustomer(id, userId);
            Map<String, Object> body = new HashMap<>();
            body.put("message", "구매가 확정되었습니다.");
            body.put("orderStatus", completed.getOrderStatus());
            body.put("completedAt", completed.getCompletedAt());
            body.put("autoCompleted", completed.isAutoCompleted());
            return ResponseEntity.ok(body);
        } catch (com.project.order_service.domain.exception.OrderNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MessageResponse(ClientMessage.of(e)));
        } catch (DomainException e) {
            log.warn("구매 확정 거절: orderId={} {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(new MessageResponse(ClientMessage.of(e)));
        }
    }

    /**
     * 반품 입고 (관리자).
     *
     * <p>🔒 관리자만 — 재고를 늘리는 경로다. 본문은 어느 주문·재고로 되돌릴지뿐이고, 수량은
     * DB 의 주문 품목 그대로다.
     */
    @PostMapping("/admin/return-received")
    public ResponseEntity<?> returnReceived(
            @RequestBody com.project.common.dto.request.ReturnReceivedRequest request,
            @RequestHeader(value = "User-Role", required = false) String userRole) {

        if (!InternalCallGuard.isAdmin(userRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new MessageResponse("관리자만 처리할 수 있습니다."));
        }
        if (request == null || request.getTrackingId() == null || request.getTrackingId().isBlank()) {
            return ResponseEntity.badRequest().body(new MessageResponse("trackingId 가 필요합니다."));
        }
        try {
            Order received = orderService.receiveReturn(request.getTrackingId(), request.restockOrDefault(),
                    request.getItems());
            Map<String, Object> body = new HashMap<>();
            body.put("message", request.restockOrDefault() ? "반품을 입고하고 재고를 복원했습니다." : "반품을 입고했습니다(재고 복원 없음).");
            body.put("orderStatus", received.getOrderStatus());
            body.put("returnReceivedAt", received.getReturnReceivedAt());
            return ResponseEntity.ok(body);
        } catch (DomainException e) {
            log.warn("반품 입고 거절: {}", e.getMessage());
            return ResponseEntity.badRequest().body(new MessageResponse(ClientMessage.of(e)));
        } catch (RuntimeException e) {
            log.error("반품 입고 처리 실패: trackingId={}", request.getTrackingId(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("반품 입고를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."));
        }
    }

    /**
     * 결제창을 열기 직전 — 재고 예약을 새로 잡는다(docs/59 §8). 모자라면 400 과 이유("재고가 부족해 결제를 시작하지 못했습니다…"),
     * 잡았거나 예약할 수 없는 상황(fail-open)이면 200. 🔒 주문자만(아니면 404).
     */
    @PostMapping("/payment/stock-hold/{trackingId}")
    public ResponseEntity<?> renewStockHold(@PathVariable("trackingId") String trackingId,
            @RequestHeader(value = "User-Id", required = false) Long userId) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new MessageResponse("로그인이 필요합니다."));
        }
        try {
            Map<String, Object> body = orderService.renewCheckoutHold(trackingId, userId);
            if (body == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MessageResponse("주문을 찾을 수 없습니다."));
            }
            return ResponseEntity.ok(body);
        } catch (DomainException e) {
            return ResponseEntity.badRequest().body(new MessageResponse(ClientMessage.of(e)));
        }
    }

    // ── 무통장입금 (docs/49 §11) ────────────────────────────────────────────

    @Autowired
    private com.project.order_service.service.BankTransferService bankTransferService;

    /** 입금 안내 받기 — NOT_PROCESSED → UNPAID. 응답이 계좌·금액·기한이다. */
    @PostMapping("/payment/bank-transfer/start")
    public ResponseEntity<?> startBankTransfer(
            @RequestBody com.project.common.dto.request.BankTransferRequest request,
            @RequestHeader(value = "User-Id", required = false) Long userId) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new MessageResponse("로그인이 필요합니다."));
        }
        if (request == null || request.getTrackingId() == null || request.getTrackingId().isBlank()) {
            return ResponseEntity.badRequest().body(new MessageResponse("trackingId 가 필요합니다."));
        }
        try {
            return ResponseEntity.ok(bankTransferService.start(request.getTrackingId(), userId, request.getDepositorName()));
        } catch (DomainException e) {
            log.warn("입금 안내 거절: {}", e.getMessage());
            return ResponseEntity.badRequest().body(new MessageResponse(ClientMessage.of(e)));
        } catch (RuntimeException e) {
            log.error("입금 안내 실패: trackingId={}", request.getTrackingId(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("입금 안내를 만들지 못했습니다. 잠시 후 다시 시도해 주세요."));
        }
    }

    /** 입금 안내 다시 보기 — 입금 대기 주문의 계좌·금액·기한. 🔒 주문자만. */
    @GetMapping("/payment/bank-transfer/{trackingId}")
    public ResponseEntity<?> bankTransferNotice(@PathVariable("trackingId") String trackingId,
            @RequestHeader(value = "User-Id", required = false) Long userId) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new MessageResponse("로그인이 필요합니다."));
        }
        Map<String, Object> notice = bankTransferService.notice(trackingId, userId);
        if (notice == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MessageResponse("주문을 찾을 수 없습니다."));
        }
        return ResponseEntity.ok(notice);
    }

    /** 입금 전 취소 — 고객 스스로, 또는 관리자. 돈이 오지 않았으니 환불이 아니라 취소다. */
    @PostMapping("/payment/bank-transfer/cancel")
    public ResponseEntity<?> cancelBankTransfer(
            @RequestBody com.project.common.dto.request.BankTransferRequest request,
            @RequestHeader(value = "User-Id", required = false) Long userId,
            @RequestHeader(value = "User-Role", required = false) String userRole) {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new MessageResponse("로그인이 필요합니다."));
        }
        if (request == null || request.getTrackingId() == null || request.getTrackingId().isBlank()) {
            return ResponseEntity.badRequest().body(new MessageResponse("trackingId 가 필요합니다."));
        }
        try {
            Order canceled = bankTransferService.cancel(request.getTrackingId(), userId, InternalCallGuard.isAdmin(userRole));
            Map<String, Object> body = new HashMap<>();
            body.put("message", "주문이 취소되었습니다.");
            body.put("orderStatus", canceled.getOrderStatus());
            return ResponseEntity.ok(body);
        } catch (DomainException e) {
            log.warn("입금 전 취소 거절: {}", e.getMessage());
            return ResponseEntity.badRequest().body(new MessageResponse(ClientMessage.of(e)));
        }
    }

    /**
     * 🔒 입금 확인 — 관리자만. 카드 결제 확인과 같은 무게다: 재고가 나가고 매출이 잡힌다.
     * 금액은 받지 않는다 — 청구액과 다르게 왔으면 확인하지 말고 고객에게 연락한다.
     */
    @PostMapping("/admin/deposit-confirmed")
    public ResponseEntity<?> depositConfirmed(
            @RequestBody com.project.common.dto.request.BankTransferRequest request,
            @RequestHeader(value = "User-Role", required = false) String userRole) {
        if (!InternalCallGuard.isAdmin(userRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new MessageResponse("관리자만 처리할 수 있습니다."));
        }
        if (request == null || request.getTrackingId() == null || request.getTrackingId().isBlank()) {
            return ResponseEntity.badRequest().body(new MessageResponse("trackingId 가 필요합니다."));
        }
        try {
            Order confirmed = bankTransferService.confirmDeposit(request.getTrackingId(), request.getDepositReference());
            Map<String, Object> body = new HashMap<>();
            body.put("message", "입금을 확인했습니다. 결제 반영은 잠시 뒤 완료됩니다.");
            body.put("orderStatus", confirmed.getOrderStatus());
            body.put("providerPaymentId", confirmed.getProviderPaymentId());
            return ResponseEntity.ok(body);
        } catch (DomainException e) {
            log.warn("입금 확인 거절: {}", e.getMessage());
            return ResponseEntity.badRequest().body(new MessageResponse(ClientMessage.of(e)));
        } catch (RuntimeException e) {
            log.error("입금 확인 실패: trackingId={}", request.getTrackingId(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("입금 확인을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."));
        }
    }

    @PostMapping("/payment/paypal")
    public ResponseEntity<?> persistPaypalPayment(@RequestBody PaypalPaymentRequest request,
            @RequestHeader(value = "User-Id", required = false) Long userId) {

        // 🔒 요청자는 게이트웨이가 검증한 헤더에서만 온다. 예전에는 본문의 userId 을
        //    그대로 썼다 — 남의 주문에 결제를 붙일 수 있었다.
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("로그인이 필요합니다."));
        }

        try {
            orderService.recordPayment(request.getTrackingId(), PaymentProvider.PAYPAL,
                    request.getPaypalOrderId(), userId);

            return new ResponseEntity<>(new MessageResponse("payment completed"), HttpStatus.OK);

        } catch (RuntimeException e) {
            log.warn("PayPal 결제 반영 실패: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(StatusMessages.PAYMENT_TRANSACTION_FAILED + ": " + ClientMessage.of(e)));

        }

    }

    /**
     * Stripe 결제 페이지를 연다.
     *
     * <p>브라우저는 응답의 {@code url} 로 이동하기만 한다. <b>금액은 서버가 정한다</b> —
     * 요청 본문에는 어느 주문인지 말고는 아무것도 없다.
     */
    @PostMapping("/payment/stripe/session")
    public ResponseEntity<?> createStripeSession(@RequestBody PaypalPaymentRequest request,
            @RequestHeader(value = "User-Id", required = false) Long userId) {

        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("로그인이 필요합니다."));
        }
        if (stripePaymentService == null) {
            // stripe.enabled=false 인 환경이다. 500 이 아니라 "지원하지 않는다" 가 맞다.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new MessageResponse("Stripe 결제가 설정되지 않았습니다."));
        }

        try {
            CheckoutSession session =
                    stripePaymentService.startCheckout(request.getTrackingId(), userId);

            Map<String, String> data = new HashMap<>();
            data.put("url", session.redirectUrl());
            data.put("sessionId", session.sessionId());
            return new ResponseEntity<>(data, HttpStatus.OK);

        } catch (DomainException e) {
            log.warn("Stripe 세션 생성 거절: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(ClientMessage.of(e)));
        } catch (RuntimeException e) {
            log.error("Stripe 세션 생성 실패(예상치 못한 오류)", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("결제를 시작하지 못했습니다. 잠시 후 다시 시도해 주세요."));
        }
    }

    /**
     * Stripe 결제 후 브라우저가 돌아왔다.
     *
     * <p>브라우저가 보고하는 것은 세션 id 하나뿐이고 그마저 믿지 않는다 — 서버가 그 세션을
     * Stripe 에 조회해 금액까지 대조한다.
     *
     * <p>웹훅과 <b>겹쳐서 도착하는 것이 정상</b>이다. 반영은 멱등하다.
     */
    @PostMapping("/payment/stripe/confirm")
    public ResponseEntity<?> confirmStripePayment(@RequestBody PaypalPaymentRequest request,
            @RequestHeader(value = "User-Id", required = false) Long userId) {

        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("로그인이 필요합니다."));
        }
        if (stripePaymentService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new MessageResponse("Stripe 결제가 설정되지 않았습니다."));
        }

        try {
            stripePaymentService.confirmReturn(request.getTrackingId(),
                    request.getProviderPaymentId(), userId);
            return new ResponseEntity<>(new MessageResponse("payment completed"), HttpStatus.OK);

        } catch (RuntimeException e) {
            log.warn("Stripe 결제 반영 실패: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(StatusMessages.PAYMENT_TRANSACTION_FAILED + ": " + ClientMessage.of(e)));
        }
    }

    /**
     * 포트원 결제 확인 — 브라우저의 결제창이 닫히고 콜백이 왔다.
     *
     * <p>포트원은 <b>브라우저가 결제창을 연다</b>(Stripe Checkout 과 달리 서버가 세션을
     * 만들지 않는다). 그래서 시작 엔드포인트가 없고 확인만 있다 — PayPal 과 같은 모양이다.
     */
    @PostMapping("/payment/portone/confirm")
    public ResponseEntity<?> confirmPortOnePayment(@RequestBody PaypalPaymentRequest request,
            @RequestHeader(value = "User-Id", required = false) Long userId) {

        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("로그인이 필요합니다."));
        }
        if (portOnePaymentService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new MessageResponse("포트원 결제가 설정되지 않았습니다."));
        }

        try {
            var outcome = portOnePaymentService.confirmReturn(request.getTrackingId(),
                    request.getProviderPaymentId(), userId);
            Map<String, Object> body = new HashMap<>();
            if (outcome == com.project.order_service.service.PortOnePaymentService.Outcome.VIRTUAL_ACCOUNT_ISSUED) {
                body.put("message", "가상계좌가 발급되었습니다. 기한 안에 입금해 주세요.");
                body.put("orderStatus", "UNPAID");
            } else {
                body.put("message", "payment completed");
                body.put("orderStatus", "PAID");
            }
            return ResponseEntity.ok(body);

        } catch (RuntimeException e) {
            log.warn("포트원 결제 반영 실패: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(StatusMessages.PAYMENT_TRANSACTION_FAILED + ": " + ClientMessage.of(e)));
        }
    }

    /**
     * 포트원 웹훅 — <b>인증 없이 열린 경로</b>.
     *
     * <h3>⚠️ 포트원 V1 웹훅에는 서명이 없다</h3>
     *
     * <p>Stripe 와 달리 발신자를 증명할 서명이 없다. 그래서 <b>본문을 단서로만 쓴다</b> —
     * {@code imp_uid} 하나만 취하고 금액·상태·주문 참조는 전부 버린 뒤 포트원 API 에 다시
     * 물어본다. 자세한 근거는 {@code PortOnePaymentService} 주석에 있다.
     *
     * <p>가상계좌처럼 <b>결제가 며칠 뒤에 완료되는</b> 수단에서는 이 경로가 유일하다 —
     * 복귀 콜백이 아예 없기 때문이다.
     */
    /**
     * 포트원 웹훅. V2 는 Standard Webhooks 헤더 셋으로 서명을 보낸다 — V1 은 없다.
     * 헤더가 없어도 받아들이고, 서명 검증 여부는 서비스가 버전에 따라 정한다.
     */
    @PostMapping("/payment/portone/webhook")
    public ResponseEntity<?> portOneWebhook(@RequestBody String payload,
            @RequestHeader(value = "webhook-id", required = false) String webhookId,
            @RequestHeader(value = "webhook-timestamp", required = false) String webhookTimestamp,
            @RequestHeader(value = "webhook-signature", required = false) String webhookSignature) {

        if (portOnePaymentService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        try {
            portOnePaymentService.handleWebhook(payload, webhookId, webhookTimestamp, webhookSignature);
            return ResponseEntity.ok().build();

        } catch (DomainException e) {
            // 다시 보내도 같은 결과일 것들이다 — 재시도를 유도하지 않는다.
            log.warn("포트원 웹훅 거절: {}", e.getMessage());
            return ResponseEntity.badRequest().build();

        } catch (RuntimeException e) {
            log.error("포트원 웹훅 처리 실패(예상치 못한 오류)", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Stripe 웹훅 — <b>인증 없이 열린 경로</b>.
     *
     * <h3>🔒 신원은 서명이 증명한다</h3>
     *
     * <p>Stripe 는 우리 토큰을 가질 수 없으므로 이 경로에 인증을 걸 수 없다. 대신
     * {@code Stripe-Signature} 헤더를 <b>웹훅 비밀키로 검증</b>한다 — 그것만이 "정말
     * Stripe 가 보냈는가" 의 답이다. 검증을 건너뛰면 이 URL 에 JSON 을 POST 하는 것만으로
     * 결제가 조작된다.
     *
     * <p>⚠️ 본문을 {@code String} 으로 받는 것은 <b>서명 때문</b>이다. JSON 으로 파싱했다
     * 다시 직렬화하면 공백·키 순서가 달라져 서명이 깨진다.
     *
     * <p>실패해도 상세를 응답에 담지 않는다 — 인증 없는 경로라 오류 문구 자체가 탐색
     * 단서가 된다. 원인은 로그에 남긴다.
     */
    @PostMapping("/payment/stripe/webhook")
    public ResponseEntity<?> stripeWebhook(@RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature) {

        if (stripePaymentService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        try {
            stripePaymentService.handleWebhook(payload, signature);
            // 200 을 돌려줘야 Stripe 가 재시도를 멈춘다.
            return ResponseEntity.ok().build();

        } catch (DomainException e) {
            // 서명 불일치·알 수 없는 주문 등. 400 이면 Stripe 가 재시도하지 않는다 —
            // 다시 보내도 같은 결과일 것들이다.
            log.warn("Stripe 웹훅 거절: {}", e.getMessage());
            return ResponseEntity.badRequest().build();

        } catch (RuntimeException e) {
            // 우리 쪽 일시적 실패다. 500 을 주면 Stripe 가 재시도한다 — 그게 맞다.
            log.error("Stripe 웹훅 처리 실패(예상치 못한 오류)", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 고객별 구매 집계 — 등급 배치가 부른다.
     *
     * <p>🔒 게이트웨이를 지난 요청에는 신원 헤더가 붙는다 — <b>헤더가 있다 = 외부에서 온
     * 요청</b>이고, 그때는 관리자만 허용한다. 서비스 간 Feign 호출은 게이트웨이를 거치지
     * 않아 헤더가 없다. 판정은 {@link InternalCallGuard} 가 한다.
     *
     * <p>⚠️ {@code User-Role} <b>하나만</b> 보면 안 된다. 게이트웨이는 토큰에서 뽑은 값이
     * 있을 때만 그 헤더를 붙이므로, role 클레임이 없는 토큰은 헤더 없이 도착해
     * "내부 호출" 로 통과한다. 세 헤더 중 하나라도 있으면 외부로 본다.
     *
     * <p>이 규칙이 없으면 로그인한 아무나 <b>전 고객의 결제 금액</b>을 페이지 단위로 긁어갈
     * 수 있다. 개별 주문 조회에는 소유권 검사가 있지만 집계에는 "내 것" 이라는 개념이 없어서,
     * 여기서 막지 않으면 막을 곳이 없다.
     *
     * <p>커서 방식이다. 응답의 마지막 {@code customerId} 를 다음 호출의 {@code afterCustomerId}
     * 로 넘긴다. <b>끝은 빈 응답으로만 판정한다</b> — 여기에는 응답 하나의 크기 상한
     * ({@code PurchaseStatsService.MAX_BATCH})이 있어서, 그보다 큰 {@code size} 를 요청하면
     * 말없이 잘라서 돌려준다. 덜 찬 응답을 끝으로 읽으면 아직 보지 않은 고객이 통째로
     * 누락된다.
     */
    @GetMapping("/internal/purchase-stats")
    ResponseEntity<?> purchaseStats(
            @RequestParam(value = "months", required = false) Integer months,
            @RequestParam(value = "afterCustomerId", required = false) Long afterCustomerId,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestHeader(value = "User-Role", required = false) String callerRole,
            @RequestHeader(value = "User-Email", required = false) String callerEmail,
            @RequestHeader(value = "User-Id", required = false) String callerId) {

        // 헤더 하나만 보면 role 클레임이 없는 토큰이 "내부 호출" 로 통과한다
        // (InternalCallGuard 주석 참조).
        if (!InternalCallGuard.allowed(callerRole, callerEmail, callerId)) {
            log.warn("외부에서 구매 집계 조회 시도를 차단");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new MessageResponse("접근할 수 없습니다."));
        }

        return ResponseEntity.ok(Map.of("stats", purchaseStatsService.recentStats(months, afterCustomerId, size)));
    }

    /**
     * 배송 서비스가 배송을 만들 때 읽는 주문의 단면 — 수취인·주소·품목.
     *
     * <p>결제 완료 이벤트에는 주소가 없다. 예전 delivery-service 는 이벤트에서 주소를 찾다
     * 못 찾고 예외를 던져 <b>모든 결제가 DLT 로 갔고 배송은 한 건도 생기지 않았다</b>
     * (docs/25 §11). 이 엔드포인트가 그 빈 자리를 채운다.
     *
     * <p>🔒 구매 집계와 같은 규칙이다 — 게이트웨이 신원 헤더가 있으면 외부 요청이고 그때는
     * 관리자만. 주소와 연락처가 들어 있으니 개별 주문 조회보다 가벼운 데이터가 아니다.
     * 금액은 싣지 않는다.
     */
    @GetMapping("/internal/orders/{orderId}/shipping")
    ResponseEntity<?> shippingInfo(
            @PathVariable("orderId") Long orderId,
            @RequestHeader(value = "User-Role", required = false) String callerRole,
            @RequestHeader(value = "User-Email", required = false) String callerEmail,
            @RequestHeader(value = "User-Id", required = false) String callerId) {

        if (!InternalCallGuard.allowed(callerRole, callerEmail, callerId)) {
            log.warn("외부에서 배송 정보 조회 시도를 차단: orderId={}", orderId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new MessageResponse("접근할 수 없습니다."));
        }

        return orderShippingInfoService.find(orderId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new MessageResponse("주문을 찾을 수 없습니다.")));
    }


    /**
     * 이 고객이 이 상품을 받았는가(배송 완료·구매 확정) — user-service 가 리뷰를 받기 전에 묻는다(docs/57 §13).
     *
     * <p>🔒 구매 집계와 같은 규칙 — 게이트웨이 신원 헤더가 있으면 외부 요청이고 그때는 관리자만. 밖에서 열려 있으면 남의 구매
     * 이력을 상품 단위로 캐낼 수 있다.
     */
    @GetMapping("/internal/purchased")
    ResponseEntity<?> purchased(
            @RequestParam("customerId") Long customerId,
            @RequestParam("productId") Long productId,
            @RequestHeader(value = "User-Role", required = false) String callerRole,
            @RequestHeader(value = "User-Email", required = false) String callerEmail,
            @RequestHeader(value = "User-Id", required = false) String callerId) {

        if (!InternalCallGuard.allowed(callerRole, callerEmail, callerId)) {
            log.warn("외부에서 구매 여부 조회 시도를 차단");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new MessageResponse("접근할 수 없습니다."));
        }
        return ResponseEntity.ok(Map.of("purchased", orderProductRepository.hasReceived(customerId, productId)));
    }
}
