package com.rag.how_to_cook.web;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.accept;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@RequiredArgsConstructor
@Configuration
public class RecipeRouter {

    @Bean
    public RouterFunction<ServerResponse> recipeRouters(RecipeHandler handler) {
        return RouterFunctions.route()
                .POST("/chat", handler::handleChat)          // 你的现有接口
                .GET("/chats", handler::getChatList)         // 侧边栏列表
                .GET("/chats/{chatId}/messages", handler::getChatMessages) // 历史记录详情
                .build();
    }
}
