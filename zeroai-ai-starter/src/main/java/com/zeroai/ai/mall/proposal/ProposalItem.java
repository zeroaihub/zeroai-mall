package com.zeroai.ai.mall.proposal;

import java.math.BigDecimal;

/**
 * 提议内的一条商品快照。
 * <p>
 * 快照在提议创建时固化，确认时必须逐项与购物车实时数据比对——
 * 价格、数量任一变化都视为「提议已过时」，拒绝确认而不是默默按新价下单。
 * <p>
 * 序列化格式为一行一条、字段以制表符分隔、商品名放在末尾可含制表符以外任意字符：
 * {@code productId \t goodsId \t unitPrice \t number \t goodsName}
 * 刻意不用 JSON：本模块同时存在 Jackson 2/3 两条版本线，持久化格式越简单越好。
 *
 * @param productId  商品货品（SKU）ID
 * @param goodsId    商品 ID
 * @param unitPrice  下单时的单价快照
 * @param number     数量
 * @param goodsName  商品名快照，仅用于展示与确认反馈
 */
public record ProposalItem(Long productId, Long goodsId, BigDecimal unitPrice, Integer number, String goodsName) {

    /** 单行最大长度保护，避免异常数据撑爆快照列。 */
    static final int MAX_LINE_LENGTH = 512;

    /**
     * 序列化为快照行。商品名中的换行会被替换，保证行边界稳定。
     *
     * @return 制表符分隔的一行
     */
    public String toLine() {
        String name = goodsName == null ? "" : goodsName.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        return productId + "\t" + goodsId + "\t" + unitPrice.toPlainString() + "\t" + number + "\t" + name;
    }

    /**
     * 从快照行反序列化。
     *
     * @param line 快照行
     * @return 商品快照；格式非法时抛出 {@link IllegalArgumentException}
     */
    public static ProposalItem fromLine(String line) {
        if (line == null || line.isBlank()) {
            throw new IllegalArgumentException("快照行为空");
        }
        if (line.length() > MAX_LINE_LENGTH) {
            throw new IllegalArgumentException("快照行超长");
        }
        String[] parts = line.split("\t", 5);
        if (parts.length < 4) {
            throw new IllegalArgumentException("快照行字段缺失");
        }
        try {
            Long productId = Long.valueOf(parts[0].trim());
            Long goodsId = Long.valueOf(parts[1].trim());
            BigDecimal unitPrice = new BigDecimal(parts[2].trim());
            Integer number = Integer.valueOf(parts[3].trim());
            String goodsName = parts.length > 4 ? parts[4] : "";
            return new ProposalItem(productId, goodsId, unitPrice, number, goodsName);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("快照行数值非法", e);
        }
    }
}
