package com.clnx.kg_course_advisor.agent;

import com.clnx.kg_course_advisor.entity.mysql.User;
import com.clnx.kg_course_advisor.service.KnowledgeGraphContextService;
import com.clnx.kg_course_advisor.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MasterAgentServiceTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private SubAgentTools subAgentTools;

    @Mock
    private UserService userService;

    @Mock
    private KnowledgeGraphContextService knowledgeGraphContextService;

    private InMemoryChatMemory chatMemory;
    private MasterAgentService masterAgentService;

    @BeforeEach
    void setUp() {
        chatMemory = new InMemoryChatMemory();
        masterAgentService = new MasterAgentService(chatMemory, chatModel, subAgentTools, userService, knowledgeGraphContextService);

        lenient().when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("routing unavailable"));
        lenient().when(userService.findById(1L)).thenReturn(Optional.of(
                User.builder()
                        .id(1L)
                        .username("alice")
                        .realName("Alice")
                        .build()
        ));
        lenient().when(knowledgeGraphContextService.buildContext(any(), anyLong()))
                .thenReturn(com.clnx.kg_course_advisor.dto.GraphContextResponse.builder()
                        .nodes(List.of())
                        .edges(List.of())
                        .focusNodeIds(List.of())
                        .annotations(List.of())
                        .stats(com.clnx.kg_course_advisor.dto.GraphStatsDto.builder()
                                .nodeCount(0)
                                .edgeCount(0)
                                .build())
                        .build());
    }

    @Test
    void courseIdQuestionFallsBackToQaInsteadOfAction() {
        when(subAgentTools.qaAgentStructured("C001是什么课？", "1", "session-qa"))
                .thenReturn("""
                        {"status":"SUCCESS","answer":"C001 是一门课程介绍示例。"}
                        """);

        String reply = masterAgentService.chat("C001是什么课？", "session-qa", 1L);

        assertThat(reply).isEqualTo("C001 是一门课程介绍示例。");
        verify(subAgentTools).qaAgentStructured("C001是什么课？", "1", "session-qa");
        verify(subAgentTools, never()).actionAgentStructured(anyString(), anyString(), anyString());
    }

    @Test
    void streamErrorFallbackIsPersistedToHistory() {
        when(subAgentTools.qaAgentStructured("张三是谁？", "1", "session-stream-error"))
                .thenThrow(new RuntimeException("qa failed"));

        List<String> events = masterAgentService.chatStream("张三是谁？", "session-stream-error", 1L)
                .collectList()
                .block();

        assertThat(joinContent(events)).contains("抱歉，处理过程中出现了错误。");
        List<Message> history = chatMemory.get("session-stream-error");
        assertThat(history).hasSize(2);
        assertThat(history.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(history.get(1).getText()).isEqualTo("抱歉，处理过程中出现了错误。");
    }

    @Test
    void streamedRecommendationCanStillResolveSelectFirstCourse() {
        when(subAgentTools.recommenderAgentStructured("推荐机器学习课程", "1", "session-rec"))
                .thenReturn("""
                        {
                          "status": "SUCCESS",
                          "recommendationReason": "根据兴趣推荐",
                          "courses": [
                            {
                              "id": "C_course-v1:TsinghuaX+20396185X+sp",
                              "name": "机器学习导论",
                              "reason": "覆盖机器学习基础",
                              "teacherNames": ["王老师"]
                            },
                            {
                              "id": "C_course-v1:TsinghuaX+20396187X+sp",
                              "name": "数据挖掘",
                              "reason": "适合继续进阶",
                              "teacherNames": ["李老师"]
                            }
                          ],
                          "learningPath": []
                        }
                        """);
        when(subAgentTools.actionAgentStructured("enroll 机器学习导论", "1", "session-rec"))
                .thenReturn("""
                        {"status":"SUCCESS","message":"已成功为你选上《机器学习导论》。"}
                        """);

        List<String> firstEvents = masterAgentService.chatStream("推荐机器学习课程", "session-rec", 1L)
                .collectList()
                .block();

        String renderedReply = joinContent(firstEvents);
        assertThat(renderedReply).contains("1. **机器学习导论**");
        assertThat(renderedReply).doesNotContain("C_course-v1:TsinghuaX+20396185X+sp");

        String followUpReply = masterAgentService.chat("选第一门", "session-rec", 1L);

        assertThat(followUpReply).isEqualTo("已成功为你选上《机器学习导论》。");
        verify(subAgentTools).actionAgentStructured("enroll 机器学习导论", "1", "session-rec");
    }

    @Test
    void relatedFollowUpFallsBackToRecommender() {
        when(subAgentTools.recommenderAgentStructured("工程相关的有没有", "1", "session-related"))
                .thenReturn("""
                        {
                          "status": "NOT_FOUND",
                          "recommendationReason": "数据库中没有找到与你需求直接匹配的真实课程。你可以换一个更具体的方向，或者直接告诉我相关知识点。",
                          "courses": [],
                          "learningPath": [],
                          "suggestedAction": "你可以换一个更具体的关键词。"
                        }
                        """);

        String reply = masterAgentService.chat("工程相关的有没有", "session-related", 1L);

        assertThat(reply).contains("数据库中没有找到与你需求直接匹配的真实课程");
        verify(subAgentTools).recommenderAgentStructured("工程相关的有没有", "1", "session-related");
        verify(subAgentTools, never()).qaAgentStructured(anyString(), anyString(), anyString());
    }

    @Test
    void explicitSelectCourseRequestShouldRouteToActionInsteadOfRecommendation() {
        when(subAgentTools.actionAgentStructured("帮我选数据科学导论", "1", "session-action"))
                .thenReturn("""
                        {"status":"SUCCESS","message":"已为你处理选课请求。"}
                        """);

        String reply = masterAgentService.chat("帮我选数据科学导论", "session-action", 1L);

        assertThat(reply).isEqualTo("已为你处理选课请求。");
        verify(subAgentTools).actionAgentStructured("帮我选数据科学导论", "1", "session-action");
        verify(subAgentTools, never()).recommenderAgentStructured(anyString(), anyString(), anyString());
    }

    @Test
    void clearConversationStateRemovesAllAgentMemories() {
        chatMemory.add("session-clear", List.of(new UserMessage("main")));
        chatMemory.add("session-clear:qa", List.of(new AssistantMessage("qa")));
        chatMemory.add("session-clear:rec", List.of(new AssistantMessage("rec")));
        chatMemory.add("session-clear:act", List.of(new AssistantMessage("act")));

        masterAgentService.clearConversationState("session-clear");

        assertThat(chatMemory.get("session-clear")).isEmpty();
        assertThat(chatMemory.get("session-clear:qa")).isEmpty();
        assertThat(chatMemory.get("session-clear:rec")).isEmpty();
        assertThat(chatMemory.get("session-clear:act")).isEmpty();
    }

    @Test
    @DisplayName("QA teacher question should build graph context around the asked course")
    void qaTeacherQuestionBuildsCourseFocusedGraphContext() {
        when(subAgentTools.qaAgentStructured("数据结构课程的授课老师是谁？", "1", "session-teacher"))
                .thenReturn("""
                        {
                          "status": "SUCCESS",
                          "answer": "数据结构课程由王老师授课。",
                          "results": [
                            {
                              "tool": "getCourseTeachers",
                              "evidence": "数据结构 -> 王老师"
                            }
                          ],
                          "relatedCourses": [],
                          "entities": {
                            "courseNames": [],
                            "conceptNames": [],
                            "teacherNames": ["王老师"]
                          }
                        }
                        """);

        masterAgentService.chatStream("数据结构课程的授课老师是谁？", "session-teacher", 1L)
                .collectList()
                .block();

        ArgumentCaptor<com.clnx.kg_course_advisor.dto.GraphContextRequest> captor =
                ArgumentCaptor.forClass(com.clnx.kg_course_advisor.dto.GraphContextRequest.class);
        verify(knowledgeGraphContextService).buildContext(captor.capture(), eq(1L));

        com.clnx.kg_course_advisor.dto.GraphContextRequest request = captor.getValue();
        assertThat(request.getScenario()).isEqualTo("qa");
        assertThat(request.getCourseNames()).containsExactly("数据结构");
        assertThat(request.getTeacherNames()).isEmpty();
        assertThat(request.getIncludePrerequisites()).isFalse();
    }

    @Test
    @DisplayName("QA prerequisite question should request a compact course-focused graph")
    void qaPrerequisiteQuestionBuildsCompactCourseGraphContext() {
        when(subAgentTools.qaAgentStructured("给我看一下数据结构课程的先修关系", "1", "session-prerequisite-graph"))
                .thenReturn("""
                        {
                          "status": "SUCCESS",
                          "answer": "数据结构的先修课程主要包括：程序设计基础、离散数学。",
                          "results": [
                            {
                              "tool": "getCoursePrerequisites",
                              "evidence": "数据结构 -> 程序设计基础、离散数学"
                            }
                          ],
                          "relatedCourses": [
                            {
                              "id": "C001",
                              "name": "数据结构",
                              "content": "讲授线性表、树和图"
                            }
                          ],
                          "entities": {
                            "courseNames": ["数据结构"],
                            "conceptNames": [],
                            "teacherNames": []
                          }
                        }
                        """);

        masterAgentService.chatStream("给我看一下数据结构课程的先修关系", "session-prerequisite-graph", 1L)
                .collectList()
                .block();

        ArgumentCaptor<com.clnx.kg_course_advisor.dto.GraphContextRequest> captor =
                ArgumentCaptor.forClass(com.clnx.kg_course_advisor.dto.GraphContextRequest.class);
        verify(knowledgeGraphContextService, org.mockito.Mockito.atLeastOnce()).buildContext(captor.capture(), eq(1L));

        com.clnx.kg_course_advisor.dto.GraphContextRequest request = captor.getAllValues().stream()
                .filter(item -> "qa".equals(item.getScenario()))
                .findFirst()
                .orElseThrow();

        assertThat(request.getCourseIds()).containsExactly("C001");
        assertThat(request.getCourseNames()).contains("数据结构");
        assertThat(request.getConceptNames()).isEmpty();
        assertThat(request.getTeacherNames()).isEmpty();
        assertThat(request.getIncludePrerequisites()).isTrue();
        assertThat(request.getLimit()).isEqualTo(10);
    }

    @Test
    @DisplayName("Recommendation graph context should prioritize keyword matched courses and shared concepts")
    void recommendationGraphContextUsesMatchedCourses() {
        when(subAgentTools.recommenderAgentStructured("推荐数据科学方向课程", "1", "session-rec-graph"))
                .thenReturn("""
                        {
                          "status": "SUCCESS",
                          "keywords": ["数据科学"],
                          "recommendationReason": "根据关键词匹配到了相关课程。",
                          "matchedCourses": [
                            {
                              "id": "C001",
                              "name": "数据科学导论",
                              "concepts": ["数据分析", "统计基础"]
                            },
                            {
                              "id": "C002",
                              "name": "机器学习基础",
                              "concepts": ["数据分析", "模型训练"]
                            }
                          ],
                          "courses": [
                            {
                              "id": "C001",
                              "name": "数据科学导论",
                              "concepts": ["数据分析", "统计基础"]
                            }
                          ],
                          "learningPath": ["数据科学导论"]
                        }
                        """);

        masterAgentService.chatStream("推荐数据科学方向课程", "session-rec-graph", 1L)
                .collectList()
                .block();

        ArgumentCaptor<com.clnx.kg_course_advisor.dto.GraphContextRequest> captor =
                ArgumentCaptor.forClass(com.clnx.kg_course_advisor.dto.GraphContextRequest.class);
        verify(knowledgeGraphContextService, org.mockito.Mockito.atLeastOnce()).buildContext(captor.capture(), eq(1L));

        com.clnx.kg_course_advisor.dto.GraphContextRequest request = captor.getAllValues().stream()
                .filter(item -> "recommendation".equals(item.getScenario()))
                .findFirst()
                .orElseThrow();

        assertThat(request.getCourseIds()).contains("C001", "C002");
        assertThat(request.getCourseNames()).contains("数据科学导论", "机器学习基础");
        assertThat(request.getConceptNames()).contains("数据科学", "数据分析", "统计基础", "模型训练");
        assertThat(request.getIncludePrerequisites()).isTrue();
    }

    private String joinContent(List<String> events) {
        if (events == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (String event : events) {
            if (event != null && !event.startsWith("__STATUS__:")) {
                sb.append(event);
            }
        }
        return sb.toString();
    }

    private static final class InMemoryChatMemory implements ChatMemory {

        private final Map<String, List<Message>> conversations = new LinkedHashMap<>();

        @Override
        public void add(String conversationId, List<Message> messages) {
            conversations.computeIfAbsent(conversationId, ignored -> new ArrayList<>()).addAll(messages);
        }

        @Override
        public List<Message> get(String conversationId) {
            return new ArrayList<>(conversations.getOrDefault(conversationId, List.of()));
        }

        @Override
        public void clear(String conversationId) {
            conversations.remove(conversationId);
        }
    }
}
