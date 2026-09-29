package com.project.chatserver.dto.response;


import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL) // Dto 를 JSON으로 변환 시 null값인 field 제외
public class ResponseDto {
    private Integer status;
    private Object data;

    public ResponseDto(Integer status) {
        this.status = status;
    }

    public static ResponseDto addStatus(Integer status) {
        return new ResponseDto(status);
    }
    // public static StatusResponseDto addStatus(Integer status, Chat data) {
    //     return new StatusResponseDto(status, data);
    // }

    public static ResponseDto success(){
        return new ResponseDto(200);
    }
    public static ResponseDto success(Object data){
        return new ResponseDto(200, data);
    }
}

