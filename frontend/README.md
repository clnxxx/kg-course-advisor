# 知识图谱课程推荐系统 - 前端

基于 Vue 3 + TypeScript + Element Plus 的智能选课系统前端。

## 技术栈

- **Vue 3** - 渐进式 JavaScript 框架
- **TypeScript** - 类型安全的 JavaScript
- **Element Plus** - 基于 Vue 3 的组件库
- **Pinia** - Vue 的状态管理库
- **Vue Router** - Vue 的官方路由管理器
- **Axios** - 基于 Promise 的 HTTP 客户端
- **Vite** - 下一代前端构建工具
- **SCSS** - CSS 预处理器

## 项目结构

```
frontend/
├── src/
│   ├── api/                 # API 服务层
│   │   └── index.ts        # 封装 axios 和所有 API 调用
│   ├── components/          # 公共组件
│   ├── stores/              # Pinia 状态管理
│   │   ├── auth.ts         # 认证状态管理
│   │   └── chat.ts         # 聊天状态管理
│   ├── types/               # TypeScript 类型定义
│   │   └── index.ts        # 全局类型定义
│   ├── views/               # 页面视图
│   │   ├── LoginView.vue   # 登录页面
│   │   ├── RegisterView.vue # 注册页面
│   │   └── ChatView.vue    # 聊天主页面
│   ├── router/              # 路由配置
│   │   └── index.ts        # 路由定义和守卫
│   ├── App.vue              # 根组件
│   └── main.ts              # 应用入口
├── index.html               # HTML 模板
├── vite.config.ts           # Vite 配置
├── tsconfig.json            # TypeScript 配置
└── package.json             # 项目依赖
```

## 功能特性

### 用户认证
- ✅ 用户登录（JWT Token）
- ✅ 用户注册
- ✅ 登录状态持久化
- ✅ 路由守卫（未登录跳转）

### 智能对话
- ✅ 实时对话界面
- ✅ 流式消息响应（SSE）
- ✅ 对话历史记录
- ✅ 示例问题快速发送
- ✅ 新对话功能

### UI 设计
- ✅ 响应式布局
- ✅ Element Plus 组件库
- ✅ 美观的渐变背景
- ✅ 流畅的过渡动画
- ✅ 自定义滚动条

## 开发命令

```bash
# 安装依赖
npm install

# 启动开发服务器
npm run dev

# 类型检查
npm run type-check

# 构建生产版本
npm run build

# 预览生产构建
npm run preview

# 代码格式化
npm run format

# 代码检查
npm run lint
```

## API 接口

### 认证接口
- `POST /api/auth/login` - 用户登录
- `POST /api/auth/register` - 用户注册
- `GET /api/auth/me` - 获取当前用户信息

### 聊天接口
- `POST /api/chat` - 发送消息（同步）
- `POST /api/chat/stream` - 发送消息（流式）

## 配置说明

### 后端 API 地址
在 `src/api/index.ts` 中配置：

```typescript
const apiClient: AxiosInstance = axios.create({
  baseURL: 'http://localhost:8080/api',  // 后端地址
  timeout: 30000,
});
```

### 路由配置
支持以下路由：
- `/login` - 登录页
- `/register` - 注册页
- `/chat` - 聊天页（需要登录）
- `/` - 重定向到 `/chat`

## 使用说明

1. 确保后端服务已启动（默认端口 8080）
2. 运行 `npm run dev` 启动前端开发服务器
3. 访问 `http://localhost:5173`（或 Vite 显示的端口）
4. 注册账户或登录已有账户
5. 开始与 AI 助手对话

## 示例对话

- "推荐一门编程入门课程"
- "Python 课程有哪些？"
- "我选了什么课？"
- "数据结构课程的老师是谁？"
- "帮我找人工智能相关的课程"

## 注意事项

1. 确保后端 CORS 配置允许前端域名访问
2. 流式响应需要浏览器支持 ReadableStream API
3. 建议使用现代浏览器（Chrome、Firefox、Edge）

## 浏览器兼容性

- Chrome 80+
- Firefox 75+
- Safari 13.1+
- Edge 80+

## 贡献指南

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 创建 Pull Request

## 许可证

MIT License

## 原 Vue 项目模板说明

[VS Code](https://code.visualstudio.com/) + [Vue (Official)](https://marketplace.visualstudio.com/items?itemName=Vue.volar) (and disable Vetur).

### Type Support for `.vue` Imports in TS

TypeScript cannot handle type information for `.vue` imports by default, so we replace the `tsc` CLI with `vue-tsc` for type checking. In editors, we need [Volar](https://marketplace.visualstudio.com/items?itemName=Vue.volar) to make the TypeScript language service aware of `.vue` types.

### Customize configuration

See [Vite Configuration Reference](https://vite.dev/config/).

### Project Setup

```sh
npm install
```

### Compile and Hot-Reload for Development

```sh
npm run dev
```

### Type-Check, Compile and Minify for Production

```sh
npm run build
```

### Lint with [ESLint](https://eslint.org/)

```sh
npm run lint
```
