package com.zeroai.ai.web;

import com.zeroai.ai.mall.proposal.ActionProposalService;
import com.zeroai.ai.mall.proposal.vo.ConfirmResVO;
import com.zeroai.ai.mall.proposal.vo.ProposalVO;
import com.zeroai.util.exception.BusinessException;
import com.zeroai.util.util.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
