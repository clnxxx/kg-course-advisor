package com.clnx.kg_course_advisor.controller;

import com.clnx.kg_course_advisor.agent.ChatOrchestratorService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class AgentChatController {

    private final ChatOrchestratorService chatOrchestratorService;
    private final ChatMemory chatMemory;

    private Long getCurrentUserId() {
        return (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private String buildUserSessionId(String sessionId) {
        String rawSessionId = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId.trim();
        // Keep conversation id short to fit JDBC chat-memory schema.
        return "u" + getCurrentUserId() + "_" + shortHash(rawSessionId, 12);
    }

    private String shortHash(String text, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, Math.min(length, sb.length()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    @GetMapping("/history")
    public ResponseEntity<List<Map<String, String>>> getChatHistory(@RequestParam(required = false) String sessionId) {
        String conversationId = buildUserSessionId(sessionId);
        List<Message> messages = chatMemory.get(conversationId);

        List<Map<String, String>> history = new ArrayList<>();
        if (messages == null) {
            return ResponseEntity.ok(history);
        }
        for (Message msg : messages) {
            String role = null;
            if (msg instanceof UserMessage) {
                role = "user";
            } else if (msg instanceof AssistantMessage) {
                role = "assistant";
            }
            if (role != null) {
                Map<String, String> item = new HashMap<>();
                item.put("role", role);
                item.put("content", msg.getText());
                history.add(item);
            }
        }

        return ResponseEntity.ok(history);
    }

    @DeleteMapping("/history")
    public ResponseEntity<Void> deleteChatHistory(@RequestParam(required = false) String sessionId) {
        String conversationId = buildUserSessionId(sessionId);
        chatMemory.clear(conversationId);
        chatOrchestratorService.clearConversationState(conversationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping
    public String chat(@RequestBody ChatRequest request) {
        return chatOrchestratorService.chat(
                request.getMessage(),
                buildUserSessionId(request.getSessionId()),
                getCurrentUserId()
        );
    }

    @PostMapping(value = "/stream", produces = "text/event-stream")
    public Flux<String> chatStream(@RequestBody ChatRequest request) {
        return chatOrchestratorService.chatStream(
                request.getMessage(),
                buildUserSessionId(request.getSessionId()),
                getCurrentUserId()
        );
    }

    @Data
    public static class ChatRequest {
        private String message;
        private String sessionId = "default";
    }
}
