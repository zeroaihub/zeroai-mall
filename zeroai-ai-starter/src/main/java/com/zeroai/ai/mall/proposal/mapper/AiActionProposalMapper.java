package com.zeroai.ai.mall.proposal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zeroai.ai.mall.proposal.entity.AiActionProposal;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * AI 写操作提议 Mapper。
 * <p>
 * 状态迁移全部走<b>条件 UPDATE</b>（WHERE 里带 status 与归属），
 * 让数据库而不是应用内存来裁决「谁能确认」：并发双击时只有一个请求受影响行数为 1，
 * 其余拿到 0 后直接走「已确认/确认中」分支，不会重复下单。
 * <p>
 * 为什么不用乐观锁版本号：状态本身就是互斥变量，条件更新已经足够，
 * 加 version 只会多一层读-改-写窗口。
 */
public interface AiActionProposalMapper extends BaseMapper<AiActionProposal> {

    /**
     * 抢占确认权：PENDING → CONFIRMING。
     * <p>
     * 幂等语义：同一提议在并发下只有一个请求返回 1。过期与归属校验都在 WHERE 里完成，
     * 过期或非本人的请求拿不到确认权。
     *
     * @param proposalNo 提议编号
     * @param userId     当前用户 ID
     * @param now        当前时间
     * @return 受影响行数，1 表示抢占成功
     */
    @Update("UPDATE ai_action_proposal SET status = 'CONFIRMING', update_time = #{now} "
            + "WHERE proposal_no = #{proposalNo} AND user_id = #{userId} "
            + "AND status = 'PENDING' AND expire_time > #{now}")
    int claimPending(@Param("proposalNo") String proposalNo,
                     @Param("userId") Long userId,
                     @Param("now") java.time.LocalDateTime now);

    /**
     * 确认成功：CONFIRMING → CONFIRMED，回写订单号。
     *
     * @param id      主键
     * @param orderSn 原下单入口返回的订单号
     * @param now     当前时间
     * @return 受影响行数
     */
    @Update("UPDATE ai_action_proposal SET status = 'CONFIRMED', order_sn = #{orderSn}, "
            + "confirm_time = #{now}, update_time = #{now} "
            + "WHERE id = #{id} AND status = 'CONFIRMING'")
    int markConfirmed(@Param("id") Long id,
                      @Param("orderSn") String orderSn,
                      @Param("now") java.time.LocalDateTime now);

    /**
     * 确认被业务校验拒绝：CONFIRMING → REJECTED，记录原因码。
     * <p>
     * 走 REJECTED 而不是回 PENDING：价格变了/库存没了属于提议已过时，
     * 让用户重试同一条只会再失败一次，必须重新生成提议。
     *
     * @param id       主键
     * @param failCode 原因码
     * @param now      当前时间
     * @return 受影响行数
     */
    @Update("UPDATE ai_action_proposal SET status = 'REJECTED', fail_code = #{failCode}, "
            + "confirm_time = #{now}, update_time = #{now} "
            + "WHERE id = #{id} AND status = 'CONFIRMING'")
    int markRejected(@Param("id") Long id,
                     @Param("failCode") String failCode,
                     @Param("now") java.time.LocalDateTime now);

    /**
     * 确认过程中出现可重试的系统异常：CONFIRMING → PENDING，保留提议允许再次确认。
     * <p>
     * 安全前提：调用点只在「下单入口异常且确认未落单」时使用；
     * 即便极端情况消息已投递，原下单入口还有购物车指纹幂等，重试不会产生第二张订单。
     *
     * @param id       主键
     * @param failCode 原因码（SUBMIT_RETRYABLE）
     * @param now      当前时间
     * @return 受影响行数
     */
    @Update("UPDATE ai_action_proposal SET status = 'PENDING', fail_code = #{failCode}, "
            + "update_time = #{now} "
            + "WHERE id = #{id} AND status = 'CONFIRMING'")
    int revertToPending(@Param("id") Long id,
                        @Param("failCode") String failCode,
                        @Param("now") java.time.LocalDateTime now);

    /**
     * 惰性标记过期：PENDING → EXPIRED。
     * <p>
     * 不引入定时任务：提议量小，且过期判定在确认入口必做，这里只是把状态落盘便于查询与统计。
     *
     * @param proposalNo 提议编号
     * @param userId     当前用户 ID
     * @param now        当前时间
     * @return 受影响行数
     */
    @Update("UPDATE ai_action_proposal SET status = 'EXPIRED', fail_code = 'EXPIRED', "
            + "update_time = #{now} "
            + "WHERE proposal_no = #{proposalNo} AND user_id = #{userId} "
            + "AND status = 'PENDING' AND expire_time <= #{now}")
    int markExpired(@Param("proposalNo") String proposalNo,
                    @Param("userId") Long userId,
                    @Param("now") java.time.LocalDateTime now);

    /**
     * 按提议编号查询，<b>必须带 userId</b>：跨用户查询直接查不到，不靠上层再判断归属。
     *
     * @param proposalNo 提议编号
     * @param userId     当前用户 ID
     * @return 提议；不存在或不属于该用户时返回 null
     */
    @Select("SELECT * FROM ai_action_proposal WHERE proposal_no = #{proposalNo} AND user_id = #{userId}")
    AiActionProposal selectByProposalNo(@Param("proposalNo") String proposalNo,
                                        @Param("userId") Long userId);

    /**
     * 按幂等键查询，用于「同一快照重复生成提议」时复用既有记录。
     *
     * @param userId        当前用户 ID
     * @param idempotencyKey 幂等键
     * @return 提议；不存在时返回 null
     */
    @Select("SELECT * FROM ai_action_proposal WHERE user_id = #{userId} AND idempotency_key = #{idempotencyKey}")
    AiActionProposal selectByIdempotencyKey(@Param("userId") Long userId,
                                            @Param("idempotencyKey") String idempotencyKey);
}
