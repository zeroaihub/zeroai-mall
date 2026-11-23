package com.zeroai.ai.agent;

import com.zeroai.ai.config.AiAgentProperties;
import com.zeroai.ai.history.AiChatHistory;
import com.zeroai.ai.mall.event.CartAdded;
import com.zeroai.ai.mall.event.CartSelection;
import com.zeroai.ai.mall.event.GuideUiEvents;
import com.zeroai.ai.mall.event.ProductCards;
import com.zeroai.ai.mall.proposal.vo.ProposalVO;
import com.zeroai.ai.web.AiEvent;
import com.zeroai.ai.web.AiEventType;
import com.zeroai.ai.web.SseSink;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

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
 * Agent 通道的请求编排：建 Agent、订阅事件流、下发 SSE、处理取消与并发。
 * <p>
 * 生命周期约定：
 * <ul>
 *   <li><b>每次请求一个 Agent</b>：工具实例持有该请求的 userId，用户隔离由对象边界保证。</li>
 *   <li><b>取消即停止计费</b>：客户端断连后释放 Reactor 订阅并调用 {@code agent.interrupt()}，
 *       不让模型继续生成。这是实施方案 Phase 4 验收项「断连后不继续消耗费用」的实现点。</li>
 *   <li><b>同会话单飞</b>：同一用户同一会话同时只允许一个在途请求，
 *       防止前端重试或双击导致重复计费与事件交错。</li>
 * </ul>
 * 本类不做鉴权：userId 由调用方在入口线程从鉴权上下文取到后传入。
 */
public class AgentChatHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentChatHandler.class);

    /** 匿名会话在 RuntimeContext 中的用户标识，仅用于 SDK 内部区分，不代表任何权限。 */
    private static final String ANONYMOUS_PRINCIPAL = "anonymous";

    /**
     * 客户端主动断开（关页面、切后台被回收、点停止）时的历史错误码。
     * 明确区分于模型侧失败，便于排查「为什么这条回答不完整」。
     */
    private static final String CANCEL_CLIENT_ABORT = "CLIENT_ABORT";

    private final MallAgentFactory agentFactory;
    private final AiAgentProperties properties;

    /**
     * 对话历史门面。可为 null（单测未装配历史时不记录），
     * 内部已把「存储缺失 / 开关关闭 / 匿名」收敛为空操作，调用点无需判空。
     */
    private final AiChatHistory chatHistory;

    /**
     * 在途会话集合。键由 userId 与 sessionId 共同构成，避免不同用户撞同名会话。
     */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * @param agentFactory Agent 工厂，不可为 null
     * @param properties   AI 配置，用于读取会话与步数上限
     * @param chatHistory  对话历史门面，可为 null
     */
    public AgentChatHandler(MallAgentFactory agentFactory, AiAgentProperties properties,
                            AiChatHistory chatHistory) {
        this.agentFactory = agentFactory;
        this.properties = properties;
        this.chatHistory = chatHistory;
    }

    /**
     * 处理一次 Agent 对话，事件通过 {@code sink} 下发。
     * <p>
     * 方法本身立即返回，真正的模型调用在弹性线程池中异步执行，
     * 不占用 Web 容器请求线程。副作用：消耗模型 token；不写任何业务数据。
     *
     * @param sink       SSE 发送出口
     * @param userId     服务端鉴权得到的用户 ID，匿名为 null
     * @param sessionId  会话标识，由服务端派生，不来自请求参数
     * @param question   用户问题
     */
    public void handle(SseSink sink, Long userId, String sessionId, String question) {
        String requestKey = requestKey(userId, sessionId);
        if (!inFlight.add(requestKey)) {
            log.info("Agent 通道拒绝并发请求，userId={}, sessionId={}", userId, sessionId);
            sink.send(AiEvent.of(AiEventType.ERROR, Map.of(
                    "code", "AI_BUSY",
                    "message", "上一条消息还在处理中，请稍候再试")));
            sink.complete();
            return;
        }

        AtomicInteger steps = new AtomicInteger();
        AtomicBoolean textSeen = new AtomicBoolean(false);
        AtomicBoolean truncated = new AtomicBoolean(false);
        AtomicReference<HarnessAgent> agentRef = new AtomicReference<>();
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();

        /*
         * 单轮追踪上下文：把 userId / sessionId / 起始时间 / 首字时间 / 正文长度收在一起，
         * 供事件回调打日志。放在局部变量而不是实例字段：同一个 handler 会被多个会话并发复用，
         * 写成字段就会串台。
         */
        TurnTrace trace = new TurnTrace(userId, sessionId, System.currentTimeMillis(),
                new AtomicLong(0L), new AtomicInteger());
        log.info("Agent 对话开始 userId={} sessionId={} questionLength={} preview=\"{}\"",
                userId, sessionId, question.length(), preview(question));

        AiChatHistory.Turn turn = chatHistory == null
                ? null
                : chatHistory.begin(userId, sessionId, question);

        // 断连优先于正常完成：先把已产出的内容存档，再取消订阅，最后中断 Agent。
        // 断连时不会走到 onComplete/onError，若不在此处收尾，这一轮永远不会落库——
        // 用户关掉对话框再回来，就会看到「问过的问题和回答都不见了」。
        sink.onClose(() -> {
            if (turn != null) {
                turn.fail(CANCEL_CLIENT_ABORT, steps.get());
            }
            Disposable subscription = subscriptionRef.get();
            if (subscription != null && !subscription.isDisposed()) {
                log.info("客户端断连，取消模型流，userId={}, sessionId={}", userId, sessionId);
                subscription.dispose();
            }
            interruptQuietly(agentRef.get());
        });

        // 本轮是否只产出了「界面产物」而没有任何文本：用于在收尾时补一句说明，
        // 避免用户只看到卡片、气泡却是空的
        AtomicReference<String> uiFallback = new AtomicReference<>();

        MallAgentContext context = new MallAgentContext(userId, sessionId);
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId(userId == null ? ANONYMOUS_PRINCIPAL : String.valueOf(userId))
                .sessionId(sessionId)
                .build();

        Flux<AgentEvent> events = Flux.defer(() -> {
                    // 建 Agent 涉及创建 workspace 目录，放到弹性线程池，避免阻塞容器线程
                    // 商品卡 / 加购 / 购物车勾选 / 提议确认四类事件共用同一个 sink：
                    // 前端拿到事件后各自走独立接口，不需要再经过 Agent
                    long createStart = System.currentTimeMillis();
                    HarnessAgent agent = agentFactory.create(context,
                            new SseGuideEvents(sink, trace, uiFallback));
                    agentRef.set(agent);
                    log.info("Agent 实例就绪 userId={} sessionId={} costMs={} tools={}",
                            userId, sessionId, System.currentTimeMillis() - createStart, toolNames(agent));
                    return agent.streamEvents(new UserMessage(question), runtimeContext);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(signal -> {
                    closeQuietly(agentRef.get());
                    inFlight.remove(requestKey);
                    log.info("Agent 通道结束 userId={} sessionId={} signal={} steps={} elapsedMs={}",
                            userId, sessionId, signal, steps.get(),
                            System.currentTimeMillis() - trace.startedAt());
                });

        subscriptionRef.set(events.subscribe(
                event -> onEvent(sink, event, steps, textSeen, truncated, turn, trace),
                error -> onError(sink, error, userId, sessionId, steps, turn),
                () -> onComplete(sink, steps, truncated, turn, trace, uiFallback)));
    }

    /**
     * 单轮请求的追踪上下文。
     *
     * @param userId      用户 ID，匿名为 null
     * @param sessionId   会话标识
     * @param startedAt   请求开始时间（毫秒）
     * @param firstTokenAt 首个正文增量的时间，0 表示未到达
     * @param textChars   已下发的正文长度
     */
    private record TurnTrace(Long userId, String sessionId, long startedAt,
                             AtomicLong firstTokenAt, AtomicInteger textChars) {
    }

    /**
     * 问题预览：去掉换行并截断，避免把整段 prompt 打进日志。
     *
     * @param question 原始问题
     * @return 不超过 40 字的单行预览
     */
    private static String preview(String question) {
        if (question == null) {
            return "";
        }
        String flat = question.replaceAll("\\s+", " ").trim();
        return flat.length() <= 40 ? flat : flat.substring(0, 40) + "…";
    }

    /**
     * 当前请求注册的工具名，用于确认「这一轮模型到底能看见什么工具」。
     *
     * @param agent Agent 实例
     * @return 工具名集合；取不到时返回空集合
     */
    private static java.util.Set<String> toolNames(HarnessAgent agent) {
        try {
            return agent.getDelegate().getToolkit().getToolNames();
        } catch (RuntimeException e) {
            return java.util.Set.of();
        }
    }

    /**
     * 工具事件到 SSE 事件的转换器。
     * <p>
     * 只做「领域对象 → 前端载荷」的字段裁剪与命名转换，不做筛选：是否该推由工具自己决定。
     * 金额统一用 {@code toPlainString()} 下发，避免 JSON 数字在 JS 里丢精度或被格式化成科学计数法。
     * 所有推送都吞掉异常——UI 事件失败不能影响模型的下一轮推理。
     */
    private final class SseGuideEvents implements GuideUiEvents {

        private final SseSink sink;

        /** 本轮追踪上下文，只用于日志里的用户/会话关联。 */
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
        public void onProposal(ProposalVO proposal) {
            if (proposal != null) {
                log.info("下发下单提议 userId={} sessionId={} proposalNo={} status={} actualPrice={} itemCount={} expireAt={}",
                        trace.userId(), trace.sessionId(), proposal.proposalNo(), proposal.status(),
                        proposal.actualPrice() == null ? "" : proposal.actualPrice().toPlainString(),
                        proposal.items() == null ? 0 : proposal.items().size(), proposal.expireAt());
            }
            AgentChatHandler.this.onProposal(sink, proposal);
        }

        /**
         * 发送事件，异常只记 debug：连接已关闭属于正常现象（用户切页、点停止）。
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

    /**
     * 提议生成回调：下发 confirm_action 事件。
     * <p>
     * 只下发确认决策需要的字段（编号、金额、过期时间、商品行数），
     * 不下发地址、手机号与内部主键；确认本身走独立接口，由用户显式触发。
     *
     * @param sink     SSE 出口
     * @param proposal 提议视图
     */
    private void onProposal(SseSink sink, ProposalVO proposal) {
        if (proposal == null) {
            return;
        }
        sink.send(AiEvent.of(AiEventType.CONFIRM_ACTION, Map.of(
                "proposalNo", proposal.proposalNo() == null ? "" : proposal.proposalNo(),
                "actionType", proposal.actionType() == null ? "" : proposal.actionType(),
                "actualPrice", proposal.actualPrice() == null ? "" : proposal.actualPrice().toPlainString(),
                "itemCount", proposal.items() == null ? 0 : proposal.items().size(),
                "expireAt", proposal.expireAt() == null ? "" : proposal.expireAt(),
                "confirmPath", "/ai/action/confirm/" + (proposal.proposalNo() == null ? "" : proposal.proposalNo()),
                "message", proposal.message() == null ? "需要你确认后才会创建订单" : proposal.message())));
    }

    /**
     * 单个事件处理：白名单映射 + 特殊事件兜底。
     *
     * @param sink      SSE 出口
     * @param event     AgentScope 事件
     * @param steps     工具调用步数计数器
     * @param textSeen  是否已下发过文本增量
     * @param truncated 是否因超出步数上限被截断
     * @param turn      本轮历史记录器，可为 null
     * @param trace     本轮追踪上下文
     */
    private void onEvent(SseSink sink,
                         AgentEvent event,
                         AtomicInteger steps,
                         AtomicBoolean textSeen,
                         AtomicBoolean truncated,
                         AiChatHistory.Turn turn,
                         TurnTrace trace) {
        if (AgentEventMapper.isToolCall(event)) {
            int step = steps.incrementAndGet();
            // 工具名是排障的第一现场：第几步、调了什么、是本轮第几次
            log.info("工具调用开始 userId={} sessionId={} step={} tool={}",
                    trace.userId(), trace.sessionId(), step, toolNameOf(event));
        }
        // 历史里只登记工具名，不登记入参与结果：入参可能带地址/订单信息
        if (turn != null && event instanceof ToolCallStartEvent toolStart) {
            turn.tool(toolStart.getToolCallName());
        }
        if (event instanceof ExceedMaxItersEvent) {
            truncated.set(true);
            log.warn("Agent 达到最大步数上限 userId={} sessionId={} maxSteps={}",
                    trace.userId(), trace.sessionId(), properties.getMaxSteps());
            return;
        }
        // 本模块注册的工具里只有加购与提议会写库，且都不创建订单；
        // 出现「需要用户确认 / 外部执行」说明工具链被改坏，按错误上报而不是硬编码确认流程。
        if (event instanceof RequireUserConfirmEvent || event instanceof RequireExternalExecutionEvent) {
            log.error("Agent 出现未预期的写操作确认请求，工具注册可能被破坏 userId={} sessionId={}",
                    trace.userId(), trace.sessionId());
            sink.send(AiEvent.of(AiEventType.ERROR, Map.of(
                    "code", "AI_UNEXPECTED_WRITE",
                    "message", "当前不支持该操作，请通过商品页正常下单")));
            return;
        }
        // 兜底：模型未产生流式增量时，用最终结果补一段文本，避免前端空白
        if (event instanceof AgentResultEvent resultEvent && !textSeen.get()) {
            String text = resultEvent.getResult() == null ? null : resultEvent.getResult().getTextContent();
            if (text != null && !text.isBlank()) {
                textSeen.set(true);
                trace.firstTokenAt().compareAndSet(0L, System.currentTimeMillis());
                log.info("模型未产生流式增量，改用最终结果补发 userId={} sessionId={} textLength={}",
                        trace.userId(), trace.sessionId(), text.length());
                trace.textChars().addAndGet(text.length());
                if (turn != null) {
                    turn.delta(text);
                }
                sink.send(AiEvent.of(AiEventType.MESSAGE_DELTA, Map.of("delta", text)));
            }
            return;
        }
        AgentEventMapper.map(event).ifPresent(mapped -> {
            if (AiEventType.MESSAGE_DELTA.equals(mapped.type())) {
                textSeen.set(true);
                if (mapped.data() instanceof Map<?, ?> data && data.get("delta") instanceof String delta) {
                    trace.textChars().addAndGet(delta.length());
                }
                // 首字延迟是「用户觉得卡不卡」的核心指标，只打第一次
                if (trace.firstTokenAt().compareAndSet(0L, System.currentTimeMillis())) {
                    log.info("首字到达 userId={} sessionId={} firstTokenMs={}",
                            trace.userId(), trace.sessionId(),
                            trace.firstTokenAt().get() - trace.startedAt());
                }
                if (turn != null && mapped.data() instanceof Map<?, ?> data
                        && data.get("delta") instanceof String delta) {
                    turn.delta(delta);
                }
            } else if (mapped.data() instanceof Map<?, ?> data) {
                // 过程态与工具结果逐条落日志：正文增量不打（几十条会淹没链路），
                // 其余事件才是「流转到哪一步」的关键
                log.info("下发事件 type={} userId={} sessionId={} tool={} message={}",
                        mapped.type(), trace.userId(), trace.sessionId(),
                        data.get("tool"), data.get("message"));
            }
            sink.send(mapped);
        });
    }

    /**
     * 从工具调用事件取工具名，取不到时返回 unknown，仅用于日志。
     *
     * @param event AgentScope 事件
     * @return 工具名
     */
    private static String toolNameOf(AgentEvent event) {
        if (event instanceof ToolCallStartEvent start && start.getToolCallName() != null) {
            return start.getToolCallName();
        }
        return "unknown";
    }

    /**
     * 流异常处理：对外只给可读文案，异常细节进日志且不含凭据。
     *
     * @param sink      SSE 出口
     * @param error     异常
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @param steps     已发生的工具调用步数
     * @param turn      本轮历史记录器，可为 null
     */
    private void onError(SseSink sink, Throwable error, Long userId, String sessionId,
                         AtomicInteger steps, AiChatHistory.Turn turn) {
        String code = classify(error);
        log.error("Agent 对话失败，userId={}, sessionId={}, code={}", userId, sessionId, code, error);
        if (turn != null) {
            turn.fail(code, steps.get());
        }
        sink.send(AiEvent.of(AiEventType.ERROR, Map.of(
                "code", code,
                "message", friendlyMessage(code))));
        sink.complete();
    }

    /**
     * 流正常结束：下发 done 事件，携带步数与截断标记。
     *
     * @param sink      SSE 出口
     * @param steps     工具调用步数
     * @param truncated 是否被步数上限截断
     * @param turn      本轮历史记录器，可为 null
     * @param trace     本轮追踪上下文
     */
    private void onComplete(SseSink sink, AtomicInteger steps, AtomicBoolean truncated,
                            AiChatHistory.Turn turn, TurnTrace trace,
                            AtomicReference<String> uiFallback) {
        // 模型只调工具、一句话没说时，正文会是空的：补一句与产物对应的说明。
        // 不补的话前端只能显示一个空气泡，用户以为回答丢了
        String fallback = uiFallback.get();
        if (trace.textChars().get() == 0 && fallback != null) {
            if (turn != null) {
                turn.delta(fallback);
            }
            trace.textChars().addAndGet(fallback.length());
            sink.send(AiEvent.of(AiEventType.MESSAGE_DELTA, Map.of("delta", fallback)));
            log.info("补齐空回答文案 userId={} sessionId={} text={}", trace.userId(), trace.sessionId(), fallback);
        }
        if (turn != null) {
            turn.success(steps.get());
        }
        long firstTokenMs = trace.firstTokenAt().get() == 0L ? -1L : trace.firstTokenAt().get() - trace.startedAt();
        log.info("回答完成 userId={} sessionId={} steps={} firstTokenMs={} elapsedMs={} textChars={} truncated={}",
                trace.userId(), trace.sessionId(), steps.get(), firstTokenMs,
                System.currentTimeMillis() - trace.startedAt(), trace.textChars().get(), truncated.get());
        sink.send(AiEvent.of(AiEventType.DONE, Map.of(
                "steps", steps.get(),
                "truncated", truncated.get())));
        sink.complete();
    }

    /**
     * 组装在途请求键。用户维度参与，保证不同用户不会互相阻塞。
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @return 唯一请求键
     */
    private static String requestKey(Long userId, String sessionId) {
        return (userId == null ? ANONYMOUS_PRINCIPAL : userId) + "|" + sessionId;
    }

    /**
     * 把异常归类为稳定错误码。只做字符串特征判断，不向上抛。
     *
     * @param error 异常
     * @return 错误码
     */
    private static String classify(Throwable error) {
        if (error == null) {
            return "AI_STREAM_ERROR";
        }
        String text = String.valueOf(error.getMessage()).toLowerCase();
        if (text.contains("401") || text.contains("unauthorized")
                || text.contains("authentication") || text.contains("invalid api key")) {
            return "AI_AUTH_FAILED";
        }
        if (text.contains("timeout") || text.contains("timed out")) {
            return "AI_TIMEOUT";
        }
        return "AI_STREAM_ERROR";
    }

    /**
     * 错误码对应的对外文案，不暴露任何内部细节。
     *
     * @param code 错误码
     * @return 中文提示
     */
    private static String friendlyMessage(String code) {
        return switch (code) {
            case "AI_AUTH_FAILED" -> "AI 服务暂时不可用，请稍后再试";
            case "AI_TIMEOUT" -> "回答超时，请稍后再试或换个问法";
            default -> "服务繁忙，请稍后再试";
        };
    }

    /**
     * 中断 Agent，忽略重复中断与已关闭带来的异常。
     *
     * @param agent Agent 实例，可为 null
     */
    private static void interruptQuietly(HarnessAgent agent) {
        if (agent == null) {
            return;
        }
        try {
            agent.interrupt();
        } catch (RuntimeException e) {
            log.debug("中断 Agent 失败（可能已结束）：{}", e.getMessage());
        }
    }

    /**
     * 释放 Agent 资源，幂等。
     *
     * @param agent Agent 实例，可为 null
     */
    private static void closeQuietly(HarnessAgent agent) {
        if (agent == null) {
            return;
        }
        try {
            agent.close();
        } catch (RuntimeException e) {
            log.debug("关闭 Agent 失败：{}", e.getMessage());
        }
    }
}
