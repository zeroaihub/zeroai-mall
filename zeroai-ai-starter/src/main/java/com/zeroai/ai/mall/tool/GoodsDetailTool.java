package com.zeroai.ai.mall.tool;

import com.zeroai.domain.api.goods.entity.Goods;
import com.zeroai.domain.api.goods.entity.GoodsProduct;
import com.zeroai.domain.api.goods.response.GoodsDetailResponseVO;
import com.zeroai.domain.api.goods.service.IGoodsDetailService;
import io.agentscope.core.tool.Tool;

import java.util.List;

/**
 * 只读工具：商品详情。
 * <p>
 * 复用 H5 商品详情页同一条链路 {@link IGoodsDetailService#getGoodsDetailData(Long)}，
 * 保证 AI 通道与页面展示一致。不返回规格全量组合，只给模型做推荐所需的信息。
 */
public class GoodsDetailTool {

    /** 最多列出的 SKU 数，避免长尾规格占满上下文。 */
    private static final int MAX_SKU = 5;

    private final IGoodsDetailService goodsDetailService;

    /**
     * @param goodsDetailService 商品详情服务
     */
    public GoodsDetailTool(IGoodsDetailService goodsDetailService) {
        this.goodsDetailService = goodsDetailService;
    }

    /**
     * 查询单个商品详情。
     *
     * @param goodsId 商品 ID，来自 search_products 的返回
     * @return JSON 字符串，含名称、价格、简介与 SKU 库存概要
     */
    @Tool(name = "get_product_detail",
            description = "按商品ID查询商品详情，返回名称、价格、简介、可选规格与库存。只读操作。",
            readOnly = true,
            concurrencySafe = true)
    public String getProductDetail(Long goodsId) {
        if (goodsId == null) {
            return "{\"error\":\"MISSING_GOODS_ID\",\"hint\":\"需要先通过 search_products 拿到 goodsId\"}";
        }

        GoodsDetailResponseVO detail;
        try {
            detail = goodsDetailService.getGoodsDetailData(goodsId);
        } catch (RuntimeException e) {
            return "{\"error\":\"DETAIL_UNAVAILABLE\",\"hint\":\"商品详情查询失败，请稍后再试\"}";
        }
        if (detail == null || detail.getInfo() == null) {
            return "{\"error\":\"GOODS_NOT_FOUND\",\"hint\":\"商品不存在或已下架，请重新搜索\"}";
        }

        Goods info = detail.getInfo();
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"goodsId\":").append(ToolJson.num(goodsId))
                .append(",\"name\":").append(ToolJson.str(info.getName()))
                .append(",\"retailPrice\":").append(ToolJson.num(info.getRetailPrice()))
                .append(",\"counterPrice\":").append(ToolJson.num(info.getCounterPrice()))
                .append(",\"brief\":").append(ToolJson.str(info.getBrief()))
                .append(",\"onSale\":").append(info.getIsOnSale())
                .append(",\"skus\":[");

        List<GoodsProduct> skus = detail.getProductList();
        int emitted = 0;
        if (skus != null) {
            for (GoodsProduct sku : skus) {
                if (emitted >= MAX_SKU) {
                    break;
                }
                if (emitted > 0) {
                    sb.append(',');
                }
                sb.append("{\"skuId\":").append(ToolJson.num(sku.getId()))
                        .append(",\"spec\":").append(ToolJson.str(joinSpec(sku.getSpecifications())))
                        .append(",\"price\":").append(ToolJson.num(sku.getPrice()))
                        .append(",\"availableStock\":").append(ToolJson.num(sku.getNumber()))
                        .append('}');
                emitted++;
            }
        }
        return sb.append("]}").toString();
    }

    /**
     * 拼接规格数组为可读文本。
     *
     * @param specs 规格值数组，可能为 null
     * @return 以斜杠连接的规格文本，无规格时返回空串
     */
    private String joinSpec(String[] specs) {
        return specs == null ? "" : String.join("/", specs);
    }
}
