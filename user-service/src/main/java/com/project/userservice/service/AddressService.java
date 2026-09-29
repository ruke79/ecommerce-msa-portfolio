package com.project.userservice.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.userservice.model.ShippingAddress;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.ShippingAddressRepository;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.dto.AddressDto;
import com.project.userservice.security.request.AddressRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Transactional
@Slf4j
@Service
@RequiredArgsConstructor
public class AddressService {

    private final ShippingAddressRepository shippingAddressRepository;
    private final UserRepository userRepository;

    public AddressDto saveShippingAddress(AddressRequest request, String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // 🔒 사람을 가리키는 값은 로그에 남기지 않는다. username 은 사용자가 고른
        //    이름이거나 소셜 계정의 provider_providerId 이고, 둘 다 그 사람을 특정한다.
        //    다른 경로(보안 알림·기기 인식)가 userId 만 남기는 것과 기준을 맞춘다.
        //    배송지 저장은 자주 일어나므로 INFO 한 줄이 그대로 쌓인다.
        log.debug("배송지 저장: userId={}", user.getUserId());

        // 매핑은 ShippingAddress.from 하나뿐이다 — 예전에는 빌더로 채운 직후 deepCopy 가
        // 같은 필드를 다시 덮었다(ShippingAddress.from 주석 참조).
        ShippingAddress address = ShippingAddress.from(request.getAddress());

        address.setUser(user);

        address = shippingAddressRepository.save(address);
        log.debug("배송지 저장 완료: addressId={}", address.getShippingAddressId());

        // 양방향 연관을 메모리에서도 맞춰 둔다. 소유 측은 address.user 라 DB 에는 이미
        // 반영됐고, 이 줄은 같은 트랜잭션 안에서 user 를 다시 읽는 코드가 방금 만든
        // 주소를 보게 하려는 것이다.
        user.getShippingAddresses().add(address);

        // 🧹 여기 있던 `userRepository.save(user); // prevent java.stackoverflow` 를 지웠다.
        //    주석의 설명은 사실이 아니다. user 는 바로 위에서 findByUsername 으로 읽은
        //    **영속 상태 엔티티**이고 이 클래스는 @Transactional 이라, 변경은 커밋 시점에
        //    더티 체킹으로 반영된다. save 는 아무 일도 하지 않으면서 SELECT/UPDATE 를
        //    한 번 더 유발할 뿐이다. 스택오버플로와는 아무 관계가 없다 — 그건 양방향
        //    연관을 toString/직렬화할 때 나는 증상이고, 그 방어는 @JsonBackReference 가
        //    이미 하고 있다.

        AddressDto result = new AddressDto();
        AddressDto.deepCopyShippingAddressDto(result, address);

        return result;
    }

    /**
     * 기본 배송지를 고른다.
     *
     * <p>🔴 <b>예전에는 두 가지가 잘못돼 있었다.</b>
     *
     * <ol>
     *   <li>현재 기본 배송지를 {@code findByActive(true)} 로 찾았다 — {@code user_id} 조건이
     *       없는 <b>전 테이블 조회</b>다. 사용자가 둘 이상 기본 배송지를 가지면 Optional
     *       쿼리가 두 행을 만나 {@code IncorrectResultSizeDataAccessException} → 400 이 되고,
     *       그 전이라도 전체에 active 가 하나뿐일 때 그것이 남의 것이면 <b>남의 기본
     *       배송지를 꺼 버렸다.</b></li>
     *   <li>사용자의 배송지를 전부 불러 자바에서 반복하며 id 를 대조했다. 배송지가 N 개면
     *       N 개를 다 읽는다. 쿼리 하나로 충분하다.</li>
     * </ol>
     */
    public AddressDto selectShipAddress(String username, String addressId) {
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            log.warn("selectShipAddress: 사용자를 찾을 수 없다");
            return null;
        }

        Long targetId = parseAddressId(addressId);
        if (targetId == null) {
            return null;
        }

        // 내 것 중에서 찾는다. 소유권 확인이 쿼리에 들어 있으므로 빠뜨릴 수가 없다.
        ShippingAddress address = shippingAddressRepository
                .findByShippingAddressIdAndUser_UserId(targetId, user.getUserId())
                .orElse(null);

        if (address == null) {
            log.warn("selectShipAddress: 본인의 배송지가 아니거나 존재하지 않는다");
            return null;
        }

        // 이 사용자의 현재 기본 배송지만 끈다.
        shippingAddressRepository.findByUser_UserIdAndActiveTrue(user.getUserId())
                .filter(current -> !Objects.equals(current.getShippingAddressId(), targetId))
                .ifPresent(current -> {
                    current.setActive(false);
                    shippingAddressRepository.save(current);
                });

        address.setActive(true);
        address = shippingAddressRepository.save(address);

        AddressDto dto = new AddressDto();
        AddressDto.deepCopyShippingAddressDto(dto, address);
        return dto;
    }

    /**
     * 주소 id 파싱.
     *
     * <p>예전에는 {@code Long.parseLong(addressId)} 를 그대로 불렀다. 숫자가 아닌 값이 오면
     * {@code NumberFormatException} 이 그대로 올라가 500 이 된다 — 잘못된 요청은 400 이다.
     * (컨트롤러가 RuntimeException 을 400 으로 바꾸기는 하지만, 메시지가
     * "For input string: ..." 이라 무엇이 문제인지 알 수 없다.)
     */
    private Long parseAddressId(String addressId) {
        try {
            return Long.parseLong(addressId);
        } catch (NumberFormatException e) {
            log.warn("주소 id 가 숫자가 아니다");
            return null;
        }
    }

    @Transactional(readOnly = true)
    public List<AddressDto> getShipAddresses(String username) {
        // 🔒 username 은 로그에 남기지 않는다 — 사용자를 특정하는 값이다.
        //    필요한 것은 "누구의 주소를 몇 건 읽었나" 이고, 그건 userId 로 충분하다.
        List<ShippingAddress> addresses = new ArrayList<>();

        // try-catch 블록을 추가하여 예외를 처리
        try {
            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // Lazy 로딩 문제 해결 발생
            //addresses = shippingAddressRepository.findByUser_UserId(user.getUserId());
            // JOIN FETCH를 사용하여 User와 ShippingAddress를 함께 로드
            addresses = shippingAddressRepository.findByUser_UserIdWithUser(user.getUserId());

            log.debug("배송지 조회: userId={} count={}", user.getUserId(), addresses.size());
        } catch (RuntimeException e) {
            // 없는 사용자는 빈 목록이다. 이메일·username 을 남기면 "그 계정이 있는지"가
            // 로그로 새어 나간다.
            log.warn("배송지 조회: 사용자를 찾지 못해 빈 목록을 돌려준다");
            return Collections.emptyList();
        }

        // 스트림 API를 사용하여 변환 로직을 간결하게 작성
        List<AddressDto> addressDtos = addresses.stream()
                .map(address -> {
                    AddressDto dto = new AddressDto();
                    AddressDto.deepCopyShippingAddressDto(dto, address);
                    return dto;
                })
                .collect(Collectors.toList());

        return addressDtos;
    }

    /**
     * 배송지를 고친다 — <b>본인 것만</b>, 그리고 기본 배송지 여부는 그대로.
     *
     * <p>지금까지 주소는 등록과 삭제만 됐다. 나라 하나를 잘못 골랐으면(목록에서 두 한국이
     * 나란히 있어 실제로 그랬다, docs/25 §11.9) 지우고 다시 넣어야 했고, 그러면 기본 배송지
     * 표시도 함께 사라졌다.
     *
     * <p>소유권 판정은 삭제와 같다 — 내 것 중에서 찾고, 없으면 남의 것인지 없는 것인지
     * 구분해 말하지 않는다. 값은 {@link ShippingAddress#applyFrom} 이 옮긴다(필드 목록은 거기
     * 하나뿐이다). {@code active} 는 옮기지 않는다 — 그것은 {@link #selectShipAddress} 의 일이다.
     *
     * <p>과거 주문의 배송지는 바뀌지 않는다. 주문은 주문 시점의 주소를 복사해 갖는다.
     */
    public AddressDto updateShippingAddress(String username, String addressId, AddressDto changes) {
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            log.warn("updateShippingAddress: 사용자를 찾을 수 없다");
            throw new AccessDeniedException("본인의 배송지가 아닙니다.");
        }

        Long targetId = parseAddressId(addressId);
        if (targetId == null) {
            throw new IllegalArgumentException("주소 id 가 올바르지 않습니다.");
        }

        ShippingAddress target = shippingAddressRepository
                .findByShippingAddressIdAndUser_UserId(targetId, user.getUserId())
                .orElse(null);
        if (target == null) {
            log.warn("updateShippingAddress: 본인의 배송지가 아니거나 존재하지 않는다");
            throw new AccessDeniedException("본인의 배송지가 아닙니다.");
        }

        target.applyFrom(changes);
        target = shippingAddressRepository.save(target);
        log.debug("배송지 수정: userId={} addressId={}", user.getUserId(), targetId);

        AddressDto dto = new AddressDto();
        AddressDto.deepCopyShippingAddressDto(dto, target);
        return dto;
    }

    /**
     * 배송지를 지운다.
     *
     * <p>🔴 <b>예전에는 소유권 검사가 없었다(IDOR).</b>
     *
     * <pre>
     *   Optional&lt;User&gt; user = userRepository.findByUsername(username);
     *   if (!user.isPresent()) return;
     *
     *   Optional&lt;ShippingAddress&gt; deleteAddress =
     *           shippingAddressRepository.findById(Long.parseLong(addressId));
     *   if (deleteAddress.isPresent()) {
     *       user.get().getShippingAddresses().remove(deleteAddress.get());  // 남의 것이면 무동작
     *       shippingAddressRepository.delete(deleteAddress.get());          // ← 그래도 지운다
     *   }
     * </pre>
     *
     * 사용자를 조회는 하지만 <b>그 주소가 이 사용자 것인지 대조하지 않았다.</b> 컬렉션에서
     * 빼는 {@code remove} 는 남의 것이면 조용히 지나가고, 그 다음 줄의 {@code delete} 가
     * 그냥 실행된다. 로그인한 아무나 주소 id 하나만 알면 남의 배송지를 지울 수 있었다.
     *
     * <p>TSID 라 id 추측이 어렵다는 것은 완화 요인이지 대책이 아니다. 같은 파일의
     * {@code selectShipAddress} 는 (방식은 비효율적이었지만) 소유권을 확인하고 있었다 —
     * 삭제만 빠져 있었다.
     *
     * <p>주소를 지워도 <b>과거 주문의 배송지는 바뀌지 않는다.</b> 주문은 주문 시점의 주소를
     * {@code OrderAddress} 로 복사해 갖는다(order-service OrderDataService).
     */
    public void deleteShippingAddress(String username, String addressId) {
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            log.warn("deleteShippingAddress: 사용자를 찾을 수 없다");
            return;
        }

        Long targetId = parseAddressId(addressId);
        if (targetId == null) {
            return;
        }

        // 내 것 중에서 찾는다 — 없으면 남의 것이거나 존재하지 않는다. 둘을 구분해
        // 알려 주지 않는다(존재 여부가 새면 그 자체가 정보다).
        ShippingAddress target = shippingAddressRepository
                .findByShippingAddressIdAndUser_UserId(targetId, user.getUserId())
                .orElse(null);

        if (target == null) {
            log.warn("deleteShippingAddress: 본인의 배송지가 아니거나 존재하지 않는다");
            throw new AccessDeniedException("본인의 배송지가 아닙니다.");
        }

        user.getShippingAddresses().remove(target);
        shippingAddressRepository.delete(target);
    }
}