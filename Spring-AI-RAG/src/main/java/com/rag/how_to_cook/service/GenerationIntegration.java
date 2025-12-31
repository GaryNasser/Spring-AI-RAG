package com.rag.how_to_cook.service;

import com.rag.how_to_cook.domain.MetadataFilterExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.ListOutputConverter;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Component
public class GenerationIntegration {
    public final ChatClient chatClient;
    private static final Logger log = LoggerFactory.getLogger(GenerationIntegration.class);
    private final DataPreparation dataPreparation;

    GenerationIntegration(
            ChatClient.Builder builder,
            ChatMemory chatMemory,
            DataPreparation dataPreparation) {
        this.chatClient = builder
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .build();
        this.dataPreparation = dataPreparation;
    }

    public String summariseTitle(String query) {
        PromptTemplate pt = new PromptTemplate("""
                根据用户输入的第一句话，帮我生成一个简洁的标题（不超过10个字）：
                
                请仅返回总结出来的标题
                
                用户输入的第一句话：{query}
                """);
        Map<String, Object> vars = Map.of("query", query);
        Message message = pt.createMessage(vars);

        return chatClient.prompt().messages(message).call().content();
    }

    public String queryRouter(String query) {
        PromptTemplate promptTemplate = new PromptTemplate("""
                根据用户的问题，将其分类为以下三种类型之一：
                
                1. 'list' - 用户想要获取菜品列表或推荐，只需要菜名
                   例如：推荐几个素菜、有什么川菜、给我3个简单的菜
                
                2. 'detail' - 用户想要具体的制作方法或详细信息
                   例如：宫保鸡丁怎么做、制作步骤、需要什么食材
                
                3. 'general' - 其他一般性问题
                   例如：什么是川菜、制作技巧、营养价值
                
                请只返回分类结果：list、detail 或 general
                
                用户问题: {query}
                
                分类结果:
                """);

        Map<String, Object> vars = Map.of("query", query);
        Message message = promptTemplate.createMessage(vars);

        return chatClient.prompt().messages(message).call().content();
    }

    public String queryRewrite(String query) {
        PromptTemplate promptTemplate = new PromptTemplate("""
                你是一个智能查询分析助手。请分析用户的查询，判断是否需要重写以提高食谱搜索效果。
                
                原始查询: {query}
                
                分析规则：
                1. **具体明确的查询**（直接返回原查询）：
                   - 包含具体菜品名称：如"宫保鸡丁怎么做"、"红烧肉的制作方法"
                   - 明确的制作询问：如"蛋炒饭需要什么食材"、"糖醋排骨的步骤"
                   - 具体的烹饪技巧：如"如何炒菜不粘锅"、"怎样调制糖醋汁"
                
                2. **模糊不清的查询**（需要重写）：
                   - 过于宽泛：如"做菜"、"有什么好吃的"、"推荐个菜"
                   - 缺乏具体信息：如"川菜"、"素菜"、"简单的"
                   - 口语化表达：如"想吃点什么"、"有饮品推荐吗"
                
                重写原则：
                - 保持原意不变
                - 增加相关烹饪术语
                - 优先推荐简单易做的
                - 保持简洁性
                
                示例：
                - "做菜" → "简单易做的家常菜谱"
                - "有饮品推荐吗" → "简单饮品制作方法"
                - "推荐个菜" → "简单家常菜推荐"
                - "川菜" → "经典川菜菜谱"
                - "宫保鸡丁怎么做" → "宫保鸡丁怎么做"（保持原查询）
                - "红烧肉需要什么食材" → "红烧肉需要什么食材"（保持原查询）
                
                请输出最终查询（如果不需要重写就返回原查询）:
                """);
        Map<String, Object> vars = Map.of("query", query);
        Message message = promptTemplate.createMessage(vars);

        return chatClient.prompt().messages(message).call().content();
    }

    Flux<String> generateListAnswer(String query, List<Document> contextDocs) {
        if (contextDocs == null || contextDocs.isEmpty()) {
            log.info("未找到相关菜品");
            return Flux.just("抱歉，没有找到相关的菜品信息。");
        }

        List<String> dishNames = new ArrayList<>();

        for (Document contextDoc : contextDocs) {
            if (contextDoc == null) {
                log.info("发现空文档");
                continue; // 跳过当前这次循环，继续处理下一个文档
            }

            String dishName = contextDoc.getMetadata().getOrDefault("dishName", "未知菜品").toString();
            log.info("{}", dishName);
            if (!dishNames.contains(dishName)) {
                dishNames.add(dishName);
            }
        }

        if (dishNames.size() == 1) {
            return Flux.just(String.format("为您推荐：%s", dishNames.getFirst()));
        }
        else if (dishNames.size() <= 3) {
            String formattedDishes = IntStream.range(0, dishNames.size())
                    .mapToObj(i -> String.format("%d. %s", i + 1, dishNames.get(i)))
                    .collect(Collectors.joining("\n"));

            return Flux.just("为您推荐以下菜品：\n" + formattedDishes);

        }
        else {
            // 使用 stream().limit(3) 来模拟 dish_names[:3]
            String formattedDishes = IntStream.range(0, 3)
                    .mapToObj(i -> String.format("%d. %s", i + 1, dishNames.get(i)))
                    .collect(Collectors.joining("\n"));

            String suffix = String.format("\n\n还有其他 %d 道菜品可供选择。", dishNames.size() - 3);

            return Flux.just("为您推荐以下菜品：\n" + formattedDishes + suffix);
        }
    }

    Flux<String> generateBasicAnswer(String query, List<Document> contextDocs) {
        PromptTemplate pt = new PromptTemplate("""
                你是一位专业的烹饪助手。请根据以下食谱信息回答用户的问题。
                
                用户问题: {question}
                
                相关食谱信息:
                {context}
                
                请提供详细、实用的回答。如果信息不足，请诚实说明。
                
                回答:
                """);
        Map<String, Object> vars = Map.of("question", query, "context", contextDocs);
        Message message = pt.createMessage(vars);
        return chatClient.prompt().messages(message).stream().content();
    }

    Flux<String> generateStepByStepAnswer(String query, List<Document> contextDocs) {
        PromptTemplate pt = new PromptTemplate("""
                你是一位专业的烹饪导师。请根据食谱信息，为用户提供详细的分步骤指导。
                
                用户问题: {question}
                
                相关食谱信息:
                {context}
                
                请灵活组织回答，建议包含以下部分（可根据实际内容调整）：
                
                ## 🥘 菜品介绍
                [简要介绍菜品特点和难度]
                
                ## 🛒 所需食材
                [列出主要食材和用量]
                
                ## 👨‍🍳 制作步骤
                [详细的分步骤说明，每步包含具体操作和大概所需时间]
                
                ## 💡 制作技巧
                [仅在有实用技巧时包含。优先使用原文中的实用技巧，如果原文的"附加内容"与烹饪无关或为空，可以基于制作步骤总结关键要点，或者完全省略此部分]
                
                注意：
                - 根据实际内容灵活调整结构
                - 不要强行填充无关内容或重复制作步骤中的信息
                - 重点突出实用性和可操作性
                - 如果没有额外的技巧要分享，可以省略制作技巧部分
                
                回答:
                """);
        Map<String, Object> vars = Map.of("question", query, "context", contextDocs);
        Message message = pt.createMessage(vars);
        return chatClient.prompt().messages(message).stream().content();
    }

    MetadataFilterExpression extractFiltersFromQuery(String query) {
        ListOutputConverter outputConverter = new ListOutputConverter();

        PromptTemplate pt1 = new PromptTemplate("""
                # Role
                你是一个智能菜谱难度分类助手。你的任务是从用户的自然语言输入中提取“难度意图”，并将其映射为标准的难度等级列表。
                
                # Standard Values (标准难度库)
                系统仅支持以下 5 种确切的难度描述（请严格使用这些词，不要创造新词）：
                1. "very difficult"
                2. "difficult"
                3. "medium"
                4. "easy"
                5. "very easy"
                
                # Mapping Rules (映射规则)
                请根据用户的语义，按照以下逻辑进行归类：
                - **高难度/挑战/很难** -> 映射为 very difficult, difficult
                - **中等难度/一般/还行** -> 映射为 medium
                - **低难度/简单/新手/快手** -> 映射为 easy, very easy
                - **极端困难/地狱级** -> 映射为 very difficult
                - **随便/无所谓/未提及难度** -> very difficult, difficult, medium, easy, very easy
                
                # Output Format
                请仅输出标准的难度词汇，如果有多个，用英文逗号分隔。
                用户输入: "{userInput}"
                格式： "{format}"
                """);

        Map<String, Object> var1 = Map.of("userInput", query, "format", outputConverter.getFormat());
        Message message1 = pt1.createMessage(var1);
        String difficultiesResponse = chatClient.prompt().messages(message1).call().content();

        List<String> difficulties = outputConverter.convert(difficultiesResponse);

        PromptTemplate pt2 = new PromptTemplate("""
                  # 可用类别
                  你必须从以下列表中选择一个或多个类别：
                  ["meat_dish", "vegetable_dish", "soup", "dessert", "breakfast", "staple", "aquatic", "condiment", "drink"]
    
                  # 规则
                  - 如果一道菜品或需求符合多个类别，请同时返回所有类别。
                  - 如果用户的需求与食物无关，或过于模糊无法判断，请返回 "other"。
    
                  # 示例
                  - 用户输入: "我想吃一份麻婆豆腐"
                  - 输出: vegetable_dish
                  - 用户输入: "早上吃什么比较好？"
                  - 输出: breakfast
                  - 用户输入: "来一份番茄鸡蛋汤和一碗米饭"
                  - 输出: vegetable_dish, soup, staple
                  - 用户输入: "今天天气真好"
                  - 输出: meat_dish, vegetable_dish, soup, dessert, breakfast, staple, aquatic, condiment, drink
    
                  ---
                  请根据以上规则和示例，对以下用户输入进行分类：
    
                  用户输入: "{userInput}"
                  格式： "{format}"
                """);

        Map<String, Object> var2 = Map.of("userInput", query, "format", outputConverter.getFormat());
        Message message2 = pt2.createMessage(var2);

        String categoriesResponse = chatClient.prompt().messages(message2).call().content();

        List<String> categories = outputConverter.convert(categoriesResponse);



        if (difficulties != null && categories != null) {
            return new MetadataFilterExpression(difficulties, categories);
        }

        return null;
    }
}
