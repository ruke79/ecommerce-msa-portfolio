package com.project.userservice.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.project.common.constants.StatusMessages;
import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.UserRepository;

@Component
public class AuthUtil {

    
    private final UserRepository userRepository;

    @Autowired
    public AuthUtil(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Long loggedInUserId(){
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new RuntimeException(StatusMessages.USER_NOT_FOUND));
        return user.getUserId();
    }

    public User loggedInUser(){
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new RuntimeException(StatusMessages.USER_NOT_FOUND));
    }
}
