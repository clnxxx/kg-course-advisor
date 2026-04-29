package com.clnx.kg_course_advisor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CourseRecommendationServiceTest {

    @Mock
    private Driver neo4jDriver;

    @Mock
    private ChatModel chatModel;

    private CourseRecommendationService courseRecommendationService;

    @BeforeEach
    void setUp() {
        courseRecommendationService = new CourseRecommendationService(
                neo4jDriver,
                chatModel,
                new ObjectMapper()
        );
    }

    @Test
    void ruleBasedExtractionKeepsCoreDomainKeyword() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("llm unavailable"));

        List<String> keywords = courseRecommendationService.extractKeywords("推荐一些工程相关的课程");

        assertThat(keywords).containsExactly("工程");
    }

    @Test
    void llmKeywordExtractionAcceptsGroundedAliasButRejectsInventedSpecificTerm() {
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("""
                        {"keywords":["人工智能","机器学习"]}
                        """))
        )));

        List<String> keywords = courseRecommendationService.extractKeywords("我适合先学哪几门 AI 课程？");

        assertThat(keywords).containsExactly("人工智能");
    }
    @Test
    void resolveCoursePrerequisitesReturnsPrerequisiteCoursesInsteadOfConcepts() throws Exception {
        Session session = org.mockito.Mockito.mock(Session.class);
        Result result = org.mockito.Mockito.mock(Result.class);
        Record record1 = org.mockito.Mockito.mock(Record.class);
        Record record2 = org.mockito.Mockito.mock(Record.class);

        when(neo4jDriver.session()).thenReturn(session);
        when(session.run(any(String.class), anyMap())).thenReturn(result);
        when(result.list()).thenReturn(List.of(record1, record2));
        when(record1.get("prerequisiteCourseName")).thenReturn(Values.value("离散数学"));
        when(record2.get("prerequisiteCourseName")).thenReturn(Values.value("程序设计基础"));

        Method method = CourseRecommendationService.class.getDeclaredMethod("resolveCoursePrerequisites", String.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> prerequisites = (List<String>) method.invoke(courseRecommendationService, "C001");

        assertThat(prerequisites).containsExactly("离散数学", "程序设计基础");
    }
}
