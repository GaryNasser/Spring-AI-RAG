package com.rag.how_to_cook.security;

import com.rag.how_to_cook.domain.*;
import com.rag.how_to_cook.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
@RequiredArgsConstructor
public class AuthenticationService {
    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final ReactiveAuthenticationManager authenticationManager;

    public Mono<AuthenticationResponse> registry(RegisterRequest request) {
        return Mono.fromCallable(() -> {
            var user = new User();
            user.setUsername(request.username());
            user.setPassword(passwordEncoder.encode(request.password()));
            user.setRole("ROLE_USER");

            repository.save(user);
            return user;
        })
                .subscribeOn(Schedulers.boundedElastic())
                .cast(UserDetails.class)
                .map(userDetails -> {
                    String token = jwtService.generateToken(userDetails);
                    return new AuthenticationResponse(token);
                });
    }

    public Mono<AuthenticationResponse> authenticate(AuthenticationRequest request) {
        return authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.username(),
                        request.password()
                )
        )
                .map(auth -> {
                    UserDetails userDetails = (UserDetails) auth.getPrincipal();
                    String token = jwtService.generateToken(userDetails);
                    return new AuthenticationResponse(token);
                });
    }
}
