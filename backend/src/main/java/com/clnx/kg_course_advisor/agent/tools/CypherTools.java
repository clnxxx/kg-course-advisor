package com.clnx.kg_course_advisor.agent.tools;

import com.clnx.kg_course_advisor.agent.AgentProgressNotifier;
import com.clnx.kg_course_advisor.service.CourseActionService;
import com.clnx.kg_course_advisor.service.CourseRecommendationService;
import com.clnx.kg_course_advisor.service.CypherExecutionService;
import com.clnx.kg_course_advisor.util.UserIdUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CypherTools {

    private final CypherExecutionService cypherExecutionService;
    private final CourseActionService courseActionService;
    private final CourseRecommendationService courseRecommendationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool(description = "Search courses by keyword")
    public String searchCourses(@ToolParam(description = "keyword") String keyword) {
        AgentProgressNotifier.publish("Searching knowledge graph: courses");
        log.info("searchCourses: keyword={}", keyword);
        String cypher = "MATCH (c:Course) WHERE c.name CONTAINS $keyword RETURN c.id, c.name, c.content LIMIT 10";
        return cypherExecutionService.executeQuery(cypher, Map.of("keyword", keyword));
    }

    // Alias wrappers for providers/models that emit uppercase snake_case tool names.
    @Tool(description = "Alias of searchCourses(keyword)")
    public String SEARCH_COURSES(@ToolParam(description = "keyword") String keyword) {
        return searchCourses(keyword);
    }

    @Tool(description = "Get concepts of a course")
    public String getCourseConcepts(@ToolParam(description = "course name") String courseName) {
        AgentProgressNotifier.publish("Searching knowledge graph: course concepts");
        log.info("getCourseConcepts: courseName={}", courseName);
        String cypher = "MATCH (c:Course)-[:COURSE_CONCEPT]->(k:Concept) WHERE c.name CONTAINS $courseName RETURN c.name AS courseName, k.name AS conceptName, k.content AS conceptContent LIMIT 20";
        return cypherExecutionService.executeQuery(cypher, Map.of("courseName", courseName));
    }

    @Tool(description = "Alias of getCourseConcepts(courseName)")
    public String GET_COURSE_CONCEPTS(@ToolParam(description = "course name") String courseName) {
        return getCourseConcepts(courseName);
    }

    @Tool(description = "Get prerequisites of a concept")
    public String getConceptPrerequisites(@ToolParam(description = "concept name") String conceptName) {
        AgentProgressNotifier.publish("Searching knowledge graph: prerequisites");
        log.info("getConceptPrerequisites: conceptName={}", conceptName);
        String cypher = "MATCH (pre:Concept)-[:PREREQUISITE_DEPENDENCY]->(k:Concept) WHERE k.name CONTAINS $conceptName RETURN k.name AS conceptName, pre.name AS prerequisiteName, pre.content AS prerequisiteContent";
        return cypherExecutionService.executeQuery(cypher, Map.of("conceptName", conceptName));
    }

    @Tool(description = "Alias of getConceptPrerequisites(conceptName)")
    public String GET_CONCEPT_PREREQUISITES(@ToolParam(description = "concept name") String conceptName) {
        return getConceptPrerequisites(conceptName);
    }

    @Tool(description = "Get prerequisite courses of a course")
    public String getCoursePrerequisites(@ToolParam(description = "course name") String courseName) {
        AgentProgressNotifier.publish("Searching knowledge graph: course prerequisites");
        log.info("getCoursePrerequisites: courseName={}", courseName);
        return cypherExecutionService.executeQuery(coursePrerequisiteCypher(), Map.of("courseName", courseName));
    }

    @Tool(description = "Alias of getCoursePrerequisites(courseName)")
    public String GET_COURSE_PREREQUISITES(@ToolParam(description = "course name") String courseName) {
        return getCoursePrerequisites(courseName);
    }

    public List<Map<String, Object>> findCoursePrerequisites(String courseName) {
        log.info("findCoursePrerequisites: courseName={}", courseName);
        return cypherExecutionService.executeQueryRecords(coursePrerequisiteCypher(), Map.of("courseName", courseName));
    }

    @Tool(description = "Get teachers by course name")
    public String getCourseTeachers(@ToolParam(description = "course name") String courseName) {
        AgentProgressNotifier.publish("Searching knowledge graph: course teachers");
        log.info("getCourseTeachers: courseName={}", courseName);
        String cypher = "MATCH (t:Teacher)-[:TEACHER_COURSE]->(c:Course) WHERE c.name CONTAINS $courseName RETURN t.name AS teacherName, c.id AS courseId, c.name AS courseName, c.content AS courseContent";
        return cypherExecutionService.executeQuery(cypher, Map.of("courseName", courseName));
    }

    @Tool(description = "Alias of getCourseTeachers(courseName)")
    public String GET_COURSE_TEACHERS(@ToolParam(description = "course name") String courseName) {
        return getCourseTeachers(courseName);
    }

    @Tool(description = "Get courses by teacher name")
    public String getTeacherCourses(@ToolParam(description = "teacher name") String teacherName) {
        AgentProgressNotifier.publish("Searching knowledge graph: teacher courses");
        log.info("getTeacherCourses: teacherName={}", teacherName);
        String cypher = "MATCH (t:Teacher)-[:TEACHER_COURSE]->(c:Course) WHERE t.name CONTAINS $teacherName RETURN t.name AS teacherName, c.id AS courseId, c.name AS courseName, c.content AS courseContent";
        return cypherExecutionService.executeQuery(cypher, Map.of("teacherName", teacherName));
    }

    @Tool(description = "Alias of getTeacherCourses(teacherName)")
    public String GET_TEACHER_COURSES(@ToolParam(description = "teacher name") String teacherName) {
        return getTeacherCourses(teacherName);
    }

    @Tool(description = "Get enrolled courses for user")
    public String getUserEnrolledCourses(@ToolParam(description = "user id, e.g. 1") String userId) {
        AgentProgressNotifier.publish("Searching knowledge graph: enrolled courses");
        List<String> userIds = UserIdUtils.buildUserIdCandidates(userId);
        log.info("getUserEnrolledCourses: userId={}, candidates={}", userId, userIds);
        String cypher = """
                MATCH (u)-[:USER_COURSE]->(c:Course)
                WHERE (u:User OR u:Student)
                  AND toString(u.id) IN $userIds
                OPTIONAL MATCH (c)-[:COURSE_TIME]->(t:Time)
                RETURN DISTINCT
                  c.id AS courseId,
                  c.name AS courseName,
                  c.content AS courseContent,
                  t.id AS timeId,
                  t.weekday_name AS weekdayName,
                  t.half_day AS halfDay,
                  t.period_index AS periodIndex,
                  t.start_time AS startTime,
                  t.end_time AS endTime
                """;
        return cypherExecutionService.executeQuery(cypher, Map.of("userIds", userIds));
    }

    @Tool(description = "Alias of getUserEnrolledCourses(userId)")
    public String GET_USER_ENROLLED_COURSES(@ToolParam(description = "user id, e.g. 1") String userId) {
        return getUserEnrolledCourses(userId);
    }

    @Tool(description = "Search courses by concept")
    public String searchCoursesByConcept(@ToolParam(description = "concept name") String conceptName) {
        AgentProgressNotifier.publish("Searching knowledge graph: courses by concept");
        log.info("searchCoursesByConcept: conceptName={}", conceptName);
        String cypher = "MATCH (c:Course)-[:COURSE_CONCEPT]->(k:Concept) WHERE k.name CONTAINS $conceptName RETURN c.id AS courseId, c.name AS courseName, c.content AS courseContent, k.name AS conceptName LIMIT 10";
        return cypherExecutionService.executeQuery(cypher, Map.of("conceptName", conceptName));
    }

    @Tool(description = "Alias of searchCoursesByConcept(conceptName)")
    public String SEARCH_COURSES_BY_CONCEPT(@ToolParam(description = "concept name") String conceptName) {
        return searchCoursesByConcept(conceptName);
    }

    @Tool(description = "Enroll a user to a course")
    public String enrollCourse(
            @ToolParam(description = "user id, e.g. 1") String userId,
            @ToolParam(description = "course id or course name") String courseId) {
        AgentProgressNotifier.publish("Executing enrollment operation");
        log.info("enrollCourse: userId={}, courseId={}", userId, courseId);
        try {
            Long parsedUserId = Long.parseLong(userId);
            Map<String, Object> result = courseActionService.enrollByCourseReference(parsedUserId, courseId);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("enrollCourse failed: userId={}, courseId={}", userId, courseId, e);
            return "{\"status\":\"ERROR\",\"message\":\"Enrollment failed: " + e.getMessage() + "\"}";
        }
    }

    @Tool(description = "Alias of enrollCourse(userId, courseId)")
    public String ENROLL_COURSE(
            @ToolParam(description = "user id, e.g. 1") String userId,
            @ToolParam(description = "course id or course name") String courseId) {
        return enrollCourse(userId, courseId);
    }

    @Tool(description = "Drop a user from a course")
    public String dropCourse(
            @ToolParam(description = "user id, e.g. 1") String userId,
            @ToolParam(description = "course id or course name") String courseId) {
        AgentProgressNotifier.publish("Executing drop operation");
        log.info("dropCourse: userId={}, courseId={}", userId, courseId);
        try {
            Long parsedUserId = Long.parseLong(userId);
            Map<String, Object> result = courseActionService.dropByCourseReference(parsedUserId, courseId);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("dropCourse failed: userId={}, courseId={}", userId, courseId, e);
            return "{\"status\":\"ERROR\",\"message\":\"Drop failed: " + e.getMessage() + "\"}";
        }
    }

    @Tool(description = "Alias of dropCourse(userId, courseId)")
    public String DROP_COURSE(
            @ToolParam(description = "user id, e.g. 1") String userId,
            @ToolParam(description = "course id or course name") String courseId) {
        return dropCourse(userId, courseId);
    }

    @Tool(description = "Recommend courses based on user preferences and knowledge graph")
    public String recommendCourses(
            @ToolParam(description = "user id, e.g. 1") String userId,
            @ToolParam(description = "keywords or requirements for recommendation") String keywords) {
        AgentProgressNotifier.publish("Searching knowledge graph: course recommendations");
        log.info("recommendCourses: userId={}, keywords={}", userId, keywords);
        try {
            Long parsedUserId = Long.parseLong(userId);
            Map<String, Object> result = courseRecommendationService.recommendCourses(parsedUserId, keywords);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("recommendCourses failed", e);
            return "{\"status\":\"ERROR\",\"message\":\"Recommendation failed: " + e.getMessage() + "\"}";
        }
    }

    @Tool(description = "Alias of recommendCourses(userId, keywords)")
    public String RECOMMEND_COURSES(
            @ToolParam(description = "user id, e.g. 1") String userId,
            @ToolParam(description = "keywords or requirements for recommendation") String keywords) {
        return recommendCourses(userId, keywords);
    }

    private String coursePrerequisiteCypher() {
        return """
                MATCH (course:Course)
                WITH course,
                     CASE
                       WHEN toLower(coalesce(course.name, '')) = toLower($courseName) THEN 100
                       WHEN size($courseName) >= 4 AND toLower(coalesce(course.name, '')) STARTS WITH toLower($courseName) THEN 30
                       WHEN size($courseName) >= 2 AND toLower(coalesce(course.name, '')) CONTAINS toLower($courseName) THEN 10
                       ELSE 0
                     END AS score
                WHERE score > 0
                ORDER BY score DESC, size(coalesce(course.name, '')) ASC, coalesce(course.name, '') ASC
                LIMIT 1
                OPTIONAL MATCH (preCourse:Course)-[:COURSE_CONCEPT]->(preConcept:Concept)-[:PREREQUISITE_DEPENDENCY]->(concept:Concept)<-[:COURSE_CONCEPT]-(course)
                WHERE elementId(preCourse) <> elementId(course)
                WITH course, preCourse, collect(DISTINCT preConcept.name) AS prerequisiteConceptNames
                RETURN
                  course.id AS courseId,
                  course.name AS courseName,
                  course.content AS courseContent,
                  preCourse.id AS prerequisiteCourseId,
                  preCourse.name AS prerequisiteCourseName,
                  prerequisiteConceptNames
                ORDER BY courseName ASC, size(prerequisiteConceptNames) DESC, prerequisiteCourseName ASC
                """;
    }
}
