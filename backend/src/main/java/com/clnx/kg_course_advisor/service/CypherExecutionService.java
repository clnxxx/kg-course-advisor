package com.clnx.kg_course_advisor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Record;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Cypher 执行服务
 * 用于执行 Action Agent 生成的 Cypher 语句
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CypherExecutionService {

    private final Driver neo4jDriver;

    /**
     * 执行 Cypher 查询语句（只读）
     */
    public String executeQuery(String cypher) {
        log.info("执行 Cypher 查询: {}", cypher);

        try (Session session = neo4jDriver.session()) {
            Result result = session.run(cypher);
            List<Record> records = result.list();

            if (records.isEmpty()) {
                return "查询结果为空";
            }

            // 格式化输出结果
            StringBuilder sb = new StringBuilder();
            for (Record record : records) {
                sb.append(formatRecord(record)).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("Cypher 查询执行失败: {}", cypher, e);
            return "查询执行失败: " + e.getMessage();
        }
    }

    /**
     * 执行 Cypher 写入语句（创建、更新、删除）
     */
    public String executeWrite(String cypher) {
        log.info("执行 Cypher 写入: {}", cypher);

        try (Session session = neo4jDriver.session()) {
            Result result = session.run(cypher);

            // 获取执行摘要
            var summary = result.consume();
            int nodesCreated = summary.counters().nodesCreated();
            int relationshipsCreated = summary.counters().relationshipsCreated();
            int nodesDeleted = summary.counters().nodesDeleted();
            int relationshipsDeleted = summary.counters().relationshipsDeleted();
            int propertiesSet = summary.counters().propertiesSet();

            StringBuilder sb = new StringBuilder("操作成功！");
            if (nodesCreated > 0) sb.append(" 创建了 ").append(nodesCreated).append(" 个节点。");
            if (relationshipsCreated > 0) sb.append(" 创建了 ").append(relationshipsCreated).append(" 个关系。");
            if (nodesDeleted > 0) sb.append(" 删除了 ").append(nodesDeleted).append(" 个节点。");
            if (relationshipsDeleted > 0) sb.append(" 删除了 ").append(relationshipsDeleted).append(" 个关系。");
            if (propertiesSet > 0) sb.append(" 设置了 ").append(propertiesSet).append(" 个属性。");

            return sb.toString();
        } catch (Exception e) {
            log.error("Cypher 写入执行失败: {}", cypher, e);
            return "操作执行失败: " + e.getMessage();
        }
    }

    /**
     * 执行参数化 Cypher 查询语句（只读）
     */
    public String executeQuery(String cypher, Map<String, Object> params) {
        log.info("执行参数化 Cypher 查询: {}, 参数: {}", cypher, params);

        try (Session session = neo4jDriver.session()) {
            Result result = session.run(cypher, params);
            List<Record> records = result.list();

            if (records.isEmpty()) {
                return "查询结果为空";
            }

            StringBuilder sb = new StringBuilder();
            for (Record record : records) {
                sb.append(formatRecord(record)).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("参数化 Cypher 查询执行失败: {}, 参数: {}", cypher, params, e);
            return "查询执行失败: " + e.getMessage();
        }
    }

    /**
     * 执行参数化 Cypher 写入语句（创建、更新、删除）
     */
    public List<Map<String, Object>> executeQueryRecords(String cypher, Map<String, Object> params) {
        log.info("Executing structured Cypher query: {}, params: {}", cypher, params);

        try (Session session = neo4jDriver.session()) {
            Result result = session.run(cypher, params);
            List<Map<String, Object>> rows = new ArrayList<>();
            while (result.hasNext()) {
                Record record = result.next();
                Map<String, Object> row = new LinkedHashMap<>();
                for (String key : record.keys()) {
                    row.put(key, record.get(key).isNull() ? null : record.get(key).asObject());
                }
                rows.add(row);
            }
            return rows;
        } catch (Exception e) {
            log.error("Structured Cypher query failed: {}, params: {}", cypher, params, e);
            return List.of();
        }
    }

    public String executeWrite(String cypher, Map<String, Object> params) {
        log.info("执行参数化 Cypher 写入: {}, 参数: {}", cypher, params);

        try (Session session = neo4jDriver.session()) {
            Result result = session.run(cypher, params);

            var summary = result.consume();
            int nodesCreated = summary.counters().nodesCreated();
            int relationshipsCreated = summary.counters().relationshipsCreated();
            int nodesDeleted = summary.counters().nodesDeleted();
            int relationshipsDeleted = summary.counters().relationshipsDeleted();
            int propertiesSet = summary.counters().propertiesSet();

            StringBuilder sb = new StringBuilder("操作成功！");
            if (nodesCreated > 0) sb.append(" 创建了 ").append(nodesCreated).append(" 个节点。");
            if (relationshipsCreated > 0) sb.append(" 创建了 ").append(relationshipsCreated).append(" 个关系。");
            if (nodesDeleted > 0) sb.append(" 删除了 ").append(nodesDeleted).append(" 个节点。");
            if (relationshipsDeleted > 0) sb.append(" 删除了 ").append(relationshipsDeleted).append(" 个关系。");
            if (propertiesSet > 0) sb.append(" 设置了 ").append(propertiesSet).append(" 个属性。");

            return sb.toString();
        } catch (Exception e) {
            log.error("参数化 Cypher 写入执行失败: {}, 参数: {}", cypher, params, e);
            return "操作执行失败: " + e.getMessage();
        }
    }

    /**
     * 执行 Cypher 并返回结果（通用方法）
     */
    public String execute(String cypher, boolean isWrite) {
        if (isWrite) {
            return executeWrite(cypher);
        } else {
            return executeQuery(cypher);
        }
    }

    /**
     * 格式化单条记录
     */
    private String formatRecord(Record record) {
        return record.keys().stream()
                .map(key -> key + ": " + formatValue(record.get(key).asObject()))
                .collect(Collectors.joining(", "));
    }

    /**
     * 格式化值
     */
    private String formatValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            return map.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(", ", "{", "}"));
        }
        if (value instanceof List) {
            return ((List<?>) value).stream()
                    .map(this::formatValue)
                    .collect(Collectors.joining(", ", "[", "]"));
        }
        return value.toString();
    }
}
