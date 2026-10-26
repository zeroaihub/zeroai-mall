package com.zeroai.ai.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 发送封装，供单轮问答通道与 Agent 通道共用。
 * <p>
 * 职责边界：
 * <ul>
 *   <li>屏蔽「连接已关闭」的重复判断，让调用方专注于业务事件语义。</li>
 *   <li>把「连接结束」统一收敛为一次关闭回调，供上层释放资源（如取消模型订阅）。</li>
 * </ul>
 * 线程安全：本类可被 Reactor 回调线程与容器线程同时访问，内部状态全部为原子或并发容器。
 * <p>
 * 关闭语义说明：正常完成、超时、异常、客户端断连都会触发关闭回调，
 * 触发顺序为先标记关闭、再依次执行监听器。监听器必须幂等，因为同一连接只触发一次标记，
 * 但监听器注册晚于关闭时会被立即执行，可能出现与首轮回调并发的场景。
 */
public final class SseSink {

    private static final Logger log = LoggerFactory.getLogger(SseSink.class);

    private final SseEmitter emitter;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final List<Runnable> closeListeners = new CopyOnWriteArrayList<>();

    /**
     * @param emitter 容器提供的 SSE 连接，不可为 null
     */
    public SseSink(SseEmitter emitter) {
        this.emitter = emitter;
        emitter.onCompletion(this::markClosed);
        emitter.onError(e -> {
            log.debug("SSE 连接异常：{}", e.getMessage());
            markClosed();
        });
        emitter.onTimeout(() -> {
            log.debug("SSE 连接超时，服务端主动结束");
            markClosed();
            try {
                emitter.complete();
            } catch (IllegalStateException e) {
                // 超时与完成竞争，连接可能已结束，无需处理
                log.debug("SSE 超时后连接已结束：{}", e.getMessage());
            }
        });
    }

    /**
     * 取出底层连接，供控制器作为返回值交给容器。
     *
     * @return SSE 连接
     */
    public SseEmitter emitter() {
        return emitter;
    }

    /**
     * 连接是否已关闭。关闭后所有发送都会静默丢弃。
     *
     * @return 已关闭返回 true
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 注册连接关闭回调。连接已关闭时立即执行，保证不丢通知。
     * <p>
     * 典型用途：客户端断连后取消模型流订阅，避免继续消耗 token。
     *
     * @param listener 幂等的清理动作
     */
    public void onClose(Runnable listener) {
        closeListeners.add(listener);
        if (closed.get()) {
            runQuietly(listener);
        }
    }

    /**
     * 发送单个事件。连接已关闭或发送失败时静默返回，不向调用方抛异常。
     *
     * @param event 事件载荷
     */
    public void send(AiEvent event) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event.type()).data(event, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 发送失败，连接可能已关闭：{}", e.getMessage());
            markClosed();
        }
    }

    /**
     * 结束连接。重复调用安全，防止超时与正常完成竞争导致报错。
     */
    public void complete() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            emitter.complete();
        } catch (IllegalStateException e) {
            log.debug("SSE 连接已结束：{}", e.getMessage());
        }
        closeListeners.forEach(SseSink::runQuietly);
    }

    /**
     * 标记关闭并通知监听器。用于容器侧回调（完成 / 异常 / 超时）。
     */
    private void markClosed() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeListeners.forEach(SseSink::runQuietly);
    }

    /**
     * 执行清理动作，单条失败不影响其余监听器。
     *
     * @param listener 清理动作
     */
    private static void runQuietly(Runnable listener) {
        try {
            listener.run();
        } catch (RuntimeException e) {
            log.warn("SSE 关闭回调执行失败：{}", e.getMessage());
        }
    }
}
