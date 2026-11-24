package com.zeroai.ai.engine.langchain4j.tool;

import com.zeroai.domain.api.goods.entity.GoodsProduct;
import com.zeroai.domain.api.goods.service.IGoodsProductService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 只读工具：SKU 库存查询（LangChain4j 版）。
 * <p>
 * 行为与 {@code com.zeroai.ai.mall.tool.StockStatusTool}（AgentScope 版）完全一致：
 * 只调用 {@link IGoodsProductService#getById} 做单条读取，
 * <b>不调用</b> freezeStock / releaseFrozenStock 等任何写方法。
 * <b>两边方法体必须同步修改</b>，见 ADR-003。
 */
public class StockStatusTool {

    private static final Logger log = LoggerFactory.getLogger(StockStatusTool.class);

    private final IGoodsProductService goodsProductService;

    /**
     * @param goodsProductService 商品货品服务，仅使用其只读方法
     */
    public StockStatusTool(IGoodsProductService goodsProductService) {
        this.goodsProductService = goodsProductService;
    }

    /**
     * 查询指定 SKU 的可售与冻结库存。
     *
     * @param skuId 商品货品 ID，来自 get_product_detail 的 skus 列表
     * @return JSON 字符串，含可售库存与冻结库存；库存为 0 时提示缺货
     */
    @Tool(name = "get_stock_status",
            value = "按SKU查询可售库存与冻结库存。只读操作，绝对不能修改库存。")
    public String getStockStatus(@P("商品货品ID，来自 get_product_detail 的 skus 列表") Long skuId) {
        log.info("工具调用 get_stock_status skuId={}", skuId);
        if (skuId == null) {
            return "{\"error\":\"MISSING_SKU_ID\",\"hint\":\"需要先通过 get_product_detail 拿到 skuId\"}";
        }

        GoodsProduct sku;
        try {
            sku = goodsProductService.getById(skuId);
        } catch (RuntimeException e) {
            log.warn("工具失败 get_stock_status skuId={} error={}", skuId, e.getMessage());
            return "{\"error\":\"STOCK_UNAVAILABLE\",\"hint\":\"库存查询失败，请稍后再试\"}";
        }
        if (sku == null) {
            log.info("工具结果 get_stock_status skuId={} found=false", skuId);
            return "{\"error\":\"SKU_NOT_FOUND\",\"hint\":\"该规格不存在，请确认规格后再查\"}";
        }

        Integer available = sku.getNumber();
        Integer locked = sku.getLockedStock();
        boolean inStock = available != null && available > 0;
        log.info("工具结果 get_stock_status skuId={} available={} locked={} inStock={}",
                skuId, available, locked, inStock);

        return "{\"skuId\":" + JsonText.num(skuId)
                + ",\"availableStock\":" + JsonText.num(available)
                + ",\"lockedStock\":" + JsonText.num(locked)
                + ",\"inStock\":" + inStock
                + ",\"hint\":" + JsonText.str(inStock ? "有货，可正常下单" : "当前规格缺货")
                + "}";
    }
}
