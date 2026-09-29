// package com.project.order_service.service;


// import java.util.Optional;
// import java.util.UUID;
// import java.util.stream.Collectors;
// import java.util.List;
// import java.math.BigDecimal;
// import java.util.ArrayList;
// import java.time.Duration; // TTL을 위해 Duration 추가

// import org.springframework.stereotype.Service;
// import org.springframework.transaction.annotation.Transactional;
// import org.springframework.data.domain.Page;
// import org.springframework.data.domain.PageRequest;
// import org.springframework.data.domain.Sort;
// import org.springframework.data.redis.core.RedisTemplate; // RedisTemplate 추가

// import com.fasterxml.jackson.core.JsonProcessingException; // JSON 처리 예외
// import com.fasterxml.jackson.databind.ObjectMapper;
// import com.project.common.constants.OrderStatus;
// import com.project.common.constants.PaymentStatus;
// import com.project.common.constants.PaymentType;
// import com.project.common.dto.CartProductDto;
// import com.project.common.dto.ProductColorDto;
// import com.project.common.dto.SharedUserDto;
// import com.project.common.dto.request.PaypalPaymentRequest;
// import com.project.common.message.dto.request.PaymentRequest;
// import com.project.common.outbox.OutboxStatus;
// import com.project.order_service.client.CartServiceClient;
// import com.project.order_service.client.CatalogServiceClient;
// import com.project.order_service.client.UserServiceClient;

// import com.project.order_service.dto.OrderAddressDto;
// import com.project.order_service.dto.OrderDto;

// import com.project.order_service.dto.OrderedProductDto;
// import com.project.order_service.dto.request.OrderRequest;
// import com.project.order_service.model.Customer;
// import com.project.order_service.model.Order;
// import com.project.order_service.model.OrderAddress;
// import com.project.order_service.model.OrderProduct;
// import com.project.order_service.outbox.PaymentOutboxHelper;
// import com.project.order_service.repository.jpa.CustomerRepository;
// import com.project.order_service.repository.jpa.OrderAddressRepository;
// import com.project.order_service.repository.jpa.OrderProductRepository;
// import com.project.order_service.repository.jpa.OrderRepository;

// import lombok.RequiredArgsConstructor;
// import lombok.extern.slf4j.Slf4j;

// @Transactional
// @Slf4j
// @Service
// @RequiredArgsConstructor
// public class OrderService_RedisTemplate {

//     private final OrderRepository orderRepository;
//     private final CustomerRepository customerRepository;

//     private final PaymentOutboxHelper paymentOutboxHelper;

//     private final CatalogServiceClient catalogServiceClient;
//     private final CartServiceClient cartServiceClient;

//     private final OrderProductRepository orderedProductRepository;
//     private final OrderAddressRepository orderAddressRepository;

//     private final ObjectMapper objectMapper; // ObjectMapper를 주입받아 사용
//     private final RedisTemplate<String, String> redisTemplate; // RedisTemplate 주입

//     // 캐시 키 prefix 및 TTL (원하는 값으로 설정)
//     private static final String CUSTOMER_ID_CACHE_PREFIX = "customer:id:";
//     private static final String ORDERS_BY_CUSTOMER_CACHE_PREFIX = "orders:customer:";
//     private static final String ORDER_DETAIL_CACHE_PREFIX = "order:detail:";
//     private static final String ORDERS_LIST_CACHE_PREFIX = "orders:list:";
//     private static final String ORDERS_PAGE_CACHE_PREFIX = "orders:page:";
//     private static final String ORDERS_CURSOR_CACHE_PREFIX = "orders:cursor:";
//     private static final Duration CACHE_TTL = Duration.ofMinutes(30); // 30분 TTL 설정

//     // --- 기존 메서드 수정 ---

//     @Transactional(readOnly = true)
//     public Long getCustomerIdByEmail(String email) {
//         String cacheKey = CUSTOMER_ID_CACHE_PREFIX + email;
//         String cachedId = redisTemplate.opsForValue().get(cacheKey);

//         if (cachedId != null) {
//             log.debug("Cache hit for getCustomerIdByEmail: {}", email);
//             return Long.valueOf(cachedId);
//         }

//         log.debug("Cache miss for getCustomerIdByEmail: {}", email);
//         Long customerId = customerRepository.findByEmail(email)
//                                              .map(Customer::getId)
//                                              .orElse(null);

//         if (customerId != null) {
//             redisTemplate.opsForValue().set(cacheKey, String.valueOf(customerId), CACHE_TTL);
//         }
//         return customerId;
//     }

//     @Transactional(readOnly = true)
//     public List<Order> getOrdersByCustomerId(Long customerId) {
//         String cacheKey = ORDERS_BY_CUSTOMER_CACHE_PREFIX + customerId;
//         String cachedOrdersJson = redisTemplate.opsForValue().get(cacheKey);

//         if (cachedOrdersJson != null) {
//             log.debug("Cache hit for getOrdersByCustomerId: {}", customerId);
//             try {
//                 // List<Order> 타입으로 역직렬화
//                 return objectMapper.readValue(cachedOrdersJson,
//                                             objectMapper.getTypeFactory().constructCollectionType(List.class, Order.class));
//             } catch (JsonProcessingException e) {
//                 log.error("Failed to deserialize cached orders for customerId: {}", customerId, e);
//                 // 캐시 데이터 손상 시 재조회를 위해 null 반환
//                 return null;
//             }
//         }

//         log.debug("Cache miss for getOrdersByCustomerId: {}", customerId);
//         List<Order> orders = orderRepository.findByCustomerId(customerId).orElse(null);

//         if (orders != null && !orders.isEmpty()) {
//             try {
//                 redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(orders), CACHE_TTL);
//             } catch (JsonProcessingException e) {
//                 log.error("Failed to serialize orders for customerId: {}", customerId, e);
//             }
//         }
//         return orders;
//     }

//     @Transactional(readOnly = true)
//     public Order getOrderByTrackingId(String trackingId) {
//         // 이 메서드는 외부 시스템에서 직접 조회하므로 캐시하지 않거나,
//         // 필요하다면 OrderDto 캐시와 연계하여 사용해야 할 수 있습니다.
//         // 현재는 캐싱을 적용하지 않고 DB에서 직접 조회합니다.
//         return orderRepository.findByTrackingId(trackingId).orElse(null);
//     }

//     @Transactional
//     public Order createOrder(OrderRequest request) {
//         // 주문 생성 시, 관련 캐시 무효화
//         // customerId 캐시와 customerId별 주문 목록 캐시를 무효화합니다.
//         Long customerId = getCustomerIdByEmail(request.getEmail()); // 여기서 캐시 활용

//         // 캐시 무효화 (email과 customerId 기반)
//         if (customerId != null) {
//             redisTemplate.delete(CUSTOMER_ID_CACHE_PREFIX + request.getEmail());
//             redisTemplate.delete(ORDERS_BY_CUSTOMER_CACHE_PREFIX + customerId);
//             redisTemplate.delete(ORDERS_LIST_CACHE_PREFIX + request.getEmail() + ":*"); // 이메일 기준 모든 필터 캐시 무효화
//             redisTemplate.delete(ORDERS_PAGE_CACHE_PREFIX + customerId + ":*"); // 페이지별 캐시 무효화
//             redisTemplate.delete(ORDERS_CURSOR_CACHE_PREFIX + customerId + ":*"); // 커서 기반 캐시 무효화
//         }
//         // ... 기존 createOrder 로직 ...
//         // 이하 로직은 동일
//         if (customerId != null) {
//             Order order = Order.builder()
//                     .trackingId(UUID.randomUUID().toString())
//                     .paymentMethod(request.getPaymentMethod())
//                     .couponApplied(request.getCouponApplied())
//                     .total(request.getTotal())
//                     .totalBeforeDiscount(request.getTotalBeforeDiscount())
//                     .orderStatus(OrderStatus.NOT_PROCESSED)
//                     .customerId(customerId)
//                     .build();

//             List<OrderProduct> ordered = new ArrayList<>();

//             for (CartProductDto p : request.getProducts()) {
//                 OrderProduct op = OrderProduct.builder()
//                         .name(p.getName())
//                         .colorId(Long.parseLong(p.getColor().getId()))
//                         .image(p.getImage())
//                         .price(p.getPrice())
//                         .qty(p.getQty())
//                         .size(p.getSize())
//                         .productId(Long.parseLong(cartServiceClient.getProductId(p.getId())))
//                         .order(order)
//                         .build();

//                 ordered.add(op);
//             }

//             order.setOrderedProducts(ordered);
//             Order result = orderRepository.save(order);

//             OrderAddress address = OrderAddress.builder()
//                     .firstname(request.getShippingAddress().getFirstname())
//                     .lastname(request.getShippingAddress().getLastname())
//                     .address1(request.getShippingAddress().getAddress1())
//                     .address2(request.getShippingAddress().getAddress2())
//                     .city(request.getShippingAddress().getCity())
//                     .state(request.getShippingAddress().getState())
//                     .zipCode(request.getShippingAddress().getZipCode())
//                     .country(request.getShippingAddress().getCountry())
//                     .phoneNumber(request.getShippingAddress().getPhoneNumber())
//                     .build();

//             result.setShippingAddress(address);
//             address.setOrder(result);
//             orderAddressRepository.save(address);

//             return result;
//         }
//         return null;
//     }

//     @Transactional(readOnly = true)
//     public OrderDto getOrder(Long orderId, String email, String filter) {
//         String cacheKey = ORDER_DETAIL_CACHE_PREFIX + orderId + ":" + email + ":" + (filter.isEmpty() ? "all" : filter);
//         String cachedOrderDtoJson = redisTemplate.opsForValue().get(cacheKey);

//         if (cachedOrderDtoJson != null) {
//             log.debug("Cache hit for getOrder: {}, {}, {}", orderId, email, filter);
//             try {
//                 return objectMapper.readValue(cachedOrderDtoJson, OrderDto.class);
//             } catch (JsonProcessingException e) {
//                 log.error("Failed to deserialize cached OrderDto for key: {}", cacheKey, e);
//                 return null;
//             }
//         }

//         log.debug("Cache miss for getOrder: {}, {}, {}", orderId, email, filter);
//         Order data = filter.isEmpty()
//                 ? orderRepository.findById(orderId).orElse(null)
//                 : orderRepository.findByOrderIdAndOrderStatus(orderId, OrderStatus.getStatus(filter)).orElse(null);

//         if (data != null) {
//             OrderDto orderDto = convertToOrderDto(data);
//             try {
//                 redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(orderDto), CACHE_TTL);
//             } catch (JsonProcessingException e) {
//                 log.error("Failed to serialize OrderDto for key: {}", cacheKey, e);
//             }
//             return orderDto;
//         }
//         return null;
//     }

//     @Transactional(readOnly = true)
//     public List<OrderDto> getOrders(String email, String filter) {
//         String cacheKey = ORDERS_LIST_CACHE_PREFIX + email + ":" + (filter.isEmpty() ? "all" : filter);
//         String cachedOrderDtosJson = redisTemplate.opsForValue().get(cacheKey);

//         if (cachedOrderDtosJson != null) {
//             log.debug("Cache hit for getOrders: {}, {}", email, filter);
//             try {
//                 return objectMapper.readValue(cachedOrderDtosJson,
//                                             objectMapper.getTypeFactory().constructCollectionType(List.class, OrderDto.class));
//             } catch (JsonProcessingException e) {
//                 log.error("Failed to deserialize cached OrderDto list for key: {}", cacheKey, e);
//                 return null;
//             }
//         }

//         log.debug("Cache miss for getOrders: {}, {}", email, filter);
//         Long customerId = getCustomerIdByEmail(email); // 캐시 활용
//         List<Order> orders = getOrdersByCustomerId(customerId); // 캐시 활용

//         if (orders != null && !orders.isEmpty()) {
//             List<OrderDto> orderDtos = orders.stream()
//                     .map(o -> getOrder(o.getOrderId(), email, filter)) // 개별 주문 조회 (캐시 활용)
//                     .collect(Collectors.toList());
//             try {
//                 redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(orderDtos), CACHE_TTL);
//             } catch (JsonProcessingException e) {
//                 log.error("Failed to serialize OrderDto list for key: {}", cacheKey, e);
//             }
//             return orderDtos;
//         }
//         return null;
//     }

//     @Transactional(readOnly = true)
//     public Page<OrderDto> getOrdersByCustomerIdWithPagination(Long customerId, int page, int size) {
//         String cacheKey = ORDERS_PAGE_CACHE_PREFIX + customerId + ":" + page + ":" + size;
//         String cachedOrdersPageJson = redisTemplate.opsForValue().get(cacheKey);

//         if (cachedOrdersPageJson != null) {
//             log.debug("Cache hit for getOrdersByCustomerIdWithPagination: {}, {}, {}", customerId, page, size);
//             try {
//                 // Page<OrderDto> 타입을 직접 역직렬화하는 것은 복잡하므로,
//                 // 여기서는 List<OrderDto>로 변환하여 저장하고 로직에서 Page 객체로 재구성하는 것을 고려
//                 // 또는 Page 객체를 Custom Serializer/Deserializer로 처리해야 함.
//                 // 간단하게 List<OrderDto>를 캐싱하고 PageImpl로 재구성하는 예시
//                 List<OrderDto> content = objectMapper.readValue(cachedOrdersPageJson,
//                                             objectMapper.getTypeFactory().constructCollectionType(List.class, OrderDto.class));
//                 // 실제 Page 객체에는 totalElements, totalPages 등의 정보가 필요하지만,
//                 // 여기서는 간단히 content만으로 PageImpl을 생성합니다.
//                 // 완벽한 Page 객체를 캐싱하려면 별도의 DTO 또는 커스텀 직렬화가 필요합니다.
//                 return new PageRequest(page, size).getPage(); // 예시: 실제 구현에서는 content, pageable, total 정보 필요
//             } catch (JsonProcessingException e) {
//                 log.error("Failed to deserialize cached orders page for key: {}", cacheKey, e);
//                 return null;
//             }
//         }

//         log.debug("Cache miss for getOrdersByCustomerIdWithPagination: {}, {}, {}", customerId, page, size);
//         PageRequest pageRequest = PageRequest.of(page, size, Sort.by("createdAt").descending());
//         Page<Order> ordersPage = orderRepository.findByCustomerId(customerId, pageRequest);

//         Page<OrderDto> orderDtosPage = ordersPage.map(this::convertToOrderDto);

//         if (orderDtosPage != null && orderDtosPage.hasContent()) {
//             try {
//                 // Page 객체 전체를 직렬화하는 대신, content 부분만 직렬화
//                 redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(orderDtosPage.getContent()), CACHE_TTL);
//             } catch (JsonProcessingException e) {
//                 log.error("Failed to serialize orders page content for key: {}", cacheKey, e);
//             }
//         }
//         return orderDtosPage;
//     }

//     @Transactional(readOnly = true)
//     public Page<OrderDto> getOrdersByCustomerIdWithCursor(Long customerId, Long cursorId, int size) {
//         String cacheKey = ORDERS_CURSOR_CACHE_PREFIX + customerId + ":" + (cursorId == null ? "null" : cursorId) + ":" + size;
//         String cachedOrdersPageJson = redisTemplate.opsForValue().get(cacheKey);

//         if (cachedOrdersPageJson != null) {
//             log.debug("Cache hit for getOrdersByCustomerIdWithCursor: {}, {}, {}", customerId, cursorId, size);
//             try {
//                 List<OrderDto> content = objectMapper.readValue(cachedOrdersPageJson,
//                                             objectMapper.getTypeFactory().constructCollectionType(List.class, OrderDto.class));
//                 return new PageRequest(0, size).getPage(); // 예시
//             } catch (JsonProcessingException e) {
//                 log.error("Failed to deserialize cached cursor orders page for key: {}", cacheKey, e);
//                 return null;
//             }
//         }

//         log.debug("Cache miss for getOrdersByCustomerIdWithCursor: {}, {}, {}", customerId, cursorId, size);
//         PageRequest pageRequest = PageRequest.of(0, size, Sort.by("orderId").descending());
//         Page<Order> ordersPage;

//         if (cursorId == null || cursorId == 0) {
//             ordersPage = orderRepository.findByCustomerIdOrderByOrderIdDesc(customerId, pageRequest);
//         } else {
//             ordersPage = orderRepository.findByCustomerIdAndOrderIdLessThanOrderByOrderIdDesc(customerId, cursorId, pageRequest);
//         }

//         Page<OrderDto> orderDtosPage = ordersPage.map(this::convertToOrderDto);

//         if (orderDtosPage != null && orderDtosPage.hasContent()) {
//             try {
//                 redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(orderDtosPage.getContent()), CACHE_TTL);
//             } catch (JsonProcessingException e) {
//                 log.error("Failed to serialize cursor orders page content for key: {}", cacheKey, e);
//             }
//         }
//         return orderDtosPage;
//     }

//     @Transactional
//     public void persisitPaypalPayment(PaypalPaymentRequest request) {
//         Order order = getOrderByTrackingId(request.getTrackingId());
//         if (order != null) {
//             PaymentRequest payload = PaymentRequest.builder()
//                     .orderId(order.getOrderId())
//                     .customerId(getCustomerIdByEmail(request.getUserEmail())) // 캐시 활용
//                     .trackingId(request.getTrackingId())
//                     .amounts(request.getAmounts())
//                     .paypalOrderId(request.getPaypalOrderId())
//                     .couponName(request.getCouponName())
//                     .orderStatus(OrderStatus.valueOf(request.getOrderStatus()))
//                     .paymentType(PaymentType.PAYPAL)
//                     .paymentStatus(PaymentStatus.valueOf(request.getPaymentStatus()))
//                     .build();

//             // 결제 완료 시, 관련 캐시 무효화 (주문 목록, 개별 주문 상세 등)
//             Long customerId = getCustomerIdByEmail(request.getUserEmail()); // 재확인 (이미 위에서 호출함)
//             if (customerId != null) {
//                 redisTemplate.delete(ORDERS_BY_CUSTOMER_CACHE_PREFIX + customerId);
//                 // 모든 개별 주문 상세 (order:detail:orderId:email:filter)도 무효화해야 하지만,
//                 // 키를 정확히 알기 어렵다면 customerId에 해당하는 모든 order:detail:* 키를 삭제해야 합니다.
//                 // 이는 redisTemplate.keys()를 사용하여 패턴 매칭으로 삭제할 수 있으나,
//                 // 대량의 키에 대해 keys()는 성능 저하를 유발할 수 있으므로 주의해야 합니다.
//                 // 가능하면 정확한 키를 아는 경우에만 삭제하는 것이 좋습니다.
//                 // 여기서는 편의상 일부만 명시적으로 삭제하고, 나머지는 TTL에 맡기거나
//                 // 서비스의 캐싱 전략에 따라 다른 방법을 사용해야 합니다.
//                 redisTemplate.delete(ORDERS_LIST_CACHE_PREFIX + request.getUserEmail() + ":*");
//                 redisTemplate.delete(ORDERS_PAGE_CACHE_PREFIX + customerId + ":*");
//                 redisTemplate.delete(ORDERS_CURSOR_CACHE_PREFIX + customerId + ":*");
//             }
//             paymentOutboxHelper.savePaymentOutbox(payload, OutboxStatus.STARTED);
//         }
//     }

//     // --- 가상의 cartEmpty 및 cartRollback 메서드 (OrderService에 있다고 가정) ---
//     // 실제로는 CartServiceClient를 통해 Cart-service의 캐시된 메서드를 호출하게 될 것입니다.

//     @Transactional
//     public void cartEmpty(Long userId) {
//         log.info("Performing cartEmpty for user: {}", userId);
//         // 실제 Cart-service의 API를 호출하여 장바구니를 비우는 로직
//         // cartServiceClient.emptyUserCart(userId); // 이런 식으로 호출될 것

//         // 장바구니가 비워졌으므로, 해당 userId의 장바구니 관련 캐시를 무효화합니다.
//         // 이 OrderService에서는 '장바구니'에 대한 직접적인 캐시를 관리하지 않지만,
//         // 만약 OrderService가 '특정 사용자의 현재 장바구니 요약' 등을 캐시한다면 무효화해야 합니다.
//         // 현재 OrderService는 주문 관련 캐시만 관리하므로, 여기서는 해당 캐시를 무효화할 것은 없습니다.
//         // 이 로직은 CartService의 `cartEmpty` 메서드에 적용되어야 합니다.
//     }

//     @Transactional
//     public void cartRollback(Long userId) {
//         log.info("Performing cartRollback for user: {}", userId);
//         // 실제 Cart-service의 API를 호출하여 장바구니 상품 수량을 1로 초기화하는 로직
//         // cartServiceClient.rollbackUserCart(userId); // 이런 식으로 호출될 것

//         // 장바구니 내용이 변경되었으므로, 해당 userId의 장바구니 관련 캐시를 무효화합니다.
//         // 마찬가지로 이 로직은 CartService의 `cartRollback` 메서드에 적용되어야 합니다.
//     }


//     // 도메인 엔티티를 DTO로 변환하는 공통 메서드
//     private OrderDto convertToOrderDto(Order data) {
//         List<OrderedProductDto> dtos = data.getOrderedProducts().stream()
//                 .map(product -> {
//                     ProductColorDto color = null;
//                     try {
//                          color = objectMapper.convertValue(
//                                 catalogServiceClient.getColorInfo(Long.toString(product.getColorId())).getBody(),
//                                 ProductColorDto.class);
//                     } catch (Exception e) {
//                         log.error("Failed to get color info for product: {}", product.getColorId(), e);
//                         // 오류 발생 시 기본값 또는 예외 처리
//                         color = ProductColorDto.builder().id(Long.toString(product.getColorId())).build();
//                     }

//                     return OrderedProductDto.builder()
//                             .color(color)
//                             .id(Long.toString(product.getOrderProductId()))
//                             .image(product.getImage())
//                             .name(product.getName())
//                             .price(product.getPrice())
//                             .qty(product.getQty())
//                             .build();
//                 })
//                 .collect(Collectors.toList());

//         OrderAddressDto address = new OrderAddressDto();
//         if (data.getShippingAddress() != null) {
//             OrderAddressDto.toDto(address, data.getShippingAddress());
//         }


//         return OrderDto.builder()
//                 .id(Long.toString(data.getOrderId()))
//                 .trackingId(data.getTrackingId())
//                 .couponApplied(data.getCouponApplied())
//                 .deliveredCreatedAt(data.getDeliveredCreatedAt())
//                 .orderStatus(data.getOrderStatus().name())
//                 .paymentMethod(data.getPaymentMethod())
//                 .paymentStatus(PaymentStatus.COMPLETED.getStatus())
//                 .products(dtos)
//                 .shippingAddress(address)
//                 .totalBeforeDiscount(data.getTotalBeforeDiscount())
//                 .total(data.getTotal())
//                 .build();
//     }
// }