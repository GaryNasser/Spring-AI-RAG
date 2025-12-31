package com.rag.how_to_cook.security;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements WebFilter {

    private final JwtService jwtService;
    private final ReactiveUserDetailsService reactiveUserDetailsService;

    @NotNull
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, @NotNull WebFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return chain.filter(exchange);
        }

        String jwt = authHeader.substring(7);
        String username = null;

        // ================== 修复点开始 ==================
        try {
            // 尝试解析 Token。如果 Token 过期，extractUsername 会抛出 ExpiredJwtException
            username = jwtService.extractUsername(jwt);
        } catch (Exception e) {
            // 捕获所有 JWT 相关异常（过期、签名错误、格式错误等）
            // 如果解析失败，不抛出 500 错误，而是直接放行（chain.filter），当作没有携带 Token 处理
            // 这样用户就能正常访问 /sign-in 接口进行重新登录了
            return chain.filter(exchange);
        }
        // ================== 修复点结束 ==================

        if (username != null) {
            // 保存 final 变量供 lambda 使用
            String finalUsername = username;

            return reactiveUserDetailsService.findByUsername(username)
                    .flatMap(userDetails -> {
                        boolean isTokenValid = false;
                        try {
                            // 再次验证完整性（防止 extractUsername 成功但其他校验失败）
                            isTokenValid = jwtService.isTokenValid(jwt, userDetails);
                        } catch (Exception e) {
                            // 验证出错视同无效
                        }

                        if (isTokenValid) {
                            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                                    userDetails,
                                    null,
                                    userDetails.getAuthorities()
                            );
                            // 认证成功：将 Authentication 写入 Reactor 上下文
                            return chain.filter(exchange)
                                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
                        }

                        // Token 无效但用户名存在：以匿名身份继续
                        return chain.filter(exchange);
                    })
                    // 如果找不到用户，也以匿名身份继续
                    .switchIfEmpty(chain.filter(exchange));
        }

        return chain.filter(exchange);
    }
}