package com.clnx.kg_course_advisor.service;

import com.clnx.kg_course_advisor.util.UserIdUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CourseActionService {

    private static final Pattern COURSE_ID_PATTERN = Pattern.compile("(?i)(C\\d{3,}|C_[^\\s|]+)");

    private final Driver neo4jDriver;

    public Map<String, Object> enrollByCourseReference(Long userId, String requestedCourseReference) {
        CourseResolution resolution = resolveCourseReference(requestedCourseReference);
        if (!resolution.canProceed()) {
            return buildFailureResponse("SELECT", resolution.message(), resolution.course(), resolution.additionalInfo());
        }

        List<String> userIds = UserIdUtils.buildUserIdCandidates(userId);
        try (Session session = neo4jDriver.session()) {
            String cypher = """
                    CALL {
                      WITH $userIds AS userIds
                      OPTIONAL MATCH (candidate)
                      WHERE (candidate:User OR candidate:Student)
                        AND toString(candidate.id) IN userIds
                      WITH candidate
                      ORDER BY CASE
                        WHEN candidate IS NOT NULL AND candidate:Student THEN 0
                        ELSE 1
                      END
                      RETURN candidate AS u
                      LIMIT 1
                    }
                    OPTIONAL MATCH (c:Course {id: $courseId})
                    OPTIONAL MATCH (u)-[:USER_COURSE]->(alreadySelected:Course {id: $courseId})
                    OPTIONAL MATCH (c)-[:COURSE_TIME]->(targetTime:Time)
                    WITH u, c, alreadySelected, collect(DISTINCT targetTime) AS targetTimes
                    OPTIONAL MATCH (u)-[:USER_COURSE]->(conflictCourse:Course)-[:COURSE_TIME]->(conflictTime:Time)
                    WHERE c IS NOT NULL
                      AND size(targetTimes) > 0
                      AND conflictCourse.id <> c.id
                      AND any(targetTime IN targetTimes WHERE
                        targetTime IS NOT NULL
                        AND conflictTime IS NOT NULL
                        AND (
                          (targetTime.id IS NOT NULL AND conflictTime.id = targetTime.id)
                          OR (
                            coalesce(toString(conflictTime.weekday_index), '') = coalesce(toString(targetTime.weekday_index), '')
                            AND coalesce(conflictTime.half_day, '') = coalesce(targetTime.half_day, '')
                            AND coalesce(toString(conflictTime.period_index), '') = coalesce(toString(targetTime.period_index), '')
                          )
                        )
                      )
                    WITH u, c, alreadySelected, targetTimes, collect(DISTINCT conflictCourse) AS conflictCourses
                    FOREACH (_ IN CASE
                      WHEN u IS NOT NULL
                       AND c IS NOT NULL
                       AND alreadySelected IS NULL
                       AND size(conflictCourses) = 0
                      THEN [1]
                      ELSE []
                    END |
                      MERGE (u)-[:USER_COURSE]->(c)
                    )
                    WITH u, c, alreadySelected, targetTimes, conflictCourses
                    OPTIONAL MATCH (u)-[:USER_COURSE]->(selectedCourse:Course {id: $courseId})
                    RETURN
                      CASE
                        WHEN u IS NULL THEN 'USER_NOT_FOUND'
                        WHEN c IS NULL THEN 'COURSE_NOT_FOUND'
                        WHEN alreadySelected IS NOT NULL THEN 'ALREADY_ENROLLED'
                        WHEN size(conflictCourses) > 0 THEN 'TIME_CONFLICT'
                        WHEN selectedCourse IS NULL THEN 'ENROLL_NOT_PERSISTED'
                        WHEN size(targetTimes) = 0 THEN 'SUCCESS_NO_TIME'
                        ELSE 'SUCCESS'
                      END AS status,
                      c.id AS courseId,
                      c.name AS courseName,
                      c.content AS courseContent,
                      [course IN conflictCourses | course.name] AS conflictCourseNames
                    """;

            Record record = session.run(cypher, Map.of(
                    "userIds", userIds,
                    "courseId", resolution.course().id()
            )).single();

            String status = record.get("status").asString();
            log.info("Enroll execution result: userId={}, requestedCourseReference={}, matchedCourseName={}, status={}",
                    userId, requestedCourseReference, resolution.course().name(), status);

            Map<String, Object> affectedCourse = buildAffectedCourse(
                    record.get("courseId").isNull() ? resolution.course().id() : record.get("courseId").asString(),
                    record.get("courseName").isNull() ? resolution.course().name() : record.get("courseName").asString(),
                    record.get("courseContent").isNull() ? resolution.course().content() : record.get("courseContent").asString()
            );

            return switch (status) {
                case "SUCCESS" -> buildSuccessResponse(
                        "SELECT",
                        String.format("已成功为你选上《%s》。", affectedCourse.get("name")),
                        affectedCourse,
                        "matchedCourseName=" + resolution.course().name()
                );
                case "SUCCESS_NO_TIME" -> buildSuccessResponse(
                        "SELECT",
                        String.format("已成功为你选上《%s》，但这门课暂时没有时间信息。", affectedCourse.get("name")),
                        affectedCourse,
                        "matchedCourseName=" + resolution.course().name() + ";scheduleStatus=TIME_UNAVAILABLE"
                );
                case "ALREADY_ENROLLED" -> buildFailureResponse(
                        "SELECT",
                        String.format("你已经选过《%s》了，这次没有重复选课。", affectedCourse.get("name")),
                        affectedCourse,
                        "lowLevelStatus=ALREADY_ENROLLED"
                );
                case "TIME_CONFLICT" -> buildFailureResponse(
                        "SELECT",
                        String.format("《%s》与当前已选课程时间冲突，未完成选课。", affectedCourse.get("name")),
                        affectedCourse,
                        "conflictCourses=" + record.get("conflictCourseNames").asList(value -> value.asString()).stream()
                                .collect(Collectors.joining(" / "))
                );
                case "ENROLL_NOT_PERSISTED" -> buildFailureResponse(
                        "SELECT",
                        String.format("《%s》这次没有成功写入选课结果。", affectedCourse.get("name")),
                        affectedCourse,
                        "lowLevelStatus=ENROLL_NOT_PERSISTED"
                );
                case "COURSE_NOT_FOUND" -> buildFailureResponse(
                        "SELECT",
                        String.format("没有找到可选课程《%s》。", requestedCourseReference),
                        affectedCourse,
                        "lowLevelStatus=COURSE_NOT_FOUND"
                );
                case "USER_NOT_FOUND" -> buildErrorResponse("SELECT", "当前用户不存在，无法执行选课。");
                default -> buildFailureResponse(
                        "SELECT",
                        String.format("《%s》选课未完成。", affectedCourse.get("name")),
                        affectedCourse,
                        "lowLevelStatus=" + status
                );
            };
        } catch (Exception e) {
            log.error("Enroll by course reference failed: userId={}, requestedCourseReference={}", userId, requestedCourseReference, e);
            return buildErrorResponse("SELECT", "选课时发生异常：" + e.getMessage());
        }
    }

    public Map<String, Object> enrollByCourseName(Long userId, String requestedCourseName) {
        return enrollByCourseReference(userId, requestedCourseName);
    }

    public Map<String, Object> dropByCourseReference(Long userId, String requestedCourseReference) {
        CourseResolution resolution = resolveEnrolledCourseReference(userId, requestedCourseReference);
        if (!resolution.canProceed()) {
            return buildFailureResponse("DROP", resolution.message(), resolution.course(), resolution.additionalInfo());
        }

        List<String> userIds = UserIdUtils.buildUserIdCandidates(userId);
        try (Session session = neo4jDriver.session()) {
            String cypher = """
                    MATCH (u)
                    WHERE (u:User OR u:Student)
                      AND toString(u.id) IN $userIds
                    WITH u
                    ORDER BY CASE WHEN u:Student THEN 0 ELSE 1 END
                    LIMIT 1
                    OPTIONAL MATCH (u)-[r:USER_COURSE]->(c:Course {id: $courseId})
                    FOREACH (_ IN CASE WHEN r IS NOT NULL THEN [1] ELSE [] END | DELETE r)
                    RETURN CASE WHEN r IS NULL THEN 'NOT_ENROLLED' ELSE 'SUCCESS' END AS status,
                           c.id AS courseId,
                           c.name AS courseName,
                           c.content AS courseContent
                    """;

            Record record = session.run(cypher, Map.of(
                    "userIds", userIds,
                    "courseId", resolution.course().id()
            )).single();

            String status = record.get("status").asString();
            Map<String, Object> affectedCourse = buildAffectedCourse(
                    resolution.course().id(),
                    resolution.course().name(),
                    resolution.course().content()
            );

            return switch (status) {
                case "SUCCESS" -> buildSuccessResponse(
                        "DROP",
                        String.format("已为你退选《%s》。", affectedCourse.get("name")),
                        affectedCourse,
                        "matchedCourseName=" + resolution.course().name()
                );
                case "NOT_ENROLLED" -> buildFailureResponse(
                        "DROP",
                        String.format("你当前并没有选《%s》，所以没有退课。", affectedCourse.get("name")),
                        affectedCourse,
                        "lowLevelStatus=NOT_ENROLLED"
                );
                default -> buildFailureResponse(
                        "DROP",
                        String.format("《%s》退课未完成。", affectedCourse.get("name")),
                        affectedCourse,
                        "lowLevelStatus=" + status
                );
            };
        } catch (Exception e) {
            log.error("Drop by course reference failed: userId={}, requestedCourseReference={}", userId, requestedCourseReference, e);
            return buildErrorResponse("DROP", "退课时发生异常：" + e.getMessage());
        }
    }

    public Map<String, Object> dropByCourseName(Long userId, String requestedCourseName) {
        return dropByCourseReference(userId, requestedCourseName);
    }

    private CourseResolution resolveCourseReference(String requestedCourseReference) {
        String courseId = extractCourseId(requestedCourseReference);
        if (courseId != null) {
            return resolveCourseById(courseId);
        }
        return resolveCourseByName(requestedCourseReference);
    }

    private CourseResolution resolveEnrolledCourseReference(Long userId, String requestedCourseReference) {
        String courseId = extractCourseId(requestedCourseReference);
        if (courseId != null) {
            return resolveEnrolledCourseById(userId, courseId);
        }
        return resolveEnrolledCourseByName(userId, requestedCourseReference);
    }

    private CourseResolution resolveCourseById(String courseId) {
        try (Session session = neo4jDriver.session()) {
            String cypher = """
                    MATCH (c:Course)
                    WHERE toUpper(c.id) = toUpper($courseId)
                    RETURN c.id AS courseId, c.name AS courseName, c.content AS courseContent
                    LIMIT 2
                    """;

            List<Record> records = session.run(cypher, Map.of("courseId", courseId)).list();
            if (records.isEmpty()) {
                return CourseResolution.failure(
                        String.format("没有找到编号为 %s 的课程。", courseId.toUpperCase()),
                        null,
                        "reason=COURSE_ID_NOT_FOUND"
                );
            }

            return CourseResolution.success(toCandidate(records.get(0)), "matchedBy=COURSE_ID");
        }
    }

    private CourseResolution resolveCourseByName(String requestedCourseName) {
        String normalized = normalizeCourseReference(requestedCourseName);
        if (normalized.isBlank()) {
            return CourseResolution.failure("课程名称不明确，请直接告诉我要操作的课程名。", null, "reason=EMPTY_COURSE_NAME");
        }

        List<String> fallbackQueries = buildFallbackQueries(normalized);

        CourseResolution lastFailure = null;
        for (String query : fallbackQueries) {
            CourseResolution resolution = resolveCourseBySingleName(query, normalized);
            if (resolution.canProceed()) {
                return resolution;
            }
            lastFailure = resolution;
        }

        return lastFailure == null
                ? CourseResolution.failure(
                String.format("没有找到与《%s》匹配的课程。", normalized),
                null,
                "reason=COURSE_NOT_FOUND")
                : lastFailure;
    }

    private CourseResolution resolveEnrolledCourseById(Long userId, String courseId) {
        List<String> userIds = UserIdUtils.buildUserIdCandidates(userId);
        try (Session session = neo4jDriver.session()) {
            String cypher = """
                    MATCH (u)-[:USER_COURSE]->(c:Course)
                    WHERE (u:User OR u:Student)
                      AND toString(u.id) IN $userIds
                      AND toUpper(c.id) = toUpper($courseId)
                    RETURN c.id AS courseId, c.name AS courseName, c.content AS courseContent
                    LIMIT 2
                    """;

            List<Record> records = session.run(cypher, Map.of(
                    "userIds", userIds,
                    "courseId", courseId
            )).list();

            if (records.isEmpty()) {
                return CourseResolution.failure(
                        String.format("你当前没有选编号为 %s 的课程。", courseId.toUpperCase()),
                        null,
                        "reason=NOT_ENROLLED"
                );
            }

            return CourseResolution.success(toCandidate(records.get(0)), "matchedBy=COURSE_ID");
        }
    }

    private CourseResolution resolveEnrolledCourseByName(Long userId, String requestedCourseName) {
        String normalized = normalizeCourseReference(requestedCourseName);
        if (normalized.isBlank()) {
            return CourseResolution.failure("课程名称不明确，请直接告诉我要退掉的课程名。", null, "reason=EMPTY_COURSE_NAME");
        }

        List<String> userIds = UserIdUtils.buildUserIdCandidates(userId);
        try (Session session = neo4jDriver.session()) {
            String cypher = """
                    MATCH (u)-[:USER_COURSE]->(c:Course)
                    WHERE (u:User OR u:Student)
                      AND toString(u.id) IN $userIds
                    WITH c,
                      CASE
                        WHEN c.name = $courseName THEN 0
                        WHEN toLower(c.name) = toLower($courseName) THEN 1
                        WHEN replace(toLower(c.name), ' ', '') = replace(toLower($courseName), ' ', '') THEN 2
                        WHEN toLower(c.name) CONTAINS toLower($courseName) THEN 3
                        WHEN toLower($courseName) CONTAINS toLower(c.name) THEN 4
                        ELSE 99
                      END AS matchScore
                    WHERE matchScore < 99
                    RETURN c.id AS courseId, c.name AS courseName, c.content AS courseContent, matchScore
                    ORDER BY matchScore, size(c.name), c.name
                    LIMIT 5
                    """;

            List<Record> records = session.run(cypher, Map.of(
                    "userIds", userIds,
                    "courseName", normalized
            )).list();

            if (records.isEmpty()) {
                return CourseResolution.failure(
                        String.format("你当前没有选中与《%s》匹配的课程。", normalized),
                        null,
                        "reason=NOT_ENROLLED"
                );
            }

            int bestScore = records.get(0).get("matchScore").asInt();
            List<CourseCandidate> bestCandidates = records.stream()
                    .filter(record -> record.get("matchScore").asInt() == bestScore)
                    .map(this::toCandidate)
                    .toList();

            if (bestScore <= 2 && bestCandidates.size() == 1) {
                return CourseResolution.success(bestCandidates.get(0), "matchedByScore=" + bestScore);
            }

            String candidateNames = records.stream()
                    .map(record -> record.get("courseName").asString())
                    .distinct()
                    .collect(Collectors.joining(" / "));

            return CourseResolution.failure(
                    String.format("没有办法唯一确认你要退的课程《%s》。最接近的是：%s", normalized, candidateNames),
                    bestCandidates.isEmpty() ? null : bestCandidates.get(0),
                    "candidateCourses=" + candidateNames
            );
        }
    }

    private String normalizeCourseReference(String value) {
        String normalized = value == null ? "" : value.trim();
        normalized = normalized.replace('《', ' ')
                .replace('》', ' ')
                .replace('“', ' ')
                .replace('”', ' ')
                .replace('"', ' ')
                .replace('*', ' ')
                .replace('`', ' ')
                .replace('_', ' ');
        normalized = normalized.replaceAll("^[请帮麻烦给我把将想要还再就一下\\s]+", "");
        normalized = normalized.replaceAll("^(我还想|我想要|我想|我要|帮我|给我|请帮我|请给我|麻烦帮我)", "");
        normalized = normalized.replaceAll("^(就选|就退|再选|再退|选课|选修|选择|选上|选|退课|退选|退|查询|查一下|查|报名|注册|取消选课|取消|删除|enroll|drop|query)\\s*", "");
        normalized = normalized.replaceAll("^(一门|一个|一下|这门|这个|它)\\s*", "");
        normalized = normalized.replaceAll("\\s*-\\s*由.*$", " ");
        normalized = normalized.replaceAll("\\s*由.*老师讲授$", " ");
        normalized = normalized.replaceAll("\\s*老师.*$", " ");
        normalized = normalized.replaceAll("\\s*[-:：]+\\s*$", " ");
        normalized = normalized.replaceAll("[。！？！，,.!?]+$", " ");
        normalized = normalized.replaceAll("\\s*吧$", " ");
        normalized = normalized.replaceAll("\\s+", " ").trim();

        if (normalized.contains(" / ") || normalized.contains("/")) {
            String[] parts = normalized.split("\\s*/\\s*");
            String best = "";
            for (String part : parts) {
                String candidate = part.trim();
                if (candidate.length() > best.length()) {
                    best = candidate;
                }
            }
            if (!best.isBlank()) {
                normalized = best;
            }
        }

        return normalized;
    }

    private CourseResolution resolveCourseBySingleName(String query, String originalQuery) {
        try (Session session = neo4jDriver.session()) {
            String cypher = """
                    MATCH (c:Course)
                    WITH c,
                      CASE
                        WHEN c.name = $courseName THEN 0
                        WHEN toLower(c.name) = toLower($courseName) THEN 1
                        WHEN replace(toLower(c.name), ' ', '') = replace(toLower($courseName), ' ', '') THEN 2
                        WHEN toLower(c.name) CONTAINS toLower($courseName) THEN 3
                        WHEN toLower($courseName) CONTAINS toLower(c.name) THEN 4
                        ELSE 99
                      END AS matchScore
                    WHERE matchScore < 99
                    RETURN c.id AS courseId, c.name AS courseName, c.content AS courseContent, matchScore
                    ORDER BY matchScore, size(c.name), c.name
                    LIMIT 5
                    """;

            List<Record> records = session.run(cypher, Map.of("courseName", query)).list();
            if (records.isEmpty()) {
                return CourseResolution.failure(
                        String.format("没有找到与《%s》匹配的课程。", originalQuery),
                        null,
                        "reason=COURSE_NOT_FOUND"
                );
            }

            int bestScore = records.get(0).get("matchScore").asInt();
            List<CourseCandidate> bestCandidates = records.stream()
                    .filter(record -> record.get("matchScore").asInt() == bestScore)
                    .map(this::toCandidate)
                    .toList();

            if (bestCandidates.size() == 1 && (bestScore <= 3 || (bestScore == 4 && query.length() >= 4))) {
                return CourseResolution.success(bestCandidates.get(0), "matchedByScore=" + bestScore + ";query=" + query);
            }

            if (bestScore <= 1) {
                CourseCandidate preferred = choosePreferredCandidate(bestCandidates, query);
                if (preferred != null) {
                    return CourseResolution.success(preferred, "matchedByApproximateDuplicate=" + bestScore + ";query=" + query);
                }
            }

            String candidateNames = records.stream()
                    .map(record -> record.get("courseName").asString())
                    .distinct()
                    .collect(Collectors.joining(" / "));

            return CourseResolution.failure(
                    String.format("没有找到可以直接确认的课程《%s》。最接近的是：%s", originalQuery, candidateNames),
                    bestCandidates.isEmpty() ? null : bestCandidates.get(0),
                    "candidateCourses=" + candidateNames
            );
        }
    }

    private List<String> buildFallbackQueries(String normalized) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        if (!normalized.isBlank()) {
            queries.add(normalized);
        }

        if (normalized.contains(" ")) {
            queries.add(normalized.replace(" ", ""));
        }

        if (normalized.contains("程序设计基础") && normalized.contains("C++")) {
            queries.add("C++语言程序设计基础");
        }

        if (normalized.contains(" / ")) {
            for (String part : normalized.split("\\s*/\\s*")) {
                if (!part.isBlank()) {
                    queries.add(part.trim());
                }
            }
        }

        return new ArrayList<>(queries);
    }

    private CourseCandidate choosePreferredCandidate(List<CourseCandidate> candidates, String query) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        String normalizedQuery = normalizeComparableName(query);
        List<CourseCandidate> exactNameCandidates = candidates.stream()
                .filter(candidate -> normalizeComparableName(candidate.name()).equals(normalizedQuery))
                .toList();

        if (exactNameCandidates.size() == 1) {
            return exactNameCandidates.get(0);
        }

        if (!exactNameCandidates.isEmpty() && allSameNormalizedName(exactNameCandidates)) {
            return exactNameCandidates.get(0);
        }

        if (allSameNormalizedName(candidates)) {
            return candidates.get(0);
        }

        return null;
    }

    private boolean allSameNormalizedName(List<CourseCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        String first = normalizeComparableName(candidates.get(0).name());
        for (CourseCandidate candidate : candidates) {
            if (!normalizeComparableName(candidate.name()).equals(first)) {
                return false;
            }
        }
        return true;
    }

    private String normalizeComparableName(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", "")
                .replace("《", "")
                .replace("》", "")
                .trim()
                .toLowerCase();
    }

    private String extractCourseId(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = COURSE_ID_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).toUpperCase();
        }
        return null;
    }

    private CourseCandidate toCandidate(Record record) {
        return new CourseCandidate(
                record.get("courseId").asString(),
                record.get("courseName").asString(),
                record.get("courseContent").isNull() ? "" : record.get("courseContent").asString()
        );
    }

    private Map<String, Object> buildAffectedCourse(String id, String name, String content) {
        Map<String, Object> course = new LinkedHashMap<>();
        course.put("id", id == null ? "" : id);
        course.put("name", name == null ? "" : name);
        course.put("content", content == null ? "" : content);
        return course;
    }

    private Map<String, Object> buildSuccessResponse(String operationType, String message, Map<String, Object> affectedCourse, String additionalInfo) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "SUCCESS");
        response.put("operationType", operationType);
        response.put("message", message);
        response.put("affectedCourse", affectedCourse);
        response.put("enrolledCourses", List.of());
        response.put("metadata", Map.of(
                "operationTime", OffsetDateTime.now().toString(),
                "additionalInfo", additionalInfo == null ? "" : additionalInfo
        ));
        response.put("conflictCourses", extractConflictCourseMaps(additionalInfo));
        response.put("targetTime", resolveCourseTargetTime(String.valueOf(affectedCourse.getOrDefault("id", ""))));
        response.put("currentScheduleRefs", List.of());
        response.put("graphFocus", buildGraphFocus(affectedCourse));
        response.put("canProceed", true);
        response.put("requiresConfirmation", false);
        response.put("confirmationPrompt", "");
        return response;
    }

    private Map<String, Object> buildFailureResponse(String operationType, String message, CourseCandidate course, String additionalInfo) {
        return buildFailureResponse(
                operationType,
                message,
                course == null ? Map.of("id", "", "name", "", "content", "") : buildAffectedCourse(course.id(), course.name(), course.content()),
                additionalInfo
        );
    }

    private Map<String, Object> buildFailureResponse(String operationType, String message, Map<String, Object> affectedCourse, String additionalInfo) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "FAILED");
        response.put("operationType", operationType);
        response.put("message", message);
        response.put("affectedCourse", affectedCourse);
        response.put("enrolledCourses", List.of());
        response.put("metadata", Map.of(
                "operationTime", OffsetDateTime.now().toString(),
                "additionalInfo", additionalInfo == null ? "" : additionalInfo
        ));
        response.put("conflictCourses", extractConflictCourseMaps(additionalInfo));
        response.put("targetTime", resolveCourseTargetTime(String.valueOf(affectedCourse.getOrDefault("id", ""))));
        response.put("currentScheduleRefs", List.of());
        response.put("graphFocus", buildGraphFocus(affectedCourse));
        response.put("canProceed", false);
        response.put("requiresConfirmation", false);
        response.put("confirmationPrompt", "");
        return response;
    }

    private Map<String, Object> buildErrorResponse(String operationType, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ERROR");
        response.put("operationType", operationType);
        response.put("message", message);
        response.put("affectedCourse", Map.of("id", "", "name", "", "content", ""));
        response.put("enrolledCourses", List.of());
        response.put("metadata", Map.of(
                "operationTime", OffsetDateTime.now().toString(),
                "additionalInfo", ""
        ));
        response.put("conflictCourses", List.of());
        response.put("targetTime", null);
        response.put("currentScheduleRefs", List.of());
        response.put("graphFocus", List.of());
        response.put("canProceed", false);
        response.put("requiresConfirmation", false);
        response.put("confirmationPrompt", "");
        return response;
    }

    private List<String> buildGraphFocus(Map<String, Object> affectedCourse) {
        if (affectedCourse == null) {
            return List.of();
        }
        Object courseId = affectedCourse.get("id");
        if (courseId == null) {
            return List.of();
        }
        String normalized = String.valueOf(courseId).trim();
        return normalized.isBlank() ? List.of() : List.of(normalized);
    }

    private List<Map<String, Object>> extractConflictCourseMaps(String additionalInfo) {
        if (additionalInfo == null || !additionalInfo.startsWith("conflictCourses=")) {
            return List.of();
        }

        String raw = additionalInfo.substring("conflictCourses=".length()).trim();
        if (raw.isBlank()) {
            return List.of();
        }

        return java.util.Arrays.stream(raw.split("\\s*/\\s*"))
                .map(String::trim)
                .filter(name -> !name.isBlank())
                .distinct()
                .map(name -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", "");
                    item.put("name", name);
                    return item;
                })
                .toList();
    }

    private Map<String, Object> resolveCourseTargetTime(String courseId) {
        if (courseId == null || courseId.isBlank()) {
            return null;
        }

        try (Session session = neo4jDriver.session()) {
            String cypher = """
                    MATCH (:Course {id: $courseId})-[:COURSE_TIME]->(t:Time)
                    RETURN t.weekday_index AS weekdayIndex,
                           t.half_day AS halfDay,
                           t.period_index AS periodIndex,
                           t.start_time AS startTime,
                           t.end_time AS endTime
                    LIMIT 1
                    """;

            List<Record> records = session.run(cypher, Map.of("courseId", courseId)).list();
            if (records.isEmpty()) {
                return null;
            }

            Record record = records.get(0);
            Map<String, Object> time = new LinkedHashMap<>();
            time.put("weekdayIndex", record.get("weekdayIndex").isNull() ? null : record.get("weekdayIndex").asInt());
            time.put("halfDay", record.get("halfDay").isNull() ? null : record.get("halfDay").asString());
            time.put("periodIndex", record.get("periodIndex").isNull() ? null : record.get("periodIndex").asInt());
            time.put("startTime", record.get("startTime").isNull() ? null : record.get("startTime").asString());
            time.put("endTime", record.get("endTime").isNull() ? null : record.get("endTime").asString());
            return time;
        } catch (Exception e) {
            log.debug("Failed to resolve target time for course {}", courseId, e);
            return null;
        }
    }

    private record CourseCandidate(String id, String name, String content) {
    }

    private record CourseResolution(boolean canProceed, CourseCandidate course, String message, String additionalInfo) {
        static CourseResolution success(CourseCandidate course, String additionalInfo) {
            return new CourseResolution(true, course, "", additionalInfo);
        }

        static CourseResolution failure(String message, CourseCandidate course, String additionalInfo) {
            return new CourseResolution(false, course, message, additionalInfo);
        }
    }
}
