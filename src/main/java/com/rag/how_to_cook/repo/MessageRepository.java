package com.rag.how_to_cook.repo;

import com.rag.how_to_cook.domain.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, String> {
    List<Message> findByChatId_IdOrderByCreatedAtAsc(String chatId);
}
