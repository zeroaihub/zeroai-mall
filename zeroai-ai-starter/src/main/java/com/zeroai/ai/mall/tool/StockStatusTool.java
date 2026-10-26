package com.zeroai.ai.mall.tool;

import com.zeroai.domain.api.goods.entity.GoodsProduct;
import com.zeroai.domain.api.goods.service.IGoodsProductService;
import io.agentscope.core.tool.Tool;

/**
 * 只读工具：SKU 库存查询。
 * <p>
 * 安全边界：本类只调用 {@link IGoodsProductService#getById} 做单条读取，
 * <b>不调用</b> freezeStock / releaseFrozenStock / reduceStock / addStock 等任何写方法。
 * 注入这个服务的唯一原因是要按 skuId 定位库存，H5 侧同字段来自同一实体。
 * <p>
 * 若后续需要更严格的隔离，可改为只依赖 {@code IGoodsDetailService}，
 * 代价是无法按 skuId 精确查询。
 */
public class StockStatusTool {

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
            description = "按SKU查询可售库存与冻结库存。只读操作，绝对不能修改库存。",
            readOnly = true,
            concurrencySafe = true)
    public String getStockStatus(Long skuId) {
        if (skuId == null) {
            return "{\"error\":\"MISSING_SKU_ID\",\"hint\":\"需要先通过 get_product_detail 拿到 skuId\"}";
        }

        GoodsProduct sku;
        try {
            sku = goodsProductService.getById(skuId);
        } catch (RuntimeException e) {
            return "{\"error\":\"STOCK_UNAVAILABLE\",\"hint\":\"库存查询失败，请稍后再试\"}";
        }
        if (sku == null) {
            return "{\"error\":\"SKU_NOT_FOUND\",\"hint\":\"该规格不存在，请确认规格后再查\"}";
        }

        Integer available = sku.getNumber();
        Integer locked = sku.getLockedStock();
        boolean inStock = available != null && available > 0;

        return "{\"skuId\":" + ToolJson.num(skuId)
                + ",\"availableStock\":" + ToolJson.num(available)
                + ",\"lockedStock\":" + ToolJson.num(locked)
                + ",\"inStock\":" + inStock
                + ",\"hint\":" + ToolJson.str(inStock ? "有货，可正常下单" : "当前规格缺货")
                + "}";
    }
}
