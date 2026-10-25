# TravelMind 智旅平台

> 基于 **Spring Boot + Vue 3 + 大语言模型** 的全栈移动端旅游规划平台。支持智能行程推荐与 AI 旅游问答，基于 **SSE 流式响应** 实现打字机效果，提升用户交互体验。

---

## 📋 项目简介

TravelMind 智旅平台是一款 AI 驱动的移动端旅游规划 H5 应用。用户输入目的地、预算和旅行天数，系统自动生成结构化的旅游行程安排；同时提供 AI 旅游问答功能，用户可以像聊天一样咨询旅游相关问题。

**项目亮点：**

- 🤖 **AI 智能规划**：根据城市 / 预算 / 天数自动生成结构化旅游行程 JSON
- 💬 **流式对话**：基于 SSE 实现 AI 回答逐字输出，打字机效果
- 📱 **移动端适配**：Vue 3 + Vant UI，专为手机端优化
- 🔧 **多模型支持**：兼容 DeepSeek / 硅基流动等 OpenAI 格式 API
- 🛡 **健壮性设计**：Prompt 引导 + 三层正则兜底，保证 JSON 解析成功率

---

## 🛠 技术栈

### 后端

| 分类 | 技术 | 版本 | 说明 |
|------|------|------|------|
| 开发语言 | Java | 17 | LTS 版本 |
| 后端框架 | Spring Boot | 4.0.6 | spring-boot-starter-webmvc |
| 参数校验 | Spring Validation | - | `@Valid` + 注解校验 |
| HTTP 客户端 | OkHttp | 4.12.0 | 调用大模型 API |
| JSON 处理 | Jackson | - | 序列化 / 反序列化 |
| 流式响应 | SSE (Server-Sent Events) | - | SseEmitter 实现 |
| 代码简化 | Lombok | - | 注解生成样板代码 |
| 构建工具 | Maven | - | 依赖管理与构建 |
| 大模型 API | DeepSeek / 硅基流动 | - | OpenAI 兼容格式 |

### 前端

| 分类 | 技术 | 版本 | 说明 |
|------|------|------|------|
| 前端框架 | Vue | 3.5.32 | Composition API |
| UI 组件库 | Vant | 4.9.24 | 移动端 UI 库 |
| 路由 | Vue Router | 4.3.0 | 单页路由管理 |
| HTTP 请求 | Axios + Fetch API | - | 普通请求用 Axios，流式用 Fetch |
| 构建工具 | Vite | 8.0.10 | 极速开发构建 |

---

## 🏗 项目架构

### 整体架构

```
┌─────────────┐  HTTP/SSE   ┌─────────────┐  HTTP   ┌──────────────┐
│ 前端 H5     │ ───────────▶│ Spring Boot │ ───────▶│  大模型 API  │
│ Vue3 + Vant │ ◀───────────│  后端服务   │ ◀───────│ DeepSeek/硅基 │
└─────────────┘             └─────────────┘         └──────────────┘
     │                            │
     ├── 普通 REST API            ├── Controller：TravelController
     │    POST /recommend         ├── Service：TravelService
     │                            ├── Utils：LLMutils（流式+非流式）
     └── SSE 流式接口             ├── DTO / VO：请求响应封装
          POST /chat              └── Config：CORS / 全局异常处理
```

### 后端分层结构

```
travel-java-server/
├── controller/
│   └── TravelController.java       # 接口层（推荐 + 对话 + 健康检查）
├── service/
│   └── TravelService.java          # 业务层（Prompt 构建 + 结果解析）
├── utils/
│   └── LLMutils.java               # 工具层（LLM API 调用封装）
├── dto/
│   ├── TravelRequestDTO.java       # 行程推荐请求参数
│   └── ChatRequestDTO.java         # AI 对话请求参数
├── vo/
│   ├── Result.java                 # 统一响应体
│   ├── TravelRecommendVO.java      # 行程推荐响应
│   ├── StreamChunkVO.java          # 流式分片响应
│   ├── StreamDoneVO.java           # 流式结束响应
│   └── StreamErrorVO.java          # 流式错误响应
├── common/
│   ├── CorsConfig.java             # CORS 跨域配置
│   └── GlobalExceptionHandler.java # 全局异常处理
└── TravelJavaServerApplication.java # 启动类
```

### 前端页面结构

```
travel-h5-java/src/
├── views/
│   ├── Home.vue          # 首页（城市选择 / 预算 / 天数 / 热门目的地）
│   ├── Detail.vue        # 行程详情页（每日行程 / 预算明细 / 温馨提示）
│   ├── Chat.vue          # AI 对话页（流式聊天 / 快捷问题）
│   └── Profile.vue       # 个人中心（静态展示）
├── components/
│   ├── ChatBubble.vue    # 聊天气泡组件
│   ├── BudgetTable.vue   # 预算表格组件
│   └── SpotItem.vue      # 景点条目组件
├── router/
│   └── index.js          # 路由配置
├── utils/
│   └── request.js        # Axios 封装
├── App.vue
└── main.js
```

---

## 💡 核心功能与技术亮点

### 1. 智能旅游规划（非流式）

**接口：** `POST /api/travel/recommend`

用户输入城市、预算、天数，后端调用大模型生成结构化旅游行程 JSON，前端解析后展示。

**完整流程：**

1. 用户在首页填写表单 → 点击「开始规划」→ 跳转详情页
2. 详情页 `onMounted` 调用 `/recommend` 接口
3. 后端 `Spring Validation` 校验参数（城市非空、天数 1-30、预算 ≥ 100）
4. Service 层构建旅游规划 Prompt（角色设定 + 输入信息 + JSON 格式约束）
5. `LLMutils.chat()` 调用大模型 API（同步等待完整响应）
6. `extractJson()` 从大模型响应中提取 JSON（三层兜底策略）
7. Jackson 反序列化为 `TravelRecommendVO` 对象 → 包装 `Result` 返回
8. 前端解析数据，用折叠面板展示每日行程、预算明细等

**JSON 提取三层兜底策略：**

| 层级 | 策略 | 匹配规则 |
|------|------|----------|
| 第一层 | Markdown JSON 代码块 | 匹配 ` ```json ... ``` ` |
| 第二层 | 通用代码块 | 匹配 ` ``` ... ``` `（无 json 标记） |
| 第三层 | 直接截取 | 找第一个 `{` 和最后一个 `}` 截取 |

> 解决大模型返回 Markdown 代码块与 JSON 混杂导致的解析异常问题。

---

### 2. AI 旅游咨询（SSE 流式响应）

**接口：** `POST /api/travel/chat`，`Content-Type: text/event-stream`

用户发送问题，AI 实时流式返回回答，前端实现打字机效果。

**完整流程：**

1. 前端使用 `Fetch API` 发起 POST 请求（Axios 不支持流式读取）
2. 后端 Controller 返回 `SseEmitter`（`produces = "text/event-stream"`）
3. Service 层启动**独立线程**，调用 `LLMutils.chatStream()` 流式方法
   > 为什么开新线程？SseEmitter 发送是异步的，如果在请求线程中同步执行流式调用，会阻塞 Tomcat 工作线程。开新线程后请求线程立即返回 `SseEmitter`，后续数据推送在独立线程中进行。
4. OkHttp 发起流式请求（`stream: true`），逐行读取 SSE 数据流
5. 每收到一个 delta 片段 → 通过 Consumer 回调 → `emitter.send()` 推送给前端
6. 流结束 → 发送 done 信号 → `emitter.complete()`
7. 前端 `reader.read()` 循环读取流 → 解析 `data:` 行 → 追加内容 → 打字机效果

**SSE vs WebSocket 选型：**

| 特性 | SSE | WebSocket |
|------|-----|-----------|
| 协议 | HTTP | 独立协议（ws://） |
| 通信方向 | 服务端 → 客户端（单向） | 全双工（双向） |
| 实现复杂度 | 低，浏览器原生支持 | 高，需协议升级握手 |
| 适用场景 | AI 对话、消息推送、实时日志 | 聊天室、在线协作、游戏 |

> 选型理由：AI 对话本质是服务端流式输出，SSE 足够用；大模型 API 本身就是 SSE 格式输出，后端直接转发更自然。

---

### 3. LLM 调用工具类（LLMutils）

核心工具类，封装大模型 API 的两种调用方式：

| 方法 | 模式 | 适用场景 | 实现方式 |
|------|------|----------|----------|
| `chat()` | 非流式 | 行程规划（需完整 JSON 解析） | OkHttp 同步执行，读取完整 response body |
| `chatStream()` | 流式 | AI 对话（打字机效果） | 逐行读取 SSE，通过 `Consumer<String>` 回调 |

**核心设计：**

- **OkHttp 超时配置**：连接 60s / 读取 120s / 写入 60s，适配大模型生成慢的特点
- **请求体构建**：字符串拼接 JSON（含 system / user 双角色 message），配合 `escapeJson()` 转义特殊字符
- **模型可切换**：构造函数传入 baseUrl / apiKey / model，支持切换 DeepSeek / 硅基流动等兼容 OpenAI 格式的服务商
- **SseEmitter 超时保护**：180s 超时，避免连接长时间挂起

---

### 4. 参数校验与全局异常处理

**Spring Validation 参数校验：**

`TravelRequestDTO` 校验规则：
- `@NotNull` 城市 / 天数 / 预算不能为空
- `@Min(1)` `@Max(30)` 天数范围 1-30 天
- `@DecimalMin("100")` 预算不能低于 100 元

校验不通过抛出 `MethodArgumentNotValidException`，由全局异常处理器统一捕获。

**全局异常处理：**

- `@RestControllerAdvice` + `@ExceptionHandler` 统一处理异常
- 参数校验异常：Stream 拼接所有字段错误消息，返回 400 状态码
- 统一返回 `Result.fail(code, message)` 格式，前端统一处理

---

### 5. CORS 跨域配置

前后端分离项目必须配置跨域，SSE 请求也需要跨域支持。

- `CorsFilter` 方式实现，基于 `UrlBasedCorsConfigurationSource`
- 允许所有来源 / 请求头 / HTTP 方法 / 暴露响应头
- `setAllowCredentials(true)` + `addAllowedOriginPattern("*")` 配合使用
- 生产环境建议限制为具体域名

---

## 🚀 快速启动

### 环境要求

- JDK 17+
- Maven 3.6+
- Node.js 16+
- 大模型 API Key（DeepSeek 或 硅基流动）

### 后端启动

1. **克隆项目**

```bash
git clone <仓库地址>
cd travel-java-server
```

2. **修改配置**

编辑 `src/main/resources/application.yml`：

```yaml
server:
  port: 3200

llm:
  base-url: https://api.siliconflow.cn/v1   # 或 DeepSeek: https://api.deepseek.com/v1
  model: Pro/zai-org/GLM-5.1                # 模型名称
  api-key: your_api_key_here                # 替换为你的 API Key
```

3. **启动服务**

```bash
mvn spring-boot:run
```

或运行启动类 `TravelJavaServerApplication.java`。

4. **验证**

访问 `http://localhost:3200/api/travel/hello`，返回 `hello world` 即启动成功。

### 前端启动

1. **进入前端目录**

```bash
cd travel-h5-java
```

2. **安装依赖**

```bash
npm install
```

3. **修改接口地址**

编辑 `src/utils/request.js`，将 baseURL 指向后端地址：

```js
const baseURL = 'http://localhost:3200/api/travel'
```

4. **启动开发服务**

```bash
npm run dev
```

5. **访问**

浏览器打开 Vite 提示的本地地址（如 `http://localhost:5173`），建议用手机模式 / 移动端模拟器浏览。

---

## 🐳 Docker 部署

无需本地安装 JDK / Maven / Node.js，只需 Docker 环境即可一键构建启动前后端。

### 部署架构

```
浏览器 ──▶ Nginx 容器 (80 端口)
            ├── /            静态页面（Vue3 构建产物）
            └── /api/**  ──▶ Spring Boot 容器 (3200，仅容器网络内可见) ──▶ 大模型 API
```

- 前后端同域部署，通过 Nginx 反向代理转发 `/api` 请求，天然规避 CORS 问题
- SSE 流式响应已在 Nginx 中关闭缓冲（`proxy_buffering off`），打字机效果正常
- 后端端口不映射到宿主机，仅容器网络内可达

### 部署文件清单

```
travelMind/
├── docker-compose.yml              # 编排文件：定义前后端两个容器
├── .env                            # 存放 LLM_API_KEY（不提交 git）
├── travel-h5/
│   ├── Dockerfile                  # Node 22 构建 → Nginx 托管
│   ├── nginx.conf                  # 静态托管 + /api 反代（含 SSE 关缓冲）
│   └── .dockerignore
└── travel-java/
    ├── Dockerfile                  # Maven + JDK17 多阶段构建 → 轻量 JRE 运行
    ├── settings-aliyun.xml         # Maven 阿里云镜像，加速依赖下载
    └── .dockerignore
```

### 环境准备

**Linux 服务器：**

```bash
curl -fsSL https://get.docker.com | bash -s docker --mirror Aliyun
systemctl enable --now docker
```

**Windows 本机（Docker Desktop）：**

1. 管理员 PowerShell 执行 `wsl --install`，重启电脑
2. `winget install Docker.DockerDesktop`
3. 启动 Docker Desktop，右下角鲸鱼图标变绿即就绪
4. 拉镜像超时时，在 Settings → Docker Engine 中配置镜像加速：

```json
"registry-mirrors": ["https://docker.m.daocloud.io"]
```

### 配置密钥

项目根目录创建 `.env` 文件（docker compose 自动读取）：

```bash
LLM_API_KEY=你的硅基流动API密钥
```

后端 `application.yml` 通过 `${LLM_API_KEY}` 读取，密钥不出现在代码中。

### 构建并启动

```bash
cd travelMind
docker compose up -d --build
```

首次构建需 5~10 分钟（下载基础镜像 + Maven/npm 依赖），后续构建利用层缓存约 1~2 分钟。

### 验证

```bash
docker compose ps                             # 两个容器状态应为 running
curl http://localhost/api/travel/hello        # 返回 hello world 即后端正常
```

浏览器打开 `http://localhost`，测试行程规划与 AI 对话功能。

### 常用运维命令

```bash
docker compose ps                        # 查看容器状态
docker compose logs -f travel-backend    # 查看后端日志
docker compose up -d --build             # 修改代码后重新构建
docker compose stop                      # 停止容器（保留）
docker compose start                     # 再次启动
docker compose down                      # 停止并删除容器
```

> 服务器重启后打开 Docker / Docker Desktop，容器会自动恢复运行（已配置 `restart: unless-stopped`）。

### 常见问题

| 问题 | 原因 | 解决方法 |
|------|------|----------|
| 拉取镜像超时 | 直连 Docker Hub 网络慢 | 配置 `registry-mirrors` 镜像加速 |
| `docker-credential-desktop not found` | 终端 PATH 缺少 Docker bin 目录 | 将 `C:\Program Files\Docker\Docker\resources\bin` 加入 PATH 后重试 |
| AI 功能报 401 | API 密钥失效或额度耗尽 | 到硅基流动更换密钥，更新 `.env` 后执行 `docker compose up -d` 重建容器 |
| 修改 `.env` 后不生效 | 环境变量仅在容器创建时注入 | 执行 `docker compose up -d`，自动重建后端容器 |

### 部署到云服务器

与本地流程一致：服务器安装 Docker → 上传项目目录（`scp -r travelMind root@服务器IP:/opt/travelMind`）→ 创建 `.env` → `docker compose up -d --build`。最后在云服务器安全组放行 80 端口即可，3200 端口保持不对公网开放。



