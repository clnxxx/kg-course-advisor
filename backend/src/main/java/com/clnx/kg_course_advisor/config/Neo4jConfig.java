package com.clnx.kg_course_advisor.config;

import org.neo4j.driver.Driver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.neo4j.core.transaction.Neo4jTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Neo4j 配置
 * 使用 Spring Boot 自动配置的 Neo4j Driver 连接
 * 所有查询通过 CypherExecutionService 以 Text-to-Cypher 方式执行
 */
@Configuration
public class Neo4jConfig {

    /**
     * 配置 Neo4j 事务管理器，使用不同的名称避免冲突
     */
    @Bean(name = "neo4jTransactionManager")
    public PlatformTransactionManager neo4jTransactionManager(Driver driver) {
        return new Neo4jTransactionManager(driver);
    }
}
