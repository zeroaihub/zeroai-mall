package com.zeroai.ai.engine.langchain4j.agent;

import com.zeroai.ai.config.AiAgentProperties;
import com.zeroai.ai.engine.langchain4j.tool.AddToCartTool;
import com.zeroai.ai.engine.langchain4j.tool.GoodsDetailTool;
import com.zeroai.ai.engine.langchain4j.tool.ListMyAddressesTool;
import com.zeroai.ai.engine.langchain4j.tool.ListMyCartTool;
import com.zeroai.ai.engine.langchain4j.tool.MyOrdersTool;
import com.zeroai.ai.engine.langchain4j.tool.ProposeOrderTool;
import com.zeroai.ai.engine.langchain4j.tool.SearchKnowledgeTool;
import com.zeroai.ai.engine.langchain4j.tool.SearchProductsTool;
import com.zeroai.ai.engine.langchain4j.tool.ShowProductsTool;
import com.zeroai.ai.engine.langchain4j.tool.StockStatusTool;
import com.zeroai.ai.history.AiChatHistory;
import com.zeroai.ai.knowledge.KnowledgeService;
import com.zeroai.ai.mall.event.CartAdded;
import com.zeroai.ai.mall.event.CartSelection;
import com.zeroai.ai.mall.event.GuideUiEvents;
import com.zeroai.ai.mall.event.ProductCards;
import com.zeroai.ai.mall.port.MallSearchPort;
import com.zeroai.ai.mall.proposal.ActionProposalProperties;
import com.zeroai.ai.mall.proposal.ActionProposalService;
import com.zeroai.ai.web.AiEvent;
import com.zeroai.ai.web.AiEventType;
import com.zeroai.ai.web.SseSink;
import com.zeroai.domain.api.cart.service.ICartService;
import com.zeroai.domain.api.goods.service.IGoodsDetailService;
import com.zeroai.domain.api.goods.service.IGoodsProductService;
import com.zeroai.domain.api.goods.service.IGoodsService;
import com.zeroai.domain.api.trade.service.IAddressService;
import com.zeroai.domain.api.trade.service.IMobileOrderService;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LangChain4j Agent 通道编排：构建 AiServices 助手、订阅 TokenStream、下发 SSE、处理取消与并发。
 * <p>
 * <b>与 {@code com.zeroai.ai.agent.AgentChatHandler}（AgentScope 版）是平行实现</b>，
 * 生命周期约定一致：每次请求构建一个助手（工具实例持有该请求的 userId）、
 * 同会话单飞、客户端断连停止事件转发。本类不 import 任何 AgentScope 类型，
 * 也不引用 mall.tool 包（工具用 langchain4j.tool 包的平行版本），见 ADR-003。
 * <p>
 * 与 AgentScope 版的客观差异（对比学习的重点，详见 ADR-003）：
 * <ul>
 *   <li>TokenStream 1.24 没有取消/中断 API：客户端断连只能停止事件转发，
 *       模型与已排队工具会继续跑完（AgentScope 可 {@code interrupt()} 立即断）；</li>
 *   <li>步数上限用 {@code maxToolCallingRoundTrips}（按模型往返计），
 *       AgentScope 是按事件步数计，语义不完全等价；</li>
 *   <li>没有跨请求会话记忆（每次请求独立），AgentScope 有 Harness 记忆。</li>
 * </ul>
 * 本类不做鉴权：userId 由调用方在入口线程取到后传入。
 */
public class Lc4jAgentHandler {

    private static final Logger log = LoggerFactory.getLogger(Lc4jAgentHandler.class);

    /** 客户端主动断开时的历史错误码，与 AgentScope 版一致。 */
    private static final String CANCEL_CLIENT_ABORT = "CLIENT_ABORT";

    /** 工具执行中的过程态文案：下发为 thinking 事件。 */
    private static final Map<String, String> TOOL_PROGRESS = Map.of(
            "search_products", "正在检索商品",
            "get_product_detail", "正在查看商品详情",
            "get_stock_status", "正在确认库存",
            "search_knowledge", "正在查商城规则",
            "list_my_orders", "正在查询你的订单",
            "list_my_addresses", "正在核对收货地址",
            "list_my_cart", "正在查看购物车",
            "add_to_cart", "正在加入购物车",
            "show_products", "正在展示商品",
            "propose_order", "正在准备下单");

    /** 工具执行完成后的结果态文案：下发为 tool_result 事件。 */
    private static final Map<String, String> TOOL_DONE_TEXT = Map.of(
            "search_products", "已检索商品",
            "get_product_detail", "已获取商品详情",
            "get_stock_status", "已确认库存",
            "search_knowledge", "已查商城规则",
            "list_my_orders", "已查询订单",
            "list_my_addresses", "已核对地址",
            "list_my_cart", "已查看购物车",
            "add_to_cart", "已加入购物车",
            "show_products", "已展示商品",
            "propose_order", "已准备下单");

    private final AiAgentProperties properties;
    private final StreamingChatModel model;
    private final String systemPrompt;

    /** 商城只读工具与业务服务依赖；业务层与 AgentScope 通道共享同一批 Bean（见 ADR-003）。 */
    private final MallSearchPort searchPort;
    private final IGoodsDetailService goodsDetailService;
    private final IGoodsService goodsService;
    private final IGoodsProductService goodsProductService;
    private final IMobileOrderService mobileOrderService;
    private final IAddressService addressService;
    private final ICartService cartService;
    private final ActionProposalService actionProposalService;
    private final ActionProposalProperties actionProperties;
    private final KnowledgeService knowledgeService;

    /** 对话历史门面。可为 null，内部已把存储缺失/开关关闭/匿名收敛为空操作。 */
    private final AiChatHistory chatHistory;

    /** 在途会话集合：同一用户同一会话同时只允许一个在途请求。 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * @param properties   AI 配置
     * @param model        流式模型（装配类构建，可跨请求共享）
     * @param systemPrompt System Prompt 全文（装配时已从外置文件加载）
     * @param searchPort   检索端口，可为 null（搜索工具显式降级）
     * @param chatHistory  对话历史门面，可为 null
     */
    public Lc4jAgentHandler(AiAgentProperties properties,
                            StreamingChatModel model,
                            String systemPrompt,
                            MallSearchPort searchPort,
                            IGoodsDetailService goodsDetailService,
                            IGoodsService goodsService,
                            IGoodsProductService goodsProductService,
                            IMobileOrderService mobileOrderService,
                            IAddressService addressService,
                            ICartService cartService,
                            ActionProposalService actionProposalService,
                            ActionProposalProperties actionProperties,
                            KnowledgeService knowledgeService,
                            AiChatHistory chatHistory) {
        this.properties = properties;
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.searchPort = searchPort;
        this.goodsDetailService = goodsDetailService;
        this.goodsService = goodsService;
        this.goodsProductService = goodsProductService;
        this.mobileOrderService = mobileOrderService;
        this.addressService = addressService;
        this.cartService = cartService;
        this.actionProposalService = actionProposalService;
        this.actionProperties = actionProperties;
        this.knowledgeService = knowledgeService;
        this.chatHistory = chatHistory;
    }

    /**
     * 处理一次 Agent 对话，事件通过 {@code sink} 下发。方法立即返回，异步执行。
     *
     * @param sink      SSE 发送出口
     * @param userId    服务端鉴权得到的用户 ID，匿名为 null
     * @param sessionId 会话标识，由服务端派生
     * @param question  用户问题
     */
    public void handle(SseSink sink, Long userId, String sessionId, String question) {
        String requestKey = requestKey(userId, sessionId);
        if (!inFlight.add(requestKey)) {
            log.info("LangChain4j 通道拒绝并发请求，userId={}, sessionId={}", userId, sessionId);
            sink.send(AiEvent.of(AiEventType.ERROR, Map.of(
                    "code", "AI_BUSY",
                    "message", "上一条消息还在处理中，请稍候再试")));
            sink.complete();
            return;
        }

        AtomicInteger steps = new AtomicInteger();
        AtomicBoolean textSeen = new AtomicBoolean(false);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<String> uiFallback = new AtomicReference<>();
        TurnTrace trace = new TurnTrace(userId, sessionId, System.currentTimeMillis(),
                new AtomicLong(0L), new AtomicInteger());
        log.info("LangChain4j 对话开始 userId={} sessionId={} questionLength={} preview=\"{}\"",
                userId, sessionId, question.length(), preview(question));

        AiChatHistory.Turn turn = chatHistory == null ? null : chatHistory.begin(userId, sessionId, question);
        // 首条「正在理解你的问题」由 AiChatController 统一下发，这里不再重复

        try {
            Assistant assistant = AiServices.builder(Assistant.class)
                    .streamingChatModel(model)
                    .systemMessage(systemPrompt)
                    .tools(buildTools(userId, new SseGuideEvents(sink, trace, uiFallback)))
                    // 步数止损：语义是「模型往返次数」（每轮可并行多个工具），与 AgentScope 的
                    // 事件步数不完全等价，取同一配置值作为对齐基准
                    .maxToolCallingRoundTrips(Math.max(properties.getMaxSteps(), 1))
                    .build();

            sink.onClose(() -> {
                cancelled.set(true);
                if (turn != null) {
                    turn.fail(CANCEL_CLIENT_ABORT, steps.get());
                }
                log.info("LangChain4j 通道客户端断连 userId={} sessionId={} steps={}",
                        userId, sessionId, steps.get());
            });

            assistant.chat(question)
                    .onPartialResponse(delta -> onDelta(sink, delta, cancelled, textSeen, turn, trace))
                    .beforeToolExecution(before -> onBeforeTool(sink, before.request().name(),
                            steps, turn, trace, cancelled))
                    .onToolExecuted(executed -> onToolExecuted(sink, executed, cancelled, trace))
                    .onCompleteResponse(response -> onComplete(sink, requestKey, cancelled,
                            steps, textSeen, uiFallback, turn, trace))
                    .onError(error -> onError(sink, requestKey, error, cancelled, steps, turn, trace))
                    .start();
        } catch (RuntimeException e) {
            // 助手构建阶段失败（工具实例构造、配置非法）：转成 SSE error，不穿透控制器
            log.error("LangChain4j 助手构建失败 userId={} sessionId={}", userId, sessionId, e);
            inFlight.remove(requestKey);
            if (turn != null) {
                turn.fail("AI_BUILD_ERROR", steps.get());
            }
            sink.send(AiEvent.of(AiEventType.ERROR, Map.of(
                    "code", "AI_BUILD_ERROR", "message", "服务繁忙，请稍后再试")));
            sink.complete();
        }
    }

    /** 文本增量：写历史 + 下发 message_delta + 首字日志。 */
    private void onDelta(SseSink sink, String delta, AtomicBoolean cancelled, AtomicBoolean textSeen,
                         AiChatHistory.Turn turn, TurnTrace trace) {
        if (cancelled.get() || delta == null || delta.isEmpty()) {
            return;
        }
        textSeen.set(true);
        trace.textChars().addAndGet(delta.length());
        if (turn != null) {
            turn.delta(delta);
        }
        // 首字延迟是「用户觉得卡不卡」的核心指标，只打第一次
        if (trace.firstTokenAt().compareAndSet(0L, System.currentTimeMillis())) {
            log.info("首字到达 userId={} sessionId={} firstTokenMs={}",
                    trace.userId(), trace.sessionId(),
                    trace.firstTokenAt().get() - trace.startedAt());
        }
        sendQuietly(sink, AiEvent.of(AiEventType.MESSAGE_DELTA, Map.of("delta", delta)), trace);
    }

    /** 工具执行前：步数计数、历史登记、下发 thinking。 */
    private void onBeforeTool(SseSink sink, String tool, AtomicInteger steps,
                              AiChatHistory.Turn turn, TurnTrace trace, AtomicBoolean cancelled) {
        if (cancelled.get()) {
            return;
        }
        steps.incrementAndGet();
        if (turn != null) {
            turn.tool(tool);
        }
        log.info("工具调用开始 userId={} sessionId={} step={} tool={}",
                trace.userId(), trace.sessionId(), steps.get(), tool);
        sendQuietly(sink, AiEvent.of(AiEventType.THINKING, Map.of(
                "tool", tool,
                "message", TOOL_PROGRESS.getOrDefault(tool, "正在处理"))), trace);
    }

    /** 工具执行完成：下发 tool_result。结果文本不透传（可能含内部明细），只给结果态。 */
    private void onToolExecuted(SseSink sink, ToolExecution executed,
                                AtomicBoolean cancelled, TurnTrace trace) {
        if (cancelled.get() || executed == null) {
            return;
        }
        String tool = executed.request() == null ? "unknown" : executed.request().name();
        boolean success = !executed.hasFailed();
        log.info("工具执行完成 userId={} sessionId={} tool={} success={} durationMs={}",
                trace.userId(), trace.sessionId(), tool, success,
                executed.duration() == null ? -1 : executed.duration().toMillis());
        sendQuietly(sink, AiEvent.of(AiEventType.TOOL_RESULT, Map.of(
                "tool", tool,
                "success", success,
                "message", success
                        ? TOOL_DONE_TEXT.getOrDefault(tool, "已完成")
                        : "查询失败")), trace);
    }

    /** 正常完成：无文本时补可视产物对应的说明，然后 done + 收尾。 */
    private void onComplete(SseSink sink, String requestKey, AtomicBoolean cancelled,
                            AtomicInteger steps, AtomicBoolean textSeen,
                            AtomicReference<String> uiFallback,
                            AiChatHistory.Turn turn, TurnTrace trace) {
        if (cancelled.get()) {
            inFlight.remove(requestKey);
            return;
        }
        inFlight.remove(requestKey);
        // 模型只调工具、一句话没说时补一句说明，避免空气泡（与 AgentScope 版行为一致）
        String fallback = uiFallback.get();
        if (!textSeen.get() && fallback != null) {
            if (turn != null) {
                turn.delta(fallback);
            }
            trace.textChars().addAndGet(fallback.length());
            sendQuietly(sink, AiEvent.of(AiEventType.MESSAGE_DELTA, Map.of("delta", fallback)), trace);
            log.info("补齐空回答文案 userId={} sessionId={} text={}",
                    trace.userId(), trace.sessionId(), fallback);
        }
        if (turn != null) {
            turn.success(steps.get());
        }
        log.info("回答完成 userId={} sessionId={} steps={} firstTokenMs={} textChars={}",
                trace.userId(), trace.sessionId(), steps.get(),
                trace.firstTokenAt().get() == 0L ? -1 : trace.firstTokenAt().get() - trace.startedAt(),
                trace.textChars().get());
        sendQuietly(sink, AiEvent.of(AiEventType.DONE, Map.of(
                "steps", steps.get(), "truncated", false)), trace);
        sink.complete();
    }

    /** 生成失败：历史记失败，下发 error，收尾。 */
    private void onError(SseSink sink, String requestKey, Throwable error, AtomicBoolean cancelled,
                         AtomicInteger steps, AiChatHistory.Turn turn, TurnTrace trace) {
        if (cancelled.get()) {
            inFlight.remove(requestKey);
            return;
        }
        inFlight.remove(requestKey);
        log.error("LangChain4j 流式失败 userId={} sessionId={} steps={}",
                trace.userId(), trace.sessionId(), steps.get(), error);
        if (turn != null) {
            turn.fail("AI_STREAM_ERROR", steps.get());
        }
        sendQuietly(sink, AiEvent.of(AiEventType.ERROR, Map.of(
                "code", "AI_STREAM_ERROR", "message", "服务繁忙，请稍后再试")), trace);
        sink.complete();
    }

    /**
     * 按请求构建工具集。结构对齐 {@code MallAgentFactory.buildToolkit}：
     * 匿名用户不注册任何用户级工具；提议工具仅在提议开关开启时注册。
     *
     * @param userId 当前登录用户 ID，匿名为 null
     * @param events 前端事件出口
     * @return 注册给 AiServices 的工具实例列表
     */
    private List<Object> buildTools(Long userId, GuideUiEvents events) {
        List<Object> tools = new ArrayList<>(10);
        int maxSearches = properties.getMaxSearchesPerTurn() == null
                ? SearchProductsTool.DEFAULT_MAX_SEARCHES
                : properties.getMaxSearchesPerTurn();
        tools.add(new SearchProductsTool(searchPort, maxSearches));
        tools.add(new ShowProductsTool(goodsService, events));
        tools.add(new GoodsDetailTool(goodsDetailService));
        tools.add(new StockStatusTool(goodsProductService));
        if (knowledgeService != null) {
            tools.add(new SearchKnowledgeTool(knowledgeService));
        }
        // 匿名用户不注册任何用户级工具：数据边界在对象层面就不存在
        if (userId != null) {
            tools.add(new MyOrdersTool(mobileOrderService, userId));
            tools.add(new ListMyAddressesTool(addressService, userId));
            tools.add(new ListMyCartTool(cartService, userId));
            tools.add(new AddToCartTool(cartService, userId, events));
            if (actionProposalService != null && actionProperties.isEnabled()) {
                tools.add(new ProposeOrderTool(actionProposalService, userId, events));
            }
        }
        return tools;
    }

    /**
     * 发送事件，异常只记 debug：连接已关闭属于正常现象（用户切页、点停止），
     * 不能让下发失败打断 TokenStream 的回调链。
     *
     * @param sink  SSE 出口
     * @param event 待下发事件
     */
    private void sendQuietly(SseSink sink, AiEvent event, TurnTrace trace) {
        try {
            sink.send(event);
        } catch (RuntimeException e) {
            log.debug("事件下发失败 type={} error={}", event.type(), e.getMessage());
        }
    }

    private static String requestKey(Long userId, String sessionId) {
        return (userId == null ? "anonymous" : userId) + ":" + sessionId;
    }

    private static String preview(String question) {
        return question.length() <= 40 ? question : question.substring(0, 40) + "...";
    }

    /** 单轮追踪上下文：局部变量，避免多会话并发串台。 */
    private record TurnTrace(Long userId, String sessionId, long startedAt,
                             AtomicLong firstTokenAt, AtomicInteger textChars) {
    }

    /** AiServices 服务接口：流式返回，框架内部处理工具调用循环。 */
    interface Assistant {

        TokenStream chat(String message);
    }

    /**
     * 前端事件出口（LangChain4j 版）。
     * <p>
     * payload 结构与 {@code AgentChatHandler.SseGuideEvents} 完全一致——前端契约固定，
     * 两条通道必须发同一种事件。<b>两边必须同步修改</b>，见 ADR-003。
     * AgentScope 版还有 onProposal（模型直接建提议的遗留路径）；LangChain4j 版的
     * propose_order 只弹层不建提议，保留空实现以满足接口契约。
     */
    private final class SseGuideEvents implements GuideUiEvents {

        private final SseSink sink;
        private final TurnTrace trace;
        /** 本轮可视产物的兜底文案，供收尾时补白；只记第一条。 */
        private final AtomicReference<String> uiFallback;

        private SseGuideEvents(SseSink sink, TurnTrace trace, AtomicReference<String> uiFallback) {
            this.sink = sink;
            this.trace = trace;
            this.uiFallback = uiFallback;
        }

        @Override
        public void onProductCards(ProductCards cards) {
            if (cards == null || cards.items() == null || cards.items().isEmpty()) {
                return;
            }
            List<Map<String, Object>> items = new ArrayList<>(cards.items().size());
            for (ProductCards.Card card : cards.items()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("goodsId", card.goodsId());
                item.put("name", card.name());
                item.put("price", card.price() == null ? "" : card.price().toPlainString());
                item.put("sales", card.sales() == null ? 0 : card.sales());
                item.put("picUrl", card.picUrl() == null ? "" : card.picUrl());
                items.add(item);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("keyword", cards.keyword() == null ? "" : cards.keyword());
            payload.put("items", items);
            log.info("下发商品卡 userId={} sessionId={} keyword=\"{}\" count={} goodsIds={}",
                    trace.userId(), trace.sessionId(), cards.keyword(), items.size(),
                    items.stream().map(item -> item.get("goodsId")).toList());
            uiFallback.compareAndSet(null, "已按你的条件挑出这几件，点「加入购物车」即可。");
            send(AiEvent.of(AiEventType.PRODUCT_CARDS, payload));
        }

        @Override
        public void onCartAdded(CartAdded added) {
            if (added == null) {
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("cartId", added.cartId());
            payload.put("goodsId", added.goodsId());
            payload.put("goodsName", added.goodsName() == null ? "" : added.goodsName());
            payload.put("unitPrice", added.unitPrice() == null ? "" : added.unitPrice().toPlainString());
            payload.put("number", added.number() == null ? 0 : added.number());
            payload.put("picUrl", added.picUrl() == null ? "" : added.picUrl());
            log.info("下发加购成功 userId={} sessionId={} cartId={} goodsId={} goodsName=\"{}\" number={}",
                    trace.userId(), trace.sessionId(), added.cartId(), added.goodsId(),
                    added.goodsName(), added.number());
            uiFallback.compareAndSet(null, "已加入购物车。");
            send(AiEvent.of(AiEventType.CART_ADDED, payload));
        }

        @Override
        public void onCartSelection(CartSelection selection) {
            if (selection == null) {
                return;
            }
            List<Map<String, Object>> items = new ArrayList<>(
                    selection.items() == null ? 0 : selection.items().size());
            if (selection.items() != null) {
                for (CartSelection.Item item : selection.items()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("cartId", item.cartId());
                    row.put("goodsId", item.goodsId());
                    row.put("productId", item.productId());
                    row.put("goodsName", item.goodsName() == null ? "" : item.goodsName());
                    row.put("unitPrice", item.unitPrice() == null ? "" : item.unitPrice().toPlainString());
                    row.put("number", item.number() == null ? 0 : item.number());
                    row.put("checked", item.checked());
                    row.put("picUrl", item.picUrl() == null ? "" : item.picUrl());
                    items.add(row);
                }
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("addressId", selection.addressId());
            payload.put("addressText", selection.addressText() == null ? "" : selection.addressText());
            payload.put("userCouponId", selection.userCouponId());
            payload.put("totalNumber", selection.totalNumber());
            payload.put("items", items);
            log.info("下发购物车勾选层 userId={} sessionId={} addressId={} itemCount={} totalNumber={} checkedInCart={}",
                    trace.userId(), trace.sessionId(), selection.addressId(), items.size(),
                    selection.totalNumber(),
                    items.stream().filter(row -> Boolean.TRUE.equals(row.get("checked"))).count());
            uiFallback.compareAndSet(null, "请在下方确认商品和数量，点「生成下单提议」后才会创建订单。");
            send(AiEvent.of(AiEventType.CART_SELECT, payload));
        }

        @Override
        public void onProposal(com.zeroai.ai.mall.proposal.vo.ProposalVO proposal) {
            // LangChain4j 通道的 propose_order 只弹层不建提议，不会走到这里；
            // 保留空实现以满足接口契约
        }

        /**
         * 发送事件，异常只记 debug：连接已关闭属于正常现象。
         *
         * @param event 待下发事件
         */
        private void send(AiEvent event) {
            try {
                sink.send(event);
            } catch (RuntimeException e) {
                log.debug("UI 事件下发失败 type={} error={}", event.type(), e.getMessage());
            }
        }
    }
}
