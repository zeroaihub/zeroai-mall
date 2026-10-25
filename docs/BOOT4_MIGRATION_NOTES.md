# Boot 3.5 → Boot 4.1 迁移手册（Spring AI 2.0 前置）

> 背景：Spring AI **2.0.2**（当前 2.0 线最新稳定补丁）是对着 **Spring Boot 4.1.2** 编译的，
> 官方要求 Boot 4.x。因此「把 Spring AI 升到最新」实际等于一次 **整栈迁移**：
> Spring Framework 7 + Jackson 3 + Spring Security 7。
>
> 本文记录 zeroai-mall 的真实迁移过程与每一处踩坑，可直接作为其他老项目的检查清单。
> 迁移结果：17 模块全量编译通过、**213 个测试全绿**、6 条冒烟（登录/首页/搜索/详情/加购/下单）全部通过。

---

## 0. 迁移前后对照

| 组件 | 迁移前 | 迁移后 |
|---|---|---|
| JDK | 17 | **21** |
| Spring Boot | 3.5.14 | **4.1.2** |
| Spring AI | 1.1.8 | **2.0.2** |
| Jackson | 2.x（`com.fasterxml.jackson`） | **3.x（`tools.jackson`）** |
| Spring Security | 6.x | **7.x** |
| MyBatis-Plus | 3.5.16（boot3 starter） | **3.5.17（boot4 starter）** |
| Sa-Token | 1.45.0（boot3 starter） | **1.46.0（boot4 starter）** |
| Druid | `druid-spring-boot-starter` 1.2.28 | **`druid-spring-boot-4-starter`** 1.2.28 |
| Lettuce | 6.8.2（手工钉版本） | **7.5.2**（交 BOM） |
| Netty | 4.1.134（手工钉版本） | **4.2.19**（交 BOM） |

---

## 1. 依赖矩阵要改什么

### 1.1 starter 改名 / 换 artifactId

| 旧 | 新 |
|---|---|
| `spring-boot-starter-aop` | **`spring-boot-starter-aspectj`** |
| `mybatis-plus-spring-boot3-starter` | **`mybatis-plus-spring-boot4-starter`**（3.5.17） |
| `sa-token-spring-boot3-starter` | **`sa-token-spring-boot4-starter`**（1.46.0） |
| `druid-spring-boot-starter` | **`druid-spring-boot-4-starter`** |

> `spring-boot-starter-web`、`spring-boot-starter-test`、`spring-boot-starter-validation` 等仍沿用原名。

### 1.2 BOM 不再管理的依赖，必须自己钉版本

Boot 4 的 `spring-boot-dependencies` 移除了部分条目，缺失时 Maven 直接报
`'dependencies.dependency.version' ... is missing`，构建根本进不到编译阶段。

- **`spring-retry`**：本仓库实测，3.5.14 的 BOM 有 3 处引用，4.1.2 为 0 处 → 需自建 property + `dependencyManagement`。

### 1.3 手工钉死的中间件客户端版本是最大的雷

这两处**编译期完全看不出来**，只在启动时炸：

| 症状 | 根因 | 修法 |
|---|---|---|
| `NoClassDefFoundError: io/lettuce/core/DriverInfo` | 项目把 `lettuce.version` 钉在 6.8.2，而 Spring Data Redis 4.x 需要 Lettuce 7 | 删除版本覆盖，交给 BOM（7.5.2） |
| `NoClassDefFoundError: io/netty/channel/MultiThreadIoEventLoopGroup` | 项目把 11 个 `netty-*` 逐个钉在 4.1.134，而 Lettuce 7 需要 Netty 4.2 | 删除全部 netty `dependencyManagement` 条目，交给 BOM（4.2.19） |

**检查方法**：`grep -nE "lettuce|netty|jackson" pom.xml`，凡是手工写死版本的，一律问一句「Boot 4 管理的是哪个版本」。

---

## 2. 包路径迁移（编译期报错，逐个改）

| 旧包/类 | 新包/类 |
|---|---|
| `org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer` | `org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer` |
| `org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration` | `org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration` |
| `org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory` | `org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory` |
| `org.springframework.boot.web.servlet.server.ServletWebServerFactory` | `org.springframework.boot.web.server.servlet.ServletWebServerFactory` |
| `TomcatServletWebServerFactory#addAdditionalTomcatConnectors` | `#addAdditionalConnectors` |

> Boot 4 把 `spring-boot-autoconfigure` 拆成了按技术划分的模块（`spring-boot-data-redis`、
> `spring-boot-restclient`、`spring-boot-tomcat`、`spring-boot-web-server`…），
> 类都还在，只是包被搬到对应模块里。找不到类时**先用 jar 查真实路径**，别猜：
> ```bash
> unzip -l ~/.m2/repository/org/springframework/boot/spring-boot-tomcat/4.1.2/*.jar | grep TomcatServletWebServerFactory
> ```

### MyBatis-Plus 3.5.17 的包移动（容易漏）

`IService` / `ServiceImpl` 从 `com.baomidou.mybatisplus.extension.service` 迁到
**`com.baomidou.mybatisplus.spring.service`**（`.impl` 同理）。本仓库受影响 **68 个文件**：

```bash
grep -rl "com.baomidou.mybatisplus.extension.service" --include=*.java . \
  | xargs sed -i 's|com\.baomidou\.mybatisplus\.extension\.service|com.baomidou.mybatisplus.spring.service|g'
```

`Page`、`InnerInterceptor` 等仍在 `extension.plugins.*`，不要一起改。

---

## 3. Jackson 2 → Jackson 3

- 包名：`com.fasterxml.jackson.databind` / `.core` → **`tools.jackson.databind` / `.core`**
- **注解包不变**：`com.fasterxml.jackson.annotation.*`（`@JsonFormat`、`@JsonIgnore`…）不用动
- **异常变了**：`JsonProcessingException`（受检）→ `JacksonException`（**RuntimeException**）。
  原来 `catch (IOException e)` 包 `writeValue` 的写法会编译报「exception never thrown」，必须改捕获类型

本仓库 `JsonUtil` 的改法：

```java
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
...
try {
    return objectWriter.writeValueAsString(value);
} catch (JacksonException e) {   // 原来 catch IOException，Jackson 3 下不再声明受检异常
    throw new Exception(e);
}
```

> `new ObjectMapper()` 仍可用；需要定制时用 `JsonMapper.builder()`。
> Jackson 3 还提供 `JsonMapper.builderWithJackson2Defaults()`，可作为渐进迁移的过渡。

---

## 4. Spring AI 1.x → 2.0（API 级变更）

### 4.1 `OpenAiApi` 已删除

1.x 的核心写法：

```java
OpenAiApi api = OpenAiApi.builder().baseUrl(url).apiKey(key).build();
OpenAiChatModel.builder().openAiApi(api).defaultOptions(opts).build();
```

2.0 改用**官方 OpenAI Java SDK**，`spring-ai-openai` 只依赖 `openai-java-core`（只有接口），
OkHttp 实现要单独引：

```xml
<dependency>
    <groupId>com.openai</groupId>
    <artifactId>openai-java-client-okhttp</artifactId>
    <version>${openai-java.version}</version>   <!-- 与 spring-ai-openai 传递的 core 版本一致：4.80.0 -->
</dependency>
```

```java
OpenAIClient client = OpenAIOkHttpClient.builder()
        .baseUrl(properties.getBaseUrl())
        .apiKey(properties.getApiKey())
        .build();

OpenAiChatModel.builder()
        .openAiClient(client)     // 不再是 openAiApi(...)
        .options(opts)            // 不再是 defaultOptions(...)
        .build();
```

### 4.2 保持「未开启 AI 时零影响」的做法

不要用 `spring-ai-starter-model-openai` 的自动装配：它会在缺 `api-key` 时让应用启动失败。
仍用库级坐标 + 自建 Bean，并用 `@ConditionalOnProperty(zeroai.ai.enabled=true)` 兜住整条链路。
`ChatClient.builder(ChatModel)` / `prompt()` 在 2.0 中签名未变，控制器无需改动。

---

## 5. Sa-Token 1.46 的反序列化白名单（升级后登录必踩）

Sa-Token 1.46 起，Redis 中的 Session 走 JSON 多态反序列化，**只允许白名单内的类型**，
否则登录直接失败：

```
无法反序列化的类型：com.zeroai.mobile.framework.security.LoginUserDetail，
请先将其注册到 JSON 全局类型白名单
```

三种登记方式，按场景选：

| 方式 | 适用 |
|---|---|
| 实体 `implements cn.dev33.satoken.json.SaJsonType` | **本模块自己的类**，最推荐 |
| `SaJsonStrategy.instance.registerAllowType(X.class)`（必须在 `SpringApplication.run` 之前） | 启动早期集中登记 |
| `META-INF/satoken/sa-json-type.list` 按行写全类名 | **跨模块类型**，不能给被依赖模块反向引入 sa-token |

本仓库最终做法：`LoginUserDetail implements SaJsonType` +
`zeroai-mobile-api/src/main/resources/META-INF/satoken/sa-json-type.list` 里登记
`com.zeroai.domain.api.trade.entity.Member`（后者在 domain-api，不应感知 sa-token）。

> 排查技巧：这个错误被全局异常处理器包成 `403 认证失败`，看日志里 `handleSaTokenException` 的 message 才有真实原因。

---

## 6. 回归验证清单

```bash
# 1) 全量编译 + 测试（213 个用例）
export JAVA_HOME=/path/to/jdk-21
mvn -B clean package

# 2) 字节码版本必须是 65（Java 21）
javap -verbose -cp zeroai-util/target/classes com.zeroai.util.util.R | grep major

# 3) 关键客户端版本已抬升（看打出来的 fat jar）
unzip -l jars/zeroai-mobile-api.jar | grep -E "lettuce-core|netty-common|jackson-databind"

# 4) 两个进程都起（少一个订单永远不落库），跑 6 条冒烟
#    登录 → 首页 → 搜索 → 详情 → 加购 → 下单
```

**必须真启动验证**：本次迁移里，`lettuce` 和 `netty` 两个问题都是「编译 + 测试全绿，
一启动就 `NoClassDefFoundError`」。测试覆盖不到 Spring 上下文启动时的类加载。

---

## 7. 迁移顺序建议（给其他老项目）

1. **先升 JDK 21 + Lombok**（1.18.30+），单独跑一次全量构建，确认基线干净
2. **再升 Boot 4 + 换 starter**，此时不要动业务代码，先把编译错误清干净
3. **包路径迁移**：逐个模块 `mvn -pl <module> -am compile`，按报错改
4. **Jackson 3**：先改 `JsonUtil` 这类集中封装，再改零散使用点
5. **启动验证**：这一步才会暴露 lettuce / netty / Sa-Token 白名单问题
6. **最后接 Spring AI 2.0**：AI 模块本身改动很小（`OpenAiApi` → `OpenAIClient`）

> 反面教材：反过来先动 Spring AI，会在一个完全陌生的框架里同时面对 Jackson 3、
> Security 7 和包路径迁移三类问题，排查成本翻倍。

---

## 7. Spring Security 7：`UserDetails#getAuthorities()` 不能返回 null

**这是本次迁移漏掉的第 9 个坑**，直到 2026-10-25 接前端联调、第一次走后台登录才暴露。

### 现象

后台登录 `POST /login` 返回 HTTP 200 + `{"code":5000,"data":null,"msg":null}`，
日志里既没有「登录失败」也没有「登录成功」，只有一段异常栈：

```
Caused by: java.lang.NullPointerException: Cannot invoke "java.util.Collection.size()" because "c" is null
    at java.base/java.util.LinkedHashSet.<init>(LinkedHashSet.java:181)
    at org.springframework.security.authentication.dao.AbstractUserDetailsAuthenticationProvider
        .createSuccessAuthentication(AbstractUserDetailsAuthenticationProvider.java:227)
    at com.zeroai.admin.framework.security.service.LoginService.login(LoginService.java:31)
```

### 根因

`LoginUserDetail#getAuthorities()` 一直返回 `null`（Boot 3 / Security 6 时代没出问题——
那条路径取的是 token 上的 authorities，`AbstractAuthenticationToken` 对 null 是容忍的）。
Security 7 改了 `createSuccessAuthentication`，直接对 `user.getAuthorities()` 构造
`new LinkedHashSet<>(authorities)`，null 立刻 NPE。

### 修法

本项目鉴权走 `@PreAuthorize("@ss.hasPermi(...)")` → `getPermissions()`，**不读 authorities**，
所以返回空集合即可，行为不变：

```java
@Override
public Collection<? extends GrantedAuthority> getAuthorities() {
    return Collections.emptySet();   // 不能返回 null，见上
}
```

> 换项目自检：`grep -rn "getAuthorities()" --include=*.java .`，
> 凡 `return null` 或可能返回 null 的实现都要改；如果项目真用 authority 鉴权，
> 这里应返回 `permissions.stream().map(SimpleGrantedAuthority::new).toList()`。
>
> **为什么迁移时没发现**：P0 冒烟只覆盖了移动端（Sa-Token），后台是 Spring Security 独立链路，
> 直到前端接上、真人点登录才走到这段代码。**回归清单必须包含两个入口各自的登录**。
