package com.clnx.kg_course_advisor.agent;

import reactor.core.publisher.Flux;

public interface ChatOrchestratorService {

    String chat(String message, String sessionId, Long userId);

    Flux<String> chatStream(String message, String sessionId, Long userId);

    void clearConversationState(String sessionId);
}
