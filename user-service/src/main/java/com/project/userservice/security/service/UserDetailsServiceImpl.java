package com.project.userservice.security.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.project.userservice.model.User;
import com.project.userservice.repository.jpa.UserRepository;

@Transactional
@Service
public class UserDetailsServiceImpl implements UserDetailsService {
   
   
    
    private final UserRepository userRepository;
    

    @Autowired
    public UserDetailsServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }



    // @Override
    // @Transactional
    // public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    //     User user = userRepository.findByUsername(username)
    //             .orElseThrow(() -> new UsernameNotFoundException("User not found with username: " + username));
    //     return UserDetailsImpl.build(user);
    // }
    @Override    
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));

        return UserDetailsImpl.build(user);
    }




}
