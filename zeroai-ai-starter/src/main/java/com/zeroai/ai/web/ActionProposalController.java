package com.zeroai.ai.web;

import com.zeroai.ai.mall.proposal.ActionProposalService;
import com.zeroai.ai.mall.proposal.vo.ConfirmResVO;
import com.zeroai.ai.mall.proposal.vo.CreateProposalReqVO;
import com.zeroai.ai.mall.proposal.vo.ProposalVO;
import com.zeroai.util.exception.BusinessException;
import com.zeroai.util.util.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 写操作提议确认入口。
 * <p>
 * <b>这是唯一能触发订单写入的接口</b>，且必须由用户显式调用。
 * Agent 侧只能生成提议（见 {@code ProposeOrderTool}），拿不到这个入口的执行权。
 * <p>
 * 鉴权：与 {@code /ai/chat} 一致，由宿主的 Sa-Token 拦截器统一校验登录；
 * 本类只从 {@link AiUserContext} 取 userId，不接受请求参数里的用户标识。
 * 与 AiChatController 相同的开关条件：{@code zeroai.ai.enabled=true} 且提议链路开启，
 * 否则整个控制器不注册，不存在「忘记关就开着的写接口」。
 */
@RestController
@RequestMapping("ai/action")
@ConditionalOnExpression("${zeroai.ai.enabled:false} && ${zeroai.ai.action.enabled:false}")
public class ActionProposalController {

    private static final Logger log = LoggerFactory.getLogger(ActionProposalController.class);

    private final ActionProposalService proposalService;
    private final AiUserContext userContext;

    /**
     * @param proposalService 提议服务
     * @param userContext     用户上下文，由宿主实现
     */
    public ActionProposalController(ActionProposalService proposalService, AiUserContext userContext) {
        this.proposalService = proposalService;
        this.userContext = userContext;
    }

    /**
     * 按用户在对话内勾选的商品生成下单提议。
     * <p>
     * 为什么单独开一个接口而不是让模型再调一次工具：勾选是用户在页面上的确定性动作，
     * 生成结果应当立刻返回给页面（金额、商品清单、有效期），不该再绕一圈模型，
     * 否则用户勾完还要等一次模型往返，且模型可能改写商品清单。
     * <p>
     * 本接口<b>不创建订单</b>：只落一条待确认提议，确认仍走 {@code /confirm/{proposalNo}}。
     *
     * @param reqVO 勾选结果与收货地址（必填）、可选优惠券
     * @return 提议视图
     */
    @PostMapping("proposal")
    public R<ProposalVO> createProposal(@RequestBody CreateProposalReqVO reqVO) {
        Long userId = userContext.currentUserId();
        List<Long> cartIds = reqVO == null || reqVO.cartIds() == null ? List.of() : reqVO.cartIds();
        log.info("按勾选生成下单提议开始, userId={}, addressId={}, couponId={}, cartCount={}",
                userId, reqVO == null ? null : reqVO.addressId(),
                reqVO == null ? null : reqVO.userCouponId(), cartIds.size());
        if (cartIds.isEmpty()) {
            return R.error(400, "请先勾选要下单的商品");
        }
        // 地址必须由用户在弹层里选过：空值直接拒绝，不退回默认地址，
        // 否则「确认地址」这一步在服务端等于不存在
        if (reqVO.addressId() == null) {
            log.info("按勾选生成下单提议缺少地址, userId={}", userId);
            return R.error(400, "请先选择收货地址");
        }
        try {
            ProposalVO vo = proposalService.createProposalForSelection(
                    userId, reqVO.addressId(), reqVO.userCouponId(), cartIds);
            log.info("按勾选生成下单提议完成, userId={}, proposalNo={}, actualPrice={}",
                    userId, vo.proposalNo(), vo.actualPrice());
            return R.success(vo);
        } catch (BusinessException e) {
            log.info("按勾选生成下单提议被拒, userId={}, msg={}", userId, e.getMsg());
            return R.error(e.getCode() == null ? 500 : e.getCode(), e.getMsg());
        } catch (IllegalStateException e) {
            // 未登录等前置条件不满足：文案本身可读，直接下发
            return R.error(401, e.getMessage());
        }
    }

    /**
     * 确认下单提议。
     * <p>
     * 幂等：同一提议重复确认只会创建一次订单，其余请求返回「已确认」并带上既有订单号。
     * 副作用：确认成功时走原下单入口创建订单；失败时不产生任何订单。
     *
     * @param proposalNo 对外提议编号
     * @return 确认结果
     */
    @PostMapping("confirm/{proposalNo}")
    public R<ConfirmResVO> confirm(@PathVariable String proposalNo) {
        // 入口线程取用户快照：确认过程不经过异步回调，但仍统一走 AiUserContext，禁止从参数取
        Long userId = userContext.currentUserId();
        log.info("确认下单提议开始, userId={}, proposalNo={}", userId, proposalNo);
        try {
            ConfirmResVO resVO = proposalService.confirm(userId, proposalNo);
            log.info("确认下单提议完成, userId={}, proposalNo={}, ok={}, orderSn={}",
                    userId, proposalNo, resVO.ok(), resVO.orderSn());
            return R.success(resVO);
        } catch (BusinessException e) {
            log.info("确认下单提议被拒, userId={}, proposalNo={}, msg={}", userId, proposalNo, e.getMsg());
            return R.error(e.getCode() == null ? 500 : e.getCode(), e.getMsg());
        }
    }

    /**
     * 查询提议状态。
     * <p>
     * 跨用户查询返回「不存在」，不暴露他人提议是否存在。
     *
     * @param proposalNo 对外提议编号
     * @return 提议视图
     */
    @GetMapping("{proposalNo}")
    public R<ProposalVO> detail(@PathVariable String proposalNo) {
        Long userId = userContext.currentUserId();
        log.info("查询下单提议开始, userId={}, proposalNo={}", userId, proposalNo);
        try {
            ProposalVO vo = proposalService.get(userId, proposalNo);
            return R.success(vo);
        } catch (BusinessException e) {
            return R.error(e.getCode() == null ? 500 : e.getCode(), e.getMsg());
        }
    }
}
