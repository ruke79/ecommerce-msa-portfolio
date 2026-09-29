package com.project.order_service.mapper;

import com.project.common.dto.order.OrderAddressDto;
import com.project.order_service.model.OrderAddress;
import org.springframework.stereotype.Component;

@Component
public class OrderAddressMapper {

    /**
     * OrderAddress 엔티티를 OrderAddressDto로 변환합니다.
     * @param src 변환할 OrderAddress 엔티티 객체
     * @return OrderAddressDto 객체
     */
    static public OrderAddressDto toDto(OrderAddress src) {
        if (src == null) {
            return null;
        }

        return OrderAddressDto.builder()
            .id(Long.toString(src.getOrderAddressId()))
            .address1(src.getAddress1())
            .address2(src.getAddress2())
            .city(src.getCity())
            .state(src.getState())
            .country(src.getCountry())
            .firstname(src.getFirstname())
            .lastname(src.getLastname())
            .phoneNumber(src.getPhoneNumber())
            .zipCode(src.getZipCode())
            // 필요에 따라 active 필드 로직 추가
            // .active(src.isActive())
            .build();
    }

    /**
     * OrderAddressDto를 OrderAddress 엔티티로 변환합니다.
     * 이 메서드는 필요에 따라 구현할 수 있습니다.
     * @param dto 변환할 OrderAddressDto 객체
     * @return OrderAddress 엔티티 객체
     */
    static public OrderAddress toEntity(OrderAddressDto dto) {
        if (dto == null) {
            return null;
        }
        
        // 엔티티 빌더 패턴을 사용하여 객체 생성
        return OrderAddress.builder()
                .orderAddressId(dto.getId() != null ? Long.parseLong(dto.getId()) : null)
                .address1(dto.getAddress1())
                .address2(dto.getAddress2())
                .city(dto.getCity())
                .state(dto.getState())
                .country(dto.getCountry())
                .firstname(dto.getFirstname())
                .lastname(dto.getLastname())
                .phoneNumber(dto.getPhoneNumber())
                .zipCode(dto.getZipCode())
                .build();
    }
}