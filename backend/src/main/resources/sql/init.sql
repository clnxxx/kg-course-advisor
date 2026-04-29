-- KG Course Advisor - MySQL recovery script
-- This script recreates required tables and inserts mock data.
-- Import command example:
--   mysql -u root -p < backend/src/main/resources/sql/init.sql

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS `kg_course_advisor`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE `kg_course_advisor`;

-- Drop and recreate tables
DROP TABLE IF EXISTS `SPRING_AI_CHAT_MEMORY`;
DROP TABLE IF EXISTS `users`;

CREATE TABLE `users` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `username` VARCHAR(50) NOT NULL,
    `password` VARCHAR(255) NOT NULL,
    `email` VARCHAR(100) DEFAULT NULL,
    `student_id` VARCHAR(20) DEFAULT NULL,
    `real_name` VARCHAR(50) DEFAULT NULL,
    `major` VARCHAR(100) DEFAULT NULL,
    `grade` INT DEFAULT NULL,
    `role` VARCHAR(20) NOT NULL DEFAULT 'STUDENT',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_users_username` (`username`),
    UNIQUE KEY `uk_users_email` (`email`),
    UNIQUE KEY `uk_users_student_id` (`student_id`),
    KEY `idx_users_role` (`role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Application users';

-- Spring AI JDBC chat memory table (from spring-ai 1.1.0 schema-mysql.sql)
CREATE TABLE `SPRING_AI_CHAT_MEMORY` (
    `conversation_id` VARCHAR(36) NOT NULL,
    `content` TEXT NOT NULL,
    `type` ENUM('USER', 'ASSISTANT', 'SYSTEM', 'TOOL') NOT NULL,
    `timestamp` TIMESTAMP NOT NULL,
    KEY `SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX` (`conversation_id`, `timestamp`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Spring AI chat memory';

-- Mock users
-- Note: passwords are plaintext for emergency recovery.
-- The backend currently supports plaintext fallback and auto-migrates to BCrypt on login.
INSERT INTO `users` (`id`, `username`, `password`, `email`, `student_id`, `real_name`, `major`, `grade`, `role`) VALUES
    (1, 'admin', '123456', 'admin@kg.local', NULL, 'System Admin', NULL, NULL, 'ADMIN'),
    (2, 'teacher01', '123456', 'teacher01@kg.local', NULL, 'Zhang Teacher', 'Computer Science', NULL, 'TEACHER'),
    (3, 'student01', '123456', 'student01@kg.local', '20230001', 'Li Ming', 'Software Engineering', 3, 'STUDENT'),
    (4, 'student02', '123456', 'student02@kg.local', '20230002', 'Wang Fang', 'Artificial Intelligence', 2, 'STUDENT'),
    (5, 'student03', '123456', 'student03@kg.local', '20230003', 'Chen Lei', 'Data Science', 1, 'STUDENT');

-- Mock chat history
INSERT INTO `SPRING_AI_CHAT_MEMORY` (`conversation_id`, `content`, `type`, `timestamp`) VALUES
    ('u1_aaaaaaaaaaaa', 'Please summarize available AI courses.', 'USER', '2026-03-12 10:00:01'),
    ('u1_aaaaaaaaaaaa', 'Sure. Here are 3 AI-related courses and their key concepts.', 'ASSISTANT', '2026-03-12 10:00:03'),
    ('u3_bbbbbbbbbbbb', 'What prerequisites do I need for Machine Learning?', 'USER', '2026-03-12 10:02:10'),
    ('u3_bbbbbbbbbbbb', 'You should review linear algebra, probability, and Python basics.', 'ASSISTANT', '2026-03-12 10:02:13');

ALTER TABLE `users` AUTO_INCREMENT = 1000;

SET FOREIGN_KEY_CHECKS = 1;

-- Quick checks:
-- SELECT COUNT(*) AS user_count FROM users;
-- SELECT COUNT(*) AS memory_rows FROM SPRING_AI_CHAT_MEMORY;
