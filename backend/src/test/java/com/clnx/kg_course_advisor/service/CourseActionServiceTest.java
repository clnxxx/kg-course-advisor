package com.clnx.kg_course_advisor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CourseActionServiceTest {

    @Mock
    private Driver neo4jDriver;

    @Mock
    private Session session;

    private CourseActionService courseActionService;

    @BeforeEach
    void setUp() {
        courseActionService = new CourseActionService(neo4jDriver);
        when(neo4jDriver.session()).thenReturn(session);
    }

    @Test
    void enrollByCourseReferenceReturnsConflictCoursesWhenScheduleConflicts() {
        Result resolveResult = mock(Result.class);
        Result executeResult = mock(Result.class);
        Record matchedCourse = mock(Record.class);
        Record executionRecord = mock(Record.class);

        when(session.run(any(String.class), anyMap())).thenReturn(resolveResult, executeResult);
        when(resolveResult.list()).thenReturn(List.of(matchedCourse));
        stubCourseRecord(matchedCourse, "C001", "数据结构", "讲授线性表与树");

        when(executeResult.single()).thenReturn(executionRecord);
        when(executionRecord.get("status")).thenReturn(Values.value("TIME_CONFLICT"));
        when(executionRecord.get("courseId")).thenReturn(Values.value("C001"));
        when(executionRecord.get("courseName")).thenReturn(Values.value("数据结构"));
        when(executionRecord.get("courseContent")).thenReturn(Values.value("讲授线性表与树"));
        when(executionRecord.get("conflictCourseNames")).thenReturn(Values.value(List.of("离散数学", "程序设计基础")));

        Map<String, Object> response = courseActionService.enrollByCourseReference(7L, "C001");

        assertThat(response).containsEntry("status", "FAILED");
        assertThat(response).containsEntry("operationType", "SELECT");
        assertThat(response).containsEntry("canProceed", false);
        assertThat(response.get("graphFocus")).isEqualTo(List.of("C001"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conflictCourses = (List<Map<String, Object>>) response.get("conflictCourses");
        assertThat(conflictCourses)
                .extracting(item -> item.get("name"))
                .containsExactly("离散数学", "程序设计基础");
    }

    @Test
    void enrollByCourseReferenceReturnsFailureWhenCourseNameIsAmbiguous() {
        Result resolveResult = mock(Result.class);
        Result targetTimeResult = mock(Result.class);
        Record firstCandidate = mock(Record.class);
        Record secondCandidate = mock(Record.class);

        when(session.run(any(String.class), anyMap())).thenReturn(resolveResult, targetTimeResult);
        when(resolveResult.list()).thenReturn(List.of(firstCandidate, secondCandidate));
        when(targetTimeResult.list()).thenReturn(List.of());

        stubCourseRecord(firstCandidate, "C001", "数据结构", "讲授线性表与树");
        when(firstCandidate.get("matchScore")).thenReturn(Values.value(3));

        stubCourseRecord(secondCandidate, "C002", "数据分析", "讲授统计分析");
        when(secondCandidate.get("matchScore")).thenReturn(Values.value(3));

        Map<String, Object> response = courseActionService.enrollByCourseReference(7L, "数据");

        assertThat(response).containsEntry("status", "FAILED");
        assertThat(response).containsEntry("operationType", "SELECT");
        assertThat(String.valueOf(response.get("message"))).contains("数据结构").contains("数据分析");
        verify(session, times(2)).run(any(String.class), anyMap());
    }

    @Test
    void dropByCourseReferenceUsesResolvedCourseAndReturnsSuccess() {
        Result resolveResult = mock(Result.class);
        Result executeResult = mock(Result.class);
        Record enrolledCourse = mock(Record.class);
        Record executionRecord = mock(Record.class);

        when(session.run(any(String.class), anyMap())).thenReturn(resolveResult, executeResult);
        when(resolveResult.list()).thenReturn(List.of(enrolledCourse));
        stubCourseRecord(enrolledCourse, "C001", "数据结构", "讲授线性表与树");

        when(executeResult.single()).thenReturn(executionRecord);
        when(executionRecord.get("status")).thenReturn(Values.value("SUCCESS"));

        Map<String, Object> response = courseActionService.dropByCourseReference(7L, "C001");

        assertThat(response).containsEntry("status", "SUCCESS");
        assertThat(response).containsEntry("operationType", "DROP");
        assertThat(response).containsEntry("canProceed", true);
        assertThat(String.valueOf(response.get("message"))).contains("数据结构");
        assertThat(response.get("graphFocus")).isEqualTo(List.of("C001"));
    }

    private void stubCourseRecord(Record record, String courseId, String courseName, String courseContent) {
        when(record.get("courseId")).thenReturn(value(courseId));
        when(record.get("courseName")).thenReturn(value(courseName));
        when(record.get("courseContent")).thenReturn(value(courseContent));
    }

    private Value value(String text) {
        return text == null ? Values.NULL : Values.value(text);
    }
}
