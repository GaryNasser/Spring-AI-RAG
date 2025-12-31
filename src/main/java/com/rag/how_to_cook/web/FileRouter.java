package com.rag.how_to_cook.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.accept;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@Configuration
public class FileRouter {
    @Bean
    public RouterFunction<ServerResponse> fileRoutes(FileHandler fileHandler) {
        return RouterFunctions.route()
                .path("/api/minio", builder -> builder
                        .GET("/list", fileHandler::listFiles)
                        .POST("/upload", accept(MediaType.MULTIPART_FORM_DATA), fileHandler::uploadFile)
                        .DELETE("/delete", fileHandler::deleteFile)
                        .GET("/preview", fileHandler::preview)
                )
                .build();
    }
}
