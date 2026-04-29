package com.clnx.kg_course_advisor.agent;

import com.clnx.kg_course_advisor.agent.tools.CypherTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.agent.mode", havingValue = "single")
public class SingleAgentService implements ChatOrchestratorService {

    private static final String MEMORY_CONVERSATION_ID_KEY = "chat_memory_conversation_id";

    private static final String SINGLE_AGENT_PROMPT = """
            You are a single-agent course advising assistant.
            You must handle factual QA, course recommendation, and course operations inside one agent.

            Available tools:
            - searchCourses(keyword)
            - getCourseConcepts(courseName)
            - getConceptPrerequisites(conceptName)
            - getTeacherCourses(teacherName)
            - getCourseTeachers(courseName)
            - getUserEnrolledCourses(userId)
            - searchCoursesByConcept(conceptName)
            - recommendCourses(userId, keywords)
            - enrollCourse(userId, courseId)
            - dropCourse(userId, courseId)

            Rules:
            - Reply in concise Chinese.
            - Use tools before answering factual questions.
            - For recommendation requests, prefer using recommendCourses(userId, keywords).
            - For enrollment or drop requests, use enrollCourse or dropCourse instead of claiming success directly.
            - Never invent courses, teachers, schedules, or tool results.
            - If the user's reference is ambiguous, ask a short clarification question.
            """;

    private final ChatModel chatModel;
    private final ChatMemory chatMemory;
    private final CypherTools cypherTools;

    private volatile ChatClient cachedClient;

    @Override
    public String chat(String message, String sessionId, Long userId) {
        log.info("SingleAgent sync message: userId={}, sessionId={}, message={}", userId, sessionId, message);
        try {
            String response = getClient().prompt()
                    .user(buildUserMessage(message, userId))
                    .advisors(spec -> spec.param(MEMORY_CONVERSATION_ID_KEY, memoryConversationId(sessionId)))
                    .call()
                    .content();
            return response == null ? "" : response;
        } catch (Exception e) {
            log.error("SingleAgent sync execution failed: userId={}, sessionId={}", userId, sessionId, e);
            return "抱歉，单智能体模式处理当前请求时出现了问题。";
        }
    }

    @Override
    public Flux<String> chatStream(String message, String sessionId, Long userId) {
        log.info("SingleAgent stream message: userId={}, sessionId={}, message={}", userId, sessionId, message);
        try {
            Flux<String> content = getClient().prompt()
                    .user(buildUserMessage(message, userId))
                    .advisors(spec -> spec.param(MEMORY_CONVERSATION_ID_KEY, memoryConversationId(sessionId)))
                    .stream()
                    .content();

            return Flux.concat(
                    Flux.just("__STATUS__:Request received, single-agent reasoning"),
                    content,
                    Flux.just("__STATUS__:Answer generation completed")
            );
        } catch (Exception e) {
            log.error("SingleAgent stream execution failed: userId={}, sessionId={}", userId, sessionId, e);
            return Flux.just(
                    "__STATUS__:Error while generating answer",
                    "抱歉，单智能体模式处理当前请求时出现了问题。"
            );
        }
    }

    @Override
    public void clearConversationState(String sessionId) {
        chatMemory.clear(memoryConversationId(sessionId));
    }

    private ChatClient getClient() {
        if (cachedClient == null) {
            synchronized (this) {
                if (cachedClient == null) {
                    cachedClient = ChatClient.builder(chatModel)
                            .defaultSystem(SINGLE_AGENT_PROMPT)
                            .defaultTools(cypherTools)
                            .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                            .build();
                }
            }
        }
        return cachedClient;
    }

    private String buildUserMessage(String message, Long userId) {
        return String.format("userId: %s%nmessage: %s", userId, message);
    }

    private String memoryConversationId(String sessionId) {
        return sessionId + ":single";
    }
}
