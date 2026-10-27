package com.zeroai.ai.agent;

import com.zeroai.ai.config.AiAgentProperties;
import com.zeroai.ai.harness.AiObservabilityMiddleware;
import com.zeroai.ai.harness.HarnessProperties;
import com.zeroai.ai.harness.ToolSurfacePolicy;
import com.zeroai.ai.knowledge.KnowledgeService;
import com.zeroai.ai.mall.port.MallSearchPort;
import com.zeroai.ai.mall.proposal.ActionProposalProperties;
import com.zeroai.ai.mall.proposal.ActionProposalService;
import com.zeroai.ai.mall.proposal.vo.ProposalVO;
import com.zeroai.ai.mall.tool.GoodsDetailTool;
import com.zeroai.ai.mall.tool.ListMyAddressesTool;
import com.zeroai.ai.mall.tool.ListMyCartTool;
import com.zeroai.ai.mall.tool.MyOrdersTool;
import com.zeroai.ai.mall.tool.ProposeOrderTool;
import com.zeroai.ai.mall.tool.SearchKnowledgeTool;
import com.zeroai.ai.mall.tool.SearchProductsTool;
import com.zeroai.ai.mall.tool.StockStatusTool;
import com.zeroai.ai.prompt.PromptLoader;
import com.zeroai.domain.api.cart.service.ICartService;
import com.zeroai.domain.api.goods.service.IGoodsDetailService;
import com.zeroai.domain.api.goods.service.IGoodsProductService;
import com.zeroai.domain.api.trade.service.IAddressService;
import com.zeroai.domain.api.trade.service.IMobileOrderService;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * 商城 Agent 工厂。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>每次请求新建 Agent</b>：工具实例持有该请求的 userId，用户隔离靠对象边界保证，
 *       而不是靠「记得传对参数」。代价是一个 Agent 对象的构造成本，
 *       相比一次模型往返可忽略。</li>
 *   <li><b>匿名不注册订单工具</b>：userId 为空时连工具都不存在，模型没有可调用的入口。</li>
 *   <li><b>模型实例复用</b>：{@link OpenAIChatModel} 线程安全，构造一次即可，避免重复建连接池。</li>
 * </ul>
 * 本类不做鉴权：userId 由调用方在入口线程取值后传入，本类只负责装配。
 */
public class MallAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(MallAgentFactory.class);

    /** Agent 名称，用于日志与追踪，不影响授权。 */
    private static final String AGENT_NAME = "zeroai-mall-guide";

    private final AiAgentProperties properties;
    private final HarnessProperties harness;
    private final MallSearchPort searchPort;
    private final IGoodsDetailService goodsDetailService;
    private final IGoodsProductService goodsProductService;
    private final IMobileOrderService mobileOrderService;
    private final IAddressService addressService;
    private final ICartService cartService;
    /**
     * 提议服务，仅在 {@code zeroai.ai.action.enabled=true} 时非空。
     * 为空表示写操作提议链路未启用，工具面保持纯只读。
     */
    private final ActionProposalService actionProposalService;
    private final ActionProposalProperties actionProperties;
    /**
     * 知识检索门面，知识库关闭时为空。
     * 非空即注册 {@code search_knowledge}；它只带来「读规则」的能力，与写操作无关。
     */
    private final KnowledgeService knowledgeService;
    private final Model model;
    private final String systemPrompt;
    /**
     * 会话状态存储。显式指定根目录，避免 SDK 默认写到 {@code ~/.agentscope/state}。
     * 实例在工厂级复用：它只做文件读写，按 sessionId 分目录，不持有会话数据本身。
     */
    private final AgentStateStore stateStore;

    /**
     * @param properties          AI 配置
     * @param harness             Harness 能力开关
     * @param searchPort          检索端口，可为 null（此时搜索工具返回不可用，不影响其余工具）
     * @param goodsDetailService  商品详情服务
     * @param goodsProductService 商品货品服务，仅用于只读查库存
     * @param mobileOrderService  订单服务，仅用于只读查本人订单
     * @param addressService     地址服务，仅用于只读查本人地址
     * @param cartService        购物车服务，仅用于只读查本人购物车
     * @param actionProposalService 提议服务，可为 null（写操作提议链路未启用）
     * @param actionProperties      提议配置，可为 null 时使用默认关闭值
     * @param knowledgeService      知识检索门面，可为 null（知识库关闭时不注册检索工具）
     */
    public MallAgentFactory(AiAgentProperties properties,
                            HarnessProperties harness,
                            MallSearchPort searchPort,
                            IGoodsDetailService goodsDetailService,
                            IGoodsProductService goodsProductService,
                            IMobileOrderService mobileOrderService,
                            IAddressService addressService,
                            ICartService cartService,
                            ActionProposalService actionProposalService,
                            ActionProposalProperties actionProperties,
                            KnowledgeService knowledgeService) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new IllegalStateException(
                    "zeroai.ai.api-key 未配置：请通过环境变量 LLM_API_KEY 注入，禁止写入配置文件");
        }
        this.properties = properties;
        this.harness = harness == null ? new HarnessProperties() : harness;
        this.searchPort = searchPort;
        this.goodsDetailService = goodsDetailService;
        this.goodsProductService = goodsProductService;
        this.mobileOrderService = mobileOrderService;
        this.addressService = addressService;
        this.cartService = cartService;
        this.actionProposalService = actionProposalService;
        this.actionProperties = actionProperties == null ? new ActionProposalProperties() : actionProperties;
        this.knowledgeService = knowledgeService;
        this.model = buildModel(properties);
        this.systemPrompt = PromptLoader.load(properties.getAgentSystemPrompt());
        this.stateStore = new JsonFileAgentStateStore(Path.of(this.harness.getStateDir()));
    }

    /**
     * 为一次请求构建 Agent。
     *
     * @param context 请求上下文，userId 必须来自服务端鉴权结果
     * @return 可直接使用的 Agent，调用方负责 close()
     */
    public HarnessAgent create(MallAgentContext context) {
        return create(context, null);
    }

    /**
     * 为一次请求构建 Agent，并在提议生成时通过 {@code proposalListener} 推送 confirm_action 事件。
     *
     * @param context          请求上下文，userId 必须来自服务端鉴权结果
     * @param proposalListener 提议生成回调，可为 null
     * @return 可直接使用的 Agent，调用方负责 close()
     */
    public HarnessAgent create(MallAgentContext context, Consumer<ProposalVO> proposalListener) {
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(AGENT_NAME)
                .sysPrompt(systemPrompt)
                .model(model)
                .toolkit(buildToolkit(context, proposalListener))
                .maxIters(properties.getMaxSteps())
                .workspace(workspaceOf(context))
                // 状态落到项目内可控目录，不用 SDK 默认的 ~/.agentscope/state
                .stateStore(stateStore)
                // 商城导购不需要任何宿主机能力，全部显式关闭
                .disableFilesystemTools()
                .disableShellTool()
                .disableWebTools()
                // 模型自主读写记忆的参数不可控，长期记忆改由业务侧 UserPreferenceMemory 承担
                .disableMemoryTools()
                /*
                 * 子 Agent 能力必须关：SubagentsMiddleware 在注册子 Agent 工具的同时，
                 * 会顺带把 skill_manage / propose_skill 注册进工具集，
                 * 等于让模型可以自己新建技能文件。这与「技能只读、只来自 classpath 白名单」相冲突。
                 */
                .disableSubagents()
                .disableDynamicSubagents()
                // 禁止 @路径展开，避免把本地文件内容吸进上下文
                .disableAtPathExpansion();

        applySkills(builder);
        applyMemory(builder);
        applyObservability(builder);
        applyPlanMode(builder, context);

        HarnessAgent agent = builder.build();
        // 最后一道收敛：Builder 开关覆盖不到的工具按黑名单剔除
        ToolSurfacePolicy.enforce(agent.getDelegate().getToolkit());
        return agent;
    }

    /**
     * 按开关装配技能。
     * <p>
     * 三层防护缺一不可：
     * <ol>
     *   <li>{@code skillRepository} 只挂载 classpath 内置技能，不挂载用户可写目录；</li>
     *   <li>{@code enableSkills} 用白名单过滤，目录里多出来的技能不生效；</li>
     *   <li>{@code disableDynamicSkills} + {@code disableDefaultWorkspaceSkills}
     *       关掉「workspace 目录自动发现」——否则往
     *       {@code .agentscope/workspace/<user>/skills} 丢一个 SKILL.md 就能改模型行为。</li>
     * </ol>
     * 关闭状态下也要显式设成 none：SDK 默认是「启用全部」，不显式关闭等于开着。
     *
     * @param builder Agent 构建器
     */
    private void applySkills(HarnessAgent.Builder builder) {
        builder.disableDynamicSkills();
        builder.disableDefaultWorkspaceSkills();
        builder.enableSkillManageTool(false);

        List<String> allowed = harness.getAllowedSkills();
        if (!harness.isSkillsEnabled() || allowed == null || allowed.isEmpty()) {
            builder.skillsEnabled(false);
            return;
        }
        try {
            builder.skillRepository(new ClasspathSkillRepository(harness.getSkillsLocation()));
        } catch (IOException e) {
            // 技能加载失败不阻断主链路：没有技能只是少了策略提示，工具边界仍由 toolkit 控制
            log.warn("技能仓库加载失败，本次请求降级为无技能模式：location={} error={}",
                    harness.getSkillsLocation(), e.getMessage());
            builder.skillsEnabled(false);
            return;
        }
        builder.enableSkills(allowed.toArray(String[]::new));
        log.info("技能已装配 names={}", allowed);
    }

    /**
     * 长期记忆：默认关闭，开启时也不允许自动 flush。
     * <p>
     * 自动 flush 会把对话摘要写进记忆目录，内容含用户原话，无法保证不夹带敏感信息。
     * 因此这里固定 {@code FlushTrigger.never()}，写入时机完全由业务侧决定。
     *
     * @param builder Agent 构建器
     */
    private void applyMemory(HarnessAgent.Builder builder) {
        if (!harness.isMemoryEnabled()) {
            builder.disableMemoryHooks();
            return;
        }
        builder.memory(MemoryConfig.builder()
                .model(model)
                .flushTrigger(MemoryConfig.FlushTrigger.never())
                .build());
        log.info("长期记忆已启用（自动 flush 关闭）");
    }

    /**
     * 可观测中间件：只读旁路，不改变输入输出。
     *
     * @param builder Agent 构建器
     */
    private void applyObservability(HarnessAgent.Builder builder) {
        if (harness.isObservabilityEnabled()) {
            builder.middleware(new AiObservabilityMiddleware());
        }
    }

    /**
     * Plan Mode 实验：仅用于只读研究型任务。
     * <p>
     * 该模式下 Agent 先产出计划再执行，写操作不在本阶段开放范围。
     *
     * @param builder Agent 构建器
     * @param context 请求上下文，用于生成独立的计划目录
     */
    private void applyPlanMode(HarnessAgent.Builder builder, MallAgentContext context) {
        if (!harness.isPlanModeEnabled()) {
            return;
        }
        String session = safeSession(context);
        Path planDir = Path.of(harness.getPlanDir()).resolve(session);
        try {
            Files.createDirectories(planDir);
        } catch (IOException e) {
            log.warn("Plan 目录创建失败，Plan Mode 不启用：{} error={}", planDir, e.getMessage());
            return;
        }
        builder.enablePlanMode(true);
        builder.planFileDirectory(planDir.toString());
        builder.allowShellInPlanMode(false);
    }

    /**
     * 按会话派生独立 workspace。
     * <p>
     * 即使文件工具已关闭，也不让不同会话共享同一目录——workspace 是 SDK 的隐含状态载体，
     * 共享会让「会话隔离」依赖「工具恰好没被启用」这种脆弱前提。
     * 会话名做字符白名单过滤，避免被构造成带 {@code ..} 的路径穿越。
     *
     * @param context 请求上下文，可为 null
     * @return 本会话专属目录
     */
    private Path workspaceOf(MallAgentContext context) {
        String safeSession = safeSession(context);
        Path workspace = Path.of(properties.getAgentWorkspace()).resolve(safeSession);
        try {
            // SDK 要求工作目录预先存在，否则每次请求都会打一条告警并退化为无工作区模式
            Files.createDirectories(workspace);

            /*
             * 防止技能注入：SDK 会无条件把 <workspace>/skills 当作技能仓库挂载
             * （Layer 3 的 FileSystemSkillRepository 没有开关可关，disableDefaultWorkspaceSkills
             * 只影响 Layer 4）。也就是说，只要有人能往这个目录写一个 SKILL.md，
             * 就能改掉模型行为——而这条路径完全绕过我们的工具白名单。
             *
             * 处理方式：发现即隔离到新的空目录，并记 ERROR 以便运维发现有人动过手脚。
             * 不删除原目录，避免破坏可能的取证线索。
             */
            Path suspicious = workspace.resolve("skills");
            if (Files.exists(suspicious)) {
                Path isolated = workspace.resolve("isolated").resolve(String.valueOf(System.currentTimeMillis()));
                log.error("Agent 工作目录下发现 skills 目录，已切换隔离目录以阻断技能注入：workspace={} isolated={}",
                        workspace, isolated);
                Files.createDirectories(isolated);
                return isolated;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Agent 工作目录创建失败：" + workspace, e);
        }
        return workspace;
    }

    /**
     * 会话名只保留安全字符，避免被构造成带 {@code ..} 的路径穿越。
     *
     * @param context 请求上下文，可为 null
     * @return 可直接用作目录名的会话标识
     */
    private static String safeSession(MallAgentContext context) {
        String sessionId = context == null || context.sessionId() == null ? "default" : context.sessionId();
        return sessionId.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /**
     * 按请求上下文装配工具集。
     * <p>
     * 单独抽出便于单元测试直接断言「匿名上下文下不存在订单工具」，
     * 而不必依赖 Agent 内部结构的反射探测。
     *
     * @param context 请求上下文，可为 null
     * @return 已注册工具的工具集
     */
    Toolkit buildToolkit(MallAgentContext context) {
        return buildToolkit(context, null);
    }

    /**
     * 按请求上下文装配工具集。
     * <p>
     * 工具面分三层：
     * <ol>
     *   <li>全量只读查询：搜索、详情、库存；</li>
     *   <li>规则知识检索：售后、配送、优惠券等公开政策（匿名也可用，只读）；</li>
     *   <li>登录用户专属只读：订单、地址、购物车；</li>
     *   <li>写操作提议（仅开启时）：{@code propose_order} 只生成待确认提议，
     *       不创建订单；真正的写入口在独立确认接口。</li>
     * </ol>
     * 知识检索只增加「读政策」的能力，不会因为命中某段文本就获得任何写权限：
     * 写能力是否注册只由 {@code zeroai.ai.action.enabled} 决定，与知识库无关。
     *
     * @param context          请求上下文，可为 null
     * @param proposalListener 提议生成回调，可为 null
     * @return 已注册工具的工具集
     */
    Toolkit buildToolkit(MallAgentContext context, Consumer<ProposalVO> proposalListener) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SearchProductsTool(searchPort));
        toolkit.registerTool(new GoodsDetailTool(goodsDetailService));
        toolkit.registerTool(new StockStatusTool(goodsProductService));
        if (knowledgeService != null) {
            toolkit.registerTool(new SearchKnowledgeTool(knowledgeService));
        }
        // 匿名用户不注册任何用户级工具：数据边界在对象层面就不存在
        if (context != null && context.userId() != null) {
            Long userId = context.userId();
            toolkit.registerTool(new MyOrdersTool(mobileOrderService, userId));
            toolkit.registerTool(new ListMyAddressesTool(addressService, userId));
            toolkit.registerTool(new ListMyCartTool(cartService, userId));
            if (actionProposalService != null && actionProperties.isEnabled()) {
                toolkit.registerTool(new ProposeOrderTool(actionProposalService, userId, proposalListener));
            }
        }
        return toolkit;
    }

    /**
     * 构建 OpenAI 兼容模型实例。与 Spring AI 通道共用同一套 baseUrl / apiKey / model。
     *
     * @param properties AI 配置
     * @return 可复用的模型实例
     */
    private Model buildModel(AiAgentProperties properties) {
        return OpenAIChatModel.builder()
                .apiKey(properties.getApiKey())
                .baseUrl(properties.getBaseUrl())
                .modelName(properties.getModel())
                .stream(true)
                .formatter(new OpenAIChatFormatter())
                .build();
    }
}
