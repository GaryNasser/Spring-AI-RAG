package com.rag.how_to_cook.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RequestPredicates.accept;

@Configuration
public class AuthRouter {
    @Bean
    public RouterFunction<ServerResponse> authRouters(AuthHandler authHandler) {
        return RouterFunctions.route()
                .path("", builder -> builder
                        .POST("/sign-in", accept(MediaType.APPLICATION_JSON), authHandler::authenticate)
                )
                .POST("sign-up", accept(MediaType.APPLICATION_JSON), authHandler::registry).build();
    }
}
