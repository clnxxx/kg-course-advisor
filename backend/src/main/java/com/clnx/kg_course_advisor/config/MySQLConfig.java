package com.clnx.kg_course_advisor.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * MySQL JPA 配置
 */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
        basePackages = "com.clnx.kg_course_advisor.repository.mysql",
        transactionManagerRef = "transactionManager"
)
public class MySQLConfig {

    /**
     * 配置主事务管理器（JPA）
     */
    @Bean(name = "transactionManager")
    @Primary
    public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
