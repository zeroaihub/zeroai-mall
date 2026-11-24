package com.zeroai.ai.engine;

/**
 * 流式问答回调。
 * <p>
 * 三个回调互斥且至多触发一次终止：{@link #onError(Throwable)} 与 {@link #onComplete()}
 * 只在其中之一出现，之后不会再有任何回调。增量只在终止之前出现。
 * 实现由 Controller 提供，必须线程安全：LangChain4j 的回调线程与 Spring AI 的 Reactor 线程不同，
 * 但都允许连续快速触发。
 */
public interface ChatStreamListener {

    /**
     * 收到一段文本增量。
     *
     * @param delta 非空文本片段，可能包含换行
     */
    void onDelta(String delta);

    /**
     * 生成失败。
     *
     * @param error 原始异常，调用方负责转成用户可读文案，不要把堆栈透给前端
     */
    void onError(Throwable error);

    /**
     * 生成正常结束。
     */
    void onComplete();
}
