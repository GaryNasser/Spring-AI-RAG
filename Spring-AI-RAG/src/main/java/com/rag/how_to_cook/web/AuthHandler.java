package com.rag.how_to_cook.web;

import com.rag.how_to_cook.domain.AuthenticationRequest;
import com.rag.how_to_cook.domain.RegisterRequest;
import com.rag.how_to_cook.security.AuthenticationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class AuthHandler {
    private final AuthenticationService service;

    public Mono<ServerResponse> registry(ServerRequest request) {
        return request.bodyToMono(RegisterRequest.class)
                .flatMap(service::registry)
                .flatMap(authResponse ->
                        ServerResponse.ok().bodyValue(authResponse));
    }

    public Mono<ServerResponse> authenticate(ServerRequest request) {
        return request.bodyToMono((AuthenticationRequest.class))
                .flatMap(service::authenticate)
                .flatMap(authResponse ->
                        ServerResponse.ok().bodyValue(authResponse));
    }
}
