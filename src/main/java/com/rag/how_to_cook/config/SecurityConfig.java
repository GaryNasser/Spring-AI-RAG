package com.rag.how_to_cook.config;

import com.rag.how_to_cook.repo.UserRepository;
import com.rag.how_to_cook.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Arrays;
import java.util.List;

@RequiredArgsConstructor
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {
    private final UserRepository userRepository;

    @Bean
    ReactiveUserDetailsService reactiveUserDetailsService() {
        return username -> Mono.fromCallable(() -> userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Username not found")))
                .subscribeOn(Schedulers.boundedElastic())
                .cast(UserDetails.class);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
    @Bean
    public ReactiveAuthenticationManager authenticationManager(
            ReactiveUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        UserDetailsRepositoryReactiveAuthenticationManager manager =
                new UserDetailsRepositoryReactiveAuthenticationManager(userDetailsService);
        manager.setPasswordEncoder(passwordEncoder);
        return manager;
    }

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http, JwtAuthenticationFilter jwtWebFilter) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable) // 禁用 CSRF
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable) // 禁用 Basic Auth
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable) // 禁用表单登录
                .exceptionHandling(handling -> handling
                        // 当认证失败（401）时
                        .authenticationEntryPoint((exchange, e) -> {
                            // 如果响应已经提交，直接完成，不再尝试写 Header
                            if (exchange.getResponse().isCommitted()) {
                                return Mono.empty();
                            }
                            // 否则返回 401
                            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                            return exchange.getResponse().setComplete();
                        })
                        // 当权限不足（403）时
                        .accessDeniedHandler((exchange, e) -> {
                            if (exchange.getResponse().isCommitted()) {
                                return Mono.empty();
                            }
                            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                            return exchange.getResponse().setComplete();
                        })
                )
                .authorizeExchange(exchanges -> exchanges
                        // 放行登录和注册接口
                        .pathMatchers("/sign-in").permitAll()
                        .pathMatchers("/sign-up").permitAll()
                        .pathMatchers(HttpMethod.OPTIONS).permitAll() // 允许跨域预检
                        // 其他接口需认证
                        .anyExchange().authenticated()
                )
                // 在认证之前添加 JWT 过滤器
                .addFilterAt(jwtWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }
}
