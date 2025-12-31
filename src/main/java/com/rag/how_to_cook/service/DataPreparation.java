package com.rag.how_to_cook.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.how_to_cook.domain.DocumentInfo;
import com.rag.how_to_cook.domain.DocumentVersion;
import com.rag.how_to_cook.domain.ProcessResult;
import com.rag.how_to_cook.repo.DocumentInfoRepository;
import com.rag.how_to_cook.repo.DocumentVersionRepository;
import io.minio.*;
import io.minio.messages.Item;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class DataPreparation {
    private static final Logger log = LoggerFactory.getLogger(DataPreparation.class);

    private final MinioClient minioClient;
    private final String bucketName;

    private final DocumentInfoRepository docInfoRepo;
    private final DocumentVersionRepository docVersionRepo;
    private final ObjectMapper objectMapper;

    private static final Set<String> CATEGORY_SET;
    private static final Map<String, String> DIFFICULTY_MAPPING;

    static {

        CATEGORY_SET = Set.of(
                "meat_dish", "vegetable_dish", "soup",
                "dessert", "breakfast", "staple",
                "aquatic", "condiment", "drink"
        );

        DIFFICULTY_MAPPING = new LinkedHashMap<>();
        DIFFICULTY_MAPPING.put("★★★★★", "very difficult");
        DIFFICULTY_MAPPING.put("★★★★", "difficult");
        DIFFICULTY_MAPPING.put("★★★", "medium");
        DIFFICULTY_MAPPING.put("★★", "easy");
        DIFFICULTY_MAPPING.put("★", "very easy");
    }

    public DataPreparation(
            MinioClient minioClient,
            @Value("${minio.bucket}") String bucketName,
            DocumentInfoRepository docInfoRepo,
            DocumentVersionRepository docVersionRepo) {
        this.minioClient = minioClient;
        this.bucketName = bucketName;
        this.docInfoRepo = docInfoRepo;
        this.docVersionRepo = docVersionRepo;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 1. 全量加载入口 (启动时调用)
     */
    public ProcessResult loadAllData() throws Exception {
        log.info("开始全量扫描 MinIO bucket: {} ...", bucketName);

        boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
        if (!found) {
            log.error("Bucket {} 不存在！", bucketName);
            return null;
        }

        Iterable<Result<Item>> results = minioClient.listObjects(
                ListObjectsArgs.builder().bucket(bucketName).recursive(true).build()
        );

        List<Document> chunksToAdd = new ArrayList<>();
        List<String> idsToDelete = new ArrayList<>();


        int count = 0;
        for (Result<Item> result : results) {
            Item item = result.get();
            // 过滤逻辑：跳过文件夹，且只处理 .md 文件
            if (item.isDir() || !item.objectName().toLowerCase().endsWith(".md")) {
                continue;
            }

            // e.g., "user_123/recipe.md"
            String objectName = item.objectName();

            String userId = extractUserIdFromPath(objectName);
            log.info(userId);

            ProcessResult processResult = processSingleFile(objectName, userId);

            // 复用单文件处理逻辑
            chunksToAdd.addAll(processResult.newChunks());
            idsToDelete.addAll(processResult.idsToDelete());
            count++;
        }
        log.info("初始化扫描完成，共处理 {} 个 Markdown 文件。", count);

        return new ProcessResult(chunksToAdd, idsToDelete);
    }

    @Transactional // 1. 加上事务，确保原子性
    public List<String> explicitDelete(String objectName, String userId) {

        String sourceUrl = "minio://" + bucketName + "/" + objectName;
        List<String> allChunksToDelete = new ArrayList<>();

        // 1. 查父文档
        Optional<DocumentInfo> documentInfoOpt = docInfoRepo.findBySourceUrl(sourceUrl);

        if (documentInfoOpt.isEmpty()) {
            log.warn("文件不存在于数据库，无法删除: {}", objectName);
            return Collections.emptyList();
        }

        DocumentInfo documentInfo = documentInfoOpt.get();

        if (!documentInfo.getUserId().equals(userId)) {
            throw new RuntimeException("权限不足：该文件属于其他用户");
        }

        // 2. 收集所有版本（包括历史版本）的 ChunkID，确保向量库删干净
        // 因为是 CascadeType.ALL，直接从对象里取 versions 即可 (JPA会在事务内自动加载)
        List<DocumentVersion> allVersions = documentInfo.getVersions();

        if (allVersions != null) {
            for (DocumentVersion version : allVersions) {
                if (version.getChunkIds() != null && !version.getChunkIds().isEmpty()) {
                    try {
                        List<String> ids = objectMapper.readValue(
                                version.getChunkIds(),
                                new TypeReference<List<String>>() {}
                        );
                        allChunksToDelete.addAll(ids);
                    } catch (Exception e) {
                        // 3. 这里的策略取决于你：
                        // 选择 A: 记录错误但继续删除 (防止因脏数据导致无法删除文件)
                        log.error("解析版本 v{} 的 ChunkID 失败，可能导致向量残留", version.getVersionNumber(), e);

                        // 选择 B: 抛出异常回滚 (严谨模式)
                        // throw new RuntimeException("元数据损坏，无法解析 ChunkID", e);
                    }
                }
            }
        }

        // 4. 级联删除 (JPA 会自动删除 versions 表里的对应记录)
        docInfoRepo.delete(documentInfo);

        log.info("已级联删除文档及 {} 个历史版本，准备清理 {} 个向量索引",
                allVersions != null ? allVersions.size() : 0,
                allChunksToDelete.size());

        return allChunksToDelete;
    }

    /**
     * 2. 单文件处理核心逻辑 (Controller 可复用)
     * 返回生成的 chunks，方便调用者直接写入 VectorStore
     */
    public ProcessResult processSingleFile(String objectName, String userId) {
        String sourceUrl = "minio://" + bucketName + "/" + objectName;
        List<String> chunksToDelete = new ArrayList<>();
        //log.info("正在处理文件: {}", objectName);

        try {
            // A. 读取 MinIO 内容
            String content = readContentFromMinio(objectName);
            if (content.isEmpty()) return new ProcessResult(Collections.emptyList(), Collections.emptyList());

            String contentHash = calculateHash(content);

            // B. 版本检查
            Optional<DocumentInfo> docInfoOpt = docInfoRepo.findBySourceUrl(sourceUrl);
            DocumentInfo docInfo;
            DocumentVersion latestVersion = null;

            if (docInfoOpt.isPresent()) {
                docInfo = docInfoOpt.get();

                if (!docInfo.getUserId().equals(userId)) {
                    throw new RuntimeException("权限不足：该文件属于其他用户");
                }

                Optional<DocumentVersion> vOpt = docVersionRepo.findFirstByDocumentInfoIdAndActiveTrueOrderByVersionNumberDesc(docInfo.getId());

                if (vOpt.isPresent()) {
                    // 哈希一致，说明文件没变
                    if (vOpt.get().getContentHash().equals(contentHash)) {
                        //log.info("文件未改变，跳过: {}", objectName);
                        return new ProcessResult(Collections.emptyList(), Collections.emptyList());
                    }
                    // 哈希不一致，标记旧版本失效
                    latestVersion = vOpt.get();
                    chunksToDelete = deactivateOldVersion(latestVersion);
                    //log.info("检测到文件更新，旧版本 (v{}) 已标记失效", latestVersion.getVersionNumber());
                }
            } else {
                //log.info("发现新文件: {}", objectName);
                docInfo = new DocumentInfo();
                docInfo.setSourceUrl(sourceUrl);
            }

            // C. 创建并保存父文档在两个数据库中的信息
            Document parentDoc = createAndSaveDocument(content, contentHash, docInfo, latestVersion, objectName, userId);

            // D. 切分文档 (Chunking)
            List<Document> newChunks = splitDocument(parentDoc);

            // E. 更新数据库中的 ChunkIDs
            updateVersionWithChunkIds(parentDoc.getMetadata().get("dbVersionId").toString(), newChunks);

            log.info("文件处理完成，生成 {} 个切片。", newChunks.size());
            return new ProcessResult(newChunks, chunksToDelete);

        } catch (Exception e) {
            log.error("处理文件失败: " + objectName, e);
            return new ProcessResult(Collections.emptyList(), Collections.emptyList());
        }
    }

    // ==========================================
    // 3. 辅助方法 (私有)
    // ==========================================

    private String readContentFromMinio(String objectName) throws Exception {
        try (InputStream stream = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucketName).object(objectName).build())) {

            TextReader textReader = new TextReader(new InputStreamResource(stream));
            textReader.setCharset(StandardCharsets.UTF_8);

            return textReader.get().stream()
                    .map(Document::getFormattedContent)
                    .findFirst()
                    .orElse("");
        }
    }

    private List<String> deactivateOldVersion(DocumentVersion oldVersion) {
        oldVersion.setActive(false);
        docVersionRepo.save(oldVersion);

        if (oldVersion.getChunkIds() != null && !oldVersion.getChunkIds().isEmpty()) {
            try {
                return objectMapper.readValue(oldVersion.getChunkIds(), new TypeReference<>() {
                });
            } catch (Exception e) {
                log.error("解析旧版本 ChunkID 失败", e);
            }
        }
        return Collections.emptyList();
    }

    private Document createAndSaveDocument(
            String content,
            String hash,
            DocumentInfo docInfo,
            DocumentVersion oldVersion,
            String objectName,
            String userId
    ) {
        // 1. 临时文档用于提取元数据
        Document tempDoc = new Document(content);
        tempDoc.getMetadata().put("source", docInfo.getSourceUrl());
        tempDoc.getMetadata().put("userId", userId);
        enhanceMetadata(tempDoc, objectName);
        // 2. 设置 DocumentInfo 信息
        docInfo.setDishName(tempDoc.getMetadata().get("dishName").toString());
        if (docInfo.getCreatedAt() == null) docInfo.setCreatedAt(LocalDateTime.now());
        if (docInfo.getUserId() == null) {
            docInfo.setUserId(userId);
        };
        // 3. 设置 DocumentVersion 信息
        DocumentVersion newVersion = new DocumentVersion();
        newVersion.setContentHash(hash);
        newVersion.setVersionNumber(oldVersion != null ? oldVersion.getVersionNumber() + 1 : 1);
        newVersion.setActive(true);
        newVersion.setCreatedAt(LocalDateTime.now());
        // 4. 生成双向关系
        docInfo.addVersion(newVersion);
        // 利用级联属性，存了父亲自动存儿子
        DocumentInfo savedDocInfo = docInfoRepo.save(docInfo);

        DocumentVersion savedVersion = savedDocInfo.getVersions().getLast();

        // 构建最终 Parent Document
        Document finalDoc = new Document(content, tempDoc.getMetadata());
        finalDoc.getMetadata().put("parentId", savedDocInfo.getId());
        finalDoc.getMetadata().put("dbVersionId", savedVersion.getId());
        finalDoc.getMetadata().put("docType", "parent");
        finalDoc.getMetadata().put("userId", userId);

        return finalDoc;
    }

    private List<Document> splitDocument(Document parentDoc) {
        TokenTextSplitter splitter = new TokenTextSplitter(400, 100, 5, 10000, true);
        // 只切分当前这一个文档
        List<Document> chunks = splitter.apply(List.of(parentDoc));

        String parentId = parentDoc.getMetadata().get("parentId").toString();

        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            chunk.getMetadata().put("chunkId", UUID.randomUUID().toString());
            chunk.getMetadata().put("parentId", parentId);
            chunk.getMetadata().put("docType", "child");
            chunk.getMetadata().put("chunkIndex", i);
        }

        log.info("分割成: {} 小块", chunks.size());
        return chunks;
    }

    private void updateVersionWithChunkIds(String versionId, List<Document> chunks) {
        List<String> ids = chunks.stream()
                .map(d -> d.getMetadata().get("chunkId").toString())
                .collect(Collectors.toList());

        docVersionRepo.findById(versionId).ifPresent(v -> {
            try {
                v.setChunkIds(objectMapper.writeValueAsString(ids));
                docVersionRepo.save(v);
            } catch (JsonProcessingException e) {
                log.error("保存 ChunkID 序列化失败", e);
            }
        });
    }

    private void enhanceMetadata(Document doc, String objectName) {
        doc.getMetadata().put("category", "other");

        // 路径包含分类映射
        for (String category : CATEGORY_SET) {
            if (objectName.contains(category)) {
                doc.getMetadata().put("category", category);
                break;
            }
        }

        // 提取文件名作为菜名
        String fileName = objectName;
        if (objectName.contains("/")) {
            fileName = objectName.substring(objectName.lastIndexOf("/") + 1);
        }
        int lastDot = fileName.lastIndexOf('.');
        String dishName = (lastDot > 0) ? fileName.substring(0, lastDot) : fileName;
        doc.getMetadata().put("dishName", dishName);

        // 提取难度
        String content = doc.getFormattedContent();
        for (Map.Entry<String, String> entry : DIFFICULTY_MAPPING.entrySet()) {
            if (content.contains(entry.getKey())) {
                doc.getMetadata().put("difficulty", entry.getValue());
                break;
            }
        }

        log.info("{}", doc.getMetadata());
    }

    private String calculateHash(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return new BigInteger(1, hashBytes).toString(16);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    // ==========================================
    // 4. 检索阶段使用的辅助方法
    // ==========================================

    public List<Document> getParentDocument(List<Document> childChunks) {
        if (childChunks == null || childChunks.isEmpty()) return Collections.emptyList();

        Set<String> parentIds = childChunks.stream()
                .map(chunk -> chunk.getMetadata().get("parentId").toString())
                .collect(Collectors.toSet());
        if (parentIds.isEmpty()) return Collections.emptyList();

        List<DocumentInfo> parentInfos = docInfoRepo.findAllById(parentIds);
        List<Document> parentDocs = new ArrayList<>();

        for (DocumentInfo docInfo : parentInfos) {
            try {
                String prefix = "minio://" + bucketName + "/";
                if (!docInfo.getSourceUrl().startsWith(prefix)) {
                    continue;
                }
                String objectName = docInfo.getSourceUrl().substring(prefix.length());

                // 重新从 MinIO 读取原始内容
                String content = readContentFromMinio(objectName);
                if (content.isEmpty()) continue;

                Document parentDoc = new Document(content);
                parentDoc.getMetadata().put("source", docInfo.getSourceUrl());
                parentDoc.getMetadata().put("dishName", docInfo.getDishName());
                parentDoc.getMetadata().put("parentId", docInfo.getId());

                enhanceMetadata(parentDoc, objectName);
                parentDocs.add(parentDoc);

            } catch (Exception e) {
                log.error("无法重新加载父文档: {}", docInfo.getSourceUrl(), e);
            }
        }

        // 简单排序
        Map<String, Long> parentFrequencies = childChunks.stream()
                .collect(Collectors.groupingBy(chunk -> chunk.getMetadata().get("parentId").toString(), Collectors.counting()));
        parentDocs.sort(Comparator.comparing(
                (Document doc) -> parentFrequencies.getOrDefault(doc.getMetadata().get("parentId").toString(), 0L)
        ).reversed());

        return parentDocs;
    }

    // Getters
    //public List<Document> getChunksToAdd() { return this.chunks; }
    //public Set<String> getChunkIdsToDelete() { return this.chunkIdsToDelete; }
    public Map<String, String> getDifficultyMapping() { return DIFFICULTY_MAPPING; }

    private String extractUserIdFromPath(String objectName) {
        int slashIndex = objectName.indexOf('/');
        if (slashIndex > 0) {
            return objectName.substring(0, slashIndex);
        }
        return null; // 或者返回默认管理员ID
    }
}