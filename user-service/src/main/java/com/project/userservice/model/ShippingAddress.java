
package com.project.userservice.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.project.userservice.dto.AddressDto;

import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@Entity
@SuperBuilder
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED) 
/*
 * 인덱스는 Flyway 가 소유한다(V7). 여기에 @Index 를 적지 않는 이유:
 *
 *   · 예전에는 `@Index(columnList = "firstname, lastname, address1, city, state, zipCode")`
 *     가 있었다. 6개 컬럼짜리 복합 인덱스인데 **이 인덱스를 타는 쿼리가 하나도 없었다** —
 *     조회는 전부 user_id 또는 pk 로 한다. 하는 일이라고는 쓰기마다 인덱스를 갱신하고,
 *     실명 + 전체 주소를 정렬된 형태로 한 벌 더 복제해 두는 것뿐이었다. DB 덤프가
 *     유출되면 "이 사람 주소 찾기" 가 인덱스 스캔 한 번이 된다.
 *
 *   · 그리고 진짜 필요한 것(사용자당 기본 배송지 하나를 강제하는 **부분** 유니크 인덱스)은
 *     JPA 애너테이션으로 표현할 수 없다. 인덱스 정의가 두 곳으로 갈리느니 한 곳에 둔다.
 *
 * Hibernate 의 ddl-auto: validate 는 인덱스를 보지 않으므로 이 차이는 기동을 막지 않는다.
 */
@Table(name="shipping_address", schema="users")
public class ShippingAddress extends BaseEntity {

    
     @Id @Tsid    
    @Column(name = "shipping_address_id")
    private Long shippingAddressId;


    private String firstname;

    private String lastname;

    @NotBlank(message="주소는 공백이 없어야 합니다.")
    @Size(min=2, message="주소1은 적어도 2자 이상이어야 합니다.")
    private String address1;

    private String address2;

    @NotBlank(message="도시명은 공백이 없어야 합니다.")
    @Size(min=2, message="도시명은 적어도 2자 이상이어야 합니다.")
    private String city;

    @NotBlank(message="도명은 공백이 없어야 합니다.")
    @Size(min=2, message="도명 적어도 2자 이상이어야 합니다.")
    private String state;

    @NotBlank(message="우편번호는 공백이 없어야 합니다.")
    //@Pattern(regexp = "(^$|[0-9]{5})", message="우편번호는 5자리 숫자이어야 합니다.")
    @Size(min=2, message="우편번호는 적어도 2자 이상이어야 합니다.")
    private String zipCode;
    
    private String country;

    private String phoneNumber;

    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="user_id", referencedColumnName = "user_id", nullable=false)
    @JsonBackReference
    private User user;    

    @Builder.Default
    private boolean active = false;
    
    // @OneToMany(mappedBy = "shippingAddress", fetch = FetchType.LAZY,
    // cascade = CascadeType.PERSIST,targetEntity = Order.class)
    // private List<Order> orders;

    /**
     * DTO 로부터 새 배송지를 만든다 — <b>매핑이 있는 유일한 자리</b>다.
     *
     * <h3>🐛 예전에는 같은 매핑이 두 벌이었다</h3>
     *
     * <p>{@code AddressService.saveShippingAddress} 가 빌더로 전 필드를 채운 <b>직후</b>
     * {@code deepCopyShippingAddress(address, src)} 로 같은 필드를 다시 덮었다. 순수한
     * 중복인데, 중복으로 끝나지 않는다 — 나중에 빌더에만 필드를 추가하면
     * <b>deepCopy 가 그것을 null 로 덮는다.</b> 컴파일도 되고 테스트도 그 필드를 보지
     * 않으면 통과한다. 새 주소 필드 하나가 조용히 사라지는 모양이다.
     *
     * <p>둘 중 하나를 지우는 것으로는 부족했다. 지우는 쪽을 잘못 고르면 다음 사람이
     * 다시 짝을 맞춰 놓는다. 매핑을 <b>여기 하나로</b> 모으고 서비스는 이것만 부른다.
     */
    public static ShippingAddress from(AddressDto src) {
        ShippingAddress address = ShippingAddress.builder()
                .active(src.isActive())
                .build();
        address.applyFrom(src);
        return address;
    }

    /**
     * 주소 값을 DTO 에서 덮어쓴다 — <b>수정</b>이 쓴다. {@link #from} 도 이것을 부른다.
     *
     * <p>필드 목록은 여기 한 곳뿐이다. 수정 경로를 따로 나열하면 {@code from} 과 짝을 맞춰야
     * 하고, 한쪽만 고치는 날 새 항목이 수정에서 조용히 빠진다 — 예전 deepCopy 가 그 모양이었다.
     *
     * <p>{@code active}(기본 배송지) 는 <b>건드리지 않는다.</b> 그것은 주소의 내용이 아니라
     * 이 사용자의 선택이고, 바꾸는 길은 {@code selectShipAddress} 하나다. 수정 폼이 보낸
     * 값으로 기본 배송지가 슬쩍 바뀌면 다음 주문이 엉뚱한 곳으로 간다.
     */
    public void applyFrom(AddressDto src) {
        this.address1 = src.getAddress1();
        this.address2 = src.getAddress2();
        this.city = src.getCity();
        this.state = src.getState();
        this.country = src.getCountry();
        this.firstname = src.getFirstname();
        this.lastname = src.getLastname();
        this.phoneNumber = src.getPhoneNumber();
        this.zipCode = src.getZipCode();
    }

           
}
