package com.project.userservice.security.request;


import com.project.userservice.dto.AddressDto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class AddressRequest {

    // @Valid 가 있어야 중첩된 AddressDto 의 제약까지 검사한다. 없으면 address 필드가
    // null 인지만 보고 그 안은 들여다보지 않는다.
    // [JA] @Valid があってはじめてネストした AddressDto の制約まで検査する。なければ address フィールドが
    //      null かどうかだけを見て、その中は覗かない。
    @NotNull(message = "주소를 입력해 주세요.")
    @Valid
    private AddressDto address;
        
}
