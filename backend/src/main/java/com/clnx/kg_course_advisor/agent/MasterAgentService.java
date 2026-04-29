package com.clnx.kg_course_advisor.agent;

import com.clnx.kg_course_advisor.dto.ChatStreamEvent;
import com.clnx.kg_course_advisor.dto.GraphContextRequest;
import com.clnx.kg_course_advisor.dto.GraphContextResponse;
import com.clnx.kg_course_advisor.service.KnowledgeGraphContextService;
import com.clnx.kg_course_advisor.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.agent.mode", havingValue = "multi", matchIfMissing = true)
public class MasterAgentService implements ChatOrchestratorService {

    private static final List<String> AGENT_MEMORY_SUFFIXES = List.of(":qa", ":rec", ":act");
    private static final Pattern ORDINAL_PATTERN = Pattern.compile("第\\s*([0-9一二两三四五六七八九十]+)\\s*门");
    private static final Pattern NUMBERED_COURSE_PATTERN = Pattern.compile("^(\\d+)\\s*[.、]\\s*(.+)$");
    private static final Pattern COURSE_ID_PATTERN = Pattern.compile("(?i)(C\\d{3,}|C_[^\\s|]+)");
    private static final Pattern LIKELY_COURSE_PATTERN = Pattern.compile("([\\p{IsHan}A-Za-z0-9+《》()（）\\-]{2,30})课程");

    private static final String INTENT_CLASSIFICATION_PROMPT = """
            You are an intent classifier for a course advising system.
            Classify the user's message into exactly one category:
            - QA: factual questions about courses, concepts, teachers, prerequisites, or course content.
            - RECOMMENDER: requests for course recommendations, learning paths, or suggestions.
            - ACTION: requests to enroll/drop courses, query current schedule, or perform operations.

            Reply with exactly one word: QA, RECOMMENDER, or ACTION.
            """;

    private final ChatMemory chatMemory;
    private final ChatModel chatModel;
    private final SubAgentTools subAgentTools;
    private final UserService userService;
    private final KnowledgeGraphContextService knowledgeGraphContextService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile ChatClient cachedRoutingClient;
    private final Map<String, List<CourseReference>> recentRecommendationCache = new ConcurrentHashMap<>();

    MasterAgentService(ChatMemory chatMemory, ChatModel chatModel, SubAgentTools subAgentTools, UserService userService) {
        this(chatMemory, chatModel, subAgentTools, userService, null);
    }

    @Autowired
    public MasterAgentService(
            ChatMemory chatMemory,
            ChatModel chatModel,
            SubAgentTools subAgentTools,
            UserService userService,
            KnowledgeGraphContextService knowledgeGraphContextService
    ) {
        this.chatMemory = chatMemory;
        this.chatModel = chatModel;
        this.subAgentTools = subAgentTools;
        this.userService = userService;
        this.knowledgeGraphContextService = knowledgeGraphContextService;
    }

    @Override
    public String chat(String message, String sessionId, Long userId) {
        log.info("MasterAgent sync message: userId={}, sessionId={}, message={}", userId, sessionId, message);
        String reply = routeAndSummarize(message, sessionId, userId);
        persistConversation(sessionId, message, reply);
        return reply;
    }

    @Override
    public Flux<String> chatStream(String message, String sessionId, Long userId) {
        log.info("MasterAgent stream message: userId={}, sessionId={}, message={}", userId, sessionId, message);
        return Flux.<String>create(sink -> streamStructuredReply(message, sessionId, userId, sink))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public void clearConversationState(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        recentRecommendationCache.remove(sessionId);
        chatMemory.clear(sessionId);
        for (String suffix : AGENT_MEMORY_SUFFIXES) {
            chatMemory.clear(sessionId + suffix);
        }
    }

    private String routeAndSummarize(String message, String sessionId, Long userId) {
        RouteType routeType = determineRoute(message);
        String displayName = resolveUserDisplayName(userId);

        return switch (routeType) {
            case ACTION -> handleActionRoute(message, sessionId, userId, displayName);
            case RECOMMENDER -> handleRecommendationRoute(message, userId, sessionId);
            case QA -> handleQaRoute(message, userId, displayName, sessionId);
        };
    }

    private RouteType determineRoute(String message) {
        if (isActionIntent(message)) {
            return RouteType.ACTION;
        }
        if (isRecommendationIntent(message)) {
            return RouteType.RECOMMENDER;
        }

        RouteType llmRoute = routeByLlm(message);
        if (llmRoute != null) {
            return llmRoute;
        }
        return RouteType.QA;
    }

    private RouteType routeByLlm(String message) {
        try {
            if (cachedRoutingClient == null) {
                synchronized (this) {
                    if (cachedRoutingClient == null) {
                        cachedRoutingClient = ChatClient.builder(chatModel)
                                .defaultSystem(INTENT_CLASSIFICATION_PROMPT)
                                .build();
                    }
                }
            }

            String response = cachedRoutingClient.prompt().user(message).call().content();
            if (response == null || response.isBlank()) {
                return null;
            }

            String normalized = response.trim().toUpperCase();
            if (normalized.contains("RECOMMENDER")) {
                return RouteType.RECOMMENDER;
            }
            if (normalized.contains("ACTION")) {
                return RouteType.ACTION;
            }
            if (normalized.contains("QA")) {
                return RouteType.QA;
            }
            return null;
        } catch (Exception e) {
            log.warn("LLM routing failed, fallback to heuristic routing: {}", e.getMessage());
            return null;
        }
    }

    private String handleActionRoute(String message, String sessionId, Long userId, String displayName) {
        String routedMessage = rewriteActionMessageWithContext(message, sessionId);
        String json = subAgentTools.actionAgentStructured(routedMessage, String.valueOf(userId), sessionId);
        return summarizeActionResult(json, displayName);
    }

    private String handleRecommendationRoute(String message, Long userId, String sessionId) {
        String json = subAgentTools.recommenderAgentStructured(message, String.valueOf(userId), sessionId);
        cacheRecommendationReferences(sessionId, json);
        return summarizeRecommendationResult(json);
    }

    private String handleQaRoute(String message, Long userId, String displayName, String sessionId) {
        String json = subAgentTools.qaAgentStructured(message, String.valueOf(userId), sessionId);
        return summarizeQaResult(json, displayName);
    }

    private boolean isActionIntent(String message) {
        String normalized = normalizeText(message);
        if (normalized.isBlank()) {
            return false;
        }
        if (containsModernScheduleQueryCue(normalized)) {
            return true;
        }
        if (isBroadRecommendationRequest(normalized) && !containsStrongActionCue(normalized)) {
            return false;
        }
        if (containsStrongActionCue(normalized) && !containsModernFactualQuestionCue(normalized)) {
            return true;
        }
        if (containsScheduleQueryCue(normalized)) {
            return true;
        }
        if (!containsExplicitActionCue(normalized)) {
            return false;
        }
        return !containsFactualQuestionCue(normalized);
    }

    private boolean isRecommendationIntent(String message) {
        String normalized = normalizeText(message);
        if (normalized.isBlank()) {
            return false;
        }
        if (containsModernFactualQuestionCue(normalized)) {
            return false;
        }
        return isBroadRecommendationRequest(normalized);
    }

    private String summarizeActionResult(String json, String displayName) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String operationType = root.path("operationType").asText("");
            if ("QUERY".equalsIgnoreCase(operationType)) {
                String detailedScheduleReply = buildDetailedScheduleReply(root, displayName);
                if (!detailedScheduleReply.isBlank()) {
                    return detailedScheduleReply;
                }
            }
            String message = root.path("message").asText("");
            if (!message.isBlank()) {
                return message;
            }
            return displayName + "，选课执行模块已返回结果。";
        } catch (Exception e) {
            log.warn("Failed to summarize action result", e);
            return "选课操作已经执行，但整理结果时出现了一些问题。";
        }
    }

    private String buildDetailedScheduleReply(JsonNode root, String displayName) {
        JsonNode enrolledCourses = root.path("enrolledCourses");
        if (!enrolledCourses.isArray()) {
            return "";
        }

        List<String> courseNames = new ArrayList<>();
        for (JsonNode courseNode : enrolledCourses) {
            String courseName = courseNode.path("courseName").asText("").trim();
            if (!courseName.isBlank() && !courseNames.contains(courseName)) {
                courseNames.add(courseName);
            }
        }

        if (courseNames.isEmpty()) {
            return displayName + " 当前还没有已选课程。";
        }

        List<String> preview = courseNames.stream().limit(5).toList();
        String listedCourses = String.join("、", preview);
        if (courseNames.size() > preview.size()) {
            listedCourses += " 等";
        }
        return String.format("%s 当前已选 %d 门课程：%s。", displayName, courseNames.size(), listedCourses);
    }

    private String summarizeQaResult(String json, String displayName) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String answer = root.path("answer").asText("");
            if (!answer.isBlank()) {
                return answer;
            }
            return displayName + "，当前没有可直接展示的问答结果。";
        } catch (Exception e) {
            log.warn("Failed to summarize QA result", e);
            return "问答结果已返回，但整理答案时出现了一些问题。";
        }
    }

    private String summarizeRecommendationResult(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode coursesNode = root.path("courses");
            if (!coursesNode.isArray() || coursesNode.isEmpty()) {
                String reason = root.path("recommendationReason").asText("");
                return reason.isBlank() ? "当前没有合适的推荐课程。" : reason;
            }

            List<String> lines = new ArrayList<>();
            lines.add("根据你的需求，我推荐这些课程：");
            lines.add("");

            int index = 1;
            for (JsonNode courseNode : coursesNode) {
                String courseName = courseNode.path("name").asText("");
                if (courseName.isBlank()) {
                    continue;
                }

                lines.add(index + ". **" + courseName + "**");

                List<String> teachers = collectStringValues(courseNode.path("teacherNames"));
                if (!teachers.isEmpty()) {
                    lines.add("   - 老师：" + String.join("、", teachers));
                }

                String reason = courseNode.path("reason").asText("");
                if (!reason.isBlank()) {
                    lines.add("   - 推荐理由：" + reason);
                }
                lines.add("");
                index++;
            }

            lines.add("如果你想选其中一门，可以直接说“选第一门”或者直接说课程名。");
            return String.join("\n", lines).trim();
        } catch (Exception e) {
            log.warn("Failed to summarize recommendation result", e);
            return "推荐结果已返回，但整理推荐内容时出现了一些问题。";
        }
    }

    private void persistConversation(String sessionId, String userMessage, String assistantReply) {
        try {
            chatMemory.add(sessionId, List.of(
                    new UserMessage(userMessage),
                    new AssistantMessage(assistantReply)
            ));
        } catch (Exception e) {
            log.warn("Failed to persist chat memory: sessionId={}", sessionId, e);
        }
    }

    private String rewriteActionMessageWithContext(String message, String sessionId) {
        String normalized = message == null ? "" : message.trim();
        ActionVerb actionVerb = determineActionVerb(normalized);
        if (actionVerb == ActionVerb.QUERY) {
            return normalized;
        }

        String courseId = extractCourseId(normalized);
        if (courseId != null) {
            return actionVerb.command + " " + courseId;
        }

        Integer ordinal = extractOrdinal(normalized);
        if (ordinal != null) {
            CourseReference reference = resolveOrdinalCourseReference(sessionId, ordinal);
            if (reference != null) {
                return actionVerb.command + " " + reference.actionReference();
            }
        }

        if (containsDemonstrativeReference(normalized)) {
            List<CourseReference> references = resolveLatestCourseReferences(sessionId);
            if (references.size() == 1) {
                return actionVerb.command + " " + references.get(0).actionReference();
            }
        }

        return normalized;
    }

    private ActionVerb determineActionVerb(String message) {
        String normalized = normalizeText(message);
        if (normalized.contains("退课")
                || normalized.contains("退选")
                || normalized.contains("取消选课")
                || normalized.contains("drop")
                || normalized.contains("withdraw")) {
            return ActionVerb.DROP;
        }
        if (containsScheduleQueryCue(normalized)) {
            return ActionVerb.QUERY;
        }
        return ActionVerb.ENROLL;
    }

    private Integer extractOrdinal(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        Matcher matcher = ORDINAL_PATTERN.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        return parseOrdinalValue(matcher.group(1));
    }

    private Integer parseOrdinalValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(raw);
        }
        return switch (raw) {
            case "一" -> 1;
            case "二", "两" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            case "十" -> 10;
            default -> null;
        };
    }

    private CourseReference resolveOrdinalCourseReference(String sessionId, int ordinal) {
        List<CourseReference> cachedReferences = recentRecommendationCache.get(sessionId);
        if (cachedReferences != null && ordinal > 0 && ordinal <= cachedReferences.size()) {
            return cachedReferences.get(ordinal - 1);
        }

        List<Message> messages = chatMemory.get(sessionId);
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message instanceof AssistantMessage assistantMessage) {
                List<CourseReference> references = extractNumberedCourseReferences(assistantMessage.getText());
                if (ordinal > 0 && ordinal <= references.size()) {
                    return references.get(ordinal - 1);
                }
            }
        }
        return null;
    }

    private List<CourseReference> resolveLatestCourseReferences(String sessionId) {
        List<CourseReference> cachedReferences = recentRecommendationCache.get(sessionId);
        if (cachedReferences != null && !cachedReferences.isEmpty()) {
            return cachedReferences;
        }

        List<Message> messages = chatMemory.get(sessionId);
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message instanceof AssistantMessage assistantMessage) {
                List<CourseReference> references = extractNumberedCourseReferences(assistantMessage.getText());
                if (!references.isEmpty()) {
                    return references;
                }
            }
        }
        return List.of();
    }

    private List<CourseReference> extractNumberedCourseReferences(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        Map<String, CourseReference> references = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            Matcher matcher = NUMBERED_COURSE_PATTERN.matcher(line.trim());
            if (!matcher.find()) {
                continue;
            }
            CourseReference reference = toCourseReference(matcher.group(2));
            if (reference == null) {
                continue;
            }
            String key = reference.id != null ? reference.id : reference.name;
            references.putIfAbsent(key, reference);
        }
        return new ArrayList<>(references.values());
    }

    private CourseReference toCourseReference(String raw) {
        if (raw == null) {
            return null;
        }

        String normalized = raw.replace("*", "")
                .replace("`", "")
                .replace("_", "")
                .replace("《", "")
                .replace("》", "")
                .replace("\"", "")
                .trim();
        if (normalized.isBlank()
                || normalized.startsWith("老师：")
                || normalized.startsWith("推荐理由：")) {
            return null;
        }

        Matcher idMatcher = COURSE_ID_PATTERN.matcher(normalized);
        if (idMatcher.find()) {
            String courseId = idMatcher.group(1).toUpperCase();
            String courseName = normalized.replace(courseId, "").replaceFirst("^[-:：\\s]+", "").trim();
            return new CourseReference(courseId, courseName.isBlank() ? courseId : courseName);
        }

        return new CourseReference(null, normalized);
    }

    private void cacheRecommendationReferences(String sessionId, String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode coursesNode = root.path("courses");
            if (!coursesNode.isArray() || coursesNode.isEmpty()) {
                recentRecommendationCache.remove(sessionId);
                return;
            }

            List<CourseReference> references = new ArrayList<>();
            for (JsonNode courseNode : coursesNode) {
                String courseName = courseNode.path("name").asText("").trim();
                if (courseName.isBlank()) {
                    continue;
                }
                String courseId = courseNode.path("id").asText("").trim();
                references.add(new CourseReference(courseId.isBlank() ? null : courseId, courseName));
            }

            if (references.isEmpty()) {
                recentRecommendationCache.remove(sessionId);
            } else {
                recentRecommendationCache.put(sessionId, List.copyOf(references));
            }
        } catch (Exception e) {
            log.warn("Failed to cache recommendation references: sessionId={}", sessionId, e);
        }
    }

    private boolean containsExplicitActionCue(String normalized) {
        return normalized.startsWith("选")
                || normalized.startsWith("退")
                || normalized.contains("选课")
                || normalized.contains("退课")
                || normalized.contains("退选")
                || normalized.contains("取消选课")
                || normalized.contains("报名")
                || normalized.contains("注册")
                || normalized.contains("enroll")
                || normalized.contains("drop");
    }

    private boolean containsFactualQuestionCue(String normalized) {
        return normalized.contains("是什么")
                || normalized.contains("什么课")
                || normalized.contains("谁教")
                || normalized.contains("哪个老师")
                || normalized.contains("讲什么")
                || normalized.contains("前置")
                || normalized.contains("先修")
                || normalized.contains("介绍");
    }

    private boolean containsScheduleQueryCue(String normalized) {
        return normalized.contains("我的课程")
                || normalized.contains("我的课表")
                || normalized.contains("已选")
                || normalized.contains("schedule")
                || normalized.contains("timetable");
    }

    private String extractCourseId(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        Matcher matcher = COURSE_ID_PATTERN.matcher(message);
        if (matcher.find()) {
            return matcher.group(1).toUpperCase();
        }
        return null;
    }

    private boolean containsDemonstrativeReference(String message) {
        String normalized = normalizeText(message);
        return normalized.contains("这个")
                || normalized.contains("这门")
                || normalized.contains("它");
    }

    private boolean containsModernActionCue(String normalized) {
        return containsAnyCue(normalized,
                "选", "选课", "帮我选", "给我选", "我要选", "我想选",
                "退", "退课", "帮我退", "给我退", "取消选课",
                "报名", "注册", "enroll", "drop");
    }

    private boolean containsStrongActionCue(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        return containsAnyCue(normalized,
                "选课", "退课", "退选", "取消选课",
                "帮我选", "给我选", "我要选", "选上",
                "帮我退", "给我退", "我要退",
                "报名", "注册", "enroll", "drop");
    }

    private boolean isBroadRecommendationRequest(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        return containsAnyCue(normalized,
                "推荐", "适合", "入门", "想学", "学习路径", "相关", "有没有", "recommend",
                "方向", "类课程", "哪些课", "什么课", "哪几门", "看看", "接触", "补一些");
    }

    private boolean containsModernFactualQuestionCue(String normalized) {
        return containsAnyCue(normalized,
                "是谁", "什么课", "谁教", "哪个老师", "讲什么", "前置", "先修", "介绍");
    }

    private boolean containsModernScheduleQueryCue(String normalized) {
        return containsAnyCue(normalized,
                "我的课程", "我的课表", "已选", "课表", "排课", "schedule", "timetable");
    }

    private boolean containsAnyCue(String normalized, String... cues) {
        if (normalized == null || normalized.isBlank() || cues == null) {
            return false;
        }
        for (String cue : cues) {
            if (cue != null && !cue.isBlank() && normalized.contains(cue)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeText(String message) {
        return message == null ? "" : message.trim().toLowerCase();
    }

    private void streamStructuredReply(String message, String sessionId, Long userId, FluxSink<String> sink) {
        String fallbackReply = "抱歉，处理过程中出现了错误。";
        try {
            AgentProgressNotifier.setCallback(status -> emitStatusEvent(sink, status));
            String displayName = resolveUserDisplayName(userId);
            RouteType routeType = determineRoute(message);

            emitStatusEvent(sink, "Request received, analyzing intent");
            emitStatusEvent(sink, "Calling agents and graph tools");

            String fullResponse;
            switch (routeType) {
                case ACTION -> {
                    String routedMessage = rewriteActionMessageWithContext(message, sessionId);
                    String actionJson = subAgentTools.actionAgentStructured(routedMessage, String.valueOf(userId), sessionId);
                    emitActionArtifacts(sink, actionJson, userId);
                    fullResponse = summarizeActionResult(actionJson, displayName);
                }
                case RECOMMENDER -> {
                    String recommendationJson = subAgentTools.recommenderAgentStructured(message, String.valueOf(userId), sessionId);
                    cacheRecommendationReferences(sessionId, recommendationJson);
                    emitRecommendationArtifacts(sink, recommendationJson, userId);
                    fullResponse = summarizeRecommendationResult(recommendationJson);
                }
                case QA -> {
                    String qaJson = subAgentTools.qaAgentStructured(message, String.valueOf(userId), sessionId);
                    emitQaArtifacts(sink, qaJson, message, userId);
                    fullResponse = summarizeQaResult(qaJson, displayName);
                }
                default -> fullResponse = routeAndSummarize(message, sessionId, userId);
            }

            emitTextChunks(sink, fullResponse);
            persistConversation(sessionId, message, fullResponse);
            emitStatusEvent(sink, "Answer generation completed");
            emitDoneEvent(sink);
        } catch (Exception e) {
            log.error("Stream error: userId={}, sessionId={}", userId, sessionId, e);
            emitStatusEvent(sink, "Error while generating answer");
            emitTextChunks(sink, fallbackReply);
            persistConversation(sessionId, message, fallbackReply);
            emitDoneEvent(sink);
        } finally {
            AgentProgressNotifier.clearCallback();
            sink.complete();
        }
    }

    private void emitStatusEvent(FluxSink<String> sink, String status) {
        if (status != null && !status.isBlank()) {
            emitEvent(sink, "status", Map.of("status", status));
        }
    }

    private void emitTextChunks(FluxSink<String> sink, String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        int chunkSize = 256;
        for (int start = 0; start < text.length(); start += chunkSize) {
            int end = Math.min(start + chunkSize, text.length());
            emitEvent(sink, "message_delta", Map.of("delta", text.substring(start, end)));
        }
    }

    private void emitDoneEvent(FluxSink<String> sink) {
        emitEvent(sink, "done", Map.of("completed", true));
    }

    private void emitRecommendationArtifacts(FluxSink<String> sink, String json, Long userId) {
        try {
            JsonNode root = objectMapper.readTree(json);
            Map<String, Object> payload = sanitizeRecommendationPayload(objectMapper.convertValue(root, Map.class));
            emitEvent(sink, "recommendation", payload);
            emitGraphContext(sink, buildRecommendationContextRequest(root), userId);
        } catch (Exception e) {
            log.warn("Failed to emit recommendation artifacts", e);
        }
    }

    private void emitActionArtifacts(FluxSink<String> sink, String json, Long userId) {
        try {
            JsonNode root = objectMapper.readTree(json);
            emitEvent(sink, "action_preview", buildActionPreviewPayload(root));
            emitGraphContext(sink, buildActionContextRequest(root), userId);
            emitEvent(sink, "action_result", objectMapper.convertValue(root, Map.class));
        } catch (Exception e) {
            log.warn("Failed to emit action artifacts", e);
        }
    }

    private void emitQaArtifacts(FluxSink<String> sink, String json, String question, Long userId) {
        try {
            JsonNode root = objectMapper.readTree(json);
            emitGraphContext(sink, buildQaContextRequest(root, question), userId);
        } catch (Exception e) {
            log.warn("Failed to emit QA artifacts", e);
        }
    }

    private void emitGraphContext(FluxSink<String> sink, GraphContextRequest request, Long userId) {
        if (knowledgeGraphContextService == null || !hasContextInput(request)) {
            return;
        }

        try {
            GraphContextResponse response = knowledgeGraphContextService.buildContext(request, userId);
            emitEvent(sink, "graph_snapshot", objectMapper.convertValue(response, Map.class));
            if (response.getFocusNodeIds() != null && !response.getFocusNodeIds().isEmpty()) {
                emitEvent(sink, "graph_highlight", Map.of("focusNodeIds", response.getFocusNodeIds()));
            }
        } catch (Exception e) {
            log.warn("Failed to emit graph context", e);
        }
    }

    private boolean hasContextInput(GraphContextRequest request) {
        if (request == null) {
            return false;
        }
        return (request.getCourseIds() != null && !request.getCourseIds().isEmpty())
                || (request.getCourseNames() != null && !request.getCourseNames().isEmpty())
                || (request.getConceptNames() != null && !request.getConceptNames().isEmpty())
                || (request.getTeacherNames() != null && !request.getTeacherNames().isEmpty())
                || Boolean.TRUE.equals(request.getIncludeSchedule());
    }

    private GraphContextRequest buildRecommendationContextRequest(JsonNode root) {
        GraphContextRequest request = new GraphContextRequest();
        request.setScenario("recommendation");
        request.setCourseIds(mergeLists(
                collectStringValues(root.path("matchedCourses"), "id"),
                collectStringValues(root.path("courses"), "id")
        ));
        request.setCourseNames(mergeLists(
                collectStringValues(root.path("matchedCourses"), "name"),
                collectStringValues(root.path("courses"), "name")
        ));
        request.setConceptNames(mergeLists(
                collectNestedStringValues(root.path("matchedCourses"), "concepts"),
                collectNestedStringValues(root.path("courses"), "concepts"),
                collectStringValues(root.path("keywords"))
        ));
        request.setIncludePrerequisites(true);
        request.setLimit(42);
        return request;
    }

    private GraphContextRequest buildActionContextRequest(JsonNode root) {
        GraphContextRequest request = new GraphContextRequest();
        request.setScenario("action");
        request.setCourseIds(collectSingleField(root.path("affectedCourse"), "id"));
        request.setCourseNames(collectSingleField(root.path("affectedCourse"), "name"));
        request.setIncludeSchedule(true);
        request.setIncludeConflicts(root.path("conflictCourses").isArray() && !root.path("conflictCourses").isEmpty());
        request.setIncludePrerequisites(true);
        request.setLimit(40);
        return request;
    }

    private GraphContextRequest buildQaContextRequest(JsonNode root, String question) {
        GraphContextRequest request = new GraphContextRequest();
        request.setScenario("qa");
        List<String> courseIds = collectStringValues(root.path("relatedCourses"), "id");
        List<String> courseNames = mergeLists(
                collectStringValues(root.path("relatedCourses"), "name"),
                collectStringValues(root.path("entities").path("courseNames")),
                extractCourseNamesFromQuestion(question)
        );
        List<String> conceptNames = collectStringValues(root.path("entities").path("conceptNames"));
        boolean hasCourseFocus = !courseIds.isEmpty() || !courseNames.isEmpty();
        boolean prerequisiteQuestion = isPrerequisiteQuestion(question);

        request.setCourseIds(courseIds);
        request.setCourseNames(courseNames);
        request.setConceptNames(conceptNames);
        request.setTeacherNames(hasCourseFocus ? List.of() : collectStringValues(root.path("entities").path("teacherNames")));
        request.setIncludePrerequisites(shouldIncludeQaPrerequisites(question, conceptNames));
        request.setLimit(prerequisiteQuestion ? 10 : hasCourseFocus ? 18 : 24);
        return request;
    }

    private Map<String, Object> buildActionPreviewPayload(JsonNode root) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", root.path("status").asText(""));
        payload.put("operationType", root.path("operationType").asText(""));
        payload.put("affectedCourse", objectMapper.convertValue(root.path("affectedCourse"), Map.class));
        payload.put("conflictCourses", objectMapper.convertValue(root.path("conflictCourses"), List.class));
        payload.put("targetTime", objectMapper.convertValue(root.path("targetTime"), Map.class));
        payload.put("canProceed", root.path("canProceed").asBoolean(false));
        return payload;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeRecommendationPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> sanitizedPayload = new LinkedHashMap<>(payload);
        sanitizedPayload.put("courses", sanitizeRecommendationCourses(payload.get("courses")));
        if (payload.containsKey("matchedCourses")) {
            sanitizedPayload.put("matchedCourses", sanitizeRecommendationCourses(payload.get("matchedCourses")));
        }
        return sanitizedPayload;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sanitizeRecommendationCourses(Object rawCourses) {
        if (!(rawCourses instanceof List<?> rawCourseList)) {
            return List.of();
        }

        List<Map<String, Object>> sanitizedCourses = new ArrayList<>();
        for (Object rawCourse : rawCourseList) {
            if (!(rawCourse instanceof Map<?, ?> rawCourseMap)) {
                continue;
            }

            Map<String, Object> course = new LinkedHashMap<>();
            course.put("id", "");
            course.put("name", Objects.toString(rawCourseMap.get("name"), ""));
            if (rawCourseMap.containsKey("content")) {
                course.put("content", Objects.toString(rawCourseMap.get("content"), ""));
            }
            if (rawCourseMap.containsKey("reason")) {
                course.put("reason", Objects.toString(rawCourseMap.get("reason"), ""));
            }
            if (rawCourseMap.containsKey("teacherNames")) {
                course.put("teacherNames", rawCourseMap.get("teacherNames"));
            }
            if (rawCourseMap.containsKey("prerequisites")) {
                course.put("prerequisites", rawCourseMap.get("prerequisites"));
            }
            if (rawCourseMap.containsKey("concepts")) {
                course.put("concepts", rawCourseMap.get("concepts"));
            }
            if (rawCourseMap.containsKey("isEnrolled")) {
                course.put("isEnrolled", rawCourseMap.get("isEnrolled"));
            }
            if (rawCourseMap.containsKey("pathOrder")) {
                course.put("pathOrder", rawCourseMap.get("pathOrder"));
            }
            course.put("graphFocus", List.of(Objects.toString(rawCourseMap.get("name"), "")));
            sanitizedCourses.add(course);
        }
        return sanitizedCourses;
    }

    private List<String> collectSingleField(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        String value = node.path(fieldName).asText("").trim();
        return value.isBlank() ? List.of() : List.of(value);
    }

    private List<String> collectStringValues(JsonNode node) {
        return collectStringValues(node, null);
    }

    private List<String> collectStringValues(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (fieldName == null && item.isTextual()) {
                    String value = item.asText("").trim();
                    if (!value.isBlank()) {
                        values.add(value);
                    }
                } else if (fieldName != null) {
                    String value = item.path(fieldName).asText("").trim();
                    if (!value.isBlank()) {
                        values.add(value);
                    }
                }
            }
        }
        return values.stream().distinct().toList();
    }

    private List<String> collectNestedStringValues(JsonNode node, String fieldName) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            JsonNode nested = item.path(fieldName);
            if (nested.isArray()) {
                nested.forEach(child -> {
                    String value = child.asText("").trim();
                    if (!value.isBlank()) {
                        values.add(value);
                    }
                });
            }
        }
        return values.stream().distinct().toList();
    }

    @SafeVarargs
    private final List<String> mergeLists(List<String>... parts) {
        List<String> merged = new ArrayList<>();
        for (List<String> part : parts) {
            if (part != null) {
                merged.addAll(part);
            }
        }
        return merged.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }

    private List<String> extractLikelyCourseNames(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        Matcher matcher = LIKELY_COURSE_PATTERN.matcher(question);
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            if (!name.isBlank() && !"哪些".equals(name) && !"什么".equals(name)) {
                names.add(name);
            }
        }
        return names.stream().distinct().toList();
    }

    private List<String> extractCourseNamesFromQuestion(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        Matcher matcher = LIKELY_COURSE_PATTERN.matcher(question);
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            String name = normalizeLikelyCourseCandidate(matcher.group(1));
            if (isLikelyCourseName(name)) {
                names.add(name);
            }
        }
        return names.stream().distinct().toList();
    }

    private boolean shouldIncludeQaPrerequisites(String question, List<String> conceptNames) {
        if (conceptNames != null && !conceptNames.isEmpty()) {
            return true;
        }
        String normalized = normalizeText(question);
        return normalized.contains("先修")
                || normalized.contains("前置")
                || normalized.contains("依赖")
                || normalized.contains("基础");
    }

    private boolean isPrerequisiteQuestion(String question) {
        String normalized = normalizeText(question);
        return normalized.contains("先修")
                || normalized.contains("前置");
    }

    private String normalizeLikelyCourseCandidate(String candidate) {
        if (candidate == null) {
            return "";
        }

        String normalized = candidate.trim()
                .replace("《", "")
                .replace("》", "")
                .replace("（", "")
                .replace("）", "")
                .replace("(", "")
                .replace(")", "")
                .replaceAll("^[“\"'‘’]+|[”\"'‘’]+$", "");

        List<String> prefixes = List.of(
                "请问一下", "请问", "想问一下", "想问", "我想知道", "我想了解", "我想问",
                "帮我查一下", "帮我查查", "帮我看看", "帮我看下", "告诉我", "请告诉我", "关于"
        );
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String prefix : prefixes) {
                if (normalized.startsWith(prefix)) {
                    normalized = normalized.substring(prefix.length()).trim();
                    changed = true;
                }
            }
        }

        return normalized.replaceAll("^[，。、“”\"'：:；;\\s]+|[，。、“”\"'：:；;\\s]+$", "");
    }

    private boolean isLikelyCourseName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        if (candidate.length() < 2 || candidate.length() > 20) {
            return false;
        }

        List<String> blockedTerms = List.of(
                "什么", "哪些", "哪个", "老师", "授课", "选课", "推荐", "课程",
                "先修", "学分", "时间", "冲突", "内容", "介绍", "安排"
        );
        for (String blockedTerm : blockedTerms) {
            if (candidate.contains(blockedTerm)) {
                return false;
            }
        }
        return true;
    }

    private void emitEvent(FluxSink<String> sink, String type, Object payload) {
        try {
            sink.next(objectMapper.writeValueAsString(ChatStreamEvent.of(type, payload)));
        } catch (Exception e) {
            log.warn("Failed to emit stream event {}", type, e);
        }
    }

    private String resolveUserDisplayName(Long userId) {
        return userService.findById(userId)
                .map(user -> user.getRealName() != null && !user.getRealName().isBlank()
                        ? user.getRealName()
                        : user.getUsername())
                .orElse("你");
    }

    private enum RouteType {
        ACTION,
        RECOMMENDER,
        QA
    }

    private enum ActionVerb {
        ENROLL("enroll"),
        DROP("drop"),
        QUERY("query");

        private final String command;

        ActionVerb(String command) {
            this.command = command;
        }
    }

    private record CourseReference(String id, String name) {
        private String actionReference() {
            return name != null && !name.isBlank() ? name : id;
        }
    }
}
