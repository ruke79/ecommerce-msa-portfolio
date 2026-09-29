package com.project.userservice.controller;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.project.common.constants.OperationStatus;
import com.project.common.constants.StatusMessages;
import com.project.common.response.GenericResponse;
import com.project.common.response.MessageResponse;
import com.project.userservice.model.WishItem;

import com.project.userservice.security.request.WishListRequest;
import com.project.userservice.service.WishItemService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("api/user")
@RequiredArgsConstructor
public class WishlistController {

    
    private final WishItemService wishListService;


    @PutMapping("/wishlist")
    ResponseEntity<?> addWishList(@RequestBody WishListRequest request, 
    @AuthenticationPrincipal UserDetails userDetails) {

        if (null != userDetails ) {            
            String username = userDetails.getUsername();

            try {
                if (OperationStatus.OS_SUCCESS == wishListService.addWishList(username, request)) {

                    GenericResponse response = new GenericResponse("Product successfully added to your wishlist");

                    return new ResponseEntity<>(response, HttpStatus.OK);
                 }
                 else {

                    GenericResponse response = new GenericResponse("Product ix already added to your wishlist");
                    return new ResponseEntity<>(response, HttpStatus.OK);
                 }
            } catch(RuntimeException e) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(StatusMessages.ADD_WISHLIST_FAILED));       
            }
        }  else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(StatusMessages.USER_NOT_FOUND);
        }
    }


}
