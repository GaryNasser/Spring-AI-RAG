package com.rag.how_to_cook.service;


import com.rag.how_to_cook.domain.MetadataFilterExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.rag.how_to_cook.domain.ChatRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuples;

@Service
public class RecipeRAGService {
    private static final Logger log = LoggerFactory.getLogger(RecipeRAGService.class);
    private final DataPreparation dataPreparation;
    private final GenerationIntegration generationIntegration;
    private final RetrievalOptimization retrievalOptimization;

    RecipeRAGService(
            DataPreparation dataPreparation,
            GenerationIntegration generationIntegration,
            RetrievalOptimization retrievalOptimization
    ) {
        this.dataPreparation = dataPreparation;
        this.generationIntegration = generationIntegration;
        this.retrievalOptimization = retrievalOptimization;
    }

    public Flux<String> processChatStream(ChatRequest chatRequest, String userId) {
        return Mono.fromCallable(() -> {
            String routeType = generationIntegration.queryRouter(chatRequest.prompt());

            String rewriteQuery;

            if (routeType.equals("list")) {
                rewriteQuery = chatRequest.prompt();
            } else {
                rewriteQuery = generationIntegration.queryRewrite(chatRequest.prompt());
            }
            List<Document> relevantChunks;
            MetadataFilterExpression filterExpression = generationIntegration.extractFiltersFromQuery(rewriteQuery);
            log.info("元数据为: {}", filterExpression.toString());
            if (filterExpression != null) {
                relevantChunks = retrievalOptimization.metaFilteredSearch(rewriteQuery, userId, filterExpression, 5);
            } else {
                relevantChunks = retrievalOptimization.hybridSearch(rewriteQuery, userId, 5);
            }
            return new SearchContext(routeType, relevantChunks);
        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(context -> {
                    List<Document> relevantChunks = context.docs();
                    String routeType = context.route();
                    if (relevantChunks == null) {
                        relevantChunks = Collections.emptyList();
                    }
                    List<Document> relevantDocs = dataPreparation.getParentDocument(relevantChunks);
                    if (routeType.equals("list")) {

                        return generationIntegration.generateListAnswer(chatRequest.prompt(), relevantDocs);
                    } else if (routeType.equals("detail")) {
                        return generationIntegration.generateStepByStepAnswer(chatRequest.prompt(), relevantDocs);
                    } else {
                        return generationIntegration.generateBasicAnswer(chatRequest.prompt(), relevantDocs);
                    }
                });
    }

    record SearchContext(String route, List<Document> docs) {}
}
