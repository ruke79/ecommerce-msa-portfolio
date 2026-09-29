package com.project.userservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import com.project.userservice.model.ShippingAddress;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.ShippingAddressRepository;
import com.project.userservice.repository.jpa.UserRepository;

/**
 * 배송지는 <b>본인 것에만</b> 닿아야 한다.
 *
 * <p>🔴 회귀 대상 두 가지.
 *
 * <p><b>① 삭제에 소유권 검사가 없었다(IDOR).</b> {@code findById(addressId)} 로 전체
 * 테이블에서 찾은 뒤 소유자를 대조하지 않고 지웠다. 컬렉션에서 빼는 {@code remove} 는
 * 남의 것이면 조용히 지나가고, 그 다음 줄의 {@code delete} 가 그냥 실행됐다.
 *
 * <p><b>② 기본 배송지를 전 테이블에서 찾았다.</b> {@code findByActive(true)} 에는
 * {@code user_id} 조건이 없어, 전체에 active 가 하나뿐일 때 그것이 남의 것이면
 * 남의 기본 배송지를 꺼 버렸다.
 */
class AddressOwnershipTest {

    private final ShippingAddressRepository shippingAddressRepository =
            mock(ShippingAddressRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);

    private final AddressService service =
            new AddressService(shippingAddressRepository, userRepository);

    private static final Long ME = 1L;
    private static final Long SOMEONE_ELSE = 2L;
    private static final Long VICTIM_ADDRESS_ID = 999L;

    private User me() {
        User user = User.builder()
                .userId(ME)
                .email("me@example.com")
                .shippingAddresses(new ArrayList<>())
                .build();
        return user;
    }

    private ShippingAddress address(Long id, Long ownerId) {
        ShippingAddress a = ShippingAddress.builder()
                .shippingAddressId(id)
                .address1("어딘가")
                .city("서울")
                .state("서울")
                .zipCode("00000")
                .build();
        a.setUser(User.builder().userId(ownerId).shippingAddresses(new ArrayList<>()).build());
        return a;
    }

    // ── ① 삭제 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 남의 배송지는 지울 수 없다 — 예전에는 id 만 알면 지워졌다")
    void cannotDeleteSomeoneElsesAddress() {
        when(userRepository.findByUsername("me@example.com")).thenReturn(Optional.of(me()));
        // 내 것 중에서 찾으므로 비어 있다. (예전 코드는 findById 로 전체에서 찾아 왔다.)
        when(shippingAddressRepository.findByShippingAddressIdAndUser_UserId(
                VICTIM_ADDRESS_ID, ME)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.deleteShippingAddress("me@example.com", VICTIM_ADDRESS_ID.toString()))
                .isInstanceOf(AccessDeniedException.class);

        // 무엇보다 **지우지 않았다**는 것이 핵심이다.
        verify(shippingAddressRepository, never()).delete(any());
    }

    @Test
    @DisplayName("소유권 조회는 언제나 사용자 id 와 함께 나간다")
    void deleteLooksUpScopedToTheUser() {
        when(userRepository.findByUsername("me@example.com")).thenReturn(Optional.of(me()));
        when(shippingAddressRepository.findByShippingAddressIdAndUser_UserId(anyLong(), anyLong()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteShippingAddress("me@example.com", "5"))
                .isInstanceOf(AccessDeniedException.class);

        // 전체 테이블을 뒤지는 findById 로 되돌아가지 못하게 고정한다.
        verify(shippingAddressRepository).findByShippingAddressIdAndUser_UserId(eq(5L), eq(ME));
        verify(shippingAddressRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("내 배송지는 지워진다")
    void deletesOwnAddress() {
        User user = me();
        ShippingAddress mine = address(5L, ME);
        user.getShippingAddresses().add(mine);

        when(userRepository.findByUsername("me@example.com")).thenReturn(Optional.of(user));
        when(shippingAddressRepository.findByShippingAddressIdAndUser_UserId(5L, ME))
                .thenReturn(Optional.of(mine));

        service.deleteShippingAddress("me@example.com", "5");

        verify(shippingAddressRepository).delete(mine);
    }

    @Test
    @DisplayName("주소 id 가 숫자가 아니어도 500 이 나지 않는다")
    void nonNumericAddressId() {
        when(userRepository.findByUsername("me@example.com")).thenReturn(Optional.of(me()));

        // 예전에는 Long.parseLong 이 NumberFormatException 을 그대로 올려보냈다.
        service.deleteShippingAddress("me@example.com", "abc");

        verify(shippingAddressRepository, never()).delete(any());
    }

    // ── ③ 수정 ──────────────────────────────────────────────────────────────

    private static com.project.userservice.dto.AddressDto changes() {
        var dto = new com.project.userservice.dto.AddressDto();
        dto.setFirstname("길동"); dto.setLastname("홍"); dto.setPhoneNumber("010-0000-0000");
        dto.setAddress1("전남 목포시 삼학동 702호"); dto.setCity("목포시"); dto.setState("전라남도");
        dto.setZipCode("58700"); dto.setCountry("Korea, Republic of");
        dto.setActive(true);   // 폼이 이 값을 실어 보내도 무시돼야 한다
        return dto;
    }

    @Test
    @DisplayName("🔴 남의 배송지는 고칠 수 없다 — 삭제와 같은 소유권 판정")
    void cannotUpdateSomeoneElsesAddress() {
        when(userRepository.findByUsername("me@example.com")).thenReturn(Optional.of(me()));
        when(shippingAddressRepository.findByShippingAddressIdAndUser_UserId(VICTIM_ADDRESS_ID, ME))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.updateShippingAddress("me@example.com", VICTIM_ADDRESS_ID.toString(), changes()))
                .isInstanceOf(AccessDeniedException.class);

        verify(shippingAddressRepository, never()).save(any());
        verify(shippingAddressRepository, never()).findById(anyLong());
    }

    /** 나라를 잘못 골라 KP 로 저장된 주소를 고치는 경우 — 실제 사례다(docs/25 §11.9). */
    @Test
    @DisplayName("내 배송지는 값이 바뀌고, 기본 배송지 여부는 그대로다")
    void updatesOwnAddressButNotTheDefaultFlag() {
        ShippingAddress mine = address(5L, ME);
        mine.setCountry("Korea, Democratic People'S Republic of");
        mine.setActive(false);
        when(userRepository.findByUsername("me@example.com")).thenReturn(Optional.of(me()));
        when(shippingAddressRepository.findByShippingAddressIdAndUser_UserId(5L, ME))
                .thenReturn(Optional.of(mine));
        when(shippingAddressRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = service.updateShippingAddress("me@example.com", "5", changes());

        assertThat(mine.getCountry()).isEqualTo("Korea, Republic of");
        assertThat(mine.getAddress1()).isEqualTo("전남 목포시 삼학동 702호");
        assertThat(mine.getFirstname()).isEqualTo("길동");
        // 폼이 active=true 를 보내도 기본 배송지는 selectShipAddress 만 바꾼다.
        assertThat(mine.isActive()).isFalse();
        assertThat(result.getId()).isEqualTo("5");
        assertThat(result.getCountry()).isEqualTo("Korea, Republic of");
    }

    @Test
    @DisplayName("수정에서 주소 id 가 숫자가 아니면 400 감이다 — 500 이 아니다")
    void updateWithNonNumericIdIsBadRequest() {
        when(userRepository.findByUsername("me@example.com")).thenReturn(Optional.of(me()));

        assertThatThrownBy(() -> service.updateShippingAddress("me@example.com", "abc", changes()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(shippingAddressRepository, never()).save(any());
    }

    // ── ② 기본 배송지 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 기본 배송지를 끌 때 내 것만 본다 — 예전에는 전 테이블에서 찾았다")
    void deactivatesOnlyMyCurrentDefault() {
        User user = me();
        ShippingAddress mine = address(5L, ME);
        ShippingAddress myOldDefault = address(4L, ME);
        myOldDefault.setActive(true);

        when(userRepository.findByUsername("me@example.com")).thenReturn(Optional.of(user));
        when(shippingAddressRepository.findByShippingAddressIdAndUser_UserId(5L, ME))
                .thenReturn(Optional.of(mine));
        when(shippingAddressRepository.findByUser_UserIdAndActiveTrue(ME))
                .thenReturn(Optional.of(myOldDefault));
        when(shippingAddressRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.selectShipAddress("me@example.com", "5");

        assertThat(myOldDefault.isActive()).isFalse();
        assertThat(mine.isActive()).isTrue();

        // user_id 없는 전역 조회로 되돌아가지 못하게 고정한다.
        verify(shippingAddressRepository).findByUser_UserIdAndActiveTrue(ME);
    }

    @Test
    @DisplayName("🔴 남의 배송지는 기본으로 고를 수 없다")
    void cannotSelectSomeoneElsesAddress() {
        when(userRepository.findByUsername("me@example.com")).thenReturn(Optional.of(me()));
        when(shippingAddressRepository.findByShippingAddressIdAndUser_UserId(
                VICTIM_ADDRESS_ID, ME)).thenReturn(Optional.empty());

        var result = service.selectShipAddress("me@example.com", VICTIM_ADDRESS_ID.toString());

        assertThat(result).isNull();
        verify(shippingAddressRepository, never()).save(any());
    }

    @Test
    @DisplayName("이미 기본인 배송지를 다시 골라도 꺼지지 않는다")
    void reselectingTheCurrentDefaultKeepsItOn() {
        User user = me();
        ShippingAddress mine = address(5L, ME);
        mine.setActive(true);

        when(userRepository.findByUsername("me@example.com")).thenReturn(Optional.of(user));
        when(shippingAddressRepository.findByShippingAddressIdAndUser_UserId(5L, ME))
                .thenReturn(Optional.of(mine));
        when(shippingAddressRepository.findByUser_UserIdAndActiveTrue(ME))
                .thenReturn(Optional.of(mine));
        when(shippingAddressRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.selectShipAddress("me@example.com", "5");

        // 자기 자신을 끄고 켜면 결과는 같지만, 그 사이에 잠깐 기본 배송지가 없는 상태가
        // 생긴다. filter 로 걸러 아예 건드리지 않는다.
        assertThat(mine.isActive()).isTrue();
    }

    @Test
    @DisplayName("사용자를 찾을 수 없으면 아무것도 건드리지 않는다")
    void unknownUserTouchesNothing() {
        when(userRepository.findByUsername("ghost@example.com")).thenReturn(Optional.empty());

        assertThat(service.selectShipAddress("ghost@example.com", "5")).isNull();
        service.deleteShippingAddress("ghost@example.com", "5");

        verify(shippingAddressRepository, never()).save(any());
        verify(shippingAddressRepository, never()).delete(any());
    }
}
