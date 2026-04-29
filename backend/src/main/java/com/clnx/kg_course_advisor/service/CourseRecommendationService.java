package com.clnx.kg_course_advisor.service;

import com.clnx.kg_course_advisor.util.UserIdUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class CourseRecommendationService {

    private static final int MAX_RECOMMENDATION_KEYWORDS = 3;
    private static final String RECOMMENDATION_KEYWORD_PROMPT = """
            你是课程推荐关键词提取器。
            任务：从用户原话中提取最多 3 个可用于课程检索的方向词、学科词或知识点。

            规则：
            - 只能提取用户明确表达的内容，或非常直接的等价归一结果。
            - 如果用户提到“AI”，可以归一成“人工智能”。
            - 不要编造数据库里不存在的课程名，也不要把宽泛词擅自改成更具体的方向。
            - 不要输出“推荐”“课程”“相关”“有没有”这类功能词。
            - 如果没有合适关键词，返回空数组。
            - 只返回 JSON，格式必须是：{"keywords":["词1","词2"]}

            用户输入：%s
            """;
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[\\s\\S]*}");
    private static final Pattern SPLIT_PATTERN = Pattern.compile("[，,。！？!？、/\\s]+|和|与|及|以及|还有|并且|或者|还是");
    private static final Set<String> KEYWORD_STOP_WORDS = Set.of(
            "推荐", "课程", "课", "相关", "方向", "领域", "方面", "知识点", "内容", "有没有", "有吗"
    );

    private final Driver neo4jDriver;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public Map<String, Object> recommendCourses(Long userId, String requirements) {
        List<String> userIds = UserIdUtils.buildUserIdCandidates(userId);
        List<String> keywords = extractKeywords(requirements);

        List<Map<String, Object>> matchedCourses = keywords.isEmpty()
                ? List.of()
                : deduplicateCourseRecommendations(findMatchedCourses(userIds, keywords, 6));
        List<Map<String, Object>> courses = keywords.isEmpty()
                ? deduplicateCourseRecommendations(findPopularCourses(userIds, 3))
                : matchedCourses.stream().limit(3).toList();

        if (!keywords.isEmpty() && courses.isEmpty()) {
            return Map.of(
                    "status", "NOT_FOUND",
                    "keywords", keywords,
                    "recommendationReason", "数据库中没有找到与你需求直接匹配的真实课程。你可以换一个更具体的方向，或者直接告诉我相关知识点。",
                    "matchedCourses", List.of(),
                    "courses", List.of(),
                    "learningPath", List.of(),
                    "suggestedAction", "你可以换一个更具体的关键词，例如课程主题、知识点或方法名，我再按数据库里的真实课程给你推荐。"
            );
        }

        String recommendationReason = keywords.isEmpty()
                ? "你这次没有给出明确方向，所以先从数据库中的真实热门课程里给你推荐。"
                : "根据你的需求关键词匹配到了数据库中的真实课程。";

        return Map.of(
                "status", "SUCCESS",
                "keywords", keywords,
                "recommendationReason", recommendationReason,
                "matchedCourses", matchedCourses,
                "courses", courses,
                "learningPath", courses.stream().map(item -> item.get("name")).toList(),
                "suggestedAction", "如果你对其中一门感兴趣，可以直接告诉我课程名，我来帮你选课。"
        );
    }

    List<String> extractKeywords(String requirements) {
        String normalized = requirements == null ? "" : requirements.trim();
        if (normalized.isBlank()) {
            return List.of();
        }

        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        keywords.addAll(extractKeywordsByPatterns(normalized));
        keywords.addAll(extractKeywordsByRules(normalized));
        keywords.addAll(extractKeywordsByLlm(normalized));

        return keywords.stream()
                .filter(this::isUsableKeyword)
                .limit(MAX_RECOMMENDATION_KEYWORDS)
                .toList();
    }

    private List<String> extractKeywordsByPatterns(String requirements) {
        String normalized = normalizeForValidation(requirements);
        LinkedHashSet<String> keywords = new LinkedHashSet<>();

        if (normalized.contains("ai") || normalized.contains("人工智能")) {
            keywords.add("人工智能");
        }
        if (normalized.contains("数据库")) {
            keywords.add("数据库");
        }
        if (normalized.contains("心理")) {
            keywords.add("心理学");
        }
        if (normalized.contains("医学") || normalized.contains("健康")) {
            keywords.add("医学");
        }
        if (normalized.contains("数据分析")) {
            keywords.add("数据分析");
        }
        if (normalized.contains("数据相关") || normalized.contains("做数据")) {
            keywords.add("数据科学");
        }
        if (normalized.contains("数据")) {
            keywords.add("数据");
        }
        if (normalized.contains("网络安全")) {
            keywords.add("网络安全");
        }
        if (normalized.contains("网络应用")) {
            keywords.add("网络应用");
        }
        if (normalized.contains("网络")) {
            keywords.add("网络");
        }
        if (normalized.contains("嵌入式")) {
            keywords.add("嵌入式");
        }
        if (normalized.contains("硬件")) {
            keywords.add("硬件");
        }
        if (normalized.contains("会计") || normalized.contains("财务") || normalized.contains("商业分析")) {
            keywords.add("会计");
        }
        if (normalized.contains("工程")) {
            keywords.add("工程");
        }

        return new ArrayList<>(keywords);
    }

    private List<String> extractKeywordsByRules(String requirements) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        String normalized = requirements
                .replace("推荐一门课给我", " ")
                .replace("推荐课程给我", " ")
                .replace("给我推荐", " ")
                .replace("帮我推荐", " ")
                .replace("推荐一些", " ")
                .replace("推荐一下", " ")
                .replace("推荐点", " ")
                .replace("推荐", " ")
                .replace("我适合先学哪几门", " ")
                .replace("适合先学哪几门", " ")
                .replace("先学哪几门", " ")
                .replace("一门课", " ")
                .replace("课程", " ")
                .replace("想学", " ")
                .replace("我想学", " ")
                .replace("我想接触", " ")
                .replace("接触", " ")
                .replace("想先选", " ")
                .replace("想选", " ")
                .replace("先找几门", " ")
                .replace("找几门", " ")
                .replace("以后可能做", " ")
                .replace("以后也许会做", " ")
                .replace("往", " ")
                .replace("方向靠一靠", " ")
                .replace("方向", " ");

        Matcher matcher = SPLIT_PATTERN.matcher(normalized);
        int start = 0;
        while (matcher.find()) {
            addRuleKeyword(keywords, normalized.substring(start, matcher.start()));
            start = matcher.end();
        }
        addRuleKeyword(keywords, normalized.substring(start));
        addRuleKeyword(keywords, normalized);

        return new ArrayList<>(keywords);
    }

    private void addRuleKeyword(LinkedHashSet<String> keywords, String rawPart) {
        String keyword = normalizeKeyword(rawPart);
        if (isUsableKeyword(keyword)) {
            keywords.add(keyword);
        }
    }

    private List<String> extractKeywordsByLlm(String requirements) {
        try {
            Prompt prompt = new Prompt(RECOMMENDATION_KEYWORD_PROMPT.formatted(requirements));
            ChatResponse response = chatModel.call(prompt);
            String content = extractAssistantText(response);
            if (content.isBlank()) {
                return List.of();
            }

            JsonNode root = objectMapper.readTree(extractJsonObject(content));
            JsonNode keywordsNode = root.path("keywords");
            if (!keywordsNode.isArray()) {
                return List.of();
            }

            LinkedHashSet<String> keywords = new LinkedHashSet<>();
            for (JsonNode keywordNode : keywordsNode) {
                String keyword = normalizeKeyword(keywordNode.asText(""));
                if (isGroundedKeyword(keyword, requirements)) {
                    keywords.add(keyword);
                }
            }
            return new ArrayList<>(keywords);
        } catch (Exception e) {
            log.debug("LLM keyword extraction unavailable, fallback to rule-based extraction only", e);
            return List.of();
        }
    }

    private String extractAssistantText(ChatResponse response) {
        return Optional.ofNullable(response)
                .map(ChatResponse::getResult)
                .map(Generation::getOutput)
                .map(AssistantMessage::getText)
                .orElse("");
    }

    private String extractJsonObject(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7).trim();
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3).trim();
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        }

        Matcher matcher = JSON_OBJECT_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group();
        }
        return trimmed;
    }

    private boolean isGroundedKeyword(String keyword, String requirements) {
        if (!isUsableKeyword(keyword)) {
            return false;
        }

        String normalizedKeyword = normalizeForValidation(keyword);
        String normalizedRequirements = normalizeForValidation(requirements);
        if (normalizedKeyword.isBlank() || normalizedRequirements.isBlank()) {
            return false;
        }

        if (normalizedRequirements.contains(normalizedKeyword)) {
            return true;
        }

        if ("人工智能".equals(keyword)) {
            return normalizedRequirements.contains("ai") || normalizedRequirements.contains("人工智能");
        }

        return false;
    }

    private boolean isUsableKeyword(String keyword) {
        return keyword != null
                && !keyword.isBlank()
                && keyword.length() >= 2
                && !KEYWORD_STOP_WORDS.contains(keyword);
    }

    private String normalizeKeyword(String rawKeyword) {
        String keyword = rawKeyword == null ? "" : rawKeyword.trim();
        keyword = keyword
                .replaceAll("[\"“”‘’《》【】()（）]", "")
                .replaceAll("[，,。！？!？、/]", " ")
                .replaceAll("^(一些|一个|几个|几门|一门|一门课|有关|关于|偏|跟|和|想学|适合|推荐|有没有|有没|是否有|接触|看看|补一些|补点|想补|先学|先选|选|先找几门)+", "")
                .replaceAll("^(给我|帮我|请问|我想|我适合|想要|想了解|我对|对|以后可能做|以后也许会做|以后做|最近总想看看)+", "")
                .replaceAll("(相关的课程|相关课程|相关的|相关|方向的课程|方向课程|方向|领域|方面)(呢|吗|呀|啊|吧|有没有)?$", "")
                .replaceAll("(课程|课)(呢|吗|呀|啊|吧|有没有)?$", "")
                .replaceAll("类(的|呢|吗|呀|啊|吧|有没有)?$", "")
                .replaceAll("(有没有|有吗|呢|吗|呀|啊|吧)$", "")
                .replaceAll("^(比较|更|太|很|先从|从|往)+", "")
                .replaceAll("(开始|看看|入门|上手|稳妥|基础一般|零基础|不太难|别太偏纯理论|能实用一点)$", "")
                .replaceAll("^的+|的+$", "")
                .replaceAll("\\s+", "")
                .trim();

        String normalizedLower = keyword.toLowerCase();
        if ("ai".equals(normalizedLower) || "a.i".equals(normalizedLower)) {
            return "人工智能";
        }
        return keyword;
    }

    private String normalizeForValidation(String text) {
        return text == null ? "" : text.toLowerCase().replaceAll("[\\p{Punct}\\s]+", "");
    }

    private List<Map<String, Object>> findMatchedCourses(List<String> userIds, List<String> keywords, int limit) {
        try (Session session = neo4jDriver.session()) {
            String cypher = """
                    MATCH (c:Course)
                    WHERE NOT EXISTS {
                      MATCH (me)-[:USER_COURSE]->(c)
                      WHERE (me:User OR me:Student)
                        AND toString(me.id) IN $userIds
                    }
                    OPTIONAL MATCH (c)-[:COURSE_CONCEPT]->(k:Concept)
                    OPTIONAL MATCH (t:Teacher)-[:TEACHER_COURSE]->(c)
                    OPTIONAL MATCH (u)-[:USER_COURSE]->(c)
                    WITH c,
                         collect(DISTINCT k.name) AS conceptNames,
                         collect(DISTINCT t.name) AS teacherNames,
                         count(DISTINCT u) AS enrollCount
                    WITH c, conceptNames, teacherNames, enrollCount,
                         reduce(score = 0, kw IN $keywords |
                           score +
                           CASE WHEN toLower(c.name) CONTAINS toLower(kw) THEN 12 ELSE 0 END +
                           CASE WHEN any(concept IN conceptNames WHERE toLower(concept) CONTAINS toLower(kw)) THEN 3 ELSE 0 END
                         ) AS matchScore,
                         reduce(nameHitCount = 0, kw IN $keywords |
                           nameHitCount + CASE WHEN toLower(c.name) CONTAINS toLower(kw) THEN 1 ELSE 0 END
                         ) AS nameHitCount,
                         reduce(conceptHitCount = 0, kw IN $keywords |
                           conceptHitCount + CASE WHEN any(concept IN conceptNames WHERE toLower(concept) CONTAINS toLower(kw)) THEN 1 ELSE 0 END
                         ) AS conceptHitCount,
                         [kw IN $keywords
                           WHERE toLower(c.name) CONTAINS toLower(kw)
                              OR any(concept IN conceptNames WHERE toLower(concept) CONTAINS toLower(kw))] AS matchedKeywords
                    WHERE matchScore > 0
                    RETURN c.id AS courseId,
                           c.name AS courseName,
                           c.content AS courseContent,
                           teacherNames,
                           conceptNames,
                           enrollCount,
                           matchScore,
                           nameHitCount,
                           conceptHitCount,
                           matchedKeywords
                    ORDER BY nameHitCount DESC, size(matchedKeywords) DESC, conceptHitCount DESC, matchScore DESC, enrollCount DESC, courseName
                    LIMIT $limit
                    """;

            List<Record> records = session.run(cypher, Map.of(
                    "userIds", userIds,
                    "keywords", keywords,
                    "limit", limit
            )).list();

            return IntStream.range(0, records.size())
                    .mapToObj(index -> toMatchedRecommendation(records.get(index), index + 1))
                    .toList();
        }
    }

    private List<Map<String, Object>> findPopularCourses(List<String> userIds, int limit) {
        try (Session session = neo4jDriver.session()) {
            String cypher = """
                    MATCH (c:Course)
                    WHERE NOT EXISTS {
                      MATCH (me)-[:USER_COURSE]->(c)
                      WHERE (me:User OR me:Student)
                        AND toString(me.id) IN $userIds
                    }
                    OPTIONAL MATCH (t:Teacher)-[:TEACHER_COURSE]->(c)
                    OPTIONAL MATCH (u)-[:USER_COURSE]->(c)
                    RETURN c.id AS courseId,
                           c.name AS courseName,
                           c.content AS courseContent,
                           collect(DISTINCT t.name) AS teacherNames,
                           count(DISTINCT u) AS enrollCount
                    ORDER BY enrollCount DESC, courseName
                    LIMIT $limit
                    """;

            List<Record> records = session.run(cypher, Map.of(
                    "userIds", userIds,
                    "limit", limit
            )).list();

            return IntStream.range(0, records.size())
                    .mapToObj(index -> toPopularRecommendation(records.get(index), index + 1))
                    .toList();
        }
    }

    private Map<String, Object> toMatchedRecommendation(Record record, int pathOrder) {
        List<String> matchedKeywords = record.get("matchedKeywords").asList(value -> value.asString());
        List<String> teacherNames = sanitizeList(record.get("teacherNames").asList(value -> value.asString()));
        List<String> conceptNames = sanitizeList(record.get("conceptNames").asList(value -> value.asString()));
        String courseId = record.get("courseId").asString();

        String reason = matchedKeywords.isEmpty()
                ? "这门课与当前需求相关。"
                : "匹配到的关键词：" + String.join("、", matchedKeywords) + "。";

        return buildRecommendation(
                courseId,
                record.get("courseName").asString(),
                record.get("courseContent").isNull() ? "" : record.get("courseContent").asString(),
                teacherNames,
                conceptNames,
                resolveCoursePrerequisites(courseId),
                reason,
                false,
                pathOrder
        );
    }

    private Map<String, Object> toPopularRecommendation(Record record, int pathOrder) {
        List<String> teacherNames = sanitizeList(record.get("teacherNames").asList(value -> value.asString()));
        long enrollCount = record.get("enrollCount").asLong(0);
        String courseId = record.get("courseId").asString();
        String reason = enrollCount > 0
                ? "这门课在当前数据里选课人数较多，适合作为热门课程推荐。"
                : "这门课来自当前数据库中的真实课程列表，适合作为兜底推荐。";

        return buildRecommendation(
                courseId,
                record.get("courseName").asString(),
                record.get("courseContent").isNull() ? "" : record.get("courseContent").asString(),
                teacherNames,
                List.of(),
                resolveCoursePrerequisites(courseId),
                reason,
                false,
                pathOrder
        );
    }

    private Map<String, Object> buildRecommendation(
            String id,
            String name,
            String content,
            List<String> teacherNames,
            List<String> conceptNames,
            List<String> prerequisites,
            String reason,
            boolean isEnrolled,
            Integer pathOrder) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("name", name);
        item.put("content", content == null ? "" : content);
        item.put("reason", reason);
        item.put("prerequisites", prerequisites);
        item.put("isEnrolled", isEnrolled);
        item.put("teacherNames", teacherNames);
        item.put("concepts", conceptNames.stream().limit(6).toList());
        item.put("pathOrder", pathOrder);
        item.put("graphFocus", id == null || id.isBlank() ? List.of(name) : List.of(id));
        return item;
    }

    private List<Map<String, Object>> deduplicateCourseRecommendations(List<Map<String, Object>> courses) {
        if (courses == null || courses.isEmpty()) {
            return List.of();
        }

        LinkedHashMap<String, Map<String, Object>> uniqueCourses = new LinkedHashMap<>();
        for (Map<String, Object> course : courses) {
            if (course == null || course.isEmpty()) {
                continue;
            }
            String name = String.valueOf(course.getOrDefault("name", "")).trim();
            String teacherSignature = "";
            Object rawTeachers = course.get("teacherNames");
            if (rawTeachers instanceof List<?> teacherList) {
                teacherSignature = teacherList.stream()
                        .map(String::valueOf)
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .collect(Collectors.joining("|"));
            }
            String key = "name:" + name + "|teachers:" + teacherSignature;
            uniqueCourses.putIfAbsent(key, course);
        }
        return new ArrayList<>(uniqueCourses.values());
    }

    private List<String> resolveCoursePrerequisites(String courseId) {
        if (courseId == null || courseId.isBlank()) {
            return List.of();
        }

        try (Session session = neo4jDriver.session()) {
            String cypher = """
                    MATCH (target:Course {id: $courseId})-[:COURSE_CONCEPT]->(concept:Concept)
                    MATCH (preConcept:Concept)-[:PREREQUISITE_DEPENDENCY]->(concept)
                    MATCH (preCourse:Course)-[:COURSE_CONCEPT]->(preConcept)
                    WHERE preCourse.id <> target.id
                    WITH preCourse, count(DISTINCT preConcept) AS matchedConceptCount
                    RETURN preCourse.name AS prerequisiteCourseName
                    ORDER BY matchedConceptCount DESC, prerequisiteCourseName ASC
                    LIMIT 6
                    """;

            List<Record> records = session.run(cypher, Map.of("courseId", courseId)).list();
            if (records == null || records.isEmpty()) {
                return List.of();
            }

            return sanitizeList(records.stream()
                    .map(record -> record.get("prerequisiteCourseName").isNull() ? "" : record.get("prerequisiteCourseName").asString())
                    .toList());
        } catch (Exception e) {
            log.debug("Failed to resolve prerequisites for course {}", courseId, e);
            return List.of();
        }
    }

    private List<String> sanitizeList(List<String> values) {
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }
}
