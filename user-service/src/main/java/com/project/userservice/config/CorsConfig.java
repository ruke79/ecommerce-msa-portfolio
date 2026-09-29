// package com.project.userservice.config;

// import org.springframework.http.HttpHeaders;
// import java.util.List;

// import com.project.userservice.constants.TokenType;
// import org.springframework.beans.factory.annotation.Value;
// import org.springframework.context.annotation.Bean;
// import org.springframework.context.annotation.Configuration;
// import org.springframework.context.annotation.Primary;
// import org.springframework.web.cors.CorsConfiguration;
// import org.springframework.web.cors.CorsConfigurationSource;
// import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

// @Configuration
// public class CorsConfig {

//     @Value("${frontend.url}")
//     String frontendUrl;

//     @Primary
//     @Bean
//     public CorsConfigurationSource corsConfigurationSource() {
//         CorsConfiguration configuration = new CorsConfiguration();

//         configuration.setAllowedOrigins(List.of(frontendUrl,"http://localhost:3000", "http://localhost:3001"));
//         configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
//         configuration.setAllowedHeaders(List.of("*"));
//         configuration.setAllowCredentials(true);
//         configuration.setMaxAge(3600L);
//         configuration.setExposedHeaders(List.of(TokenType.ACCESS.getType(), "Set-Cookie"
//                 ));

//         UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
//         source.registerCorsConfiguration("/**", configuration);
//         return source;
//     }
// }

// //public class WebConfig implements WebMvcConfigurer {
// //
// //    @Value("${frontend.url}")
// //    private String frontendUrl;
// //
// //    @Bean
// //    public WebMvcConfigurer corsConfigurer() {
// //        return new WebMvcConfigurer() {
// //            @Override
// //            public void addCorsMappings(CorsRegistry registry) {
// //                registry.addMapping("/**")
// //                        .allowedOrigins(frontendUrl)
// //                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
// //                        .allowedHeaders("*")
// //                        .allowCredentials(true)
// //                        .maxAge(3600)
// //                        .exposedHeaders(TokenType.ACCESS.getType(), "Set-Cookie");
// //
// //            }
// //        };
// //    }
// //}