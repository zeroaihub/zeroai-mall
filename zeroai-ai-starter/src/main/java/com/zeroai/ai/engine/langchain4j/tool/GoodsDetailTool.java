package com.zeroai.ai.engine.langchain4j.tool;

import com.zeroai.domain.api.goods.entity.Goods;
import com.zeroai.domain.api.goods.entity.GoodsProduct;
import com.zeroai.domain.api.goods.response.GoodsDetailResponseVO;
import com.zeroai.domain.api.goods.service.IGoodsDetailService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 只读工具：商品详情（LangChain4j 版）。
 * <p>
 * 行为与 {@code com.zeroai.ai.mall.tool.GoodsDetailTool}（AgentScope 版）完全一致：
 * 复用 H5 商品详情页同一条链路，不返回规格全量组合，最多列 5 个 SKU。
 * <b>两边方法体必须同步修改</b>，见 ADR-003。
 */
public class GoodsDetailTool {

    private static final Logger log = LoggerFactory.getLogger(GoodsDetailTool.class);

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
            value = "按商品ID查询商品详情，返回名称、价格、简介、可选规格与库存。只读操作。")
    public String getProductDetail(@P("商品ID，来自 search_products 的返回") Long goodsId) {
        log.info("工具调用 get_product_detail goodsId={}", goodsId);
        if (goodsId == null) {
            return "{\"error\":\"MISSING_GOODS_ID\",\"hint\":\"需要先通过 search_products 拿到 goodsId\"}";
        }

        GoodsDetailResponseVO detail;
        try {
            detail = goodsDetailService.getGoodsDetailData(goodsId);
        } catch (RuntimeException e) {
            log.warn("工具失败 get_product_detail goodsId={} error={}", goodsId, e.getMessage());
            return "{\"error\":\"DETAIL_UNAVAILABLE\",\"hint\":\"商品详情查询失败，请稍后再试\"}";
        }
        if (detail == null || detail.getInfo() == null) {
            log.info("工具结果 get_product_detail goodsId={} found=false", goodsId);
            return "{\"error\":\"GOODS_NOT_FOUND\",\"hint\":\"商品不存在或已下架，请重新搜索\"}";
        }

        Goods info = detail.getInfo();
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"goodsId\":").append(JsonText.num(goodsId))
                .append(",\"name\":").append(JsonText.str(info.getName()))
                .append(",\"retailPrice\":").append(JsonText.num(info.getRetailPrice()))
                .append(",\"counterPrice\":").append(JsonText.num(info.getCounterPrice()))
                .append(",\"brief\":").append(JsonText.str(info.getBrief()))
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
                sb.append("{\"skuId\":").append(JsonText.num(sku.getId()))
                        .append(",\"spec\":").append(JsonText.str(joinSpec(sku.getSpecifications())))
                        .append(",\"price\":").append(JsonText.num(sku.getPrice()))
                        .append(",\"availableStock\":").append(JsonText.num(sku.getNumber()))
                        .append('}');
                emitted++;
            }
        }
        log.info("工具结果 get_product_detail goodsId={} name=\"{}\" onSale={} skus={}",
                goodsId, info.getName(), info.getIsOnSale(), emitted);
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
