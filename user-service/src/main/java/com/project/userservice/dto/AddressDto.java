package com.project.userservice.dto;

import com.project.userservice.model.ShippingAddress;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AddressDto {

    private String id;

    private String firstname;

    private String lastname;

    /**
     * 🐛 검증이 <b>엔티티</b>에만 있었다(ShippingAddress 의 @NotBlank/@Size). 그래서 잘못된
     * 입력이 컨트롤러를 그대로 통과해 Hibernate 의 flush 시점까지 갔고, 터질 때는
     * ConstraintViolationException 의 덤프가 사용자에게 그대로 전달됐다.
     * 검증은 경계에서 한다.
     */
    @NotBlank(message = "주소는 공백이 없어야 합니다.")
    @Size(min = 2, message = "주소1은 적어도 2자 이상이어야 합니다.")
    private String address1;

    private String address2;

    @NotBlank(message = "도시명은 공백이 없어야 합니다.")
    @Size(min = 2, message = "도시명은 적어도 2자 이상이어야 합니다.")
    private String city;

    @NotBlank(message = "도명은 공백이 없어야 합니다.")
    @Size(min = 2, message = "도명은 적어도 2자 이상이어야 합니다.")
    private String state;

    @NotBlank(message = "우편번호는 공백이 없어야 합니다.")
    @Size(min = 2, message = "우편번호는 적어도 2자 이상이어야 합니다.")
    private String zipCode;
    
    private String country;

    private String phoneNumber;

    private boolean active;


    public static void deepCopyShippingAddressDto(AddressDto address, ShippingAddress src ) {

        address.setId(Long.toString(src.getShippingAddressId()));
        address.setAddress1(src.getAddress1());
        address.setAddress2(src.getAddress2());
        address.setCity(src.getCity());
        address.setState(src.getState());
        address.setCountry(src.getCountry());
        address.setFirstname(src.getFirstname());
        address.setLastname(src.getLastname());
        address.setPhoneNumber(src.getPhoneNumber());
        address.setZipCode(src.getZipCode());
        address.setActive(src.isActive());
    }

    /** 우편번호 · 전화번호는 받을 때 NFKC — IME 의 전각 `１２３－４５６７` 이 `123-4567` 로(이름 · 주소는 손님이 쓴 그대로). */
    // [JA] 郵便番号 · 電話番号は受け取り時に NFKC — IME の全角数字を半角に(名前 · 住所は客が書いたまま)。
    public void setZipCode(String zipCode) {
        this.zipCode = com.project.common.text.Nfkc.of(zipCode);
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = com.project.common.text.Nfkc.of(phoneNumber);
    }
}
