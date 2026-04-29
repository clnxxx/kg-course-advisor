package com.clnx.kg_course_advisor.service;

import com.clnx.kg_course_advisor.entity.mysql.User;
import com.clnx.kg_course_advisor.repository.mysql.UserRepository;
import com.clnx.kg_course_advisor.util.UserIdUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Driver neo4jDriver;

    @Transactional("transactionManager")
    public User register(String username, String password, String email, String studentId, String realName,
                         String major, Integer grade) {
        String normalizedUsername = normalizeRequired(username);
        String normalizedEmail = normalizeNullable(email);
        String normalizedStudentId = normalizeNullable(studentId);
        String normalizedRealName = normalizeNullable(realName);
        String normalizedMajor = normalizeNullable(major);

        if (userRepository.existsByUsername(normalizedUsername)) {
            throw new RuntimeException("Username already exists");
        }
        if (normalizedEmail != null && userRepository.existsByEmail(normalizedEmail)) {
            throw new RuntimeException("Email is already registered");
        }
        if (normalizedStudentId != null && userRepository.existsByStudentId(normalizedStudentId)) {
            throw new RuntimeException("Student ID is already registered");
        }

        User user = User.builder()
                .username(normalizedUsername)
                .password(passwordEncoder.encode(password))
                .email(normalizedEmail)
                .studentId(normalizedStudentId)
                .realName(normalizedRealName)
                .major(normalizedMajor)
                .grade(grade)
                .role(User.UserRole.STUDENT)
                .build();

        User savedUser = userRepository.save(user);

        syncUserToNeo4jRequired(savedUser);

        return savedUser;
    }

    public void syncUserToNeo4jRequired(User user) {
        syncUserToNeo4j(user, true);
    }

    public void syncUserToNeo4jQuietly(User user) {
        syncUserToNeo4j(user, false);
    }

    private void syncUserToNeo4j(User user, boolean failOnError) {
        try (Session session = neo4jDriver.session()) {
            String cypher = """
                MERGE (s:Student {id: $userId})
                SET s:User,
                    s.username = $username,
                    s.realName = $realName,
                    s.name = $displayName,
                    s.studentId = $studentId
                RETURN s
                """;

            session.run(cypher, org.neo4j.driver.Values.parameters(
                    "userId", String.valueOf(user.getId()),
                    "username", user.getUsername(),
                    "realName", user.getRealName() != null ? user.getRealName() : "",
                    "displayName", user.getRealName() != null ? user.getRealName() : user.getUsername(),
                    "studentId", user.getStudentId() != null ? user.getStudentId() : ""
            ));

            log.info("Synced Neo4j student node: userId={}, username={}", user.getId(), user.getUsername());
        } catch (Exception e) {
            if (failOnError) {
                log.error("Failed to sync Neo4j user node: userId={}, username={}", user.getId(), user.getUsername(), e);
                throw new RuntimeException("Unable to sync Neo4j user node", e);
            }
            log.warn("Skipped Neo4j sync after MySQL success: userId={}, username={}",
                    user.getId(), user.getUsername(), e);
        }
    }

    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

    public boolean validatePassword(User user, String rawPassword) {
        if (passwordEncoder.matches(rawPassword, user.getPassword())) {
            return true;
        }

        // Backward compatibility for old plain-text passwords.
        // Only attempt migration if the stored password does NOT look like a BCrypt hash.
        String storedPassword = user.getPassword();
        if (storedPassword != null
                && !storedPassword.startsWith("$2a$")
                && !storedPassword.startsWith("$2b$")
                && !storedPassword.startsWith("$2y$")
                && rawPassword.equals(storedPassword)) {
            user.setPassword(passwordEncoder.encode(rawPassword));
            userRepository.save(user);
            log.info("Auto-migrated plaintext password for user {}", user.getUsername());
            return true;
        }

        return false;
    }

    @Transactional("transactionManager")
    public User updateProfile(Long userId, String email) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        String normalizedEmail = normalizeNullable(email);

        if (normalizedEmail != null
                && !normalizedEmail.equals(user.getEmail())
                && userRepository.existsByEmail(normalizedEmail)) {
            throw new RuntimeException("Email is already registered");
        }

        user.setEmail(normalizedEmail);

        User savedUser = userRepository.save(user);
        syncUserToNeo4jQuietly(savedUser);
        return savedUser;
    }

    public List<Map<String, Object>> getUserSchedule(Long userId) {
        List<String> userIds = UserIdUtils.buildUserIdCandidates(userId);

        try (Session session = neo4jDriver.session()) {
            String cypher = """
                    MATCH (u)-[:USER_COURSE]->(c:Course)
                    WHERE (u:User OR u:Student)
                      AND toString(u.id) IN $userIds
                    OPTIONAL MATCH (teacher:Teacher)-[:TEACHER_COURSE]->(c)
                    OPTIONAL MATCH (c)-[:COURSE_TIME]->(t:Time)
                    RETURN
                      c.id AS courseId,
                      c.name AS courseName,
                      c.content AS courseContent,
                      collect(DISTINCT teacher.name) AS teacherNames,
                      t.id AS timeId,
                      t.weekday_index AS weekdayIndex,
                      t.weekday_code AS weekdayCode,
                      t.half_day AS halfDay,
                      t.period_index AS periodIndex,
                      t.start_time AS startTime,
                      t.end_time AS endTime
                    ORDER BY
                      CASE WHEN timeId IS NULL THEN 1 ELSE 0 END,
                      weekdayIndex,
                      CASE halfDay
                        WHEN 'morning' THEN 0
                        WHEN 'afternoon' THEN 1
                        ELSE 2
                      END,
                      periodIndex,
                      courseName
                    """;

            List<Map<String, Object>> courses = new ArrayList<>();
            for (Record record : session.run(cypher, Map.of("userIds", userIds)).list()) {
                Map<String, Object> course = new LinkedHashMap<>();
                course.put("courseId", record.get("courseId").isNull() ? null : record.get("courseId").asString());
                course.put("courseName", record.get("courseName").isNull() ? null : record.get("courseName").asString());
                course.put("courseContent", record.get("courseContent").isNull() ? null : record.get("courseContent").asString());
                course.put("teacherNames", record.get("teacherNames").asList(value -> value.isNull() ? "" : value.asString()));
                course.put("timeId", record.get("timeId").isNull() ? null : record.get("timeId").asString());
                course.put("weekdayIndex", parseIntegerValue(record.get("weekdayIndex")));
                course.put("weekdayCode", record.get("weekdayCode").isNull() ? null : record.get("weekdayCode").asString());
                course.put("halfDay", record.get("halfDay").isNull() ? null : record.get("halfDay").asString());
                course.put("periodIndex", parseIntegerValue(record.get("periodIndex")));
                course.put("startTime", record.get("startTime").isNull() ? null : record.get("startTime").asString());
                course.put("endTime", record.get("endTime").isNull() ? null : record.get("endTime").asString());
                courses.add(course);
            }
            return courses;
        }
    }

    private Integer parseIntegerValue(org.neo4j.driver.Value value) {
        if (value == null || value.isNull()) {
            return null;
        }

        try {
            return value.asInt();
        } catch (Exception ignored) {
            String raw = value.asString("");
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                log.warn("Unable to parse Neo4j numeric value: {}", raw);
                return null;
            }
        }
    }

    private String normalizeRequired(String value) {
        String normalized = normalizeNullable(value);
        if (normalized == null) {
            throw new RuntimeException("Username cannot be blank");
        }
        return normalized;
    }

    private String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
