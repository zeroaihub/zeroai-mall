package com.zeroai.ai.engine;

/**
 * 引擎名常量，与配置项 {@code zeroai.ai.engine} 的取值一一对应。
 * <p>
 * 集中在这里而不是散落在各处的字符串字面量：控制器要按名字选引擎，
 * 拼写错误会静默退化成默认引擎，这类问题在运行期很难发现。
 */
public final class ChatEngineNames {

    /** Spring AI 通道：单轮无工具问答，默认值。 */
    public static final String SPRING_AI = "spring-ai";

    /** LangChain4j 通道：单轮无工具问答的另一种实现，与 Spring AI 同契约、同配置项。 */
    public static final String LANGCHAIN4J = "langchain4j";

    /** AgentScope 通道：带只读商城工具的 Agent，走另一条独立链路。 */
    public static final String AGENTSCOPE = "agentscope";

    private ChatEngineNames() {
    }
}
