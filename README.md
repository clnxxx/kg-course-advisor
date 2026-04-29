# KG Course Advisor 🎓

> 基于 Neo4j 知识图谱 + Spring AI 多智能体协作的智能选课推荐系统

[![Java](https://img.shields.io/badge/Java-17+-blue.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Vue 3](https://img.shields.io/badge/Vue-3.4-42b883.svg)](https://vuejs.org/)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

## 📖 项目简介

KG Course Advisor 是一个面向高校学生的智能选课推荐平台。系统通过构建课程知识图谱，结合大语言模型（LLM）的多智能体协作架构，为学生提供课程查询、个性化推荐、上下文选课和一键选退课等服务。

### ✨ 核心特性

- **🤖 多智能体协作**：主控智能体（Master Agent）统一进行意图识别、任务路由、结果整理，编排 QA、推荐、操作三个子智能体
- **🕸️ 知识图谱驱动**：基于 Neo4j 存储课程、知识点、教师、学生及其关系，子智能体通过参数化 Cypher 查询获取数据
- **🎯 真实课程推荐**：推荐结果来自 Neo4j 中的真实课程数据，不直接让 LLM 自由生成课程名单
- **💬 上下文选课**：支持"选第一门""选第二门课""这门课"等多轮上下文指代
- **🔒 安全操作链路**：选课、退课、课程解析等高风险环节优先走确定性服务，降低模型误操作风险
- **⚡ 流式对话**：前端通过 SSE（Server-Sent Events）实时展示 AI 回复与阶段状态，支持 Markdown 渲染
- **📊 知识图谱可视化**：基于 vis-network 的交互式图谱浏览，支持节点点击查看详情
- **⚙️ 课程管理后台**：展示课程统计数据和课程列表，便于管理

## 🛠️ 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.2.5 + Java 17 |
| AI 编排 | Spring AI 1.1.0（OpenAI 兼容接口） |
| 图数据库 | Neo4j 5.x（Bolt 协议，原生 Cypher 查询） |
| 关系数据库 | MySQL 8.x（JPA，用户认证） |
| 安全认证 | Spring Security + JWT + BCrypt |
| 前端框架 | Vue 3 + TypeScript + Composition API |
| UI 组件库 | Element Plus |
| 状态管理 | Pinia |
| 图谱可视化 | vis-network / vis-data |
| 构建工具 | Maven（后端）、Vite（前端） |

## 🏗️ 系统架构

```
用户 → Vue 3 前端（SSE 流式对话）
         ↓ HTTP/SSE
     AgentChatController（JWT 鉴权 + 会话隔离）
         ↓
     MasterAgentService（主控智能体：意图识别、路由、总结、上下文缓存）
         ↓
     SubAgentTools（三个子智能体）
     ┌──────────┼──────────┐
  QA智能体  推荐智能体  操作智能体
     └──────────┼──────────┘
         ↓
     CypherTools / 确定性业务服务
         ├── CourseRecommendationService
         ├── CourseActionService
         └── CypherExecutionService → Neo4j
```

### 多智能体实现

- **主控智能体 `MasterAgentService`**
  - 优先用 LLM 做意图分类，失败时退回规则路由
  - 根据消息分发到 QA、推荐、操作三类子智能体
  - 统一把结构化 JSON 总结为用户可读文本
  - 维护最近一轮推荐课程缓存，支持"选第一门"这类多轮操作

- **QA 子智能体**
  - 用于课程、教师、知识点、前置关系等事实问答
  - 通过 Tool Calling 调用图谱查询工具

- **推荐子智能体**
  - 采用"规则 + LLM 辅助"的关键词提取方式
  - LLM 只负责补充候选关键词，不直接生成课程
  - 最终只从 Neo4j 真实课程中返回推荐结果

- **操作子智能体**
  - 处理选课、退课、查询已选课程
  - 高风险操作优先走确定性链路
  - 支持课程编号匹配、课程名近似匹配、时间冲突检测

### 知识图谱 Schema

```
节点: Course(课程), Concept(知识点), Teacher(教师), User/Student(用户)

关系:
  COURSE_CONCEPT:          课程 → 知识点
  PREREQUISITE_DEPENDENCY: 知识点 → 知识点（前置依赖）
  TEACHER_COURSE:          教师 → 课程
  USER_COURSE:             用户 → 课程（选课）
  FIELD_CONCEPT:           知识点 → 知识点（领域层级）
```

## 📁 项目结构

```
kg-course-advisor/
├── backend/                          # Spring Boot 后端
│   ├── pom.xml
│   └── src/main/java/com/clnx/kg_course_advisor/
│       ├── agent/                     # 智能体相关
│       │   ├── MasterAgentService.java    # 主控智能体
│       │   ├── SubAgentTools.java         # 子智能体工具
│       │   └── tools/CypherTools.java    # Cypher 查询工具
│       ├── config/                     # 配置类
│       ├── controller/                 # 控制器
│       ├── service/                    # 业务服务
│       └── entity/                     # 实体类
│
├── frontend/                         # Vue 3 前端
│   ├── package.json
│   └── src/
│       ├── api/                       # API 封装
│       ├── views/                     # 页面组件
│       ├── stores/                    # Pinia 状态管理
│       └── types/                     # TypeScript 类型定义
│
└── README.md
```

## 🚀 快速开始

### 环境要求

- Java 17+
- Maven 3.6+
- Node.js 20.19.0+ 或 22.12.0+
- MySQL 8.x
- Neo4j 5.x

### 1. 克隆项目

```bash
git clone https://github.com/yourusername/kg-course-advisor.git
cd kg-course-advisor
```

### 2. 数据库准备

**MySQL**：创建数据库

```sql
CREATE DATABASE kg_course_advisor DEFAULT CHARACTER SET utf8mb4;
```

**Neo4j**：启动 Neo4j 服务，导入课程知识图谱数据。

### 3. 配置环境变量

项目使用环境变量管理敏感配置，复制示例文件并修改：

**后端环境变量**（可添加到 `~/.bashrc`、`~/.zshrc` 或系统环境变量）：

```bash
# MySQL 配置
export MYSQL_URL=jdbc:mysql://localhost:3306/kg_course_advisor?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
export MYSQL_USERNAME=root
export MYSQL_PASSWORD=your_mysql_password

# Neo4j 配置
export NEO4J_URI=bolt://localhost:7687
export NEO4J_USERNAME=neo4j
export NEO4J_PASSWORD=your_neo4j_password

# JWT 配置
export JWT_SECRET=your-256-bit-secret-key-for-jwt-token-generation
export JWT_EXPIRATION=86400000

# Spring AI 配置（支持 OpenAI、LM Studio 等兼容接口）
export SPRING_AI_OPENAI_API_KEY=your_api_key_or_lm-studio
export SPRING_AI_OPENAI_BASE_URL=http://127.0.0.1:1234
export SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL=your_model_name

# 应用模式
export APP_AGENT_MODE=multi
```

**前端环境变量**：

复制 `frontend/.env.example` 到 `frontend/.env.local` 并修改：

```bash
cp frontend/.env.example frontend/.env.local
```

编辑 `frontend/.env.local`：

```env
VITE_API_BASE_URL=http://localhost:8080/api
```

### 4. 启动后端

```bash
cd backend
mvn spring-boot:run
```

后端启动在 `http://localhost:8080`。

### 5. 启动前端

```bash
cd frontend
npm install
npm run dev
```

前端启动在 `http://localhost:5173`。

### 6. 访问系统

打开浏览器访问 `http://localhost:5173`，注册账号后即可使用。

## 📝 功能页面

| 页面 | 路径 | 说明 |
|------|------|------|
| 智能对话 | `/chat` | 与 AI 助手对话，查询课程、获取推荐、执行选退课 |
| 知识图谱 | `/graph` | 可视化浏览课程知识图谱，点击节点查看详情 |
| 课程管理 | `/admin` | 查看课程统计数据和课程列表 |

## 🔌 API 接口

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| POST | `/api/auth/login` | 用户登录 | 无 |
| POST | `/api/auth/register` | 用户注册 | 无 |
| GET | `/api/auth/me` | 获取当前用户信息 | JWT |
| POST | `/api/chat` | 同步对话 | JWT |
| POST | `/api/chat/stream` | 流式对话（SSE） | JWT |
| GET | `/api/chat/history` | 获取当前会话历史 | JWT |
| DELETE | `/api/chat/history` | 删除当前会话历史 | JWT |
| GET | `/api/admin/stats` | 课程统计数据 | JWT |
| GET | `/api/admin/courses` | 课程列表 | JWT |
| GET | `/api/graph/data` | 知识图谱数据 | JWT |

## 🤖 AI 模型配置

### 使用 LM Studio（本地）

1. 在 LM Studio 中启动本地服务，确认监听地址是 `http://127.0.0.1:1234`
2. 加载支持 Tool Calling 的模型
3. 设置环境变量：
   ```bash
   export SPRING_AI_OPENAI_API_KEY=lm-studio
   export SPRING_AI_OPENAI_BASE_URL=http://127.0.0.1:1234
   export SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL=your_model_name
   ```

### 使用云端 API（如 SiliconFlow、OpenAI 等）

```bash
export SPRING_AI_OPENAI_API_KEY=your_api_key
export SPRING_AI_OPENAI_BASE_URL=https://api.siliconflow.cn
export SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL=Qwen/Qwen2.5-7B-Instruct
```

## 📦 构建部署

```bash
# 后端打包
cd backend
mvn clean package -DskipTests

# 前端打包
cd frontend
npm run build
```

前端构建产物在 `frontend/dist/` 目录，可部署到 Nginx 等静态服务器，通过反向代理转发 `/api` 请求到后端。

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 提交 Pull Request

## 📄 许可证

本项目采用 MIT 许可证 - 详见 [LICENSE](LICENSE) 文件。

## 🙏 致谢

- [Spring AI](https://spring.io/projects/spring-ai)
- [Neo4j](https://neo4j.com/)
- [Vue 3](https://vuejs.org/)
- [Element Plus](https://element-plus.org/)
- [vis-network](https://visjs.github.io/vis-network/)

## 📧 联系方式

如有问题或建议，欢迎提交 Issue 或联系项目维护者。

---

⭐ 如果这个项目对你有帮助，欢迎给它一个 Star！
