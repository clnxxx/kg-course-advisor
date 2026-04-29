package com.clnx.kg_course_advisor.entity.mysql;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 用户实体类 - MySQL
 * 存储用户登录信息和基本资料
 * 注：选课数据存储在 Neo4j 中，通过 Student 节点关联
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 50)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(unique = true, length = 100)
    private String email;

    @Column(name = "student_id", unique = true, length = 20)
    private String studentId;

    @Column(name = "real_name", length = 50)
    private String realName;

    @Column(length = 100)
    private String major;

    private Integer grade;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private UserRole role = UserRole.STUDENT;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum UserRole {
        STUDENT, TEACHER, ADMIN
    }
}
