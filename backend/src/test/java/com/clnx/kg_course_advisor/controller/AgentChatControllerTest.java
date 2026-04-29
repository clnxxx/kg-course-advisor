package com.clnx.kg_course_advisor.controller;

import com.clnx.kg_course_advisor.agent.ChatOrchestratorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentChatControllerTest {

    @Mock
    private ChatOrchestratorService chatOrchestratorService;

    @Mock
    private ChatMemory chatMemory;

    private AgentChatController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentChatController(chatOrchestratorService, chatMemory);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getChatHistoryUsesUserScopedConversationId() {
        authenticateAs(42L);
        String conversationId = expectedConversationId(42L, "research-session");
        List<Message> storedMessages = List.of(
                new UserMessage("你好"),
                new AssistantMessage("你好，我可以帮你选课")
        );
        when(chatMemory.get(conversationId)).thenReturn(storedMessages);

        ResponseEntity<List<Map<String, String>>> response = controller.getChatHistory("research-session");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
                .containsExactly(
                        Map.of("role", "user", "content", "你好"),
                        Map.of("role", "assistant", "content", "你好，我可以帮你选课")
                );
        verify(chatMemory).get(conversationId);
    }

    @Test
    void deleteChatHistoryClearsChatMemoryAndOrchestratorState() {
        authenticateAs(7L);
        String conversationId = expectedConversationId(7L, "session-a");

        ResponseEntity<Void> response = controller.deleteChatHistory("session-a");

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(chatMemory).clear(conversationId);
        verify(chatOrchestratorService).clearConversationState(conversationId);
    }

    @Test
    void chatStreamDelegatesWithHashedConversationId() {
        authenticateAs(9L);
        String conversationId = expectedConversationId(9L, "session-b");
        AgentChatController.ChatRequest request = new AgentChatController.ChatRequest();
        request.setMessage("推荐人工智能方向课程");
        request.setSessionId("session-b");

        when(chatOrchestratorService.chatStream("推荐人工智能方向课程", conversationId, 9L))
                .thenReturn(Flux.just("chunk-1", "chunk-2"));

        List<String> events = controller.chatStream(request).collectList().block();

        assertThat(events).containsExactly("chunk-1", "chunk-2");
        verify(chatOrchestratorService).chatStream("推荐人工智能方向课程", conversationId, 9L);
    }

    private void authenticateAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of())
        );
    }

    private String expectedConversationId(Long userId, String sessionId) {
        String normalized = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId.trim();
        return "u" + userId + "_" + shortHash(normalized, 12);
    }

    private String shortHash(String text, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes).substring(0, length);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
