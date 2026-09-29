package com.project.order_service.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.common.dto.order.OrderShippingDto;
import com.project.order_service.model.Order;
import com.project.order_service.model.OrderAddress;
import com.project.order_service.model.OrderProduct;
import com.project.order_service.repository.jpa.OrderRepository;

import lombok.RequiredArgsConstructor;

/**
 * 배송 서비스가 묻는 "이 주문을 어디로, 무엇을 보내나".
 *
 * <p>결제 완료 이벤트에는 주소가 없다({@link OrderShippingDto} 주석). delivery-service 는
 * 이벤트에서 주문 번호만 얻고 나머지는 여기서 읽는다.
 *
 * <p>캐시하지 않는다. 배송은 주문당 한 번 만들어지고, 그 한 번은 정확해야 한다 — 주문 직후
 * 주소를 바꾼 손님의 옛 주소가 캐시에서 나오면 물건이 엉뚱한 곳으로 간다.
 */
@Service
@RequiredArgsConstructor
public class OrderShippingInfoService {

    private final OrderRepository orderRepository;

    @Transactional(readOnly = true)
    public Optional<OrderShippingDto> find(Long orderId) {
        return orderRepository.findById(orderId).map(OrderShippingInfoService::toDto);
    }

    static OrderShippingDto toDto(Order order) {
        OrderAddress a = order.getShippingAddress();
        OrderShippingDto.Address address = a == null ? null : new OrderShippingDto.Address(
                recipientName(a.getFirstname(), a.getLastname()),
                a.getPhoneNumber(),
                a.getZipCode(),
                a.getAddress1(),
                a.getAddress2(),
                a.getCity(),
                a.getState(),
                a.getCountry());

        List<OrderShippingDto.Item> items = order.getOrderedProducts() == null ? List.of()
                : order.getOrderedProducts().stream().map(OrderShippingInfoService::toItem).toList();

        return new OrderShippingDto(
                order.getOrderId(),
                order.getCustomerId(),
                order.getOrderStatus() == null ? null : order.getOrderStatus().name(),
                address,
                items);
    }

    /** 보낼 수는 배정된 수다 — 재고가 모자라 배정되지 못한 수(docs/53)는 창고에 없다. */
    private static OrderShippingDto.Item toItem(OrderProduct p) {
        return new OrderShippingDto.Item(p.getOrderProductId(), p.getProductId(), p.getName(), p.getImage(),
                p.allocatedQty(), p.getSkuCode());
    }

    /** 주문 주소는 이름이 둘로 나뉘어 있고, 송장에는 하나로 적는다. 한쪽만 있어도 된다. */
    private static String recipientName(String firstname, String lastname) {
        String first = firstname == null ? "" : firstname.trim();
        String last = lastname == null ? "" : lastname.trim();
        String joined = (first + " " + last).trim();
        return joined.isEmpty() ? null : joined;
    }
}
