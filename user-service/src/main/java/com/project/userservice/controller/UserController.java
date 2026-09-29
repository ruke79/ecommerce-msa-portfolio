package com.project.userservice.controller;

import com.project.common.exception.ClientMessage;

import java.io.IOException;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.project.common.constants.StatusMessages;
import com.project.common.response.MessageResponse;
import com.project.userservice.dto.AddressDto;
import com.project.userservice.dto.UserProfileDto;
import com.project.userservice.model.User;
import com.project.userservice.security.request.AddressRequest;
import com.project.userservice.security.request.PasswordRequest;
import com.project.userservice.security.request.ProfileUpdateRequest;
import com.project.userservice.security.service.UserDetailsImpl;
import com.project.common.util.FileUtil;
import com.project.userservice.service.AddressService;
import com.project.userservice.service.ImageService;
import com.project.userservice.service.impl.UserServiceImpl;

import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestPart;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/user/profile")
public class UserController {

    private final UserServiceImpl userService;

    private final AddressService addressService;

    private final ImageService imageService;

    
    // ── 내 프로필 ────────────────────────────────────────────────────────────
    //
    // 🐛 지금까지 **프로필을 돌려주는 GET 이 없었다.** 이 컨트롤러의 엔드포인트는
    //    /address · /save_address · /select · /delete · /payment · /changepm ·
    //    /update-password 가 전부였다. 그래서 화면은 JWT 페이로드로 사용자 객체를
    //    만들어 썼고(util/jwtUser.js), 거기에 없는 name·image 를 읽어 화면 곳곳에
    //    undefined 가 찍혔다. `/profile` 본문은 아예 만들어진 적이 없다.

    /** 내 프로필. 신원은 게이트웨이가 검증한 토큰이 정한다. */
    @GetMapping("/me")
    ResponseEntity<?> getMyProfile(@AuthenticationPrincipal UserDetails userDetails) {
        if (null == userDetails) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse(StatusMessages.USER_NOT_FOUND));
        }
        try {
            return ResponseEntity.ok(userService.getMyProfile(userDetails.getUsername()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new MessageResponse(ClientMessage.of(e)));
        }
    }

    // ── 로그인 기기 ──────────────────────────────────────────────────────
    //
    // 보안 알림("새로운 기기에서 로그인되었습니다")을 받은 사용자가 갈 곳이다. 알림만 있고
    // 확인할 화면이 없으면 "본인이 아니면 비밀번호를 바꾸세요" 밖에 할 수 있는 일이 없다.

    /**
     * 내가 로그인한 적 있는 기기들.
     *
     * <p>🔒 신원은 게이트웨이가 검증한 토큰이 정한다. 사용자 id 를 파라미터로 받으면
     * 남의 기기 목록을 볼 수 있다 — 이 저장소가 여러 번 겪은 형태다.
     */
    @GetMapping("/devices")
    ResponseEntity<?> myDevices(@AuthenticationPrincipal UserDetails userDetails) {
        if (null == userDetails) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse(StatusMessages.USER_NOT_FOUND));
        }
        try {
            return ResponseEntity.ok(userService.myDevices(userDetails.getUsername()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new MessageResponse(ClientMessage.of(e)));
        }
    }

    /**
     * 내 등급과 그 근거.
     *
     * <p>🔒 본인 것만 본다 — 신원은 게이트웨이가 검증한 토큰에서만 온다. 등급은 그 사람의
     * <b>소비 규모</b>를 그대로 드러내는 값이라 남이 보면 안 된다.
     *
     * <p>화면 설계는 {@link com.project.userservice.dto.TierStatusDto} 주석에 적었다 —
     * 요점은 <b>"다음 등급까지 얼마"를 주지 않는다</b>는 것이다.
     */
    @GetMapping("/tier")
    ResponseEntity<?> myTier(@AuthenticationPrincipal UserDetails userDetails) {
        if (null == userDetails) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse(StatusMessages.USER_NOT_FOUND));
        }
        try {
            return ResponseEntity.ok(userService.myTier(userDetails.getUsername()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new MessageResponse(ClientMessage.of(e)));
        }
    }

    /**
     * 기기 기억을 지운다.
     *
     * <p>⚠️ <b>세션을 끊지는 않는다.</b> 그 기기에 로그인된 상태가 있으면 그대로 남는다 —
     * 이 동작의 뜻은 "다음에 그 기기에서 로그인하면 새 기기로 보고 알린다" 뿐이다.
     * 화면 문구도 그렇게 적어야 한다. "로그아웃시킨다"로 읽히면 사용자는 조치를 했다고
     * 믿고 더 이상 아무것도 하지 않는다 — 진짜로 필요한 것은 비밀번호 변경이다.
     */
    @DeleteMapping("/devices/{deviceId}")
    ResponseEntity<?> forgetDevice(@PathVariable("deviceId") Long deviceId,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (null == userDetails) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse(StatusMessages.USER_NOT_FOUND));
        }
        // 남의 기기면 404 다 — 403 을 주면 "그 번호의 기기는 존재한다"가 새어 나간다.
        return userService.forgetDevice(userDetails.getUsername(), deviceId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new MessageResponse("기기를 찾을 수 없습니다."));
    }

    /** 표시 이름 변경. 이메일·로그인 id 는 바꾸지 않는다(UserServiceImpl 주석 참조). */
    @PutMapping("/me")
    ResponseEntity<?> updateMyProfile(@Valid @RequestBody ProfileUpdateRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (null == userDetails) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse(StatusMessages.USER_NOT_FOUND));
        }
        try {
            return ResponseEntity.ok(
                    userService.updateMyProfile(userDetails.getUsername(), request.getName()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new MessageResponse(ClientMessage.of(e)));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new MessageResponse(ClientMessage.of(e)));
        }
    }

    /**
     * 프로필 이미지 업로드.
     *
     * <p>키에 {@code images/} 접두사를 붙인다. 버킷의 공개 읽기 정책이
     * {@code arn:aws:s3:::<버킷>/images/*} 에만 걸려 있어서(ImageService 주석), 접두사
     * 없이 올리면 <b>업로드는 성공하는데 브라우저에서 403</b> 이 된다.
     *
     * <p>{@code FileUtil.getFilePath} 가 확장자를 검사한다 — 허용 목록에 없는 형식이면
     * 던진다. 업로드 경로에서 파일 형식을 믿으면 안 되기 때문이다.
     */
    @PostMapping("/me/image")
    ResponseEntity<?> uploadMyProfileImage(@RequestPart("image") MultipartFile image,
            @AuthenticationPrincipal UserDetails userDetails) {
        if (null == userDetails) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse(StatusMessages.USER_NOT_FOUND));
        }
        if (image == null || image.isEmpty()) {
            return ResponseEntity.badRequest().body(new MessageResponse("이미지를 선택해 주세요."));
        }
        if (image.getSize() > MAX_PROFILE_IMAGE_BYTES) {
            // 톰캣의 multipart 상한에 걸리면 메시지가 스택트레이스로 나간다. 먼저 막는다.
            return ResponseEntity.badRequest()
                    .body(new MessageResponse("이미지는 5MB까지 올릴 수 있습니다."));
        }

        try {
            // 접두사를 명시한다. FileUtil 의 기본값은 "image"(단수)라 정책과 어긋난다.
            String path = FileUtil.getFilePath(
                    image, "profile/" + FileUtil.getRandomFilename(), "images");
            String url = imageService.upload(image, path);

            return ResponseEntity.ok(
                    userService.updateMyProfileImage(userDetails.getUsername(), url));

        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new MessageResponse("이미지를 저장하지 못했습니다. 잠시 후 다시 시도해 주세요."));
        } catch (RuntimeException e) {
            // 확장자 검사 실패(FileUtil) 등.
            return ResponseEntity.badRequest().body(new MessageResponse(ClientMessage.of(e)));
        }
    }

    /** 프로필 이미지 삭제 — 기본 아바타(이니셜)로 돌아간다. */
    @DeleteMapping("/me/image")
    ResponseEntity<?> deleteMyProfileImage(@AuthenticationPrincipal UserDetails userDetails) {
        if (null == userDetails) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse(StatusMessages.USER_NOT_FOUND));
        }
        // S3 객체는 지우지 않는다. 같은 URL 을 다른 곳에서 참조하고 있을 수 있고(과거
        // 리뷰 등), 정리는 별도 배치의 일이다. 프로필에서 참조만 끊는다.
        return ResponseEntity.ok(userService.updateMyProfileImage(userDetails.getUsername(), null));
    }

    /** 프로필 이미지 최대 크기. */
    static final long MAX_PROFILE_IMAGE_BYTES = 5L * 1024 * 1024;

    @GetMapping("/address")
    ResponseEntity<?> getUserAddresses(@AuthenticationPrincipal UserDetails userDetails) {

        if (null != userDetails) {

            try {
                                
                List<AddressDto> repsonse = addressService.getShipAddresses(userDetails.getUsername());

                return new ResponseEntity<>(repsonse, HttpStatus.OK);
            } catch (RuntimeException e) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(new MessageResponse(StatusMessages.GET_USERINFO_FAILED));
            }
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(StatusMessages.USER_NOT_FOUND);
        }

    }

    @PostMapping("/save_address")
    ResponseEntity<?> saveShippingAddress(@jakarta.validation.Valid @RequestBody AddressRequest request,
    @AuthenticationPrincipal UserDetails userDetails) {

        if (null != userDetails) {
            String username = userDetails.getUsername();

            try {
                AddressDto response = addressService.saveShippingAddress(request, username);

                return new ResponseEntity<>(response, HttpStatus.OK);
            } catch (RuntimeException e) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(new MessageResponse(ClientMessage.of(e)));
            }
        } else {
            return new ResponseEntity<>(StatusMessages.USER_NOT_FOUND, HttpStatus.UNAUTHORIZED);
        }
    }

    @GetMapping("/select/{addressId}")
    ResponseEntity<?> selectShippingAddress(@PathVariable("addressId") String addressId,
    @AuthenticationPrincipal UserDetails userDetails) {

        if (null != userDetails) {

            String username = userDetails.getUsername();

            try {
                return new ResponseEntity<>(addressService.selectShipAddress(username, addressId), HttpStatus.OK);
            } catch (RuntimeException e) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(new MessageResponse(ClientMessage.of(e)));
            }
        } else {
            return new ResponseEntity<>(StatusMessages.USER_NOT_FOUND, HttpStatus.UNAUTHORIZED);
        }
    }

    /** 배송지 수정. 본인 것만 — 남의 것이면 403, 잘못된 id 면 400. 기본 배송지 여부는 바뀌지 않는다. */
    @PutMapping("/address/{addressId}")
    ResponseEntity<?> updateShippingAddress(@PathVariable("addressId") String addressId,
            @jakarta.validation.Valid @RequestBody AddressRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {

        if (null == userDetails) {
            return new ResponseEntity<>(StatusMessages.USER_NOT_FOUND, HttpStatus.UNAUTHORIZED);
        }
        try {
            return ResponseEntity.ok(addressService.updateShippingAddress(
                    userDetails.getUsername(), addressId, request.getAddress()));
        } catch (org.springframework.security.access.AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new MessageResponse(ClientMessage.of(e)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new MessageResponse(ClientMessage.of(e)));
        }
    }

    @DeleteMapping("/delete/{addressId}")
    ResponseEntity<?> deleteShippingAddresses(@PathVariable("addressId") String addressId,
    @AuthenticationPrincipal UserDetails userDetails) {

        if (null != userDetails) {
            try {
                String username = userDetails.getUsername();
                addressService.deleteShippingAddress(username, addressId);            
                return new ResponseEntity<>(null, HttpStatus.OK);
            } catch (RuntimeException e) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(new MessageResponse(ClientMessage.of(e)));
            }
        } else {
            return new ResponseEntity<>(StatusMessages.USER_NOT_FOUND, HttpStatus.UNAUTHORIZED);
        }
    }


    @GetMapping("/payment")
    ResponseEntity<?> getUserInfoWithPayment(@AuthenticationPrincipal UserDetails userDetails) {

        if (null != userDetails) {

            try {

                User user = userService.findByUsername(userDetails.getUsername());

                UserProfileDto repsonse = userService.findUserWithdefaultPaymentMethod(user);

                return new ResponseEntity<>(repsonse, HttpStatus.OK);
            } catch (RuntimeException e) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(new MessageResponse(StatusMessages.GET_USERINFO_FAILED));
            }
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(StatusMessages.USER_NOT_FOUND);
        }

    }

    @PutMapping("/changepm")
    ResponseEntity<?> changePayment(@RequestParam("paymentMethod") String paymentMethod,
    @AuthenticationPrincipal UserDetails userDetails) {

        if (null != userDetails) {
            String username = userDetails.getUsername();

            try {
                return new ResponseEntity<>(userService.updatePaymentMethod(username, paymentMethod), HttpStatus.OK);
            } catch (RuntimeException e) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(new MessageResponse(ClientMessage.of(e)));
            }
        } else {
            return new ResponseEntity<>(StatusMessages.USER_NOT_FOUND, HttpStatus.UNAUTHORIZED);
        }
    }

    
    

    /**
     * 로그인 상태에서 비밀번호를 바꾼다.
     *
     * <p>🐛 예전에는 <b>현재 비밀번호가 틀려도 {@code 200 "Password updated"}</b> 였다.
     * 서비스가 조건문 안에서만 저장하고 else 없이 빠져나갔는데 반환형이 void 라, 여기서는
     * 성공과 구분할 방법이 없었다. 이제 서비스가 예외를 던지고 그것이 400 이 된다.
     *
     * <p>🐛 응답이 <b>맨 문자열</b>이라 화면의 {@code data.message} 가 언제나 undefined 였다
     * — 바꾸는 데 성공해도 성공 문구가 빈칸으로 떴다. JSON 으로 바꾼다.
     */
    @PutMapping("/update-password")
    public ResponseEntity<?> updatePassword(@RequestBody PasswordRequest request,
            @AuthenticationPrincipal UserDetails userDetails) {

        if (null == userDetails) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse(StatusMessages.USER_NOT_FOUND));
        }

        try {
            UserDetailsImpl user = (UserDetailsImpl) userDetails;
            userService.updatePassword(user.getId(), request.getCurrent_password(),
                    request.getNew_password());
            return ResponseEntity.ok(new MessageResponse(
                    "비밀번호가 변경되었습니다. 다른 기기에서는 다시 로그인해야 합니다."));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(ClientMessage.of(e)));
        }
    }


}
