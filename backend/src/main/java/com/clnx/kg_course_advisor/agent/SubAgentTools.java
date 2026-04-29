package com.clnx.kg_course_advisor.agent;

import com.clnx.kg_course_advisor.agent.tools.CypherTools;
import com.clnx.kg_course_advisor.service.CourseActionService;
import com.clnx.kg_course_advisor.service.CourseRecommendationService;
import com.clnx.kg_course_advisor.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubAgentTools {

    private static final String MEMORY_CONVERSATION_ID_KEY = "chat_memory_conversation_id";
    private static final Pattern COURSE_ID_PATTERN = Pattern.compile("(?i)(C\\d{3,}|C_[^\\s|]+)");
    private static final Pattern COURSE_PREREQUISITE_QUESTION_PATTERN = Pattern.compile(
            "([\\p{IsHan}A-Za-z0-9()（）《》·\\-]{2,40}?)(?:课程|这门课|该课程|这门课程)?(?:的)?(?:具体)?(?:先修关系|先修链|先修课程|前置课程|前置要求|前置关系)"
    );

    private static final String QA_AGENT_PROMPT = """
            You are a QA sub-agent for course advising.
            Goal: answer factual questions about courses, concepts, teachers and prerequisites.

            Tools available:
            - searchCourses(keyword)
            - getCourseConcepts(courseName)
            - getConceptPrerequisites(conceptName)
            - getCoursePrerequisites(courseName)
            - getTeacherCourses(teacherName)
            - getCourseTeachers(courseName)
            - getUserEnrolledCourses(userId)
            - searchCoursesByConcept(conceptName)

            Critical rules:
            - Before answering any factual question, you must call at least one relevant tool.
            - Never answer from prior knowledge, general knowledge, or guesses.
            - Ground every SUCCESS answer in tool results from this conversation turn.
            - If relevant tool results are empty, ambiguous, or insufficient, return NOT_FOUND.
            - If you are unsure which tool to use, choose the most relevant retrieval tool first instead of guessing.
            - Do not invent courses, teachers, concepts, prerequisites, or tool results.

            You must return JSON only.
            {
              "status": "SUCCESS | NOT_FOUND | ERROR",
              "answer": "final answer",
              "results": [
                {
                  "tool": "tool name",
                  "evidence": "short summary of the retrieved result"
                }
              ],
              "relatedCourses": [
                {
                  "id": "course id",
                  "name": "course name",
                  "content": "course content",
                  "concepts": []
                }
              ],
              "entities": {
                "courseNames": [],
                "conceptNames": [],
                "teacherNames": []
              },
              "suggestedAction": "optional next step"
            }

            Status rules:
            - Use SUCCESS only when tool results directly support the answer.
            - Use NOT_FOUND when no relevant fact can be confirmed from tool results.
            - Use ERROR only for actual tool or execution failures.
            """;

    private static final String RECOMMENDER_AGENT_PROMPT = """
            You are a recommendation sub-agent for course advising.
            Goal: recommend courses and a learning path.

            Tools available:
            - searchCourses(keyword)
            - getCourseConcepts(courseName)
            - getConceptPrerequisites(conceptName)
            - searchCoursesByConcept(conceptName)
            - getUserEnrolledCourses(userId)
            - recommendCourses(userId, keywords)

            You must return JSON only.
            {
              "status": "SUCCESS | ERROR",
              "recommendationReason": "overall reason",
              "courses": [
                {
                  "id": "course id",
                  "name": "course name",
                  "content": "course content",
                  "reason": "reason",
                  "teacherNames": [],
                  "prerequisites": [],
                  "isEnrolled": false
                }
              ],
              "learningPath": [],
              "suggestedAction": "optional next step"
            }
            """;

    private static final String ACTION_AGENT_PROMPT = """
            You are an action sub-agent for course operations.
            Goal: execute enroll, drop and query operations.

            Tools available:
            - searchCourses(keyword)
            - getUserEnrolledCourses(userId)
            - enrollCourse(userId, courseId)
            - dropCourse(userId, courseId)

            Important rules:
            - `enrollCourse` may return statuses such as `SUCCESS`, `ALREADY_ENROLLED`, `TIME_CONFLICT`, `TIME_UNAVAILABLE`, `COURSE_NOT_FOUND`, or `USER_NOT_FOUND`.
            - The operation text may include a `[MASTER_CONTEXT]` block with recent candidate courses. You may use that context to interpret references like "this one" or "the first course".
            - Your final JSON `status` must reflect the tool result truthfully.
            - If the latest enroll tool result is not `SUCCESS`, then your final JSON `status` must be `FAILED` or `ERROR`, never `SUCCESS`.
            - If `TIME_CONFLICT` appears, do not claim the enrollment succeeded. Explain which enrolled courses conflict with the target course.
            - If `ALREADY_ENROLLED` appears, explain that the course was already selected and no new enrollment happened.
            - If `TIME_UNAVAILABLE` appears, explain that the course was selected but the schedule is unavailable, or follow the explicit tool result.
            - Use the user's display name when helpful, or simply say `你`; never address the user by user ID.
            - When enrolled course results include time fields, use them to explain the user's current schedule clearly.

            You must return JSON only.
            {
              "status": "SUCCESS | FAILED | PENDING_CONFIRMATION | ERROR",
              "operationType": "SELECT | DROP | QUERY",
              "message": "operation result",
              "affectedCourse": {"id": "", "name": "", "content": ""},
              "enrolledCourses": [],
              "metadata": {"operationTime": "", "additionalInfo": ""},
              "requiresConfirmation": false,
              "confirmationPrompt": ""
            }
            """;

    private static final String QA_AGENT_STREAM_PROMPT = """
            你是一个课程知识问答助手。回答用户关于课程、知识点、教师和前置知识的问题。
            使用自然的中文回答，简洁且有帮助。先使用工具从知识图谱中查找信息，再回答用户问题。

            可用工具：
            - searchCourses(keyword)：按关键词搜索课程
            - getCourseConcepts(courseName)：获取课程的知识点
            - getConceptPrerequisites(conceptName)：获取知识点的前置知识
            - getTeacherCourses(teacherName)：按教师查课程
            - getCourseTeachers(courseName)：获取课程的教师
            - getUserEnrolledCourses(userId)：获取用户已选课程
            - searchCoursesByConcept(conceptName)：按知识点搜索课程
            """;

    private static final String RECOMMENDER_AGENT_STREAM_PROMPT = """
            你是一个课程推荐助手。根据用户的需求推荐课程和学习路径。
            使用自然的中文回答，给出推荐理由。使用工具从知识图谱中查找匹配的课程。

            可用工具：
            - searchCourses(keyword)：按关键词搜索课程
            - getCourseConcepts(courseName)：获取课程的知识点
            - getConceptPrerequisites(conceptName)：获取知识点的前置知识
            - searchCoursesByConcept(conceptName)：按知识点搜索课程
            - getUserEnrolledCourses(userId)：获取用户已选课程
            - recommendCourses(userId, keywords)：根据关键词推荐课程
            """;

    private static final String ACTION_AGENT_STREAM_PROMPT = """
            你是一个选退课操作助手。执行选课、退课和查询操作。
            使用自然的中文回答，清楚说明操作结果。

            可用工具：
            - searchCourses(keyword)：按关键词搜索课程
            - getUserEnrolledCourses(userId)：获取用户已选课程
            - enrollCourse(userId, courseId)：为用户选课
            - dropCourse(userId, courseId)：为用户退课

            重要规则：
            - 操作文本可能包含[MASTER_CONTEXT]块，用于解析"这个"/"第一门"等引用。
            - 如实报告操作结果。选课失败要解释原因。
            - 使用自然语言回复，不要使用JSON格式。
            """;

    private final ChatModel chatModel;
    private final CypherTools cypherTools;
    private final CourseActionService courseActionService;
    private final CourseRecommendationService courseRecommendationService;
    private final ChatMemory chatMemory;
    private final UserService userService;
    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private volatile ChatClient cachedQaClient;
    private volatile ChatClient cachedRecommenderClient;
    private volatile ChatClient cachedActionClient;
    private volatile ChatClient cachedQaStreamClient;
    private volatile ChatClient cachedRecommenderStreamClient;
    private volatile ChatClient cachedActionStreamClient;

    // ── QA Agent ────────────────────────────────────────────────────────────

    public String qaAgentStructured(String question, String userId, String sessionId) {
        log.info("QA Agent called: userId={}, question={}", userId, question);
        AgentProgressNotifier.publish("Running QA agent");

        try {
            String deterministicJson = handleQaDeterministically(question);
            if (deterministicJson != null) {
                return deterministicJson;
            }

            if (cachedQaClient == null) {
                synchronized (this) {
                    if (cachedQaClient == null) {
                        cachedQaClient = ChatClient.builder(chatModel)
                                .defaultSystem(QA_AGENT_PROMPT)
                                .defaultTools(cypherTools)
                                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                                .build();
                    }
                }
            }

            String userMessage = String.format(
                    "userId: %s%nuserDisplayName: %s%nquestion: %s",
                    userId,
                    resolveUserDisplayName(userId),
                    question
            );
            String response = cachedQaClient.prompt()
                    .user(userMessage)
                    .advisors(spec -> spec.param(MEMORY_CONVERSATION_ID_KEY, sessionId + ":qa"))
                    .call()
                    .content();
            String jsonResponse = extractJson(response);

            if (isErrorJson(jsonResponse)) {
                log.warn("QA Agent returned malformed JSON, retrying once");
                response = cachedQaClient.prompt()
                        .user(userMessage + "\n\nReturn valid JSON only.")
                        .advisors(spec -> spec.param(MEMORY_CONVERSATION_ID_KEY, sessionId + ":qa"))
                        .call()
                        .content();
                jsonResponse = extractJson(response);
            }

            return normalizeQaResponse(jsonResponse);
        } catch (Exception e) {
            log.error("QA Agent execution failed", e);
            return createErrorJson("QA", "QA service unavailable: " + e.getMessage());
        }
    }

    public Flux<String> qaAgentStream(String question, String userId, String sessionId) {
        log.info("QA Agent stream called: userId={}, question={}", userId, question);
        AgentProgressNotifier.publish("Running QA agent");

        try {
            if (cachedQaStreamClient == null) {
                synchronized (this) {
                    if (cachedQaStreamClient == null) {
                        cachedQaStreamClient = ChatClient.builder(chatModel)
                                .defaultSystem(QA_AGENT_STREAM_PROMPT)
                                .defaultTools(cypherTools)
                                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                                .build();
                    }
                }
            }

            String userMessage = String.format(
                    "userId: %s%nuserDisplayName: %s%nquestion: %s",
                    userId,
                    resolveUserDisplayName(userId),
                    question
            );
            return cachedQaStreamClient.prompt()
                    .user(userMessage)
                    .advisors(spec -> spec.param(MEMORY_CONVERSATION_ID_KEY, sessionId + ":qa"))
                    .stream()
                    .content();
        } catch (Exception e) {
            log.error("QA Agent stream failed", e);
            return Flux.just("QA service unavailable: " + e.getMessage());
        }
    }

    // ── Recommender Agent ───────────────────────────────────────────────────

    private String handleQaDeterministically(String question) {
        if (!isCoursePrerequisiteQuestion(question)) {
            return null;
        }

        String courseReference = extractCourseFromPrerequisiteQuestion(question);
        if (courseReference.isBlank()) {
            return null;
        }

        List<Map<String, Object>> rows = cypherTools.findCoursePrerequisites(courseReference);
        if (rows.isEmpty()) {
            return serializeQaPayload(buildQaNotFoundPayload(courseReference));
        }

        String courseId = "";
        String courseName = courseReference;
        String courseContent = "";
        LinkedHashSet<String> prerequisiteCourseNames = new LinkedHashSet<>();

        for (Map<String, Object> row : rows) {
            if (row == null || row.isEmpty()) {
                continue;
            }
            courseId = firstNonBlank(courseId, row.get("courseId"));
            courseName = firstNonBlank(courseName, row.get("courseName"));
            courseContent = firstNonBlank(courseContent, row.get("courseContent"));
            addDistinctIfPresent(prerequisiteCourseNames, row.get("prerequisiteCourseName"));
        }

        return serializeQaPayload(buildCoursePrerequisitePayload(courseId, courseName, courseContent, prerequisiteCourseNames));
    }

    private boolean isCoursePrerequisiteQuestion(String question) {
        String normalized = question == null ? "" : question.trim().toLowerCase();
        return normalized.contains("先修")
                || normalized.contains("前置");
    }

    private String extractCourseFromPrerequisiteQuestion(String question) {
        if (question == null || question.isBlank()) {
            return "";
        }

        Matcher matcher = COURSE_PREREQUISITE_QUESTION_PATTERN.matcher(question);
        if (matcher.find()) {
            return cleanupPrerequisiteCourseCandidate(matcher.group(1));
        }
        return "";
    }

    private String cleanupPrerequisiteCourseCandidate(String candidate) {
        if (candidate == null) {
            return "";
        }

        String normalized = candidate.trim()
                .replace("《", "")
                .replace("》", "")
                .replace("“", "")
                .replace("”", "")
                .replace("\"", "")
                .replace("课程", "")
                .trim();

        List<String> prefixes = List.of(
                "给我看一下",
                "给我看下",
                "帮我看一下",
                "帮我看下",
                "帮我查一下",
                "帮我查下",
                "帮我看看",
                "请问",
                "我想了解",
                "我想知道",
                "告诉我",
                "看一下",
                "看下",
                "展示一下",
                "展示"
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

        normalized = normalized.replaceAll("(有哪些|有什么|有啥)$", "").trim();
        normalized = normalized.replaceAll("^[，。；：、\\s]+|[，。；：、\\s]+$", "");
        if (normalized.contains("课程之间") || normalized.length() < 2) {
            return "";
        }
        return normalized;
    }

    private Map<String, Object> buildQaNotFoundPayload(String courseReference) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("status", "NOT_FOUND");
        payload.put("answer", "未找到“" + courseReference + "”这门课程的先修信息，请确认课程名称是否准确。");
        payload.put("results", List.of(
                Map.of(
                        "tool", "getCoursePrerequisites",
                        "evidence", "No prerequisite records found for " + courseReference
                )
        ));
        payload.put("relatedCourses", List.of());
        payload.put("entities", Map.of(
                "courseNames", List.of(courseReference),
                "conceptNames", List.of(),
                "teacherNames", List.of()
        ));
        return payload;
    }

    private Map<String, Object> buildCoursePrerequisitePayload(
            String courseId,
            String courseName,
            String courseContent,
            LinkedHashSet<String> prerequisiteCourseNames
    ) {
        String normalizedTargetCourseName = normalizeCourseNameForSummary(courseName);
        Map<String, String> uniquePrerequisites = new java.util.LinkedHashMap<>();
        for (String rawName : prerequisiteCourseNames) {
            String displayName = cleanCourseNameForSummary(rawName);
            String normalizedName = normalizeCourseNameForSummary(displayName);
            if (displayName.isBlank() || normalizedName.isBlank() || normalizedName.equals(normalizedTargetCourseName)) {
                continue;
            }

            String existing = uniquePrerequisites.get(normalizedName);
            if (existing == null || displayName.length() < existing.length()) {
                uniquePrerequisites.put(normalizedName, displayName);
            }
        }

        List<String> prerequisiteList = new ArrayList<>(uniquePrerequisites.values());
        List<String> previewCourses = prerequisiteList.stream().limit(4).toList();
        String answer = prerequisiteList.isEmpty()
                ? courseName + "当前未识别到明确的先修课程。"
                : prerequisiteList.size() <= previewCourses.size()
                ? courseName + "的先修课程主要是：" + String.join("、", previewCourses) + "。"
                : courseName + "的先修课程重点包括：" + String.join("、", previewCourses) + "。";

        String evidence = prerequisiteList.isEmpty()
                ? courseName + " -> 未查询到显式先修课程"
                : courseName + " -> " + String.join("、", prerequisiteList);

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("status", "SUCCESS");
        payload.put("answer", answer);
        payload.put("results", List.of(
                Map.of(
                        "tool", "getCoursePrerequisites",
                        "evidence", evidence
                )
        ));
        payload.put("relatedCourses", List.of(
                Map.of(
                        "id", courseId == null ? "" : courseId,
                        "name", courseName == null ? "" : courseName,
                        "content", courseContent == null ? "" : courseContent
                )
        ));
        payload.put("entities", Map.of(
                "courseNames", List.of(courseName),
                "conceptNames", List.of(),
                "teacherNames", List.of()
        ));
        return payload;
    }

    private String cleanCourseNameForSummary(String rawName) {
        if (rawName == null) {
            return "";
        }
        return rawName.trim().replaceAll("\\s+", " ");
    }

    private String normalizeCourseNameForSummary(String rawName) {
        String normalized = cleanCourseNameForSummary(rawName);
        if (normalized.isBlank()) {
            return "";
        }

        String withoutParentheses = normalized
                .replaceAll("\\s*[（(][^（）()]{0,40}[）)]", "")
                .replaceAll("\\s+", " ")
                .trim();

        return withoutParentheses.isBlank() ? normalized : withoutParentheses;
    }

    private String serializeQaPayload(Map<String, Object> payload) {
        try {
            return normalizeQaResponse(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("Failed to serialize deterministic QA payload", e);
            return createErrorJson("QA", "QA result serialization failed");
        }
    }

    private String firstNonBlank(String current, Object candidate) {
        if (current != null && !current.isBlank()) {
            return current;
        }
        if (candidate == null) {
            return "";
        }
        return String.valueOf(candidate).trim();
    }

    private void addDistinctIfPresent(LinkedHashSet<String> values, Object rawValue) {
        if (rawValue == null) {
            return;
        }
        String value = String.valueOf(rawValue).trim();
        if (!value.isBlank()) {
            values.add(value);
        }
    }

    public String recommenderAgentStructured(String requirements, String userId, String sessionId) {
        log.info("Recommender Agent called: userId={}, requirements={}", userId, requirements);
        AgentProgressNotifier.publish("Running recommender agent");

        try {
            Long parsedUserId;
            try {
                parsedUserId = Long.parseLong(userId);
            } catch (NumberFormatException e) {
                return createErrorJson("RECOMMENDER", "Invalid user ID for recommendation");
            }

            return objectMapper.writeValueAsString(courseRecommendationService.recommendCourses(parsedUserId, requirements));
        } catch (Exception e) {
            log.error("Deterministic recommender execution failed", e);
            return createErrorJson("RECOMMENDER", "Recommender service unavailable: " + e.getMessage());
        }
    }

    public Flux<String> recommenderAgentStream(String requirements, String userId, String sessionId) {
        log.info("Recommender Agent stream called: userId={}, requirements={}", userId, requirements);
        AgentProgressNotifier.publish("Running recommender agent");

        try {
            if (cachedRecommenderStreamClient == null) {
                synchronized (this) {
                    if (cachedRecommenderStreamClient == null) {
                        cachedRecommenderStreamClient = ChatClient.builder(chatModel)
                                .defaultSystem(RECOMMENDER_AGENT_STREAM_PROMPT)
                                .defaultTools(cypherTools)
                                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                                .build();
                    }
                }
            }

            String userMessage = String.format(
                    "userId: %s%nuserDisplayName: %s%nrequirements: %s",
                    userId,
                    resolveUserDisplayName(userId),
                    requirements
            );
            return cachedRecommenderStreamClient.prompt()
                    .user(userMessage)
                    .advisors(spec -> spec.param(MEMORY_CONVERSATION_ID_KEY, sessionId + ":rec"))
                    .stream()
                    .content();
        } catch (Exception e) {
            log.error("Recommender Agent stream failed", e);
            return Flux.just("Recommender service unavailable: " + e.getMessage());
        }
    }

    // ── Action Agent ────────────────────────────────────────────────────────

    public String actionAgentStructured(String operation, String userId, String sessionId) {
        log.info("Action Agent called: userId={}, operation={}", userId, operation);
        AgentProgressNotifier.publish("Running action agent");

        try {
            String deterministicJson = handleActionDeterministically(operation, userId);
            if (deterministicJson != null) {
                return deterministicJson;
            }

            if (cachedActionClient == null) {
                synchronized (this) {
                    if (cachedActionClient == null) {
                        cachedActionClient = ChatClient.builder(chatModel)
                                .defaultSystem(ACTION_AGENT_PROMPT)
                                .defaultTools(cypherTools)
                                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                                .build();
                    }
                }
            }

            String userMessage = String.format(
                    "userId: %s%nuserDisplayName: %s%noperation: %s",
                    userId,
                    resolveUserDisplayName(userId),
                    operation
            );
            String response = cachedActionClient.prompt()
                    .user(userMessage)
                    .advisors(spec -> spec.param(MEMORY_CONVERSATION_ID_KEY, sessionId + ":act"))
                    .call()
                    .content();
            String jsonResponse = extractJson(response);

            if (isErrorJson(jsonResponse)) {
                log.warn("Action Agent returned malformed JSON, retrying once");
                response = cachedActionClient.prompt()
                        .user(userMessage + "\n\nReturn valid JSON only.")
                        .advisors(spec -> spec.param(MEMORY_CONVERSATION_ID_KEY, sessionId + ":act"))
                        .call()
                        .content();
                jsonResponse = extractJson(response);
            }

            return jsonResponse;
        } catch (Exception e) {
            log.error("Action Agent execution failed", e);
            return createErrorJson("ACTION", "Action service unavailable: " + e.getMessage());
        }
    }

    public Flux<String> actionAgentStream(String operation, String userId, String sessionId) {
        log.info("Action Agent stream called: userId={}, operation={}", userId, operation);
        AgentProgressNotifier.publish("Running action agent");

        String deterministicJson = handleActionDeterministically(operation, userId);
        if (deterministicJson != null) {
            return Flux.just(extractMessageFromJson(deterministicJson));
        }

        try {
            if (cachedActionStreamClient == null) {
                synchronized (this) {
                    if (cachedActionStreamClient == null) {
                        cachedActionStreamClient = ChatClient.builder(chatModel)
                                .defaultSystem(ACTION_AGENT_STREAM_PROMPT)
                                .defaultTools(cypherTools)
                                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                                .build();
                    }
                }
            }

            String userMessage = String.format(
                    "userId: %s%nuserDisplayName: %s%noperation: %s",
                    userId,
                    resolveUserDisplayName(userId),
                    operation
            );
            return cachedActionStreamClient.prompt()
                    .user(userMessage)
                    .advisors(spec -> spec.param(MEMORY_CONVERSATION_ID_KEY, sessionId + ":act"))
                    .stream()
                    .content();
        } catch (Exception e) {
            log.error("Action Agent stream failed", e);
            return Flux.just("Action service unavailable: " + e.getMessage());
        }
    }

    // ── Deterministic action handling ────────────────────────────────────────

    private String handleActionDeterministically(String operation, String userId) {
        String normalized = operation == null ? "" : operation.trim();
        if (normalized.isBlank()) {
            return null;
        }

        if (normalized.contains("[MASTER_CONTEXT]")) {
            return null;
        }

        Long parsedUserId;
        try {
            parsedUserId = Long.parseLong(userId);
        } catch (NumberFormatException ignored) {
            return null;
        }

        if (normalized.contains("退课")
                || normalized.contains("退选")
                || normalized.contains("退掉")
                || normalized.contains("帮我退")
                || normalized.contains("给我退")) {
            String courseReference = extractCourseReference(normalized);
            if (courseReference.isBlank()) {
                return serializeActionResult(buildPendingActionResult(
                        "DROP",
                        "请直接告诉我要退选的课程名或课程编号，例如 C004。"
                ));
            }
            log.info("Deterministic drop detected from explicit Chinese drop cue: userId={}, courseReference={}", userId, courseReference);
            return serializeActionResult(courseActionService.dropByCourseReference(parsedUserId, courseReference));
        }

        if (isDropOperation(normalized)) {
            String courseReference = extractCourseReference(normalized);
            if (courseReference.isBlank()) {
                return serializeActionResult(buildPendingActionResult(
                        "DROP",
                        "请直接告诉我要退选的课程名或课程编号，例如 C004。"
                ));
            }
            log.info("Deterministic drop detected: userId={}, courseReference={}", userId, courseReference);
            return serializeActionResult(courseActionService.dropByCourseReference(parsedUserId, courseReference));
        }

        if (isQueryOperation(normalized)) {
            log.info("Deterministic schedule query detected: userId={}, operation={}", userId, normalized);
            return serializeActionResult(buildQueryResult(parsedUserId));
        }

        if (isEnrollmentOperation(normalized)) {
            String courseReference = extractCourseReference(normalized);
            if (courseReference.isBlank()) {
                return serializeActionResult(buildPendingActionResult(
                        "SELECT",
                        "请直接告诉我要选的课程名或课程编号，例如 C004。"
                ));
            }
            log.info("Deterministic enroll detected: userId={}, courseReference={}", userId, courseReference);
            return serializeActionResult(courseActionService.enrollByCourseReference(parsedUserId, courseReference));
        }

        return null;
    }

    private boolean isEnrollmentOperation(String operation) {
        String normalized = operation == null ? "" : operation.toLowerCase();
        if (normalized.contains("退课")
                || normalized.contains("退选")
                || normalized.contains("退掉")
                || normalized.contains("帮我退")
                || normalized.contains("给我退")) {
            return true;
        }
        return normalized.startsWith("选")
                || normalized.contains("就选")
                || normalized.contains("选这个")
                || normalized.contains("选这门")
                || normalized.contains("选课")
                || normalized.contains("选修")
                || normalized.contains("选上")
                || normalized.contains("想选")
                || normalized.contains("还想选")
                || normalized.contains("再选")
                || normalized.contains("帮我选")
                || normalized.contains("给我选")
                || normalized.contains("报名")
                || normalized.contains("注册")
                || normalized.contains("enroll")
                || normalized.contains("select course");
    }

    private boolean isDropOperation(String operation) {
        String normalized = operation == null ? "" : operation.toLowerCase();
        return normalized.startsWith("退")
                || normalized.contains("退这个")
                || normalized.contains("退这门")
                || normalized.contains("退课")
                || normalized.contains("退选")
                || normalized.contains("取消选课")
                || normalized.contains("取消")
                || normalized.contains("删除")
                || normalized.contains("drop")
                || normalized.contains("withdraw");
    }

    private boolean isQueryOperation(String operation) {
        String normalized = operation == null ? "" : operation.toLowerCase();
        return normalized.contains("已选")
                || normalized.contains("选了什么")
                || normalized.contains("我的课程")
                || normalized.contains("我的课表")
                || normalized.contains("当前课程")
                || normalized.contains("schedule")
                || normalized.contains("timetable");
    }

    private String extractCourseReference(String operation) {
        if (operation == null || operation.isBlank()) {
            return "";
        }

        Matcher idMatcher = COURSE_ID_PATTERN.matcher(operation);
        if (idMatcher.find()) {
            return idMatcher.group(1).toUpperCase();
        }

        String normalized = operation.trim();
        normalized = normalized.replaceFirst("^再帮我选一次", "");
        normalized = normalized.replaceFirst("^再帮我退一次", "");
        normalized = normalized.replaceFirst("^再选一次", "");
        normalized = normalized.replaceFirst("^再退一次", "");
        normalized = normalized.replaceFirst("^把刚才推荐的第[一二两三四五六七八九十0-9]+门退掉", "");
        normalized = normalized.replaceAll("^[请帮麻烦给我把将想要还再就一下\\s]+", "");
        normalized = normalized.replaceAll("^(我还想|我想要|我想|我要|帮我|给我|请帮我|请给我|麻烦帮我)", "");
        normalized = normalized.replaceAll("^(就选|就退|再选|再退|选课|选修|选择|选上|选|退课|退选|退|查询|查一下|查|报名|注册|取消选课|取消|删除|enroll|drop|query)", "");
        normalized = normalized.replaceAll("^(一门|一个|一下|这门|这个|它)", "");
        normalized = normalized.replaceAll("(这门课|这门课程|课程|选课|吧)$", "");
        normalized = normalized.replaceAll("[。！？！，,.!?]$", "");
        normalized = normalized.trim();

        if ("这个".equals(normalized) || "这门".equals(normalized) || "它".equals(normalized)) {
            return "";
        }

        if ((normalized.startsWith("《") && normalized.endsWith("》"))
                || (normalized.startsWith("\"") && normalized.endsWith("\""))
                || (normalized.startsWith("\u201c") && normalized.endsWith("\u201d"))) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }

        return normalized;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String extractMessageFromJson(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String message = root.path("message").asText("");
            return message.isBlank() ? json : message;
        } catch (Exception e) {
            return json;
        }
    }

    private Map<String, Object> buildPendingActionResult(String operationType, String message) {
        return Map.of(
                "status", "PENDING_CONFIRMATION",
                "operationType", operationType,
                "message", message,
                "affectedCourse", Map.of("id", "", "name", "", "content", ""),
                "enrolledCourses", List.of(),
                "metadata", Map.of(
                        "operationTime", OffsetDateTime.now().toString(),
                        "additionalInfo", ""
                ),
                "requiresConfirmation", true,
                "confirmationPrompt", message
        );
    }

    private Map<String, Object> buildQueryResult(Long userId) {
        var courses = userService.getUserSchedule(userId);
        String displayName = resolveUserDisplayName(String.valueOf(userId));
        String answer = courses.isEmpty()
                ? String.format("%s当前还没有已选课程。", displayName)
                : String.format("%s当前已选 %d 门课程，可以在\u201c我的课程\u201d页面查看时间安排。", displayName, courses.size());

        return Map.of(
                "status", "SUCCESS",
                "operationType", "QUERY",
                "message", answer,
                "affectedCourse", Map.of("id", "", "name", "", "content", ""),
                "enrolledCourses", courses,
                "metadata", Map.of(
                        "operationTime", OffsetDateTime.now().toString(),
                        "additionalInfo", ""
                ),
                "requiresConfirmation", false,
                "confirmationPrompt", ""
        );
    }

    private String extractJson(String response) {
        if (response == null || response.trim().isEmpty()) {
            return createErrorJson("UNKNOWN", "Empty response");
        }

        String trimmed = response.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        trimmed = trimmed.trim();

        try {
            objectMapper.readTree(trimmed);
            return trimmed;
        } catch (Exception e) {
            int start = response.indexOf('{');
            int end = response.lastIndexOf('}');
            if (start >= 0 && end > start) {
                String candidate = response.substring(start, end + 1);
                try {
                    objectMapper.readTree(candidate);
                    return candidate;
                } catch (Exception ignored) {
                }
            }
            log.warn("Non-JSON LLM response: {}", response.substring(0, Math.min(120, response.length())));
            return createErrorJson("UNKNOWN", "Invalid response format: " + e.getMessage());
        }
    }

    private String serializeActionResult(Map<String, Object> result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("Failed to serialize deterministic action result", e);
            return createErrorJson("ACTION", "Action result serialization failed");
        }
    }

    private String normalizeQaResponse(String jsonResponse) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            String status = root.path("status").asText("");
            String answer = root.path("answer").asText("").trim();
            boolean hasEvidence = hasQaEvidence(root.path("results"));

            if ("SUCCESS".equalsIgnoreCase(status) && !hasEvidence) {
                log.warn("QA Agent returned SUCCESS without tool evidence. Downgrading to NOT_FOUND");
                return objectMapper.writeValueAsString(Map.of(
                        "status", "NOT_FOUND",
                        "answer", "根据当前工具查询，暂时无法确认这个问题的答案。",
                        "results", List.of(),
                        "relatedCourses", List.of(),
                        "suggestedAction", "请尝试提供更准确的课程名、知识点或教师名后再试。"
                ));
            }

            if ("NOT_FOUND".equalsIgnoreCase(status) && answer.isBlank()) {
                return objectMapper.writeValueAsString(Map.of(
                        "status", "NOT_FOUND",
                        "answer", "根据当前工具查询，暂时无法确认这个问题的答案。",
                        "results", root.path("results").isMissingNode() ? List.of() : root.path("results"),
                        "relatedCourses", root.path("relatedCourses").isMissingNode() ? List.of() : root.path("relatedCourses"),
                        "suggestedAction", root.path("suggestedAction").asText("请尝试提供更准确的课程名、知识点或教师名后再试。")
                ));
            }

            return objectMapper.writeValueAsString(buildNormalizedQaPayload(root, status, answer));
        } catch (Exception e) {
            log.warn("Failed to normalize QA response", e);
            return createErrorJson("QA", "QA response normalization failed");
        }
    }

    private Map<String, Object> buildNormalizedQaPayload(JsonNode root, String status, String answer) {
        List<Map<String, Object>> relatedCourses = normalizeRelatedCourses(root.path("relatedCourses"));

        List<String> courseNames = new ArrayList<>(collectTextValues(root.path("entities").path("courseNames")));
        List<String> conceptNames = new ArrayList<>(collectTextValues(root.path("entities").path("conceptNames")));
        List<String> teacherNames = new ArrayList<>(collectTextValues(root.path("entities").path("teacherNames")));

        for (Map<String, Object> course : relatedCourses) {
            addIfPresent(courseNames, course.get("name"));
            addAllIfPresent(conceptNames, course.get("concepts"));
            addAllIfPresent(teacherNames, course.get("teacherNames"));
        }

        Map<String, Object> normalized = new java.util.LinkedHashMap<>();
        normalized.put("status", status);
        normalized.put("answer", answer);
        normalized.put("results", root.path("results").isMissingNode() ? List.of() : objectMapper.convertValue(root.path("results"), List.class));
        normalized.put("relatedCourses", relatedCourses);
        normalized.put("entities", Map.of(
                "courseNames", courseNames.stream().distinct().toList(),
                "conceptNames", conceptNames.stream().distinct().toList(),
                "teacherNames", teacherNames.stream().distinct().toList()
        ));

        String suggestedAction = root.path("suggestedAction").asText("").trim();
        if (!suggestedAction.isBlank()) {
            normalized.put("suggestedAction", suggestedAction);
        }
        return normalized;
    }

    private List<Map<String, Object>> normalizeRelatedCourses(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }

        List<Map<String, Object>> courses = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) {
                continue;
            }
            Map<String, Object> course = new java.util.LinkedHashMap<>();
            String id = item.path("id").asText("").trim();
            String name = item.path("name").asText("").trim();
            String content = item.path("content").asText("").trim();
            if (!id.isBlank()) {
                course.put("id", id);
            }
            if (!name.isBlank()) {
                course.put("name", name);
            }
            if (!content.isBlank()) {
                course.put("content", content);
            }

            List<String> concepts = collectTextValues(item.path("concepts"));
            if (!concepts.isEmpty()) {
                course.put("concepts", concepts);
            }

            List<String> teacherNames = collectTextValues(item.path("teacherNames"));
            if (!teacherNames.isEmpty()) {
                course.put("teacherNames", teacherNames);
            }

            if (!course.isEmpty()) {
                courses.add(course);
            }
        }
        return courses;
    }

    private List<String> collectTextValues(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("").trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values.stream().distinct().toList();
    }

    private void addIfPresent(List<String> values, Object rawValue) {
        if (rawValue == null) {
            return;
        }
        String value = String.valueOf(rawValue).trim();
        if (!value.isBlank()) {
            values.add(value);
        }
    }

    private void addAllIfPresent(List<String> values, Object rawValue) {
        if (!(rawValue instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            addIfPresent(values, item);
        }
    }

    private boolean hasQaEvidence(JsonNode resultsNode) {
        if (resultsNode == null || resultsNode.isMissingNode() || resultsNode.isNull()) {
            return false;
        }

        if (resultsNode.isArray()) {
            for (JsonNode item : resultsNode) {
                if (isNonEmptyEvidenceItem(item)) {
                    return true;
                }
            }
            return false;
        }

        return isNonEmptyEvidenceItem(resultsNode);
    }

    private boolean isNonEmptyEvidenceItem(JsonNode item) {
        if (item == null || item.isMissingNode() || item.isNull()) {
            return false;
        }

        if (item.isTextual()) {
            return !item.asText("").trim().isBlank();
        }

        if (item.isNumber() || item.isBoolean()) {
            return true;
        }

        if (item.isObject()) {
            if (item.size() == 0) {
                return false;
            }
            JsonNode evidence = item.path("evidence");
            JsonNode tool = item.path("tool");
            if (!evidence.asText("").trim().isBlank()) {
                return true;
            }
            if (!tool.asText("").trim().isBlank()) {
                return true;
            }
            var fields = item.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (isNonEmptyEvidenceItem(entry.getValue())) {
                    return true;
                }
            }
            return false;
        }

        if (item.isArray()) {
            for (JsonNode child : item) {
                if (isNonEmptyEvidenceItem(child)) {
                    return true;
                }
            }
        }

        return false;
    }

    private String createErrorJson(String agentType, String errorMessage) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "ERROR",
                    "agentType", agentType,
                    "message", errorMessage,
                    "data", Map.of()
            ));
        } catch (Exception e) {
            return "{\"status\":\"ERROR\",\"message\":\"JSON serialization failed\"}";
        }
    }

    private boolean isErrorJson(String json) {
        return json.contains("\"status\"") && json.contains("\"ERROR\"") && json.contains("format");
    }

    private String resolveUserDisplayName(String userId) {
        try {
            Long id = Long.parseLong(userId);
            return userService.findById(id)
                    .map(user -> user.getRealName() != null && !user.getRealName().isBlank()
                            ? user.getRealName()
                            : user.getUsername())
                    .orElse("你");
        } catch (NumberFormatException ignored) {
            return "你";
        }
    }
}
