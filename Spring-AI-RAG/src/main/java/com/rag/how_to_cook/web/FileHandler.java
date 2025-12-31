package com.rag.how_to_cook.web;

import com.rag.how_to_cook.domain.ProcessResult;
import com.rag.how_to_cook.service.DataPreparation;
import io.minio.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyExtractor;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuples;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.util.*;
import java.util.stream.Collector;

@Component
public class FileHandler {
    private final MinioClient minioClient;
    private final String bucketName;
    private final DataPreparation dataPreparation; // 负责解析文档、版本管理
    private final VectorStore vectorStore;         // 负责存取向量 (Spring AI的核心)

    public FileHandler(MinioClient minioClient,
                               @Value("${minio.bucket}") String bucketName,
                               DataPreparation dataPreparation,
                               VectorStore vectorStore) {
        this.minioClient = minioClient;
        this.bucketName = bucketName;
        this.dataPreparation = dataPreparation;
        this.vectorStore = vectorStore;
    }

    Mono<ServerResponse> listFiles(ServerRequest request) {
        return getUserId(request).flatMap(userId -> Mono.fromCallable(() ->
                        minioClient.listObjects(
                                ListObjectsArgs
                                        .builder()
                                        .bucket(bucketName)
                                        .prefix(userId + "/")
                                        .recursive(true)
                                        .build()
                        ))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable)
                .map(item -> {
                    try {
                        return item.get().objectName();
                    } catch (Exception e) {
                        return "";
                    }
                })
                .filter(name -> name != null && !name.isEmpty() && name.toLowerCase().endsWith(".md"))
                .collectList()
                .flatMap(list -> ServerResponse.ok().bodyValue(list)));
    }

    public Mono<ServerResponse> uploadFile(ServerRequest request) {
        return getUserId(request).flatMap(userId -> request.body(BodyExtractors.toMultipartData())
                // 第一步：校验文件并聚合 DataBuffer，打包成 Tuple 返回
                .flatMap(parts -> {
                    Map<String, Part> map = parts.toSingleValueMap();
                    FilePart filePart = (FilePart) map.get("file");

                    // 1. 校验逻辑：使用 Mono.error 中断流，交给最后的 onErrorResume 处理
                    if (filePart == null) {
                        return Mono.error(new IllegalArgumentException("文件不能为空"));
                    }

                    String fileName = filePart.filename();
                    if (!fileName.toLowerCase().endsWith(".md")) {
                        return Mono.error(new IllegalArgumentException("系统仅支持 .md 文件"));
                    }

                    String objectName = userId + "/" + fileName;

                    // 2. 聚合内容，并将 (文件名, 数据流) 打包传递给下一个 flatMap
                    return DataBufferUtils.join(filePart.content())
                            .map(dataBuffer -> Tuples.of(objectName, dataBuffer));
                })
                // 第二步：接收 Tuple，执行核心业务逻辑（MinIO + 向量库）
                .flatMap(tuple -> {
                    String objectName = tuple.getT1();       // 获取文件名
                    DataBuffer dataBuffer = tuple.getT2(); // 获取数据流

                    return Mono.fromCallable(() -> {
                                InputStream inputStream = dataBuffer.asInputStream();
                                long size = dataBuffer.readableByteCount();
                                String contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;

                                // --- 业务逻辑开始 ---

                                // A. 上传 MinIO
                                minioClient.putObject(
                                        PutObjectArgs.builder()
                                                .bucket(bucketName)
                                                .object(objectName)
                                                .stream(inputStream, size, -1)
                                                .contentType(contentType)
                                                .build()
                                );

                                // B. 数据准备与向量化
                                ProcessResult processResult = dataPreparation.processSingleFile(objectName, userId);
                                List<Document> newChunks = processResult.newChunks();

                                int addedCount = 0;
                                int deletedCount = 0;

                                // C. 写入向量数据库
                                if (!newChunks.isEmpty()) {
                                    vectorStore.add(newChunks);
                                    addedCount = newChunks.size();

                                    // D. 处理旧版本清理
                                    List<String> idsToDelete = processResult.idsToDelete();
                                    if (!idsToDelete.isEmpty()) {
                                        vectorStore.delete(new ArrayList<>(idsToDelete));
                                        deletedCount = idsToDelete.size();
                                        idsToDelete.clear();
                                    }
                                } else {
                                    return "文件上传成功，但内容未变更，无需更新知识库。";
                                }

                                return String.format("处理完成：文件 '%s' 已更新。新增向量片段: %d, 删除旧片段: %d",
                                        objectName, addedCount, deletedCount);
                                // --- 业务逻辑结束 ---
                            })
                            .subscribeOn(Schedulers.boundedElastic()) // 阻塞操作在独立线程池执行
                            // 关键点：无论处理成功还是失败，都必须释放 Netty 的堆外内存
                            .doOnTerminate(() -> DataBufferUtils.release(dataBuffer));
                })
                // 第三步：统一构建响应
                .flatMap(res -> ServerResponse.ok().bodyValue(res))
                // 第四步：统一异常处理（包含校验错误和业务错误）
                .onErrorResume(e -> ServerResponse.status(500).bodyValue(e.getMessage())));
    }

    Mono<ServerResponse> deleteFile(ServerRequest request) {
        return getUserId(request).flatMap(userId -> Mono.justOrEmpty(request.queryParam("fileName"))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("参数 fileName 缺失")))
                .flatMap(fileName -> Mono.fromCallable(() -> {
                            try {
                                // 前端传过来要有用户 id 前缀
                                if (!fileName.startsWith(userId + "/") && fileName.contains("/")) {
                                    throw new RuntimeException("非法的文件路径");
                                }

                                minioClient.removeObject(
                                        RemoveObjectArgs.builder()
                                                .bucket(bucketName)
                                                .object(fileName)
                                                .build()
                                );

                                var idsToDelete = dataPreparation.explicitDelete(fileName, userId);

                                if (!idsToDelete.isEmpty()) {
                                    vectorStore.delete(idsToDelete);
                                }

                                return fileName;
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(name -> ServerResponse.ok().bodyValue("删除成功: " + name))
                .onErrorResume(e -> {
                    if (e instanceof IllegalArgumentException) {
                        return ServerResponse.status(400).bodyValue("参数缺失");
                    } else {
                        return ServerResponse.status(500).bodyValue("删除失败: " + e.getMessage());
                    }
                }));
    }

    Mono<ServerResponse> preview(ServerRequest request) {
        return getUserId(request).flatMap(userId -> Mono.justOrEmpty(request.queryParam("fileName"))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("参数 fileName 缺失")))
                .flatMap(fileName -> Mono.fromCallable(() -> {
                            try {
                                // String objectName = userId + "/" + fileName;

                                if (!fileName.startsWith(userId + "/") && fileName.contains("/")) {
                                    throw new RuntimeException("非法的文件路径");
                                }

                                InputStream stream = minioClient.getObject(
                                        GetObjectArgs.builder()
                                                .bucket(bucketName)
                                                .object(fileName)
                                                .build()
                                );
                                String downloadName = fileName.substring(fileName.lastIndexOf("/") + 1);
                                String encodedFilename = URLEncoder.encode(downloadName, StandardCharsets.UTF_8).replace("+", "%20");

                                return Tuples.of(stream, encodedFilename);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(tuple -> {
                    InputStream stream = tuple.getT1();
                    String encodedFilename = tuple.getT2();

                    return ServerResponse.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + encodedFilename + "\"")
                            .contentType(MediaType.APPLICATION_OCTET_STREAM)
                            .body(BodyInserters.fromResource(new InputStreamResource(stream)));
                })
                .onErrorResume(e -> {
                    if (e instanceof IllegalArgumentException) {
                        return ServerResponse.status(HttpStatus.BAD_REQUEST).bodyValue(e.getMessage());
                    }
                    else {
                        return ServerResponse.notFound().build();
                    }
                }));
    }

    private Mono<String> getUserId(ServerRequest request) {
        return request.principal()
                .map(Principal::getName)
                .switchIfEmpty(Mono.error(new RuntimeException("未登录用户")));
    }
}
