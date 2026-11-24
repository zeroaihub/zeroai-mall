package com.zeroai.ai.engine;

/**
 * 单轮流式对话引擎端口。
 * <p>
 * <b>为什么需要这层</b>：Spring AI 与 LangChain4j 是两套完全独立的 SDK，
 * 若控制器直接依赖 {@code ChatClient}，换引擎就要改控制器，
 * 而且 SDK 类型会顺着控制器扩散到整个模块，两套 SDK 迟早揉在一起。
 * 这里只保留「一次问答、逐段回调」这一最小契约，SDK 差异全部关在各自适配器里。
 * <p>
 * <b>隔离约束</b>（新增实现时必须遵守）：
 * <ul>
 *   <li>本包及子类型不得出现任何第三方 SDK 类型，只用 JDK 类型；</li>
 *   <li>每个适配器只能依赖自己那一套 SDK，Spring AI 与 LangChain4j 适配器互不引用；</li>
 *   <li>适配器之间的公共逻辑上提到本包，不要在适配器里互相复用。</li>
 * </ul>
 * 实现应无状态或线程安全：同一个实例会被多个请求并发调用。
 */
public interface ChatStreamEngine {

    /**
     * 引擎标识，取值与 {@code zeroai.ai.engine} 一致（见 {@link ChatEngineNames}）。
     *
     * @return 引擎名，小写短横线风格
     */
    String name();

    /**
     * 发起一次流式问答。
     * <p>
     * 方法不阻塞，立即返回取消句柄；增量、结束、异常通过 {@link ChatStreamListener} 回调，
     * 回调由引擎内部线程触发，调用方必须自行保证线程安全。
     * 取消后不再有任何回调；正常结束或异常终止后也不会再有回调。
     *
     * @param question 用户问题，调用方需保证非空
     * @param listener 回调，不可为 null
     * @return 取消句柄
     */
    ChatStreamHandle stream(String question, ChatStreamListener listener);
}
