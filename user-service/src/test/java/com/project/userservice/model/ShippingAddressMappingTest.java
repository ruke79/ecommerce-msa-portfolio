package com.project.userservice.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.userservice.dto.AddressDto;

/**
 * 배송지 매핑은 <b>한 자리</b>에만 있어야 한다 (docs/43 B5).
 *
 * <h3>🐛 무엇이 문제였나</h3>
 *
 * <p>{@code AddressService.saveShippingAddress} 가 빌더로 전 필드를 채운 <b>직후</b>
 * {@code deepCopyShippingAddress} 로 같은 필드를 다시 덮었다. 단순 중복으로 보이지만
 * 함정이 하나 있다 — 나중에 <b>빌더에만</b> 필드를 추가하면 deepCopy 가 그 필드를
 * {@code null} 로 덮는다. 컴파일도 되고, 그 필드를 보지 않는 테스트는 전부 통과한다.
 * 새로 넣은 주소 항목 하나가 조용히 사라지는 모양이다.
 *
 * <p>매핑을 {@link ShippingAddress#from} 하나로 모았다. 이 테스트는 <b>그 하나가
 * 빠짐없이 옮기는지</b>를 리플렉션으로 확인한다 — 필드가 늘어났는데 매핑을 안 고치면
 * 여기서 먼저 막힌다.
 */
@DisplayName("배송지 매핑")
class ShippingAddressMappingTest {

    /**
     * 매핑 대상이 아닌 필드.
     *
     * <p>id 는 DB 가 만들고, user 는 서비스가 붙이고, 감사 필드는 JPA 가 채운다.
     * 여기에 이름을 추가하는 것은 "이 필드는 DTO 에서 오지 않는다" 는 선언이므로,
     * 새 주소 항목을 넣으면서 무심코 여기 적는 일이 없어야 한다.
     */
    private static final Set<String> NOT_FROM_DTO = Set.of(
            "shippingAddressId", "user", "version", "createdAt", "createdBy", "updatedAt", "updatedBy");

    private static AddressDto filledDto() {
        AddressDto dto = new AddressDto();
        dto.setFirstname("홍");
        dto.setLastname("길동");
        dto.setAddress1("서울시 강남구 테헤란로 1");
        dto.setAddress2("101동 202호");
        dto.setCity("서울");
        dto.setState("서울특별시");
        dto.setZipCode("06234");
        dto.setCountry("대한민국");
        dto.setPhoneNumber("010-0000-0000");
        dto.setActive(true);
        return dto;
    }

    @Test
    @DisplayName("DTO 의 모든 값을 옮긴다")
    void everyValueIsCarried() {
        ShippingAddress address = ShippingAddress.from(filledDto());

        assertThat(address.getFirstname()).isEqualTo("홍");
        assertThat(address.getLastname()).isEqualTo("길동");
        assertThat(address.getAddress1()).isEqualTo("서울시 강남구 테헤란로 1");
        assertThat(address.getAddress2()).isEqualTo("101동 202호");
        assertThat(address.getCity()).isEqualTo("서울");
        assertThat(address.getState()).isEqualTo("서울특별시");
        assertThat(address.getZipCode()).isEqualTo("06234");
        assertThat(address.getCountry()).isEqualTo("대한민국");
        assertThat(address.getPhoneNumber()).isEqualTo("010-0000-0000");
        assertThat(address.isActive()).isTrue();
    }

    /**
     * 🔴 이 테스트가 함정을 막는다.
     *
     * <p>엔티티에 주소 필드를 하나 추가하고 {@link ShippingAddress#from} 을 고치지 않으면
     * 그 필드는 비어 있는 채로 남는다. 필드 이름을 외워 두는 대신 리플렉션으로 훑어서,
     * <b>새 필드가 생기는 순간</b> 여기가 먼저 빨개지게 한다.
     */
    @Test
    @DisplayName("매핑에서 빠진 필드가 있으면 여기서 막힌다")
    void noFieldIsSilentlyDropped() throws Exception {
        ShippingAddress address = ShippingAddress.from(filledDto());

        List<String> dropped = new java.util.ArrayList<>();
        for (Field field : ShippingAddress.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || NOT_FROM_DTO.contains(field.getName())) {
                continue;
            }
            field.setAccessible(true);
            Object value = field.get(address);
            // 채워진 DTO 로 만들었으므로 어느 필드도 비어 있으면 안 된다.
            if (value == null || Boolean.FALSE.equals(value) || "".equals(value)) {
                dropped.add(field.getName());
            }
        }

        assertThat(dropped)
                .as("ShippingAddress.from 이 옮기지 않는 필드다. 매핑을 고치거나, "
                        + "DTO 에서 오지 않는 값이면 NOT_FROM_DTO 에 적어라")
                .isEmpty();
    }

    /**
     * 수정 경로도 같은 목록을 쓴다 — {@code from} 이 {@code applyFrom} 을 부르므로 위 검사가
     * 둘을 함께 덮는다. 여기서는 {@code applyFrom} 이 <b>기본 배송지 여부를 건드리지 않는다</b>
     * 는 것만 따로 고정한다. 폼이 active 를 실어 보내도 기본 배송지는 selectShipAddress 만 바꾼다.
     */
    @Test
    @DisplayName("applyFrom 은 주소 값만 덮고 기본 배송지 여부는 그대로 둔다")
    void applyFromLeavesTheDefaultFlagAlone() {
        ShippingAddress address = ShippingAddress.builder().active(false).build();
        AddressDto dto = filledDto();   // active=true 가 실려 있다

        address.applyFrom(dto);

        assertThat(address.getAddress1()).isEqualTo("서울시 강남구 테헤란로 1");
        assertThat(address.getCountry()).isEqualTo("대한민국");
        assertThat(address.isActive()).isFalse();
    }

    /**
     * 매핑이 두 벌이 되는 것을 막는다. 예전의 {@code deepCopyShippingAddress} 처럼
     * "이미 만들어진 엔티티에 같은 필드를 다시 쓰는" 메서드가 생기면 빌더와 짝을 맞춰야
     * 하고, 한쪽만 고치는 날 값이 사라진다.
     */
    @Test
    @DisplayName("엔티티에 남은 매핑 메서드는 from 하나뿐이다")
    void thereIsExactlyOneMapper() {
        List<String> mappers = java.util.Arrays.stream(ShippingAddress.class.getDeclaredMethods())
                .filter(m -> Modifier.isStatic(m.getModifiers()))
                .map(java.lang.reflect.Method::getName)
                .filter(name -> !name.startsWith("$") && !name.equals("builder"))
                .toList();

        assertThat(mappers).containsExactly("from");
    }
}
