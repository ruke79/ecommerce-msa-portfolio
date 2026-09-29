package com.project.chat_service.common.config.swagger;

// @OpenAPIDefinition
// @Configuration
// public class SwaggerConfig {
//     @Bean
//     public OpenAPI customOpenAPI(@Value("${openapi.service.title}") String serviceTitle,
//                                  @Value("${openapi.service.version}") String serviceVersion,
//                                  @Value("${openapi.service.url}") String url) {
//         return new OpenAPI()
//                 .servers(List.of(new Server().url(url)))
//                 .components(new Components().addSecuritySchemes("Bearer",
//                         new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
//                 .addSecurityItem(new SecurityRequirement().addList("Bearer"))
//                 .info(new Info().title(serviceTitle)
//                         .description("채팅 마이크로 서비스 API")
//                         .version(serviceVersion));
//     }

// }