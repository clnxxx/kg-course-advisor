package com.clnx.kg_course_advisor.service;

import com.clnx.kg_course_advisor.dto.GraphNodeDto;
import com.clnx.kg_course_advisor.dto.GraphAnnotationDto;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Values;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KnowledgeGraphContextServiceTest {

    @Test
    void pruneIsolatedNodesRemovesDisconnectedNodesFromContextGraph() throws Exception {
        KnowledgeGraphContextService service = new KnowledgeGraphContextService(
                mock(Driver.class),
                mock(UserService.class)
        );

        Class<?> accumulatorClass = Class.forName(
                "com.clnx.kg_course_advisor.service.KnowledgeGraphContextService$GraphAccumulator"
        );
        Constructor<?> constructor = accumulatorClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object accumulator = constructor.newInstance();

        Method addNode = accumulatorClass.getDeclaredMethod("addNode", GraphNodeDto.class);
        addNode.setAccessible(true);
        Method addEdge = accumulatorClass.getDeclaredMethod("addEdge", String.class, String.class, String.class, boolean.class, Map.class);
        addEdge.setAccessible(true);

        addNode.invoke(accumulator, GraphNodeDto.builder().id("course-1").label("数据结构").group("Course").highlighted(true).build());
        addNode.invoke(accumulator, GraphNodeDto.builder().id("concept-1").label("栈").group("Concept").highlighted(false).build());
        addNode.invoke(accumulator, GraphNodeDto.builder().id("isolated-1").label("孤立课程").group("Course").highlighted(true).build());
        addEdge.invoke(accumulator, "course-1", "concept-1", "COURSE_CONCEPT", true, Map.of());

        Field focusNodeIdsField = accumulatorClass.getDeclaredField("focusNodeIds");
        focusNodeIdsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> focusNodeIds = (Set<String>) focusNodeIdsField.get(accumulator);
        focusNodeIds.add("course-1");
        focusNodeIds.add("isolated-1");

        Method pruneMethod = KnowledgeGraphContextService.class.getDeclaredMethod("pruneIsolatedNodes", accumulatorClass);
        pruneMethod.setAccessible(true);
        pruneMethod.invoke(service, accumulator);

        Field nodesField = accumulatorClass.getDeclaredField("nodes");
        nodesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, GraphNodeDto> nodes = (Map<String, GraphNodeDto>) nodesField.get(accumulator);

        assertThat(nodes).containsKeys("course-1", "concept-1");
        assertThat(nodes).doesNotContainKey("isolated-1");
        assertThat(focusNodeIds).contains("course-1");
        assertThat(focusNodeIds).doesNotContain("isolated-1");
    }

    @Test
    void parseIntegerValueAcceptsStringNumbers() throws Exception {
        KnowledgeGraphContextService service = new KnowledgeGraphContextService(
                mock(Driver.class),
                mock(UserService.class)
        );

        Method method = KnowledgeGraphContextService.class.getDeclaredMethod("parseIntegerValue", org.neo4j.driver.Value.class);
        method.setAccessible(true);

        Integer parsed = (Integer) method.invoke(service, Values.value("3"));

        assertThat(parsed).isEqualTo(3);
    }

    @Test
    void graphAccumulatorMergesDuplicateAnnotationsById() throws Exception {
        Class<?> accumulatorClass = Class.forName(
                "com.clnx.kg_course_advisor.service.KnowledgeGraphContextService$GraphAccumulator"
        );
        Constructor<?> constructor = accumulatorClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object accumulator = constructor.newInstance();

        Method addAnnotation = accumulatorClass.getDeclaredMethod("addAnnotation", GraphAnnotationDto.class);
        addAnnotation.setAccessible(true);

        addAnnotation.invoke(accumulator, GraphAnnotationDto.builder()
                .id("conflict-course-1")
                .title("时间冲突")
                .content("冲突 1")
                .tone("warning")
                .relatedNodeIds(List.of("target-1", "time-1"))
                .build());
        addAnnotation.invoke(accumulator, GraphAnnotationDto.builder()
                .id("conflict-course-1")
                .title("时间冲突")
                .content("冲突 2")
                .tone("warning")
                .relatedNodeIds(List.of("target-1", "time-2", "course-9"))
                .build());

        Field annotationsField = accumulatorClass.getDeclaredField("annotations");
        annotationsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<GraphAnnotationDto> annotations = (List<GraphAnnotationDto>) annotationsField.get(accumulator);

        assertThat(annotations).hasSize(1);
        assertThat(annotations.get(0).getRelatedNodeIds()).containsExactly("target-1", "time-1", "time-2", "course-9");
    }
}
