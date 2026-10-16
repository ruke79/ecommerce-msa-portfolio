package com.project.userservice.controller;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.ModelAndView;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.project.common.message.dto.request.UserCreatedRequest;
import com.project.userservice.message.producer.UserCreatedProducer;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.RoleRepository;
import com.project.userservice.repository.jpa.UserRepository;
import com.project.userservice.security.jwt.JwtUtils;
import com.project.userservice.security.response.LoginResponse;
import com.project.userservice.security.service.UserDetailsImpl;
import com.project.userservice.security.service.UserDetailsServiceImpl;
import com.project.userservice.security.util.VerificationResult;
import com.project.userservice.service.UserService;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
//@RestController
@Controller
@RequiredArgsConstructor
public class RegistrationController {

     
      
    private final UserService userService;
         

    @Value("${frontend.url}")
    private String frontendUrl;


    /**
     * 메일의 확인 링크가 도착하는 곳.
     *
     * <p>🐛 <b>예전에는 확인에 실패하면 계정을 지웠다.</b> {@code userService.deleteUser(...)}
     * 가 실패 경로에 그대로 있었다. 문제가 겹겹이었다:
     *
     * <ul>
     *   <li>토큰 유효기간이 3분이었으므로(VerificationToken 참조) <b>대부분의 사용자가</b>
     *       링크를 눌렀을 때 이미 만료였고, 그 결과 <b>가입한 계정이 삭제됐다.</b> 사용자는
     *       처음부터 다시 가입해야 했다.</li>
     *   <li>실패 시 {@code validateAndGetUser} 는 user 자리에 {@code null} 을 넣어 줬으므로
     *       {@code deleteUser(null)} 이 호출됐다. 그 안에서 비밀번호 재설정 토큰을
     *       {@code orElseThrow} 로 꺼내다 예외가 나 <b>500 으로 끝났다</b>. 계정이 지워지지
     *       않은 건 순전히 그 두 번째 버그 덕분이었다.</li>
     *   <li>메일 스캐너나 브라우저 프리페치가 링크를 대신 열어도 같은 경로를 탔다.</li>
     * </ul>
     *
     * <p>확인 실패는 계정을 지울 이유가 되지 않는다. 계정은 <b>미인증 상태로 두고</b> 재발송을
     * 안내한다. 미인증 계정 정리가 필요하면 별도 배치가 할 일이다.
     *
     * <p>이미 인증된 계정으로 다시 들어와도 성공으로 처리한다(멱등). 사용자가 링크를 두 번
     * 누르거나 메일 클라이언트가 미리 열어 보는 일은 흔한데, 그때 오류를 띄우면 멀쩡히
     * 가입한 사람이 실패한 줄 안다.
     */
    // 메일 링크는 {공개 주소}/user-service/registrationConfirm — 게이트웨이(user-service-public)가 /user-service 를 떼어 /registrationConfirm
    // 으로 보낸다. 🐛 예전에는 /user-service/registrationConfirm 만 받아, 게이트웨이를 지난 요청은 맞는 핸들러가 없었다(게이트웨이도
    // 그 경로를 404 로 막고 있었다 — RouteMatchingTest). 옛 경로도 남긴다(클러스터 안에서 직접 부르는 경우).
    // [JA] メールのリンクはゲートウェイが /user-service を外して /registrationConfirm で届く。以前は旧経路しか受けなかった。
    @GetMapping({"/registrationConfirm", "/user-service/registrationConfirm"})
    public ModelAndView confirmRegistration(final HttpServletRequest request, final ModelMap model, @RequestParam("token") final String token) throws JsonProcessingException {

        VerificationResult result = userService.validateAndGetUser(token);

        if ("valid".equals(result.getStatus())) {
            userService.registerConfirmed(result.getUser());
            log.info("Account verified: userId={}", result.getUser().getUserId());

            return new ModelAndView("redirect:" + frontendUrl + "/signin?verified=1", model);
        }

        // ⚠️ 토큰 값은 로그에 남기지 않는다 — 계정을 활성화할 수 있는 자격증명이다.
        log.warn("Account verification failed: status={}", result.getStatus());

        // 계정은 그대로 둔다. 프론트가 재발송 화면을 띄울 수 있도록 상태만 넘긴다.
        String reason = "expired".equals(result.getStatus()) ? "expired" : "invalid";
        return new ModelAndView(
                "redirect:" + frontendUrl + "/verification-failed?reason=" + reason, model);

        // final String result = userService.validateVerificationToken(token);        
        // final User user = userService.getUser(token);

        // log.info("User object class: {}", user.getClass().getName()); //proxy class 확인용 로그

        // log.info("Before accessing user's email");
        // String email = user.getEmail();
        // log.info("After accessing user's email: {}", email);
        
        // if ("valid".equals(result)) {
        //     userService.registerConfirmed(user);
        //     log.info("Account verified for user: {}", user.getUsername());
            
        //     // 성공 시 프론트엔드 URL로 직접 리다이렉션
        //     return new ModelAndView("redirect:" + frontendUrl + "/signin", model);
        // }

        // userService.deleteUser(user);
        // log.warn("Account verification failed for token: {}", token);
        
        // // 실패 시 프론트엔드의 다른 URL로 리다이렉션하거나, 실패 메시지를 포함
        // model.addAttribute("messageKey", "auth.message." + result);
        // model.addAttribute("expired", "expired".equals(result));
        // model.addAttribute("token", token);
        // return new ModelAndView("redirect:" + frontendUrl + "/verification-failed", model);



    }

    // Rest API 버전 (프론트엔드와 통신용)
    // 프론트엔드가 이 엔드포인트를 호출하고, JSON 응답을 받도록 구현
    // 프론트엔드에서 리다이렉션 처리    
    // @GetMapping("/registrationConfirm")
    // public ResponseEntity<Map<String, String>> confirmRegistration(final HttpServletRequest request, final ModelMap model, @RequestParam("token") final String token)  throws JsonProcessingException {

    //     //Locale locale = request.getLocale();
    //     //model.addAttribute("lang", locale.getLanguage());
    //     final String result = userService.validateVerificationToken(token);
    //     final User user = userService.getUser(token);
        
    //     Map<String, String> response = new HashMap<>();
                
    //     if ("valid".equals(result)) {
    //         userService.registerConfirmed(user);
    //         log.info("Account verified for user: {}", user.getUsername());

    //         // 성공 응답 반환: 클라이언트에게 성공 메시지와 리다이렉션 URL을 전달
    //         response.put("status", "success");
    //         response.put("message", "message.accountVerified");
    //         response.put("redirectUrl", frontendUrl + "/signin");
    //         return ResponseEntity.ok(response);
    //     }

    //     userService.deleteUser(user);
    //     log.warn("Account verification failed for token: {}", token);

    //     // 실패 응답 반환: 클라이언트에게 실패 메시지를 전달
    //     response.put("status", "failure");
    //     response.put("message", "auth.message." + result);
    //     return ResponseEntity.badRequest().body(response);
    
    // }      
    

}
