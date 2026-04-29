package com.clnx.kg_course_advisor.agent;

import com.clnx.kg_course_advisor.agent.tools.CypherTools;
import com.clnx.kg_course_advisor.service.CourseActionService;
import com.clnx.kg_course_advisor.service.CourseRecommendationService;
import com.clnx.kg_course_advisor.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubAgentToolsTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private CypherTools cypherTools;

    @Mock
    private CourseActionService courseActionService;

    @Mock
    private CourseRecommendationService courseRecommendationService;

    @Mock
    private ChatMemory chatMemory;

    @Mock
    private UserService userService;

    @Test
    void recommenderStructuredUsesDeterministicRecommendationService() {
        SubAgentTools subAgentTools = new SubAgentTools(
                chatModel,
                cypherTools,
                courseActionService,
                courseRecommendationService,
                chatMemory,
                userService
        );

        when(courseRecommendationService.recommendCourses(1L, "推荐一些建筑类的课程"))
                .thenReturn(Map.of(
                        "status", "SUCCESS",
                        "recommendationReason", "根据数据库中的真实课程推荐。",
                        "courses", List.of(
                                Map.of(
                                        "id", "C001",
                                        "name", "建筑与城市",
                                        "reason", "匹配到建筑关键词",
                                        "teacherNames", List.of("李强")
                                )
                        ),
                        "learningPath", List.of("建筑与城市"),
                        "suggestedAction", "可以直接告诉我课程名。"
                ));

        String json = subAgentTools.recommenderAgentStructured("推荐一些建筑类的课程", "1", "session-rec");

        assertThat(json).contains("\"name\" : \"建筑与城市\"");
        verify(courseRecommendationService).recommendCourses(1L, "推荐一些建筑类的课程");
    }
    @Test
    void qaStructuredAnswersCoursePrerequisitesDeterministically() throws Exception {
        SubAgentTools subAgentTools = new SubAgentTools(
                chatModel,
                cypherTools,
                courseActionService,
                courseRecommendationService,
                chatMemory,
                userService
        );

        when(cypherTools.findCoursePrerequisites("数据结构"))
                .thenReturn(List.of(
                        Map.of(
                                "courseId", "C001",
                                "courseName", "数据结构",
                                "courseContent", "讲授线性表、树和图",
                                "prerequisiteCourseId", "C010",
                                "prerequisiteCourseName", "程序设计基础",
                                "prerequisiteConceptNames", List.of("数组", "指针")
                        ),
                        Map.of(
                                "courseId", "C001",
                                "courseName", "数据结构",
                                "courseContent", "讲授线性表、树和图",
                                "prerequisiteCourseId", "C011",
                                "prerequisiteCourseName", "离散数学",
                                "prerequisiteConceptNames", List.of("集合", "逻辑")
                        ),
                        Map.of(
                                "courseId", "C001",
                                "courseName", "数据结构",
                                "courseContent", "讲授线性表、树和图",
                                "prerequisiteCourseId", "C012",
                                "prerequisiteCourseName", "大学计算机基础",
                                "prerequisiteConceptNames", List.of("基础编程")
                        ),
                        Map.of(
                                "courseId", "C001",
                                "courseName", "数据结构",
                                "courseContent", "讲授线性表、树和图",
                                "prerequisiteCourseId", "C013",
                                "prerequisiteCourseName", "C++语言程序设计基础",
                                "prerequisiteConceptNames", List.of("类", "对象")
                        ),
                        Map.of(
                                "courseId", "C001",
                                "courseName", "数据结构",
                                "courseContent", "讲授线性表、树和图",
                                "prerequisiteCourseId", "C014",
                                "prerequisiteCourseName", "基于Linux的C++",
                                "prerequisiteConceptNames", List.of("指针", "内存")
                        ),
                        Map.of(
                                "courseId", "C001",
                                "courseName", "数据结构",
                                "courseContent", "讲授线性表、树和图",
                                "prerequisiteCourseId", "C015",
                                "prerequisiteCourseName", "数据结构（Data Structures）",
                                "prerequisiteConceptNames", List.of("链表")
                        ),
                        Map.of(
                                "courseId", "C001",
                                "courseName", "数据结构",
                                "courseContent", "讲授线性表、树和图",
                                "prerequisiteCourseId", "C016",
                                "prerequisiteCourseName", "数据结构（Data Structures）（2018秋）",
                                "prerequisiteConceptNames", List.of("树")
                        ),
                        Map.of(
                                "courseId", "C001",
                                "courseName", "数据结构",
                                "courseContent", "讲授线性表、树和图",
                                "prerequisiteCourseId", "C017",
                                "prerequisiteCourseName", "数据结构(下)(自主模式)",
                                "prerequisiteConceptNames", List.of("图")
                        )
                ));

        String json = subAgentTools.qaAgentStructured("给我看一下数据结构课程的先修关系", "1", "session-prerequisite");

        JsonNode root = new ObjectMapper().readTree(json);
        assertThat(root.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(root.path("answer").asText()).isEqualTo("数据结构的先修课程重点包括：程序设计基础、离散数学、大学计算机基础、C++语言程序设计基础。");
        assertThat(root.path("answer").asText()).doesNotContain("基于Linux的C++");
        assertThat(root.path("answer").asText()).doesNotContain("2018秋", "自主模式", "等 ");
        assertThat(root.path("relatedCourses").get(0).path("name").asText()).isEqualTo("数据结构");
        assertThat(root.path("entities").path("courseNames").toString()).contains("数据结构");
        verifyNoInteractions(chatModel);
    }

    @Test
    void normalizeQaResponseBackfillsEntitiesFromRelatedCourses() throws Exception {
        SubAgentTools subAgentTools = new SubAgentTools(
                chatModel,
                cypherTools,
                courseActionService,
                courseRecommendationService,
                chatMemory,
                userService
        );

        Method method = SubAgentTools.class.getDeclaredMethod("normalizeQaResponse", String.class);
        method.setAccessible(true);

        String normalized = (String) method.invoke(subAgentTools, """
                {
                  "status": "SUCCESS",
                  "answer": "数据结构课程由王老师授课。",
                  "results": [
                    {
                      "tool": "getCourseTeachers",
                      "evidence": "数据结构 -> 王老师"
                    }
                  ],
                  "relatedCourses": [
                    {
                      "id": "C001",
                      "name": "数据结构",
                      "concepts": ["栈", "队列"],
                      "teacherNames": ["王老师"]
                    }
                  ]
                }
                """);

        JsonNode root = new ObjectMapper().readTree(normalized);
        assertThat(root.path("entities").path("courseNames").isArray()).isTrue();
        assertThat(root.path("entities").path("conceptNames").isArray()).isTrue();
        assertThat(root.path("entities").path("teacherNames").isArray()).isTrue();
        assertThat(root.path("entities").path("courseNames").toString()).contains("数据结构");
        assertThat(root.path("entities").path("conceptNames").toString()).contains("栈", "队列");
        assertThat(root.path("entities").path("teacherNames").toString()).contains("王老师");
    }
}
