# 给一个开源 Java 项目接入 AI Agent —— 完整实战与内容化方案

> 适用对象：有 3 年以上 Java/Spring 经验、想亲手把 AI Agent 落到真实业务系统、并计划把过程开源 + 做成视频系列的开发者。
> 目标不是「跑通一个 demo」，而是产出一套**可被别人复用到自己项目上的接入方案**。

> **文档状态（2026-10-25 更新）**：本文已按 **Spring Boot 4.1.2 + JDK 21 + Spring AI 2.0.2** 的**实际迁移结果**重写版本策略、模型配置与核心代码骨架。
> 三份文档的分工固定如下，冲突时按此裁决：
> - **本文** = 唯一技术基准（选型、架构、阶段目标、代码骨架、内容化）
> - `docs/AGENT_INTEGRATION_SOP.md` = 执行规程（阶段顺序、门禁、模板、偏差登记）
> - `docs/BOOT4_MIGRATION_NOTES.md` = 迁移手册（版本矩阵、包路径、逐坑修法）
>
> 历史遗留的 `AI_GUIDE_AGENT_DESIGN.md` 已废弃，不要参考。

---

## 0. 一页纸结论

| 决策项 | 结论 |
|---|---|
| 基础项目 | 主选 **zeroai-mall**（原 Boot 3.1 / JDK 17，本项目**已升级到 Boot 4.1.2 / JDK 21**；自带 docker-compose 全栈）；备选 **newbee-mall** 的 `spring-boot-3.x` 分支（最轻）、**mall**（业务最经典，Apache-2.0 但需先升 Boot） |
| AI 框架 | **Spring AI 2.0.2**（2.0 线最新补丁，要求 Boot 4.1.2 + JDK 21），LangChain4j 只做对照演示 |
| 模型 | DeepSeek / Qwen 走 OpenAI 兼容协议 + Ollama 本地兜底，一套配置切换 |
| 向量库 | **PGVector**（一个容器，SQL 可调试）；不想加容器就用 Redis Stack |
| 记忆 | Spring AI JDBC ChatMemory，复用商城已有 MySQL |
| Agent 形态 | Tool Calling 为主 + RAG 答疑 + 多步规划 + Human-in-the-loop 兜底 |
| 前端 | **非必需**。本仓库是纯后端 API 项目，用 `curl` 即可完成全部验收；H5 与后台是两个独立前端仓库，只在要「用浏览器点」时才装（见 §2.5） |
| 差异化 | 你不是写教程，你是开源一个 **`@EnableAiAgent` 可复用 starter** + 评测集 + MCP Server |
| 周期 | 8 周开发（先一次性跑通 P0–P4，再回头按集录制） |
| 交付 | 1 个开源仓库 + 14 集视频 + 1 套架构决策记录（ADR） |

**一句话风险提示**：Spring AI 2.0.x 要求 Spring Boot 4.x + Java 21，**升 2.0 等于一次整栈迁移**（Framework 7 / Jackson 3 / Security 7 / 若干 starter 改名）。本文原方案主张「用 1.1.x 做主线、最后一集讲升级」，但本项目实际执行后**改为直接上 2.0.2**：迁移路径已经跑通并留档（`docs/BOOT4_MIGRATION_NOTES.md`），风险从「未知」坍缩为「一次性 1–2 天成本」，比先写 1.x 再整体改一遍更省。若你的项目本体连 Boot 3.5 都还没到，可先停在 1.1.x 把 P1–P4 打通——但**别在陌生的框架里同时应付三类迁移问题**，顺序永远先升 Boot 再动 AI。

---

## 1. 先把「产出物」定义清楚

很多人做这类项目会烂尾，根本原因是一开始就没定义终点。你的终点应该是这四个：

1. **可运行的项目**：`git clone && docker compose up -d && mvn spring-boot:run` 十分钟内出效果。
2. **可复用的模块**：`ai-agent-module`，别人引一个依赖 + 加一个注解就能给**他自己的**项目接上。
3. **可验证的效果**：一套评测集（golden set）+ 跑批脚本，能说出「工具选择准确率 92%」而不是「感觉挺好」。
4. **可消费的内容**：14 集视频 + 每集配套讲义 + README 里的路线图。

> 第 2 点是你和其他教程最大的区别。大多数「Java 接大模型」的内容止步于 Controller 里调一次 API。你做到「抽成 starter」，价值直接上一个量级。

---

## 2. 基础项目选型

### 2.1 硬性门槛（不满足直接放弃）

| # | 门槛 | 为什么 |
|---|---|---|
| 1 | Spring Boot **≥ 3.1**（上 Spring AI 2.0.x 需 **4.1.x** + JDK 21） | Spring AI 1.x 最低要 Boot 3.4；2.0.x 官方对着 Boot 4.1.2 编译。Boot 2.x 根本接不上，只能退回 LangChain4j 手写 Bean，教学成本陡增 |
| 2 | 外部依赖 **≤ 3 个**中间件 | MySQL + Redis 最佳。再多一个 ES/RabbitMQ，观众复现失败率暴涨 |
| 3 | 有明确的 **LICENSE** | 决定你的仓库能用什么协议（见第 9 节） |
| 4 | 核心业务是 **Service 接口 + 实现** | Tool 封装最省事，直接包一层 |
| 5 | 有登录态 / 权限体系 | 否则没法讲「工具级鉴权」这个最重要的安全点 |
| 6 | 近 12 个月有提交 | 别选坟 |

### 2.2 候选对比

| 项目 | Boot | 依赖 | 协议 | AI 场景自然度 | 评价 |
|---|---|---|---|---|---|
| **zeroai-mall** | 3.1 / JDK17**（已升 4.1.2 / JDK21）** | MySQL·Redis·RabbitMQ·ES | 需核对仓库 LICENSE | 高（电商全链路） | **主选**。有 docker-compose，前后端分离，运营端 + H5 都在 |
| **newbee-mall**（`spring-boot-3.x` 分支） | 3.1 | 仅 MySQL | **GPL-3.0** | 高 | 备选。启动最快、代码最干净，但 Thymeleaf 渲染、GPL 会传染 |
| **mall**（macrozheng） | 2.7 | MySQL·Redis·ES·MongoDB·MinIO·RabbitMQ | Apache-2.0 | 很高 | 业务最经典、受众最广，但必须先做一次 Boot 3 升级（这本身可以单独做一集） |
| **苍穹外卖 sky-take-out** | 2.7 | MySQL·Redis | 课程代码，无明确开源授权 | 极高（点餐，观众秒懂） | **不建议**：协议不清 + Boot 2.7，视频发出去容易有争议 |
| **RuoYi-Vue-Pro / JeecgBoot** | 3.x | 重 | Apache-2.0 | 中 | **不建议**：官方已内置 AI 模块，你「接入」的意义被稀释了 |

### 2.3 选型建议

如果你想要**最快出内容、最不容易踩环境坑**：选 `newbee-mall` 的 Boot 3 分支，接受 GPL-3.0（你的仓库也标 GPL-3.0，教学开源完全没问题，只是别人不能拿去闭源商用）。

如果你想要**项目更像真实生产、更容易讲出彩**：选 `zeroai-mall`，先花半天把 `docker compose` 跑通，再确认 LICENSE。

如果你想要**最大受众共鸣**：选 `mall`，但把「升级到 Boot 4.1 + JDK 21」做成 EP02，踩坑记录本身就是爆款内容。

> 本项目最终按第 2 条路线执行：选定 `zeroai-mall`，并在接入 AI 之前先完成 **JDK 21 → Boot 4.1.2 → Spring AI 2.0.2** 的整栈迁移（17 模块编译通过、213 个测试全绿、6 条冒烟全过）。迁移过程见「§12 迁移实录」与 `docs/BOOT4_MIGRATION_NOTES.md`。

> ⚠️ 无论选哪个，**不要把你 clone 的第三方项目整包 push 到自己的仓库**。用 `git submodule` 引用，或者只开源你的 `ai-agent-module` + `docs` + `deploy`，在 README 里写清楚 fork 来源和修改点。这既合规，也让仓库更干净。

### 2.4 30 分钟自检清单

```bash
git clone <base-project> && cd <base-project>
docker compose up -d          # 或按 README 起 MySQL/Redis
mvn -q clean package -DskipTests
mvn spring-boot:run           # 能登录、能跑通一条主流程？
grep -r "spring-boot-starter-parent" -A2 pom.xml   # 确认 Boot 版本
cat LICENSE | head -5                              # 确认协议
```

跑不通的，直接换下一个。**不要在环境上耗超过半天。**

### 2.5 启动要装前端吗？（高频问题，先给结论）

**结论：不需要。** 本仓库是**纯后端 API 项目**，装不装前端完全不影响后端启动和 AI 能力验收。

依据（可自行复核）：

1. 全部 Maven 模块都是 Java，两个入口 `zeroai-admin-api`（**81** 端口，后台）与 `zeroai-mobile-api`（**82** 端口，H5）只对外提供 JSON 接口。
2. `src/main/resources` 下**没有** `static/`、`templates/`，也没有任何前端构建产物 —— 后端不托管页面，访问根路径不会有 UI。
3. 两个前端是**独立仓库**：H5 商城 `zeroai-mobile`、管理后台 `zeroai-admin`（见 README「前端项目」一节）。它们不在本仓库内，本机也没有 clone，后端照样能起。

三层依赖关系：

| 层 | 是否必须 | 怎么起来 | 不装的后果 |
|---|---|---|---|
| 中间件 | **必须** | `docker compose -f docker-compose-es7-rabbitmq-redis-mysql.yml -f docker-compose.docker-desktop.yml up -d mysql elasticsearch rabbitmq`，Redis 用本机 memurai | 启动失败或核心链路残缺 |
| 后端 | **必须**（就是本体） | `zeroai-mobile-api`（82）+ `zeroai-message-consumer` | 只起 mobile-api 时**订单永远不落库**（下单只投消息，落库/超时取消都在 consumer） |
| 前端 | **可选** | clone 两个前端仓库 → `npm i && npm run dev`，接口指向 81 / 82 | 只影响「用浏览器点界面」，不影响任何接口级验收 |

不装前端时怎么验收：

```bash
# 业务链路：登录 → 首页 → 搜索 → 详情 → 加购 → 下单 → 查订单
# 本项目 P0 的 6 条冒烟就是这么做的，实测输出见 docs/P0_SMOKE_CHECKLIST.md
# 免短信：先写一条"已验证"的验证码（注意必须以 JSON 字符串形式写入）
python scripts/dev/set-login-code.py --mobile 13800000000 --code 1234
curl -s -X POST "http://localhost:82/login" -H "Content-Type: application/json" \
  -d '{"mobile":"13800000000","yzm":"1234"}'

# AI 流式：看逐字 message_delta 事件
curl -N "http://localhost:82/ai/chat?q=你好"
```

> P1 阶段原计划「前端一个最小聊天框」，本项目实际**降级为一个本地 html 文件**（浏览器 `EventSource` 十行代码），不引入任何前端工程。理由很实际：这个文件的唯一职责是让 `message_delta` 可见，用不上 Vue 的构建链。

**什么时候才必须装前端**：① 要给非技术观众演示成片效果 / 录 EP00；② 要验证跨域、`Authorization` 头、Cookie 这类只有浏览器才暴露的问题；③ 要改后台页面本身。此时再 clone，并注意前端默认把请求打到 81 / 82，改端口要同步改前端配置。

---

## 3. AI 技术栈选型

### 3.1 Spring AI vs LangChain4j

| 维度 | Spring AI **2.0.2** | LangChain4j 1.22.x |
|---|---|---|
| 运行基线 | **Boot 4.1.2 + JDK 21**（Framework 7 / Jackson 3 / Security 7） | JDK 17+，Boot 3.5 即可 |
| 编程模型 | Spring 原生，`ChatClient` 流式 API | `AiServices` 注解式，接口 + 动态代理 |
| 与 Spring Boot 集成 | 官方 starter，自动装配 | 有 starter，但更偏「库」 |
| Tool Calling | `@Tool` + `ToolCallingAdvisor`，Advisor 链可组合 | `@Tool`，成熟稳定 |
| RAG | `QuestionAnswerAdvisor` + 官方 VectorStore 全家桶 | `RetrievalAugmentor`，灵活度更高 |
| MCP | 官方 MCP Server/Client starter，**Streamable HTTP 为默认** | 也支持，Quarkus 侧更活跃 |
| 版本节奏 | 快（1.x → 2.0 → 2.1-M1），破坏性变更多；2.0 线从 `OpenAiApi` 换成官方 OpenAI SDK | 更保守，1.22 仍兼容旧栈 |
| 适合谁 | Spring 团队、要长期维护的企业项目 | 老项目、非 Spring 环境、要极致灵活 |

**结论**：主线用 Spring AI（观众是 Java/Spring 人，故事最好讲），版本直接落 **2.0.2**；EP01 用 30 分钟做一个 LangChain4j 对照 demo，讲清「什么场景选哪个」——这一集单独就有传播价值。

> 2.0 对「接入层」的实际影响比想象小：`ChatClient.builder(ChatModel)`、`prompt()`、`.stream().content()` 签名都没变，**受影响的只有「怎么造 ChatModel」和「怎么造底层 HTTP 客户端」**（`OpenAiApi` 删除 → 官方 `OpenAIOkHttpClient`）。这也是为什么 §6 的骨架能先写在 1.x 再平移到 2.0，控制器一行不用改。

### 3.2 版本策略（重要）

**已定基线（本项目实测跑通，直接用这一组）**：

| 组件 | 版本 | 为什么是它 |
|---|---|---|
| JDK | **21** | Boot 4 与 Spring AI 2.0 的共同下限；字节码 target 65 |
| Spring Boot | **4.1.2** | Spring AI 2.0.2 的编译基线，官方要求 Boot 4.x |
| Spring AI | **2.0.2** | 2.0 线最新**稳定补丁**；2.1.x 仅 M1/RC，不上生产 |
| Lombok | **1.18.46**（Boot 4.1.2 BOM 实为 1.18.48） | 二者都支持 JDK 21，本项目沿用 1.18.46。**关键是 ≥1.18.30**：1.18.26 在 JDK 21 下注解处理器直接崩（`JCImport.qualid` 改成了 `JCPath`） |
| openai-java | **4.80.0** | 与 `spring-ai-openai` 传递的 `openai-java-core` 对齐 |

**四条策略**：

1. **版本靠 BOM，不靠手写**。`spring-boot-dependencies` + `spring-ai-bom` 各 import 一次，子模块一律不写 `<version>`。
2. **插件版本必须由 `pluginManagement` 兜住**。import BOM 只约束依赖、**不约束插件**；`spring-boot-maven-plugin` 漏写版本时 Maven 会去仓库元数据取最新，实测解析到 **4.2.0-M2**（里程碑），打包行为不可复现。
3. **不要手工钉中间件客户端版本**。`lettuce` / `netty` / `jackson` 这类一律交给 BOM —— 本次迁移里两个「编译全绿、启动即 `NoClassDefFoundError`」全是手工钉版本钉出来的（Spring Data Redis 4 要 Lettuce 7，Lettuce 7 要 Netty 4.2）。
4. **每个 Phase 打一个 git tag**（`p0-base`、`p1-chat`…），视频里能精确回滚到某一集的状态。

```xml
<properties>
  <java.version>21</java.version>
  <spring-boot.version>4.1.2</spring-boot.version>
  <spring-ai.version>2.0.2</spring-ai.version>
  <lombok.version>1.18.46</lombok.version>
  <openai-java.version>4.80.0</openai-java.version>
</properties>

<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-dependencies</artifactId>
      <version>${spring-boot.version}</version>
      <type>pom</type><scope>import</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-bom</artifactId>
      <version>${spring-ai.version}</version>
      <type>pom</type><scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<build>
  <pluginManagement>
    <plugins>
      <!-- 不锁它就会解析到 4.2.0-M2 -->
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <version>${spring-boot.version}</version>
      </plugin>
    </plugins>
  </pluginManagement>
</build>
```

> **starter 坐标也改名了**，别照抄老教程：`spring-boot-starter-aop` → `spring-boot-starter-aspectj`；`mybatis-plus-spring-boot3-starter` → `mybatis-plus-spring-boot4-starter`；`sa-token-spring-boot3-starter` → `sa-token-spring-boot4-starter`；`druid-spring-boot-starter` → `druid-spring-boot-4-starter`。完整对照见 `docs/BOOT4_MIGRATION_NOTES.md` §1。

**升级顺序不能反**（这是本次迁移最值钱的一条经验）：

```
JDK 21 + Lombok  →  Boot 4 + 换 starter  →  包路径迁移  →  Jackson 3  →  启动冒烟  →  最后接 Spring AI 2.0
```

反面教材是把 Spring AI 放第一步：你会同时面对 Jackson 3、Security 7、包路径搬迁三类陌生问题，排查成本翻倍。

### 3.3 模型层

**不要用 `spring.ai.openai.*` 自动装配**——本项目实际落地的配置是自建的 `zeroai.ai.*`，原因见下方「为什么不用官方 starter」：

```yaml
# zeroai-mobile-api/src/main/resources/application.yml
zeroai:
  ai:
    enabled: ${AI_ENABLED:false}                       # 总开关：false 时一个 AI Bean 都不注册
    provider: openai
    base-url: ${LLM_BASE_URL:https://api.deepseek.com} # DeepSeek / 通义 / 硅基流动 的兼容端点
    api-key: ${LLM_API_KEY:}                           # 只从环境变量来，禁止写进配置文件
    model: ${LLM_MODEL:deepseek-chat}
    temperature: 0.3
    max-tokens: 1024
    system-prompt: prompts/guide-system.st             # 外置，改完重启即生效
    chat-path: /ai/chat
    sse-timeout-ms: 120000                             # 显式设置，容器默认 30s 会截断长回答
    max-steps: 6                                       # 防死循环烧钱
    max-tool-calls-per-turn: 6
    max-history-turns: 8
    tool-timeout-ms: 3000
    degrade-on-error: true
```

一套代码，`LLM_BASE_URL` 一改就换厂商。演示时用云端保证效果，断网演示切 Ollama 保底——这个「双通道」设计本身就是一集内容（本项目 P1 先只做 OpenAI 兼容主通道，Ollama 作为独立补丁追加，避免双 `ChatModel` Bean 注入歧义）。

**为什么不用官方 starter 自动装配**（实测踩过，必看）：

| 做法 | 结果 |
|---|---|
| `spring-ai-starter-model-openai` | 会自动装配并要求 `spring.ai.openai.api-key` **存在**，没配 key 时**应用直接启动失败**——与 `zeroai.ai.enabled=false` 无关。等于「没打算用 AI 的人也被迫配 key」 |
| 库级坐标 `spring-ai-openai` + 自建 `ChatModel` Bean（**本项目采用**） | 关闭时零影响；接入方可用 `@ConditionalOnMissingBean` 覆盖模型，不冲突 |

配套的 `zeroai.ai.enabled` 是**整条链路的总闸门**，落到四个位置：`AiAgentAutoConfiguration`、`AiChatController`（控制器会被宿主组件扫描直接注册，必须重复声明条件）、`ChatModel` / `ChatClient` 的 `@ConditionalOnMissingBean`、以及业务工具模块。**少一处就会在关闭状态下启动失败**。

---

## 4. 目标架构

见随附架构图（分层：交互层 → Agent 编排层 → 能力层 → 模型层，左侧为业务底座）。

关键设计原则：

1. **AI 只做「编排」，不做「业务」**。所有写操作最终落在原来的 `Service` 上，AI 是新的调用方，不是新的业务逻辑层。
2. **权限在工具内部判定，永不信任模型传参**。模型只能传「关键词」「状态」这类无害参数；`userId` 一律从 `SecurityContext` 取。
3. **危险操作两段式**。模型只允许调用 `previewOrder()` 这类预览工具，真正下单必须由前端确认按钮触发 `/api/order/confirm`，模型无权直呼。
4. **AI 能力独立成模块**。`ai-agent-module` 不反向依赖业务，业务通过 SPI 注册工具。
5. **一切可观测**。每次调用记录 traceId、模型、token、工具链、耗时、成本。

---

## 5. 分阶段实施路线（P0–P8）

> 每个阶段都有明确验收标准。**验收不通过不进下一阶段**——这是不烂尾的唯一办法。

### P0 · 地基（2–3 天）
- 跑通 base 项目，写 `docker-compose.yml`（MySQL / Redis / PGVector / Ollama）
- 加 `.env.example`，所有密钥走环境变量
- 建仓库骨架，写 README 第一版
- **先把你自己的栈升到基线**：JDK 21 → Boot 4.1.2 → 清编译错误 → 启动冒烟（顺序见 §3.2，本项目的完整踩坑见 §12）

**验收**：新机器从零到 **6 条接口冒烟全过** ≤ 10 分钟（登录 → 首页 → 搜索 → 详情 → 加购 → 下单 → 查订单）。**不需要前端**，`curl` 即可（见 §2.5）。

> 本项目 P0 实测：Docker Desktop 下必须叠加 `docker-compose.docker-desktop.yml`（原 compose 全是 `network_mode: "host"`，虚拟机里端口不转发到宿主机）；`db-init` 按字母序执行会让 `seckill.sql` 早于主库跑而报 `ERROR 1146`；下单是异步的，**必须起 `mobile-api` + `message-consumer` 两个进程**，只起前者订单表恒为 0 条。实测记录见 `docs/P0_SMOKE_CHECKLIST.md`。

### P1 · Hello LLM（2 天）
- 引入 `spring-ai-bom` **2.0.2** + 库级 `spring-ai-openai` + `spring-ai-client-chat`（`spring-ai-openai` **不传递** `ChatClient`，必须显式加）
- `ChatModel` / `ChatClient` 单例 Bean，System Prompt 外置到 `resources/prompts/*.st`（不要硬编码在 Java 里）
- `GET /ai/chat` SSE 流式接口（starter 提供，路径可配），事件协议 `message_delta` / `error` / `done`
- 验证端：`curl -N` 或一个本地 html（`EventSource`），**不需要前端仓库**（见 §2.5）

**验收**：输入「你们有什么便宜的手机」能流式吐字；`temperature` 改动能看出差异（用 `curl -N` 或一个本地 html 即可，**不装前端**）。

### P2 · 结构化输出（2 天）
- `.call().entity(XxxDTO.class)` 把自然语言变成类型化对象
- 练习：把用户一句话解析成 `{"category":"手机","priceMax":3000,"brand":"小米"}`，直接喂给原搜索接口

**验收**：100 条测试语料，JSON 解析成功率 ≥ 95%，失败自动重试一次。

> 这一集是关键的认知转折：**LLM 不是聊天机器人，是一个「自然语言 → 结构化参数」的函数**。讲透这点，观众后面全通。

### P3 · Tool Calling 上：只读工具（3 天）
- 封装 `searchProducts` / `getProductDetail` / `myOrders` / `trackLogistics`
- `@Tool` + `@ToolParam` 描述要写清楚「什么时候该用」，这直接决定工具选择准确率
- 工具级鉴权、参数校验、异常兜底

**验收**：「我上周买的鞋到哪了」能正确调用 `myOrders` + `trackLogistics`，且**只能查到自己的订单**（用另一个账号测越权）。

### P4 · Tool Calling 下：写操作与 HITL（3 天）
- 两段式：`previewOrder(cartItems)` 返回待确认卡片 → 用户点确认 → 后端 `/order/confirm` 执行
- 幂等键、防重复提交、操作审计表
- 明确「模型不可直呼的白名单」

**验收**：模型尝试直接下单时被工具层拒绝；重复确认只产生一笔订单。

### P5 · Memory 与会话（2 天）
- `MessageChatMemoryAdvisor` + JDBC 存储
- `conversationId` 由服务端签发，校验归属，防越权读历史
- 窗口溢出策略：按消息边界截断，保留 System Prompt

**验收**：「那它多少钱？」能正确指代上一轮的商品；换账号看不到别人的会话。

### P6 · RAG 知识库（4 天）
- 语料：售后政策、运费规则、FAQ、商品详情长文本
- 切分策略对比：定长 vs 语义 vs 按文档结构
- `QuestionAnswerAdvisor` + 相似度阈值 + 引用溯源（回答必须带出处）
- 增量更新：商品改价/下架要能同步到向量库（用事件驱动，别全量重建）

**验收**：50 条售后问题，答对率 ≥ 85%，且 100% 带引用；问到知识库外的问题会说「不知道」而不是编。

### P7 · 多步 Agent 与工程化（5 天）
- 多步规划：拆解 → 逐步调工具 → 汇总（先自己写状态机，再用 Spring AI Alibaba Graph 对比）
- 工具调用次数上限（防死循环烧钱）
- 护栏：Prompt 注入检测、PII 脱敏 Advisor、输出审计
- 可观测：Micrometer + OpenTelemetry → Langfuse，看板展示 token 与成本
- 评测集：100 条 query，指标 = 工具选择准确率 / 答案忠实度 / P95 延迟 / 单次成本

**验收**：能拿出一页「效果 + 成本」数据，而不是「我觉得还行」。

### P8（加分）· MCP 化（3 天）
- 用 `spring-ai-starter-mcp-server-webmvc` 把商城能力暴露成 MCP Server
- 在 Claude Desktop / Cursor 里直接问「帮我查一下订单 12345」
- 这一集是天然爆款：「我把我五年前的 Java 老系统变成了 AI 能用的工具」

---

## 6. 核心代码骨架

### 6.1 ChatClient 装配（已落地，Spring AI **2.0** 写法）

```java
@AutoConfiguration
@ConditionalOnProperty(prefix = "zeroai.ai", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AiAgentProperties.class)
public class AiAgentAutoConfiguration {

    /** 2.0 已删除 OpenAiApi，改用官方 OpenAI Java SDK 造客户端 */
    @Bean
    @ConditionalOnMissingBean
    public ChatModel aiChatModel(AiAgentProperties props) {
        if (!StringUtils.hasText(props.getApiKey())) {
            throw new IllegalStateException("zeroai.ai.api-key 未配置：请通过环境变量 LLM_API_KEY 注入");
        }
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .baseUrl(props.getBaseUrl())
                .apiKey(props.getApiKey())
                .build();
        return OpenAiChatModel.builder()
                .openAiClient(client)                 // 1.x 是 openAiApi(...)
                .options(OpenAiChatOptions.builder()
                        .model(props.getModel())
                        .temperature(props.getTemperature())
                        .maxTokens(props.getMaxTokens())
                        .build())                     // 1.x 是 defaultOptions(...)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatClient aiChatClient(ChatModel model, AiAgentProperties props) {
        return ChatClient.builder(model)
                .defaultSystem(PromptLoader.load(props.getSystemPrompt()))
                .build();
    }
}
```

**P5 / P6 时再往 `defaultAdvisors` 里挂**（`ChatClient.builder(...)` 与 `prompt()` 在 2.0 签名未变，届时只改这一个 Bean）：

```java
var builder = ChatClient.builder(model)
    .defaultSystem(PromptLoader.load(props.getSystemPrompt()))
    .defaultAdvisors(
        MessageChatMemoryAdvisor.builder(memory).build(),
        guardAdvisor                        // 自定义：限流 + 脱敏 + 审计
    );
vectorStore.ifAvailable(vs -> builder.defaultAdvisors(
    QuestionAnswerAdvisor.builder(vs)
        .searchRequest(SearchRequest.builder().topK(4).similarityThreshold(0.6).build())
        .build()));
return builder.build();
```

**要点**：所有 Advisor 通过 `defaultAdvisors` 组合，业务代码里看不到任何「AI 基建」——这是能把 AI 抽成 starter 的前提。另外 api-key 缺失时**直接抛异常而不是静默降级**：静默会让「配置写错」表现成「模型不响应」，排查成本极高。

### 6.2 工具定义

```java
@Component
@RequiredArgsConstructor
public class MallTools {

    private final ProductService productService;
    private final OrderService orderService;

    @Tool(description = """
        按关键词搜索在售商品。当用户询问「有没有 / 推荐 / 找一下」某类商品时使用。
        只返回上架商品，下架商品不返回。
        """)
    public List<ProductBrief> searchProducts(
            @ToolParam(description = "商品关键词，如「手机」「运动鞋」") String keyword,
            @ToolParam(description = "返回条数，1-10，默认 5", required = false) Integer limit) {
        return productService.search(keyword, limit == null ? 5 : Math.min(limit, 10));
    }

    @Tool(description = "查询当前登录用户的订单列表。用户问「我的订单 / 我买的东西」时使用。")
    public List<OrderBrief> myOrders(
            @ToolParam(description = "订单状态：PAID/SHIPPED/DONE，可为空", required = false) String status) {
        // 关键：userId 来自安全上下文，模型无权指定
        Long userId = SecurityUtils.currentUserId();
        return orderService.listByUser(userId, status);
    }
}
```

### 6.3 SSE 流式接口（用 `SseEmitter`，不是 `Flux`）

```java
@RestController
@ConditionalOnProperty(prefix = "zeroai.ai", name = "enabled", havingValue = "true")   // 关键：必须重复声明
public class AiChatController {

    @GetMapping(value = "${zeroai.ai.chat-path:/ai/chat}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestParam("q") String question) {
        SseEmitter emitter = new SseEmitter(properties.getSseTimeoutMs());   // 必须显式给超时
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> { closed.set(true); emitter.complete(); });

        // 入口线程取用户快照：异步回调里 ThreadLocal 已失效
        Long userId = userContext.currentUserId();
        log.info("AI 对话开始，userId={}, questionLength={}", userId, question.length());

        chatClient.prompt().user(question).stream().content().subscribe(
            delta -> emit(emitter, closed, AiEvent.of(MESSAGE_DELTA, Map.of("delta", delta))),
            err -> { log.error("AI 流式对话失败，userId={}", userId, err);
                     emit(emitter, closed, AiEvent.of(ERROR,
                          Map.of("code", "AI_STREAM_ERROR", "message", "服务繁忙，请稍后再试")));
                     finish(emitter, closed); },
            () -> { emit(emitter, closed, AiEvent.of(DONE, Map.of("steps", 0)));
                    finish(emitter, closed); });
        return emitter;
    }
}
```

**为什么返回 `SseEmitter` 而不是 `Flux`**：本项目 Web 栈是 Servlet（MVC），`spring-ai-openai` 只是**传递**引入 webflux 给 `WebClient` 用，并未切换 Web 栈。用 `Flux` 返回值会把流式过程中的异常抛回框架，最后由全局 `@RestControllerAdvice` 接管 —— 而它会给 SSE 连接写一段 JSON，前端解析直接崩。`SseEmitter` + 端点内部自行 catch 是更稳的组合。

**三条硬约束**：

1. `@RestControllerAdvice` 不能接管 SSE 端点，必须在端点内 catch 并转成 `error` 事件。
2. `/ai/**` **不要加白名单**，必须登录后访问，否则等于给公网开了一个免费的模型代理。
3. 连接关闭后继续 `send()` 会抛 `IllegalStateException`，用 `AtomicBoolean` 做幂等收尾，否则日志会被刷爆。

**别忘的第 0 条**：`AiChatController` 在 `com.zeroai.ai` 包下，正好落在宿主 `com.zeroai` 的组件扫描路径里，会被**直接扫描注册**、绕过 `AiAgentAutoConfiguration` 的开关。类上必须重复声明同一个 `@ConditionalOnProperty`，否则 `zeroai.ai.enabled=false` 时会因为「要求注入不存在的 ChatClient」而启动失败。这是本项目实测踩到的坑：**编译全绿、启动即挂**。

### 6.4 两段式危险操作

```java
@Tool(description = """
    生成订单预览【不会真正下单】。当用户表达购买意图时使用。
    返回商品明细、总价、可用优惠。必须由用户在界面上点击确认后才会真正创建订单。
    """)
public OrderPreview previewOrder(@ToolParam(description = "商品ID列表") List<Long> productIds) {
    Long userId = SecurityUtils.currentUserId();
    return orderService.preview(userId, productIds);
}
// 注意：没有任何 @Tool 叫 createOrder。真正的下单入口只有 /api/order/confirm。
```

### 6.5 MCP Server

```java
@Configuration
public class McpServerConfig {
    @Bean
    ToolCallbackProvider mallToolProvider(MallTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
```

```yaml
spring.ai.mcp.server:
  name: mall-agent
  version: 1.0.0
  protocol: STREAMABLE          # 2026-07 规范起 Streamable HTTP 为默认，SSE 已弃用
```

### 6.6 自定义护栏 Advisor（骨架）

```java
public class AiGuardAdvisor implements CallAdvisor, StreamAdvisor {

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest req, CallAdvisorChain chain) {
        long userId = SecurityUtils.currentUserId();
        quotaService.assertAndCharge(userId);                     // 配额
        ChatClientRequest sanitized = piiMasker.mask(req);        // 手机号/身份证脱敏
        injectionDetector.check(sanitized);                       // Prompt 注入
        long start = System.nanoTime();
        try {
            ChatClientResponse resp = chain.nextCall(sanitized);
            meter.record(userId, resp, System.nanoTime() - start); // token / 耗时 / 工具链
            return resp;
        } catch (Exception e) {
            meter.recordFailure(userId, e);
            throw e;
        }
    }
    // 顺序：放在 Advisor 链最外层
}
```

---

## 7. 工程化清单（决定你是不是「生产级」）

| 类别 | 必须做的事 |
|---|---|
| 成本 | 用户级日配额、单次 maxTokens 上限、工具调用次数上限、按对话记账 |
| 稳定 | 超时（连接/读）、重试（仅幂等）、降级到规则引擎、熔断 |
| 安全 | 工具白名单、参数白名单、PII 脱敏、Prompt 注入检测、输出审计、`conversationId` 归属校验 |
| 可观测 | traceId 贯穿、token 计量、工具调用链、Langfuse/OTel 看板 |
| 质量 | golden set 回归、LLM-as-judge 忠实度、每次改 Prompt 必跑回归 |
| 数据 | 向量库增量同步（事件驱动）、文档版本化、删除即失效 |
| 交付 | Docker Compose 一键起、`.env.example`、README 三步启动 |

> 面试/技术分享时最有杀伤力的一句话：**「我把 Prompt 当代码管——外置、版本化、有回归测试。」**

---

## 8. 视频系列分集脚本（14 集）

定位：**不是「教你调 API」，是「跟我一起把一个真实系统改造成 Agent 原生」**。每集 15–25 分钟，独立可看，串起来是完整工程。

| 集 | 标题 | 核心内容 | 钩子 / 演示点 |
|---|---|---|---|
| EP00 | 我要给一个 5 年前的 Java 商城接上 AI Agent | 路线图、最终效果预览 | 先播 60 秒「成片效果」，再讲要做什么 |
| EP01 | Spring AI / LangChain4j / 直接调 HTTP，选哪个？ | 三框架各写一个 demo，同题对比 | 现场跑同一问题的三种实现，比代码量 |
| EP02 | 10 分钟把开源商城跑起来 | Docker Compose、踩坑合集 | 从 `git clone` 到登录页计时 |
| EP03 | 老项目升级 Boot 4.1 + JDK 21（整栈迁移） | 升级清单、Jackson 3、包路径搬迁 | 「升级前 3 天，升级后 3 小时」+ 现场撞 3 个启动期 NoClassDefFoundError |
| EP04 | 第一个 AI 接口：流式输出 | ChatClient、SSE、Prompt 外置 | 前端一个字一个字蹦出来 |
| EP05 | 把大模型当成「类型化的函数」 | 结构化输出、重试、兜底 | 一句话 → JSON → 直接调老接口 |
| EP06 | 让 AI 会查商品（Tool Calling 上） | `@Tool`、参数描述、只读工具 | 「有没有 3000 以内的手机」→ 真实商品卡片 |
| EP07 | 让 AI 敢下单（Tool Calling 下） | 两段式确认、幂等、鉴权 | 用另一个账号演示越权被拦 |
| EP08 | 它会记住你（Memory） | 多轮、指代消解、会话隔离 | 「那它多少钱？」正确指代 |
| EP09 | 喂给它你的售后政策（RAG 上） | 切分、Embedding、入库 | 同一问题，有/无 RAG 对比 |
| EP10 | 别让它瞎编（RAG 下） | 召回优化、引用溯源、拒答 | 问到知识库外 → 老实说不知道 |
| EP11 | 让它自己规划（多步 Agent） | 拆解 → 执行 → 反思、HITL | 一次请求串起 4 个工具 |
| EP12 | 上生产前必须做的 6 件事 | 限流、成本、可观测、注入防护 | 现场攻击自己的 Agent，看护栏生效 |
| EP13 | 把你的 Java 系统变成 MCP 工具（爆款位） | MCP Server、Cursor 直连 | 在编辑器里问「查下订单 12345」 |
| EP14 | 复盘：效果、成本、下一步 | 数据、ADR、2.1-M1 展望 | 亮出评测集与真实账单 |

**录制策略**：
- **先做后录**。先把 P0–P4 一次性跑通（约 3 周），拿到完整可演示链路，再回头按集录制。避免「录到第 7 集发现架构要改」。
- 每集一个 git tag，视频里展示 `git checkout ep06` 就能回到该集状态。
- 每集配套 `docs/ep06.md` 讲义，视频描述区放链接——这是涨粉和留存的关键。
- 所有密钥用 `.env`，**录制前检查屏幕不要泄露 API Key**。

---

## 9. 开源仓库结构与合规

### 9.1 目录

```
ai-agent-in-action/
├── README.md                  # 三步启动 + 效果 GIF + 分集索引
├── LICENSE                    # 见 9.2
├── NOTICE                     # 第三方项目来源与协议
├── docs/
│   ├── adr/                   # 架构决策记录：为什么选 Spring AI、为什么 PGVector
│   ├── episodes/              # ep00.md ~ ep14.md 讲义
│   └── pitfall.md             # 踩坑合集（搜索引擎流量入口）
├── deploy/
│   ├── docker-compose.yml     # mysql / redis / pgvector / ollama
│   ├── init-sql/
│   └── .env.example
├── base/                      # 业务底座（submodule 或 fork 说明，不整包拷贝）
├── ai-agent-module/           # ★ 你的核心资产
│   ├── ai-agent-spring-boot-starter/
│   │   ├── core/              # ChatClient 装配、Prompt 加载
│   │   ├── tool/              # 工具注册 SPI、鉴权、幂等
│   │   ├── rag/               # 切分、入库、增量同步
│   │   ├── memory/            # 会话存储与隔离
│   │   ├── guard/             # 限流、脱敏、注入检测、审计
│   │   └── web/               # SSE Controller、会话管理
│   └── ai-agent-mcp-server/
├── web/                       # Vue3 AI 助手组件（可独立复用）
├── eval/                      # golden-set.jsonl + 跑批脚本 + 报告
└── scripts/
```

> 本项目里这套核心资产已经落地为 **`zeroai-ai-starter`**（包 `com.zeroai.ai`，不依赖任何业务模块），上面的 `ai-agent-module/*` 是它未来独立开源时的目标形态。`web/` 目录对应 §2.5 提到的「20 行 html」，不必为此引入 Vue 工程。

### 9.2 协议合规（别踩坑）

| base 项目协议 | 你的仓库必须 | 说明 |
|---|---|---|
| GPL-3.0 | **GPL-3.0** | 传染性强。用 submodule 也不能「隔离」——只要构成衍生作品就得 GPL。教学开源没问题，但别人不能闭源商用 |
| Apache-2.0 / MIT | 你可自选 MIT / Apache-2.0 | 最自由，推荐优先选这类底座 |

三条硬规矩：
1. **不整包镜像别人代码**。用 submodule 或只发自己的模块 + 说明。
2. **`NOTICE` 里写清**：底座项目名、仓库地址、协议、你改了哪些文件。
3. **视频里不出现真实 API Key / 真实用户数据**。演示数据自己造。

---

## 10. 排期与成本

**8 周节奏**（每周约 15 小时，兼职可行）：

| 周 | 开发 | 内容 |
|---|---|---|
| W1 | P0 + P1 | 写 README、EP00 脚本 |
| W2 | P2 + P3 | 录 EP00–EP03 |
| W3 | P4 | 录 EP04–EP05 |
| W4 | P5 + P6 前半 | 录 EP06–EP07 |
| W5 | P6 后半 | 录 EP08–EP10 |
| W6 | P7 | 录 EP11–EP12 |
| W7 | P8（MCP） | 录 EP13 |
| W8 | 收尾、评测、文档 | 录 EP14，整理仓库，发首发 |

**成本**：本地 Docker 全跑，无需服务器。模型 API 演示阶段总花费可控制在百元级；用 Ollama 本地模型可进一步压低。真正的成本是你的时间。

---

## 11. 风险与对策

| 风险 | 概率 | 对策 |
|---|---|---|
| Spring AI 版本漂移，代码对不上 | 高 | 锁死版本号 + 每集 git tag + README 标注「本章基于 Spring AI 2.0.2 / Boot 4.1.2」 |
| 模型输出不稳定，演示翻车 | 高 | `temperature=0.3` + 结构化输出 + 录制前跑 3 遍 + 准备录屏备份 |
| 工具被越权调用 | 中 | 工具内校验权限、白名单、写操作两段式。这本身是 EP07 的卖点 |
| 环境跑不起来，观众流失 | 中 | docker-compose 一键起 + `.env.example` + 首集就演示全新机器部署 |
| 录到一半改架构 | 中 | 严格执行「先跑通 P0–P4 再录」 |
| 内容同质化 | 中 | 差异化押在「可复用 starter + 评测集 + MCP」三点上，不做又一个聊天框教程 |
| 别人 clone 后跑不通 | 中 | CI 里加一个 job：全新容器里 `docker compose up` + 冒烟测试 |

---

## 12. 迁移实录（2026-10 实测，给其他老项目照抄）

### 12.1 结果

| 项 | 数字 |
|---|---|
| 模块数 | 17（含新增 `zeroai-ai-starter`） |
| 全量 `clean package` | 通过 |
| 单元测试 | **213 个全绿**（迁移前 172） |
| 运行期冒烟 | **6/6**（登录 / 首页 / 搜索 / 详情 / 加购 / 下单） |
| 字节码 | target 65（Java 21） |

### 12.2 版本矩阵

| 组件 | 迁移前 | 迁移后 | 说明 |
|---|---|---|---|
| JDK | 17 | **21** | 不能再回退到 17，产物 target 65 |
| Spring Boot | 3.5.14 | **4.1.2** | BOM 实际管理：Tomcat **11.0.27** / Jackson **3.1.7** / Lettuce **7.5.2** / Netty **4.2.19** / Lombok **1.18.48** |
| Spring AI | 1.1.8 | **2.0.2** | `OpenAiApi` 删除，改官方 `OpenAIOkHttpClient` |
| MyBatis-Plus | 3.5.16 | **3.5.17** | `IService` 从 `extension.service` 挪到 `spring.service`，**本仓库 68 个文件** |
| Sa-Token | 1.45.0 | **1.46.0** | 新增 JSON 反序列化类型白名单 |
| Druid | `druid-spring-boot-starter` | **`druid-spring-boot-4-starter`** | artifactId 改名 |

### 12.3 迁移中最容易卡住的九个坑（其中 4 个只在启动/联调期暴露）

| # | 症状 | 根因 | 修法 |
|---|---|---|---|
| 1 | IDEA 编译 `NoSuchFieldError: JCImport.qualid` | Lombok 太旧，JDK 21 起 `qualid` 类型变为 `JCPath`，**< 1.18.30 必崩** | 升到 1.18.30+，且**只在根 pom 声明一次** |
| 2 | 打包产物行为诡异、版本不可复现 | import BOM 只约束依赖**不约束插件**，`spring-boot-maven-plugin` 漏版本时解析到 **4.2.0-M2** 里程碑 | 根 pom `pluginManagement` 锁 `${spring-boot.version}` |
| 3 | 编译报「找不到 `IService` / `ServiceImpl`」 | MyBatis-Plus 3.5.17 把 service 包移到 `com.baomidou.mybatisplus.spring.service` | 批量替换包名（`Page`、`InnerInterceptor` 仍在 `extension.plugins.*`，别一起改） |
| 4 | 编译报「exception never thrown」 | Jackson 3 把 `JsonProcessingException`（受检）换成 `JacksonException`（Runtime） | `catch` 类型改掉，包名 `com.fasterxml.jackson.databind` → `tools.jackson.databind`（**注解包不变**） |
| 5 | 启动 `NoClassDefFoundError: io/lettuce/core/DriverInfo` | 手工把 `lettuce.version` 钉在 6.8.2，Spring Data Redis 4 要 Lettuce 7 | 删掉版本覆盖，交 BOM（7.5.2） |
| 6 | 启动 `NoClassDefFoundError: io/netty/channel/MultiThreadIoEventLoopGroup` | 手工钉了 11 个 `netty-*` 到 4.1.134，Lettuce 7 要 Netty 4.2 | 删掉全部 netty 版本条目，交 BOM（4.2.19） |
| 7 | 登录直接 403「认证失败」 | Sa-Token 1.46 只允许白名单类型做 JSON 多态反序列化 | 自己模块的类 `implements SaJsonType`；跨模块类型写 `META-INF/satoken/sa-json-type.list` |
| 8 | 启动报 `required a bean of type 'ChatClient'` | starter 的 `@RestController` 落在宿主扫描路径内，绕过 `@ConditionalOnProperty` 开关 | 控制器类上**重复声明**同一个条件 |
| 9 | 后台登录 HTTP 200 + `code:5000`，`msg` 为空，无业务日志 | `UserDetails#getAuthorities()` 返回 `null`，Security 7 的 `createSuccessAuthentication` 直接 `new LinkedHashSet<>(null)` | 返回 `Collections.emptySet()`（或按需把 permissions 转 authority），详见 `BOOT4_MIGRATION_NOTES.md` §7 |

> 5、6、8 是本轮最贵的三个：**编译全绿、213 个单测全绿，一启动就死**。单测覆盖不到 Spring 上下文启动时的类加载与条件装配。
> 第 9 个更阴——**启动也正常，直到有人真的点后台登录**：P0 冒烟只覆盖移动端 Sa-Token，后台 Spring Security 是另一条链路。回归清单必须包含**两个入口各自的登录**。

### 12.4 回归验证（每次动版本都跑一遍）

```bash
# 1) 全量编译 + 测试（JDK 21；本机 IDEA 工程 SDK 即 ms-21.0.12）
export JAVA_HOME=/c/Users/13153/.jdks/ms-21.0.12
mvn -B clean package

# 2) 字节码必须是 65
javap -verbose -cp zeroai-util/target/classes com.zeroai.util.util.R | grep major

# 3) 关键客户端版本已抬升（看 fat jar 里真实打进去的）
unzip -l jars/zeroai-mobile-api.jar | grep -E "lettuce-core|netty-common|jackson-databind"

# 4) 两个进程都起，跑 6 条冒烟 —— 这一步才真正验证迁移
#    java -jar jars/zeroai-mobile-api.jar + zeroai-message-consumer
```

### 12.5 遗留项（下次动版本时一起清）

- 根 pom 仍钉着 `<tomcat.version>10.1.55</tomcat.version>`（沿用 Boot 3.5 的取值），而 Boot 4.1.2 的 BOM 管理的是 **11.0.27**。本次冒烟未暴露问题，但按 §3.2 第 3 条原则**应评估移除**，让 Tomcat 跟 BOM 走。
- `lombok.version` 钉在 1.18.46（取自 Boot 3.5.14），BOM 4.1.2 已是 1.18.48。两者都支持 JDK 21，可择机删掉覆盖。
- `db-init` 按字母序执行的历史问题未根治（`seckill.sql` 早于主库执行会 `ERROR 1146`），当前靠「先移出依赖脚本、起库后按序补导」绕过。

---

## 13. 给你的三条建议

1. **第一集别讲架构，讲效果**。观众只给你 90 秒。先播成片，再讲怎么做。
2. **把「踩坑」当资产而不是负担**。`docs/pitfall.md` 会是整个仓库流量最高的文件。
3. **坚持「AI 是调用方，不是新业务层」这个原则**。这决定了你的接入是「加分项」还是「技术债」——也是你和大多数教程最本质的区别。

---

*文档版本：v2.0 / 2026-10-25 ｜ 技术栈基线：**Spring Boot 4.1.2 + JDK 21 + Spring AI 2.0.2** ｜ 配套：`AGENT_INTEGRATION_SOP.md`（执行规程与门禁）、`BOOT4_MIGRATION_NOTES.md`（迁移手册）、`P0_SMOKE_CHECKLIST.md`（冒烟实测）*
*版本号请以实际拉取时的 Maven Central 最新补丁版为准；升级前先读 §12.3 那九个坑。*
