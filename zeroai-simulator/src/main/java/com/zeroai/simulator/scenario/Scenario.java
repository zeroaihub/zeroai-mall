package com.zeroai.simulator.scenario;

import com.zeroai.simulator.auth.VirtualUser;

import java.util.List;

/**
 * 一段可重复执行的用户行为。
 * <p>
 * 实现类只负责「按真实顺序调用接口」，并发、限速、指标全部在引擎层统一处理，
 * 这样新增场景不需要关心线程模型和 QPS 控制。
 */
public interface Scenario {

    /**
     * 场景名，用于日志与报告。
     *
     * @return 场景名
     */
    String name();

    /**
     * 执行一轮完整行为。
     * <p>
     * 返回值口径：{@code true} 表示这一轮业务上正常结束——包含「售罄」「加购后离开」这类
     * 预期内的业务性拒绝；{@code false} 只用于系统级失败（网络错误、非 200 业务码、
     * 订单未落库、状态未推进）。这样链路成功率才等于真实故障率。
     *
     * @param user 本轮使用的虚拟用户
     * @return true 表示业务上正常结束
     */
    boolean run(VirtualUser user);

    /**
     * 压测开始前的场景级准备。
     * <p>
     * 秒杀齐射需要提前签发 token（有效期 60 秒），否则爆发瞬间会被 token 签发这一步稀释掉。
     * 普通场景不需要，默认空实现。
     *
     * @param users 参与本轮的虚拟用户
     */
    default void prepare(List<VirtualUser> users) {
        // 默认无需准备
    }
}
