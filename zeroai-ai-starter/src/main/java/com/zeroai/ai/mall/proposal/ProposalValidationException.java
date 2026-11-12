package com.zeroai.ai.mall.proposal;

/**
 * 提议阶段的服务端校验失败。
 * <p>
 * 为什么单独一个异常类型：校验失败要区分「用户该怎么做」——未勾选要去勾选，
 * 商品消失要重新选，地址无效要重选。如果统一抛 {@code BusinessException}，
 * 工具层只能回一句笼统的「已拒绝」，模型就会用换关键词、改参数的方式反复重试，
 * 既费 token 又可能落到别的错误分支上。
 * <p>
 * 本异常只携带对外安全的文案：{@link #getCode()} 是给模型看的稳定错误码，
 * {@link #getDetail()} 是给模型纠正参数用的明细（如「未勾选：iPhone 保护壳」），
 * 两者都不包含堆栈、SQL 或内部主键。
 */
public class ProposalValidationException extends RuntimeException {

    /** 对外的稳定原因码。 */
    private final ProposalRejectCode code;

    /** 供模型纠正参数的明细，可为空串。 */
    private final String detail;

    /**
     * @param code 原因码
     */
    public ProposalValidationException(ProposalRejectCode code) {
        this(code, "");
    }

    /**
     * @param code   原因码
     * @param detail 明细，直接回显给模型，不得包含敏感信息
     */
    public ProposalValidationException(ProposalRejectCode code, String detail) {
        super(code == null ? "下单提议校验失败" : code.getMessage());
        this.code = code == null ? ProposalRejectCode.CART_CHANGED : code;
        this.detail = detail == null ? "" : detail;
    }

    /**
     * @return 原因码
     */
    public ProposalRejectCode getCode() {
        return code;
    }

    /**
     * @return 明细，可能为空串
     */
    public String getDetail() {
        return detail;
    }
}
