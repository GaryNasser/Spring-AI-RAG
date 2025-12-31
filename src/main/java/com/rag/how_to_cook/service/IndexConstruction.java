package com.rag.how_to_cook.service;


import com.rag.how_to_cook.domain.ProcessResult;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class IndexConstruction {
    private final VectorStore vectorStore;
    private final DataPreparation dataPreparation;

    private static final Logger log = LoggerFactory.getLogger(IndexConstruction.class);

    IndexConstruction(
            VectorStore vectorStore,
            DataPreparation dataPreparation
    ) {
        this.vectorStore = vectorStore;
        this.dataPreparation = dataPreparation;
    }

    @PostConstruct
    public void initialize() throws Exception {
        buildVectorIndex();
    }

    public VectorStore getVectorStore() {
        return this.vectorStore;
    }

    public void buildVectorIndex() throws Exception {
        log.info("向量索引构建开始");

        ProcessResult processResult = dataPreparation.loadAllData();

        List<Document> chunksToAdd = processResult.newChunks();
        List<String> chunkIdsToDelete = processResult.idsToDelete();

        if (!chunkIdsToDelete.isEmpty()) {
            log.warn("检测到 {} 个旧版本文档块需要删除...", chunkIdsToDelete.size());

            vectorStore.delete(chunkIdsToDelete);

            log.info("已成功从向量索引中删除 {} 个旧文档块。", chunkIdsToDelete.size());
        } else {
            log.info("未发现需要删除的旧文档块。");
        }

        if (chunksToAdd == null || chunksToAdd.isEmpty()) {
            log.info("没有新的文档块需要添加到向量索引。");
            log.info("向量索引构建完成");
            return;
        }

        int totalChunks = chunksToAdd.size();
        int batchSize = 100; // 批量大小
        int totalBatches = (int) Math.ceil((double) totalChunks / batchSize);

        log.info("准备开始添加 {} 个新的文档块, 分为 {} 个批次。", totalChunks, totalBatches);

        for (int i = 0; i < totalBatches; i++) {
            int start = i * batchSize;
            int end = Math.min((i + 1) * batchSize, totalChunks);

            List<Document> batch = chunksToAdd.subList(start, end);

            vectorStore.add(batch);

            log.info(
                    "批次 {}/{} 添加完毕。累计已添加 {}/{} 个文档块。",
                    (i + 1),
                    totalBatches,
                    end,
                    totalChunks
            );
        }

        log.info("向量索引增量更新完成");
    }
}
