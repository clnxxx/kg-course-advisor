package com.clnx.kg_course_advisor.controller;

import com.clnx.kg_course_advisor.service.CypherExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 课程管理后台控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final Driver neo4jDriver;

    /**
     * 获取统计数据
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = new HashMap<>();

        try (Session session = neo4jDriver.session()) {
            stats.put("totalCourses", countNodes(session, "Course"));
            stats.put("totalConcepts", countNodes(session, "Concept"));
            stats.put("totalTeachers", countNodes(session, "Teacher"));
            stats.put("totalEnrollments", countRelationships(session, "USER_COURSE"));
        }

        return ResponseEntity.ok(stats);
    }

    /**
     * 获取课程列表
     */
    @GetMapping("/courses")
    public ResponseEntity<List<Map<String, Object>>> getCourses() {
        List<Map<String, Object>> courses = new ArrayList<>();

        try (Session session = neo4jDriver.session()) {
            String cypher = """
                MATCH (c:Course)
                OPTIONAL MATCH (t:Teacher)-[:TEACHER_COURSE]->(c)
                OPTIONAL MATCH (u)-[:USER_COURSE]->(c)
                RETURN c.id AS id, c.name AS name, c.content AS content,
                       t.name AS teacher, count(DISTINCT u) AS enrollCount
                ORDER BY c.name
                """;

            Result result = session.run(cypher);
            while (result.hasNext()) {
                Record record = result.next();
                Map<String, Object> course = new HashMap<>();
                course.put("id", record.get("id").asString(""));
                course.put("name", record.get("name").asString(""));
                course.put("content", record.get("content").asString(""));
                course.put("teacher", record.get("teacher").isNull() ? "" : record.get("teacher").asString(""));
                course.put("enrollCount", record.get("enrollCount").asLong(0));
                courses.add(course);
            }
        }

        return ResponseEntity.ok(courses);
    }

    private long countNodes(Session session, String label) {
        Result result = session.run("MATCH (n:" + label + ") RETURN count(n) AS cnt");
        return result.single().get("cnt").asLong(0);
    }

    private long countRelationships(Session session, String type) {
        Result result = session.run("MATCH ()-[r:" + type + "]->() RETURN count(r) AS cnt");
        return result.single().get("cnt").asLong(0);
    }
}
