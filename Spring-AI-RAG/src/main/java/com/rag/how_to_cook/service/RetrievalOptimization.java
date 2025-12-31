package com.rag.how_to_cook.service;


import com.rag.how_to_cook.domain.MetadataFilterExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RetrievalOptimization {
    private static final Logger log = LoggerFactory.getLogger(RetrievalOptimization.class);
    private final IndexConstruction indexConstruction;
    //private final DataPreparation dataPreparation;

    RetrievalOptimization(IndexConstruction indexConstruction, DataPreparation dataPreparation) {
        //this.dataPreparation = dataPreparation;
        this.indexConstruction = indexConstruction;
    }

    public List<Document> hybridSearch(String query, String userId, Integer k) {
        log.info("启动默认搜索");
        //稀疏向量方法在 Spring AI 下不可用
        return indexConstruction.getVectorStore().similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(k)
                .build());
    }

    public List<Document> metaFilteredSearch(String query, String userId, MetadataFilterExpression filterExpression, Integer k) {
        log.info("启动元数据筛选");

        log.info("{}", userId);

        List<Document> filteredDocs = null;

        FilterExpressionBuilder b1 = new FilterExpressionBuilder();
        FilterExpressionBuilder b2 = new FilterExpressionBuilder();

        filteredDocs = indexConstruction.getVectorStore().similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(k)
                // Spring AI 解析的问题，对于数组的支持有问题

//                .filterExpression(
//                        b1.and(
//                                b1.in("category.keyword", filterExpression.categories()),
//                                b1.in("difficulty.keyword", filterExpression.difficulties())
//                ).build())

                .filterExpression(b2.eq("userId.keyword", userId).build())
                .build()
        );

        log.info("{}", filteredDocs.size());

        return filteredDocs;
    }
}
