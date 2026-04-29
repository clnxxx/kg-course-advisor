package com.clnx.kg_course_advisor.service;

import com.clnx.kg_course_advisor.dto.GraphAnnotationDto;
import com.clnx.kg_course_advisor.dto.GraphContextRequest;
import com.clnx.kg_course_advisor.dto.GraphContextResponse;
import com.clnx.kg_course_advisor.dto.GraphEdgeDto;
import com.clnx.kg_course_advisor.dto.GraphNodeDto;
import com.clnx.kg_course_advisor.dto.GraphStatsDto;
import com.clnx.kg_course_advisor.util.UserIdUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeGraphContextService {

    private final Driver neo4jDriver;
    private final UserService userService;

    public Map<String, Object> getOverviewGraph(int limit, String focusId) {
        int nodeLimit = Math.min(Math.max(limit, 10), 500);

        Map<String, Map<String, Object>> allNodeMap = new LinkedHashMap<>();
        Map<String, Set<String>> adjacency = new HashMap<>();
        List<String[]> allEdges = new ArrayList<>();
        Set<String> teacherIds = new LinkedHashSet<>();

        try (Session session = neo4jDriver.session()) {
            String cypher = """
                MATCH (a)-[r]->(b)
                WHERE (a:Course OR a:Concept OR a:Teacher)
                  AND (b:Course OR b:Concept OR b:Teacher)
                RETURN a, labels(a) AS labelsA, r, b, labels(b) AS labelsB
                """;
            Result result = session.run(cypher);
            while (result.hasNext()) {
                Record record = result.next();
                Node nodeA = record.get("a").asNode();
                Node nodeB = record.get("b").asNode();
                Relationship rel = record.get("r").asRelationship();
                List<String> labelsA = record.get("labelsA").asList(Value::asString);
                List<String> labelsB = record.get("labelsB").asList(Value::asString);

                String idA = nodeA.elementId();
                String idB = nodeB.elementId();

                allNodeMap.computeIfAbsent(idA, ignored -> toOverviewNodeMap(nodeA, labelsA));
                allNodeMap.computeIfAbsent(idB, ignored -> toOverviewNodeMap(nodeB, labelsB));

                if ("Teacher".equals(allNodeMap.get(idA).get("group"))) {
                    teacherIds.add(idA);
                }
                if ("Teacher".equals(allNodeMap.get(idB).get("group"))) {
                    teacherIds.add(idB);
                }

                allEdges.add(new String[]{idA, idB, rel.type()});
                adjacency.computeIfAbsent(idA, ignored -> new LinkedHashSet<>()).add(idB);
                adjacency.computeIfAbsent(idB, ignored -> new LinkedHashSet<>()).add(idA);
            }
        }

        Set<String> selectedIds = new LinkedHashSet<>();
        if (focusId != null && !focusId.isBlank() && allNodeMap.containsKey(focusId)) {
            Queue<String> queue = new LinkedList<>();
            selectedIds.add(focusId);
            queue.add(focusId);

            while (!queue.isEmpty() && selectedIds.size() < nodeLimit) {
                String current = queue.poll();
                for (String neighbor : adjacency.getOrDefault(current, Collections.emptySet())) {
                    if (selectedIds.size() >= nodeLimit) {
                        break;
                    }
                    if (selectedIds.add(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
        }

        Iterator<String> teacherIterator = teacherIds.iterator();
        while (selectedIds.size() < nodeLimit && teacherIterator.hasNext()) {
            String teacherId = teacherIterator.next();
            if (!selectedIds.add(teacherId)) {
                continue;
            }

            Queue<String> queue = new LinkedList<>();
            queue.add(teacherId);
            while (!queue.isEmpty() && selectedIds.size() < nodeLimit) {
                String current = queue.poll();
                for (String neighbor : adjacency.getOrDefault(current, Collections.emptySet())) {
                    if (selectedIds.size() >= nodeLimit) {
                        break;
                    }
                    if (selectedIds.add(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
        }

        List<Map<String, Object>> resultNodes = selectedIds.stream()
                .map(allNodeMap::get)
                .filter(Objects::nonNull)
                .toList();
        List<Map<String, Object>> resultEdges = allEdges.stream()
                .filter(edge -> selectedIds.contains(edge[0]) && selectedIds.contains(edge[1]))
                .map(edge -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("from", edge[0]);
                    item.put("to", edge[1]);
                    item.put("label", edge[2]);
                    return item;
                })
                .toList();

        return Map.of(
                "nodes", resultNodes,
                "edges", resultEdges,
                "nodeCount", resultNodes.size(),
                "edgeCount", resultEdges.size()
        );
    }

    public List<Map<String, Object>> searchNodes(String keyword, int limit) {
        String normalized = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return List.of();
        }

        int resultLimit = Math.min(Math.max(limit, 1), 50);
        List<Map<String, Object>> matches = new ArrayList<>();

        try (Session session = neo4jDriver.session()) {
            String cypher = """
                MATCH (n)
                WHERE (n:Course OR n:Concept OR n:Teacher OR n:Time OR n:Student OR n:User)
                  AND toLower(coalesce(n.name, '')) CONTAINS $keyword
                RETURN elementId(n) AS id,
                       coalesce(n.name, n.id, 'unknown') AS label,
                       labels(n)[0] AS group,
                       coalesce(n.content, n.name, '') AS title
                LIMIT 200
                """;

            Result result = session.run(cypher, Map.of("keyword", normalized));
            while (result.hasNext()) {
                Record rec = result.next();
                String label = rec.get("label").asString("");
                String lowerLabel = label.toLowerCase(Locale.ROOT);
                int rank = lowerLabel.equals(normalized) ? 0 : lowerLabel.startsWith(normalized) ? 1 : 2;

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", rec.get("id").asString());
                item.put("label", label);
                item.put("group", rec.get("group").asString("Other"));
                item.put("title", rec.get("title").asString(""));
                item.put("rank", rank);
                item.put("len", label.length());
                matches.add(item);
            }
        }

        matches.sort((a, b) -> {
            int rankCompare = Integer.compare((int) a.get("rank"), (int) b.get("rank"));
            if (rankCompare != 0) {
                return rankCompare;
            }
            return Integer.compare((int) a.get("len"), (int) b.get("len"));
        });

        return matches.stream()
                .limit(resultLimit)
                .map(item -> {
                    item.remove("rank");
                    item.remove("len");
                    return item;
                })
                .toList();
    }

    public GraphContextResponse buildContext(GraphContextRequest request, Long userId) {
        if (request == null) {
            request = new GraphContextRequest();
        }
        GraphAccumulator graph = new GraphAccumulator();
        int limit = Math.min(Math.max(request.getLimit() == null ? 30 : request.getLimit(), 10), 60);
        boolean includePrerequisites = request.getIncludePrerequisites() == null || request.getIncludePrerequisites();
        boolean includeSchedule = Boolean.TRUE.equals(request.getIncludeSchedule());
        boolean includeConflicts = Boolean.TRUE.equals(request.getIncludeConflicts());
        String scenario = request.getScenario() == null ? "generic" : request.getScenario().trim().toLowerCase(Locale.ROOT);
        boolean compactPrerequisiteCourseGraph = shouldUseCompactPrerequisiteCourseGraph(request, scenario, includePrerequisites);

        try (Session session = neo4jDriver.session()) {
            if (userId != null && ("action".equals(scenario) || includeSchedule)) {
                addCurrentUserNode(graph, session, userId);
            }

            if ("action".equals(scenario) && userId != null && includeSchedule) {
                addUserSchedule(graph, session, userId, limit);
            }

            addCourseBundle(
                    graph,
                    session,
                    request.getCourseIds(),
                    request.getCourseNames(),
                    includeSchedule,
                    !compactPrerequisiteCourseGraph,
                    !compactPrerequisiteCourseGraph,
                    limit
            );
            addTeacherBundle(graph, session, request.getTeacherNames(), limit);
            addConceptBundle(graph, session, request.getConceptNames(), limit);

            if (includePrerequisites) {
                addPrerequisites(graph, session);
            }

            if ("action".equals(scenario) && userId != null && includeConflicts) {
                addConflicts(graph, session, userId, request.getCourseIds(), request.getCourseNames());
            }
        }

        trimGraph(graph, limit);
        pruneIsolatedNodes(graph);
        enrichDefaultAnnotations(graph, scenario, includeConflicts);

        return GraphContextResponse.builder()
                .nodes(new ArrayList<>(graph.nodes.values()))
                .edges(new ArrayList<>(graph.edges.values()))
                .focusNodeIds(new ArrayList<>(graph.focusNodeIds))
                .annotations(new ArrayList<>(graph.annotations))
                .stats(GraphStatsDto.builder()
                        .nodeCount(graph.nodes.size())
                        .edgeCount(graph.edges.size())
                        .build())
                .build();
    }

    private Map<String, Object> toOverviewNodeMap(Node node, List<String> labels) {
        String group = labels.isEmpty() ? "Other" : labels.get(0);
        return Map.of(
                "id", node.elementId(),
                "label", node.get("name").asString(node.get("id").asString("unknown")),
                "group", group,
                "title", node.get("content").asString(node.get("name").asString(""))
        );
    }

    private void addCurrentUserNode(GraphAccumulator graph, Session session, Long userId) {
        List<String> userIds = UserIdUtils.buildUserIdCandidates(userId);
        String cypher = """
                MATCH (u)
                WHERE (u:User OR u:Student)
                  AND toString(u.id) IN $userIds
                RETURN u
                LIMIT 1
                """;
        Result result = session.run(cypher, Map.of("userIds", userIds));
        if (!result.hasNext()) {
            return;
        }
        Record record = result.next();
        if (record.get("u").isNull()) {
            return;
        }
        Node userNode = record.get("u").asNode();
        graph.addNode(toGraphNode(userNode, true));
        graph.focusNodeIds.add(userNode.elementId());
    }

    private void addUserSchedule(GraphAccumulator graph, Session session, Long userId, int limit) {
        List<String> userIds = UserIdUtils.buildUserIdCandidates(userId);
        String cypher = """
                MATCH (u)-[:USER_COURSE]->(c:Course)
                WHERE (u:User OR u:Student)
                  AND toString(u.id) IN $userIds
                OPTIONAL MATCH (c)-[timeRel:COURSE_TIME]->(t:Time)
                OPTIONAL MATCH (teacher:Teacher)-[teachRel:TEACHER_COURSE]->(c)
                RETURN u, c,
                       collect(DISTINCT teacher) AS teachers,
                       collect(DISTINCT t) AS times
                LIMIT $limit
                """;

        Result result = session.run(cypher, Map.of("userIds", userIds, "limit", limit));
        while (result.hasNext()) {
            Record record = result.next();
            Node user = record.get("u").asNode();
            Node course = record.get("c").asNode();
            graph.addNode(toGraphNode(user, false));
            graph.addNode(toGraphNode(course, true));
            graph.focusNodeIds.add(course.elementId());
            graph.addEdge(user.elementId(), course.elementId(), "USER_COURSE", true, Map.of("kind", "enrolled"));

            for (Node teacher : record.get("teachers").asList(Value::asNode)) {
                graph.addNode(toGraphNode(teacher, false));
                graph.addEdge(teacher.elementId(), course.elementId(), "TEACHER_COURSE", false, Map.of());
            }
            for (Node time : record.get("times").asList(Value::asNode)) {
                graph.addNode(toGraphNode(time, false));
                graph.addEdge(course.elementId(), time.elementId(), "COURSE_TIME", false, Map.of("kind", "schedule"));
            }
        }
    }

    private void addCourseBundle(
            GraphAccumulator graph,
            Session session,
            List<String> courseIds,
            List<String> courseNames,
            boolean includeTime,
            boolean includeTeachers,
            boolean includeConcepts,
            int limit
    ) {
        List<String> normalizedIds = sanitizeList(courseIds);
        List<String> normalizedNames = sanitizeList(courseNames);
        if (normalizedIds.isEmpty() && normalizedNames.isEmpty()) {
            return;
        }

        int matchLimit = computeCourseMatchLimit(normalizedIds, normalizedNames, limit);
        String cypher = """
                MATCH (c:Course)
                WITH c,
                     CASE WHEN c.id IN $courseIds THEN 100 ELSE 0 END AS idScore,
                     reduce(score = 0, name IN $courseNames |
                        score + CASE
                            WHEN toLower(coalesce(c.name, '')) = toLower(name) THEN 80
                            WHEN size(name) >= 4 AND toLower(coalesce(c.name, '')) STARTS WITH toLower(name) THEN 24
                            WHEN size(name) >= 6 AND toLower(coalesce(c.name, '')) CONTAINS toLower(name) THEN 12
                            ELSE 0
                        END
                     ) AS nameScore
                WITH c, (idScore + nameScore) AS score
                WHERE score > 0
                ORDER BY score DESC, size(coalesce(c.name, '')) ASC, coalesce(c.name, '') ASC
                LIMIT $matchLimit
                OPTIONAL MATCH (teacher:Teacher)-[:TEACHER_COURSE]->(c)
                OPTIONAL MATCH (c)-[:COURSE_CONCEPT]->(concept:Concept)
                OPTIONAL MATCH (c)-[:COURSE_TIME]->(time:Time)
                RETURN c,
                       collect(DISTINCT teacher) AS teachers,
                       collect(DISTINCT concept) AS concepts,
                       collect(DISTINCT time) AS times,
                       score
                ORDER BY score DESC, size(coalesce(c.name, '')) ASC, coalesce(c.name, '') ASC
                """;

        Result result = session.run(cypher, Map.of(
                "courseIds", normalizedIds,
                "courseNames", normalizedNames,
                "matchLimit", matchLimit
        ));

        Set<String> seenNormalizedCourseNames = new LinkedHashSet<>();
        while (result.hasNext()) {
            Record record = result.next();
            Node course = record.get("c").asNode();
            String normalizedCourseName = normalizeCourseNameForGraph(firstNonBlank(course, "name", "id"));
            if (!normalizedCourseName.isBlank() && !seenNormalizedCourseNames.add(normalizedCourseName)) {
                continue;
            }
            graph.addNode(toGraphNode(course, true));
            graph.focusNodeIds.add(course.elementId());

            List<Node> concepts = record.get("concepts").asList(Value::asNode);
            List<Node> teachers = record.get("teachers").asList(Value::asNode);
            List<Node> times = record.get("times").asList(Value::asNode);

            if (includeTeachers) {
                for (Node teacher : teachers) {
                    graph.addNode(toGraphNode(teacher, false));
                    graph.addEdge(teacher.elementId(), course.elementId(), "TEACHER_COURSE", false, Map.of());
                }
            }
            if (includeConcepts) {
                for (Node concept : concepts) {
                    graph.addNode(toGraphNode(concept, false));
                    graph.addEdge(course.elementId(), concept.elementId(), "COURSE_CONCEPT", true, Map.of("kind", "concept"));
                }
            }
            if (includeTime) {
                for (Node time : times) {
                    graph.addNode(toGraphNode(time, false));
                    graph.addEdge(course.elementId(), time.elementId(), "COURSE_TIME", false, Map.of("kind", "schedule"));
                }
            }
        }
    }

    private boolean shouldUseCompactPrerequisiteCourseGraph(
            GraphContextRequest request,
            String scenario,
            boolean includePrerequisites
    ) {
        if (!"qa".equals(scenario) || !includePrerequisites) {
            return false;
        }

        boolean hasCourseFocus = !sanitizeList(request.getCourseIds()).isEmpty() || !sanitizeList(request.getCourseNames()).isEmpty();
        boolean hasConceptFocus = !sanitizeList(request.getConceptNames()).isEmpty();
        boolean hasTeacherFocus = !sanitizeList(request.getTeacherNames()).isEmpty();
        return hasCourseFocus && !hasConceptFocus && !hasTeacherFocus;
    }

    private void addTeacherBundle(GraphAccumulator graph, Session session, List<String> teacherNames, int limit) {
        List<String> normalizedNames = sanitizeList(teacherNames);
        if (normalizedNames.isEmpty()) {
            return;
        }

        String cypher = """
                MATCH (teacher:Teacher)-[:TEACHER_COURSE]->(course:Course)
                WHERE any(name IN $teacherNames WHERE toLower(teacher.name) CONTAINS toLower(name))
                RETURN teacher, collect(DISTINCT course) AS courses
                LIMIT $limit
                """;

        Result result = session.run(cypher, Map.of("teacherNames", normalizedNames, "limit", limit));
        while (result.hasNext()) {
            Record record = result.next();
            Node teacher = record.get("teacher").asNode();
            graph.addNode(toGraphNode(teacher, true));
            graph.focusNodeIds.add(teacher.elementId());

            for (Node course : record.get("courses").asList(Value::asNode)) {
                graph.addNode(toGraphNode(course, false));
                graph.addEdge(teacher.elementId(), course.elementId(), "TEACHER_COURSE", true, Map.of());
            }
        }
    }

    private void addConceptBundle(GraphAccumulator graph, Session session, List<String> conceptNames, int limit) {
        List<String> normalizedNames = sanitizeList(conceptNames);
        if (normalizedNames.isEmpty()) {
            return;
        }

        String cypher = """
                MATCH (concept:Concept)
                WHERE any(name IN $conceptNames WHERE toLower(concept.name) CONTAINS toLower(name))
                OPTIONAL MATCH (course:Course)-[:COURSE_CONCEPT]->(concept)
                RETURN concept, collect(DISTINCT course) AS courses
                LIMIT $limit
                """;

        Result result = session.run(cypher, Map.of("conceptNames", normalizedNames, "limit", limit));
        while (result.hasNext()) {
            Record record = result.next();
            Node concept = record.get("concept").asNode();
            graph.addNode(toGraphNode(concept, true));
            graph.focusNodeIds.add(concept.elementId());

            for (Node course : record.get("courses").asList(Value::asNode)) {
                graph.addNode(toGraphNode(course, false));
                graph.addEdge(course.elementId(), concept.elementId(), "COURSE_CONCEPT", true, Map.of("kind", "concept"));
            }
        }
    }

    private void addPrerequisites(GraphAccumulator graph, Session session) {
        List<String> courseIds = graph.nodes.values().stream()
                .filter(node -> "Course".equals(node.getGroup()))
                .map(GraphNodeDto::getId)
                .toList();
        if (!courseIds.isEmpty()) {
            addCoursePrerequisites(graph, session, courseIds);
            return;
        }

        addConceptPrerequisites(graph, session);
    }

    private void addCoursePrerequisites(GraphAccumulator graph, Session session, List<String> courseNodeIds) {
        if (courseNodeIds == null || courseNodeIds.isEmpty()) {
            return;
        }

        String cypher = """
                MATCH (preCourse:Course)-[:COURSE_CONCEPT]->(preConcept:Concept)-[:PREREQUISITE_DEPENDENCY]->(concept:Concept)<-[:COURSE_CONCEPT]-(course:Course)
                WHERE elementId(course) IN $courseNodeIds
                  AND elementId(preCourse) <> elementId(course)
                WITH preCourse, course, collect(DISTINCT preConcept.name) AS prerequisiteConceptNames
                RETURN preCourse, course, prerequisiteConceptNames
                ORDER BY size(prerequisiteConceptNames) DESC, coalesce(preCourse.name, '') ASC
                """;

        Result result = session.run(cypher, Map.of("courseNodeIds", courseNodeIds));
        Set<String> seenPairs = new LinkedHashSet<>();
        while (result.hasNext()) {
            Record record = result.next();
            Node prerequisiteCourse = record.get("preCourse").asNode();
            Node course = record.get("course").asNode();
            String normalizedCourseName = normalizeCourseNameForGraph(firstNonBlank(course, "name", "id"));
            String normalizedPrerequisiteName = normalizeCourseNameForGraph(firstNonBlank(prerequisiteCourse, "name", "id"));
            if (normalizedPrerequisiteName.isBlank() || normalizedPrerequisiteName.equals(normalizedCourseName)) {
                continue;
            }

            String pairKey = course.elementId() + "->" + normalizedPrerequisiteName;
            if (!seenPairs.add(pairKey)) {
                continue;
            }

            List<String> prerequisiteConceptNames = sanitizeList(
                    record.get("prerequisiteConceptNames").asList(value -> value.isNull() ? "" : value.asString())
            );

            graph.addNode(toGraphNode(prerequisiteCourse, false));
            graph.addEdge(
                    prerequisiteCourse.elementId(),
                    course.elementId(),
                    "COURSE_PREREQUISITE",
                    true,
                    Map.of(
                            "kind", "prerequisite-course",
                            "concepts", prerequisiteConceptNames.stream().limit(4).toList()
                    )
            );
        }
    }

    private String normalizeCourseNameForGraph(String rawName) {
        if (rawName == null) {
            return "";
        }

        String normalized = rawName.trim()
                .replaceAll("\\s*[（(][^（）()]{0,40}[）)]", "")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized;
    }

    private void addConceptPrerequisites(GraphAccumulator graph, Session session) {
        List<String> conceptIds = graph.nodes.values().stream()
                .filter(node -> "Concept".equals(node.getGroup()))
                .map(GraphNodeDto::getId)
                .toList();
        if (conceptIds.isEmpty()) {
            return;
        }

        String cypher = """
                MATCH (pre:Concept)-[:PREREQUISITE_DEPENDENCY]->(concept:Concept)
                WHERE elementId(concept) IN $conceptIds
                RETURN pre, concept
                """;

        Result result = session.run(cypher, Map.of("conceptIds", conceptIds));
        while (result.hasNext()) {
            Record record = result.next();
            Node prerequisite = record.get("pre").asNode();
            Node concept = record.get("concept").asNode();
            graph.addNode(toGraphNode(prerequisite, false));
            graph.addEdge(prerequisite.elementId(), concept.elementId(), "PREREQUISITE_DEPENDENCY", true, Map.of("kind", "prerequisite"));
        }
    }

    private void addConflicts(
            GraphAccumulator graph,
            Session session,
            Long userId,
            List<String> courseIds,
            List<String> courseNames
    ) {
        List<String> userIds = UserIdUtils.buildUserIdCandidates(userId);
        List<String> normalizedIds = sanitizeList(courseIds);
        List<String> normalizedNames = sanitizeList(courseNames);
        if (normalizedIds.isEmpty() && normalizedNames.isEmpty()) {
            return;
        }

        String cypher = """
                MATCH (target:Course)-[:COURSE_TIME]->(targetTime:Time)
                WHERE (size($courseIds) = 0 OR target.id IN $courseIds)
                   OR any(name IN $courseNames WHERE toLower(target.name) CONTAINS toLower(name))
                MATCH (u)-[:USER_COURSE]->(current:Course)-[:COURSE_TIME]->(currentTime:Time)
                WHERE (u:User OR u:Student)
                  AND toString(u.id) IN $userIds
                  AND current.id <> target.id
                  AND (
                    coalesce(currentTime.id, '') = coalesce(targetTime.id, '')
                    OR (
                      coalesce(toString(currentTime.weekday_index), '') = coalesce(toString(targetTime.weekday_index), '')
                      AND coalesce(currentTime.half_day, '') = coalesce(targetTime.half_day, '')
                      AND coalesce(toString(currentTime.period_index), '') = coalesce(toString(targetTime.period_index), '')
                    )
                  )
                RETURN u, target, targetTime, collect(DISTINCT current) AS currentCourses, collect(DISTINCT currentTime) AS currentTimes
                """;

        Result result = session.run(cypher, Map.of(
                "userIds", userIds,
                "courseIds", normalizedIds,
                "courseNames", normalizedNames
        ));

        while (result.hasNext()) {
            Record record = result.next();
            Node user = record.get("u").asNode();
            Node target = record.get("target").asNode();
            Node targetTime = record.get("targetTime").asNode();

            graph.addNode(toGraphNode(user, true));
            graph.addNode(toGraphNode(target, true));
            graph.addNode(toGraphNode(targetTime, true));
            graph.addEdge(user.elementId(), target.elementId(), "USER_COURSE", true, Map.of("kind", "pending-enrollment"));
            graph.addEdge(target.elementId(), targetTime.elementId(), "COURSE_TIME", true, Map.of("kind", "target-time"));

            List<Node> currentCourses = record.get("currentCourses").asList(Value::asNode);
            List<Node> currentTimes = record.get("currentTimes").asList(Value::asNode);

            for (Node course : currentCourses) {
                graph.addNode(toGraphNode(course, true));
                graph.addEdge(user.elementId(), course.elementId(), "USER_COURSE", true, Map.of("kind", "conflict-course"));
            }
            for (Node time : currentTimes) {
                graph.addNode(toGraphNode(time, true));
                graph.addEdge(target.elementId(), time.elementId(), "COURSE_TIME", true, Map.of("kind", "conflict-time"));
            }

            graph.addAnnotation(GraphAnnotationDto.builder()
                    .id("conflict-" + target.elementId())
                    .title("时间冲突")
                    .content("目标课程与当前已选课程存在时间冲突，图中已标出目标课程、冲突课程和对应时间节点。")
                    .tone("warning")
                    .relatedNodeIds(mergeLists(
                            List.of(target.elementId(), targetTime.elementId()),
                            currentCourses.stream().map(Node::elementId).toList(),
                            currentTimes.stream().map(Node::elementId).toList()
                    ))
                    .build());
        }
    }

    private GraphNodeDto toGraphNode(Node node, boolean highlighted) {
        String group = node.labels().iterator().hasNext() ? node.labels().iterator().next() : "Other";
        String label = switch (group) {
            case "Time" -> formatTimeLabel(node);
            case "Student", "User" -> firstNonBlank(node, "name", "realName", "username", "id");
            default -> firstNonBlank(node, "name", "id");
        };
        String title = switch (group) {
            case "Time" -> formatTimeTitle(node);
            default -> firstNonBlank(node, "content", "name", "id");
        };

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("entityType", group);
        metadata.put("entityId", node.get("id").isNull() ? node.elementId() : node.get("id").asString());
        metadata.put("elementId", node.elementId());
        if (node.containsKey("content") && !node.get("content").isNull()) {
            metadata.put("content", node.get("content").asString());
        }
        if ("Time".equals(group)) {
            metadata.put("weekdayIndex", parseIntegerValue(node.get("weekday_index")));
            metadata.put("halfDay", node.get("half_day").isNull() ? null : node.get("half_day").asString());
            metadata.put("periodIndex", parseIntegerValue(node.get("period_index")));
            metadata.put("startTime", node.get("start_time").isNull() ? null : node.get("start_time").asString());
            metadata.put("endTime", node.get("end_time").isNull() ? null : node.get("end_time").asString());
        }

        return GraphNodeDto.builder()
                .id(node.elementId())
                .label(label)
                .group(group)
                .title(title)
                .highlighted(highlighted)
                .metadata(metadata)
                .build();
    }

    private void trimGraph(GraphAccumulator graph, int limit) {
        if (graph.nodes.size() <= limit) {
            return;
        }

        Set<String> keepIds = new LinkedHashSet<>(graph.focusNodeIds);
        if (keepIds.isEmpty()) {
            keepIds.addAll(graph.nodes.keySet().stream().limit(limit).toList());
        }

        for (GraphEdgeDto edge : graph.edges.values()) {
            if (keepIds.size() >= limit) {
                break;
            }
            if (keepIds.contains(edge.getFrom()) || keepIds.contains(edge.getTo())) {
                keepIds.add(edge.getFrom());
                keepIds.add(edge.getTo());
            }
        }

        if (keepIds.size() < limit) {
            for (String nodeId : graph.nodes.keySet()) {
                if (keepIds.size() >= limit) {
                    break;
                }
                keepIds.add(nodeId);
            }
        }

        graph.nodes.entrySet().removeIf(entry -> !keepIds.contains(entry.getKey()));
        graph.edges.entrySet().removeIf(entry -> !keepIds.contains(entry.getValue().getFrom()) || !keepIds.contains(entry.getValue().getTo()));
        graph.focusNodeIds.retainAll(keepIds);
        graph.annotations.replaceAll(annotation -> {
            List<String> relatedNodeIds = annotation.getRelatedNodeIds() == null
                    ? List.of()
                    : annotation.getRelatedNodeIds().stream().filter(keepIds::contains).toList();
            annotation.setRelatedNodeIds(relatedNodeIds);
            return annotation;
        });
    }

    private void pruneIsolatedNodes(GraphAccumulator graph) {
        if (graph.nodes.size() <= 1 || graph.edges.isEmpty()) {
            return;
        }

        Set<String> connectedNodeIds = new LinkedHashSet<>();
        for (GraphEdgeDto edge : graph.edges.values()) {
            connectedNodeIds.add(edge.getFrom());
            connectedNodeIds.add(edge.getTo());
        }

        graph.nodes.entrySet().removeIf(entry -> !connectedNodeIds.contains(entry.getKey()));
        graph.focusNodeIds.retainAll(connectedNodeIds);
        if (graph.focusNodeIds.isEmpty() && !connectedNodeIds.isEmpty()) {
            graph.focusNodeIds.add(selectFallbackFocusNodeId(graph, connectedNodeIds));
        }

        graph.annotations.replaceAll(annotation -> {
            List<String> relatedNodeIds = annotation.getRelatedNodeIds() == null
                    ? List.of()
                    : annotation.getRelatedNodeIds().stream().filter(connectedNodeIds::contains).toList();
            annotation.setRelatedNodeIds(relatedNodeIds);
            return annotation;
        });
    }

    private String selectFallbackFocusNodeId(GraphAccumulator graph, Set<String> connectedNodeIds) {
        for (GraphNodeDto node : graph.nodes.values()) {
            if (connectedNodeIds.contains(node.getId()) && node.isHighlighted()) {
                return node.getId();
            }
        }
        return connectedNodeIds.iterator().next();
    }

    private void enrichDefaultAnnotations(GraphAccumulator graph, String scenario, boolean includeConflicts) {
        if (!graph.annotations.isEmpty()) {
            return;
        }

        if ("recommendation".equals(scenario)) {
            List<String> focusIds = new ArrayList<>(graph.focusNodeIds);
            graph.addAnnotation(GraphAnnotationDto.builder()
                    .id("recommendation-focus")
                    .title("推荐子图")
                    .content("右侧图谱展示了本轮推荐课程与其知识点、教师和先修关系。")
                    .tone("info")
                    .relatedNodeIds(focusIds)
                    .build());
            return;
        }

        if ("qa".equals(scenario)) {
            List<String> focusIds = new ArrayList<>(graph.focusNodeIds);
            graph.addAnnotation(GraphAnnotationDto.builder()
                    .id("qa-focus")
                    .title("问答证据")
                    .content("高亮节点表示本轮回答直接依赖的课程、教师或知识点证据。")
                    .tone("info")
                    .relatedNodeIds(focusIds)
                    .build());
            return;
        }

        if ("action".equals(scenario) && includeConflicts) {
            List<String> focusIds = new ArrayList<>(graph.focusNodeIds);
            graph.addAnnotation(GraphAnnotationDto.builder()
                    .id("action-focus")
                    .title("选课影响")
                    .content("图谱正在展示目标课程与当前课表的关系，便于在执行前确认影响范围。")
                    .tone("warning")
                    .relatedNodeIds(focusIds)
                    .build());
        }
    }

    private String formatTimeLabel(Node time) {
        String weekday = time.containsKey("weekday_code") && !time.get("weekday_code").isNull()
                ? time.get("weekday_code").asString()
                : time.containsKey("weekday_name") && !time.get("weekday_name").isNull()
                ? time.get("weekday_name").asString()
                : "时间";
        String halfDay = time.containsKey("half_day") && !time.get("half_day").isNull()
                ? time.get("half_day").asString()
                : "";
        Integer periodIndex = time.containsKey("period_index") && !time.get("period_index").isNull()
                ? parseIntegerValue(time.get("period_index"))
                : null;
        return periodIndex == null
                ? weekday + " " + translateHalfDay(halfDay)
                : weekday + " " + translateHalfDay(halfDay) + " 第" + periodIndex + "节";
    }

    private String formatTimeTitle(Node time) {
        String label = formatTimeLabel(time);
        String startTime = time.containsKey("start_time") && !time.get("start_time").isNull() ? time.get("start_time").asString() : "";
        String endTime = time.containsKey("end_time") && !time.get("end_time").isNull() ? time.get("end_time").asString() : "";
        if (!startTime.isBlank() && !endTime.isBlank()) {
            return label + " (" + startTime + " - " + endTime + ")";
        }
        return label;
    }

    private String translateHalfDay(String halfDay) {
        return switch (halfDay) {
            case "morning" -> "上午";
            case "afternoon" -> "下午";
            default -> halfDay == null ? "" : halfDay;
        };
    }

    private Integer parseIntegerValue(Value value) {
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
            } catch (NumberFormatException ex) {
                log.warn("Unable to parse Neo4j numeric value: {}", raw);
                return null;
            }
        }
    }

    private String firstNonBlank(Node node, String... keys) {
        for (String key : keys) {
            if (node.containsKey(key) && !node.get(key).isNull()) {
                String value = node.get(key).asString("");
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return node.elementId();
    }

    private int computeCourseMatchLimit(List<String> courseIds, List<String> courseNames, int limit) {
        int requestedSeeds = (courseIds == null ? 0 : courseIds.size()) + (courseNames == null ? 0 : courseNames.size());
        int safeSeedLimit = Math.max(4, requestedSeeds * 2);
        return Math.min(limit, Math.min(10, safeSeedLimit));
    }

    @SafeVarargs
    private final List<String> mergeLists(List<String>... parts) {
        List<String> merged = new ArrayList<>();
        for (List<String> part : parts) {
            if (part != null) {
                merged.addAll(part);
            }
        }
        return merged.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private List<String> sanitizeList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }

    private static final class GraphAccumulator {
        private final Map<String, GraphNodeDto> nodes = new LinkedHashMap<>();
        private final Map<String, GraphEdgeDto> edges = new LinkedHashMap<>();
        private final LinkedHashSet<String> focusNodeIds = new LinkedHashSet<>();
        private final List<GraphAnnotationDto> annotations = new ArrayList<>();

        private void addNode(GraphNodeDto node) {
            nodes.merge(node.getId(), node, (existing, incoming) -> {
                existing.setHighlighted(existing.isHighlighted() || incoming.isHighlighted());
                if ((existing.getTitle() == null || existing.getTitle().isBlank()) && incoming.getTitle() != null) {
                    existing.setTitle(incoming.getTitle());
                }
                if (incoming.getMetadata() != null && !incoming.getMetadata().isEmpty()) {
                    Map<String, Object> mergedMetadata = new LinkedHashMap<>();
                    if (existing.getMetadata() != null) {
                        mergedMetadata.putAll(existing.getMetadata());
                    }
                    mergedMetadata.putAll(incoming.getMetadata());
                    existing.setMetadata(mergedMetadata);
                }
                return existing;
            });
        }

        private void addEdge(String from, String to, String label, boolean highlighted, Map<String, Object> metadata) {
            String key = from + "->" + to + ":" + label;
            edges.merge(key, GraphEdgeDto.builder()
                    .from(from)
                    .to(to)
                    .label(label)
                    .highlighted(highlighted)
                    .metadata(metadata == null ? Map.of() : new LinkedHashMap<>(metadata))
                    .build(), (existing, incoming) -> {
                existing.setHighlighted(existing.isHighlighted() || incoming.isHighlighted());
                if (incoming.getMetadata() != null && !incoming.getMetadata().isEmpty()) {
                    Map<String, Object> mergedMetadata = new LinkedHashMap<>();
                    if (existing.getMetadata() != null) {
                        mergedMetadata.putAll(existing.getMetadata());
                    }
                    mergedMetadata.putAll(incoming.getMetadata());
                    existing.setMetadata(mergedMetadata);
                }
                return existing;
            });
        }

        private void addAnnotation(GraphAnnotationDto annotation) {
            if (annotation == null || annotation.getId() == null || annotation.getId().isBlank()) {
                return;
            }

            for (GraphAnnotationDto existing : annotations) {
                if (!annotation.getId().equals(existing.getId())) {
                    continue;
                }
                List<String> mergedRelatedNodeIds = new ArrayList<>();
                if (existing.getRelatedNodeIds() != null) {
                    mergedRelatedNodeIds.addAll(existing.getRelatedNodeIds());
                }
                if (annotation.getRelatedNodeIds() != null) {
                    mergedRelatedNodeIds.addAll(annotation.getRelatedNodeIds());
                }
                existing.setRelatedNodeIds(mergedRelatedNodeIds.stream().filter(Objects::nonNull).distinct().toList());
                if ((existing.getContent() == null || existing.getContent().isBlank()) && annotation.getContent() != null) {
                    existing.setContent(annotation.getContent());
                }
                if ((existing.getTitle() == null || existing.getTitle().isBlank()) && annotation.getTitle() != null) {
                    existing.setTitle(annotation.getTitle());
                }
                if ((existing.getTone() == null || existing.getTone().isBlank()) && annotation.getTone() != null) {
                    existing.setTone(annotation.getTone());
                }
                return;
            }

            GraphAnnotationDto copy = GraphAnnotationDto.builder()
                    .id(annotation.getId())
                    .title(annotation.getTitle())
                    .content(annotation.getContent())
                    .tone(annotation.getTone())
                    .relatedNodeIds(annotation.getRelatedNodeIds() == null ? List.of() : new ArrayList<>(annotation.getRelatedNodeIds()))
                    .build();
            annotations.add(copy);
        }
    }
}
