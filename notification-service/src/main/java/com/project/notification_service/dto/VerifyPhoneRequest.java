package com.project.notification_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param phone  사용자가 입력한 번호. 형식은 서버에서 libphonenumber 로 판정한다 —
 *               정규식으로 미리 거르면 유효한 표기까지 막는다(국제 표기, 공백, 하이픈 등).
 *   [JA] ユーザーが入力した番号。形式はサーバーで libphonenumber が判定する — 正規表現で先に弾くと有効な表記(国際表記、空白、ハイフンなど)まで塞ぐ。
 * @param region 국가코드가 없는 입력을 어느 나라로 볼지. "KR" 또는 "JP".
 *   [JA] 国番号のない入力をどの国とみなすか。"KR" または "JP"。
 */
public record VerifyPhoneRequest(
        @NotBlank @Size(max = 30) String phone,
        @Size(max = 2) String region) {
}
