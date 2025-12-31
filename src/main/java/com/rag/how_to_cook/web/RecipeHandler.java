package com.rag.how_to_cook.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.how_to_cook.domain.*;
import com.rag.how_to_cook.repo.ChatRepository;
import com.rag.how_to_cook.repo.MessageRepository;
import com.rag.how_to_cook.repo.UserRepository;
import com.rag.how_to_cook.security.JwtService;
import com.rag.how_to_cook.service.GenerationIntegration;
import com.rag.how_to_cook.service.RecipeRAGService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class RecipeHandler {
    private final RecipeRAGService recipeRAGService;
    private final GenerationIntegration generationIntegration;
    private final ObjectMapper objectMapper;
    private final ChatRepository chatRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;

//    RecipeHandler(RecipeRAGService recipeRAGService, ObjectMapper objectMapper) {
//        this.recipeRAGService = recipeRAGService;
//        this.objectMapper = objectMapper;
//    }

    public Mono<ServerResponse> handleChat(ServerRequest request) {
        return getUserId(request).flatMap(username -> request.bodyToMono(ChatRequest.class).flatMap(chatRequest -> {

            // 第一步：获取有效的 chatId（如果是新对话则创建并保存）
            Mono<String> chatIdMono = Mono.justOrEmpty(chatRequest.chatId())
                    .switchIfEmpty(
                            Mono.fromCallable(() -> {
                                User user = userRepository.findByUsername(username)
                                        .orElseThrow(() -> new RuntimeException("用户不存在"));

                                String title = generationIntegration.summariseTitle(chatRequest.prompt());

                                Chat chat = new Chat();
                                chat.setUser(user);
                                chat.setTitle(title);
                                return chatRepository.save(chat).getId();
                            }).subscribeOn(Schedulers.boundedElastic())

                    );

            // 第二步：拿到 ID 后，先存用户消息，再开启流式响应
            return chatIdMono.flatMap(chatId -> {

                // 准备 Chat 引用对象（用于外键关联）
                Chat chatRef = new Chat();
                chatRef.setId(chatId);

                // 1. 保存用户发送的消息 (阻塞操作包装成 Mono)
                Mono<Void> saveUserMsgMono = Mono.fromRunnable(() -> {
                    Message userMsg = new Message();
                    userMsg.setChatId(chatRef);
                    userMsg.setContent(chatRequest.prompt());
                    userMsg.setRole(MessageRole.USER);
                    userMsg.setCreatedAt(LocalDateTime.now());
                    messageRepository.save(userMsg);
                }).subscribeOn(Schedulers.boundedElastic()).then();

                // 2. 准备流式响应逻辑
                StringBuilder fullAnswer = new StringBuilder();
                Flux<String> responseStream = recipeRAGService.processChatStream(chatRequest, username)
                        .doOnNext(fullAnswer::append) // 累加回答
                        .doOnComplete(() -> {
                            // 流结束时，异步保存 AI 的完整回复
                            saveAssistantMessage(chatRef, fullAnswer.toString());
                        })
                        .map(chunk -> {
                            // 转换为 JSON 格式
                            Map<String, Object> data = Map.of("content", chunk, "chatId", chatId);
                            try {
                                return objectMapper.writeValueAsString(data);
                            } catch (JsonProcessingException e) {
                                return "{\"content\":\"\"}";
                            }
                        });

                // 3. 确保先完成用户消息保存，再返回流式响应
                return saveUserMsgMono.then(
                        ServerResponse.ok()
                                .contentType(MediaType.TEXT_EVENT_STREAM)
                                .body(responseStream, String.class)
                );
            });
        }));
    }

    public Mono<ServerResponse> getChatList(ServerRequest request) {
        return getUserId(request).flatMap(username ->
                Mono.fromCallable(() -> {
                            // 1. 调用你改好的查询方法
                            List<Chat> chats = chatRepository.findByUser_UsernameOrderByCreatedAtDesc(username);

                            // 2. 转换为只包含必要字段的 Map 列表，避开 user 代理对象
                            return chats.stream().map(chat -> Map.of(
                                    "id", chat.getId(),
                                    "title", chat.getTitle() == null ? "新对话" : chat.getTitle(),
                                    "createdAt", chat.getCreatedAt() != null ? chat.getCreatedAt().toString() : ""
                            )).toList();
                        })
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(data -> ServerResponse.ok().bodyValue(data))
        );
    }

    public Mono<ServerResponse> getChatMessages(ServerRequest request) {
        String chatId = request.pathVariable("chatId");
        return getUserId(request).flatMap(userId ->
                Mono.fromCallable(() -> {
                            // 1. 从数据库查询原始 Message 实体列表
                            List<Message> messages = messageRepository.findByChatId_IdOrderByCreatedAtAsc(chatId);

                            // 2. 手动转换为简单的 Map 列表，只返回前端需要的字段
                            // 这样 Jackson 就不会去碰 Message 里的 Chat 和 User 对象了
                            return messages.stream().map(m -> Map.of(
                                    "id", m.getId(),
                                    "content", m.getContent(),
                                    "role", m.getRole().name(), // 返回 USER 或 ASSISTANT
                                    "createdAt", m.getCreatedAt() != null ? m.getCreatedAt().toString() : ""
                            )).toList();
                        })
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(data -> ServerResponse.ok().bodyValue(data))
        );
    }

    /**
     * 辅助方法：后台异步保存 AI 回复
     */
    private void saveAssistantMessage(Chat chatRef, String content) {
        Mono.fromRunnable(() -> {
                    Message assistantMsg = new Message();
                    assistantMsg.setChatId(chatRef);
                    assistantMsg.setContent(content);
                    assistantMsg.setRole(MessageRole.ASSISTANT);
                    assistantMsg.setCreatedAt(LocalDateTime.now());
                    messageRepository.save(assistantMsg);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(); // 独立订阅，不阻塞主流
    }

    private Mono<String> getUserId(ServerRequest request) {
        return request.principal()
                .map(Principal::getName)
                .switchIfEmpty(Mono.error(new RuntimeException("未登录用户")));
    }
}
