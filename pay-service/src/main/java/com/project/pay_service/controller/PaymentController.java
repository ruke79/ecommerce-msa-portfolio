// package com.project.pay_service.controller;

// import java.io.IOException;

// import lombok.RequiredArgsConstructor;
// import org.springframework.beans.factory.annotation.Autowired;
// import org.springframework.beans.factory.annotation.Value;
// import org.springframework.http.HttpStatus;
// import org.springframework.http.ResponseEntity;

// import org.springframework.web.bind.annotation.PathVariable;
// import org.springframework.web.bind.annotation.PostMapping;
// import org.springframework.web.bind.annotation.PutMapping;
// import org.springframework.web.bind.annotation.RequestBody;
// import org.springframework.web.bind.annotation.RequestHeader;
// import org.springframework.web.bind.annotation.RequestMapping;
// import org.springframework.web.bind.annotation.RestController;

// import com.project.common.dto.request.PaypalPaymentRequest;
// import com.project.pay_service.service.PaymentService;


// import jakarta.annotation.PostConstruct;
// import lombok.extern.slf4j.Slf4j;

// @Slf4j
// @RestController
// @RequestMapping("api/pay/")
// @RequiredArgsConstructor
// public class PaymentController {

// //    @Value("${imp_key}")
// //    private String restApiKey;
// //    @Value("${imp_secret}")
// //    private String restApiSecret;

//     //private IamportClient iamportClient;

    
//     private final PaymentService paymentService;  
//     //private final RefundService refundService;




// //     @PostMapping("/confirm")
// //     ResponseEntity<?> processPayment(@RequestBody PaypalPaymentRequest request  ) throws IOException {
        
// //         try {            
// //             return ResponseEntity.ok(paymentService.processPayment(request));

// //         }
// //         catch ( RuntimeException e) {
            
// // //            String token = refundService.getToken(restApiKey, restApiSecret);
// // //            refundService.refundRequest(token, request.getOrderNumber(), e.getMessage());
// //             return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);           

// //         }
        
// //     }

// //     @PostMapping("/confirm")
// //     ResponseEntity<?> processPayment(@RequestBody PaypalPaymentRequest request  ) throws IOException {
        
// //         try {            
// //             return ResponseEntity.ok(paymentService.processPayment(request));

// //         }
// //         catch ( RuntimeException e) {
            
// // //            String token = refundService.getToken(restApiKey, restApiSecret);
// // //            refundService.refundRequest(token, request.getOrderNumber(), e.getMessage());
// //             return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);           

// //         }
        
// //     }

//     // @PostConstruct
//     // public void init() {
//     //     this.iamportClient = new IamportClient(restApiKey, restApiSecret);
//     // }

//     //     @PostMapping("/verifyIamport/{imp_uid}")
//     //     public IamportResponse<Payment> paymentByImpUid (@PathVariable("imp_uid") String imp_uid) throws
//     //     IamportResponseException, IOException {
//     //         return iamportClient.paymentByImpUid(imp_uid);
//     //     }

// }
