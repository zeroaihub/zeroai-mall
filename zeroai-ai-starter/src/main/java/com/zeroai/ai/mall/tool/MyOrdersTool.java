package com.zeroai.ai.mall.tool;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zeroai.domain.api.trade.entity.Order;
import com.zeroai.domain.api.trade.response.OrderGoodsVO;
import com.zeroai.domain.api.trade.response.OrderListDataResVO;
import com.zeroai.domain.api.trade.response.OrderListResVO;
import com.zeroai.domain.api.trade.service.IMobileOrderService;
import io.agentscope.core.tool.Tool;

import java.util.List;

/**
 * 只读工具：我的订单。
 * <p>
 * 越权防护的关键设计：userId <b>不作为工具参数</b>，而是在构造本工具实例时由服务端注入，
 * 模型无论如何都拿不到修改它的入口。用户身份来源为入口层的 Sa-Token 上下文
 * （{@code MobileSecurityUtils.getUserId()}），且只允许在入口线程取值。
 * <p>
 * 未登录用户不会注册本工具（见 {@code MallAgentFactory}），因此不存在匿名查询订单的可能。
 */
public class MyOrdersTool {

    /** 单次返回订单数上限。 */
    private static final int MAX_LIMIT = 5;

    /** 展示类型 0：全部订单，与 H5 订单列表默认值一致。 */
    private static final int SHOW_TYPE_ALL = 0;

    private final IMobileOrderService mobileOrderService;

    /** 当前登录用户 ID，服务端注入，模型不可见。 */
    private final Long userId;

    /**
     * @param mobileOrderService 订单服务
     * @param userId             当前登录用户 ID，必须非空
     */
    public MyOrdersTool(IMobileOrderService mobileOrderService, Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空：未登录用户不允许查询订单");
        }
        this.mobileOrderService = mobileOrderService;
        this.userId = userId;
    }

    /**
     * 查询当前登录用户自己的订单。
     *
     * @param limit 期望返回条数，缺省 3，最大 5
     * @return JSON 字符串，仅含当前用户的订单；不存在跨用户数据
     */
    @Tool(name = "list_my_orders",
            description = "查询当前登录用户本人的订单列表，返回订单号、金额、状态与商品名。只读操作，仅能查到自己的订单。",
            readOnly = true,
            concurrencySafe = true)
    public String listMyOrders(Integer limit) {
        int size = limit == null ? 3 : Math.min(Math.max(limit, 1), MAX_LIMIT);

        OrderListResVO result;
        try {
            result = mobileOrderService.selectListPage(new Page<>(1, size), SHOW_TYPE_ALL, userId);
        } catch (RuntimeException e) {
            return "{\"error\":\"ORDER_QUERY_FAILED\",\"hint\":\"订单查询失败，请稍后再试\"}";
        }

        List<OrderListDataResVO> orders = result == null ? null : result.getData();
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"count\":").append(orders == null ? 0 : orders.size()).append(",\"orders\":[");
        if (orders != null) {
            for (int i = 0; i < orders.size(); i++) {
                OrderListDataResVO order = orders.get(i);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"orderSn\":").append(ToolJson.str(order.getOrderSn()))
                        .append(",\"actualPrice\":").append(ToolJson.num(order.getActualPrice()))
                        .append(",\"status\":").append(ToolJson.str(order.getOrderStatusText()))
                        .append(",\"goods\":").append(ToolJson.str(summarizeGoods(order.getGoodsList())))
                        .append('}');
            }
        }
        return sb.append("]}").toString();
    }

    /**
     * 把订单商品列表压缩为「商品名 x 数量」的可读文本。
     *
     * @param goodsList 订单商品列表，可能为 null
     * @return 摘要文本，无商品时返回空串
     */
    private String summarizeGoods(List<OrderGoodsVO> goodsList) {
        if (goodsList == null || goodsList.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < goodsList.size(); i++) {
            OrderGoodsVO goods = goodsList.get(i);
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(goods.getGoodsName()).append(" x ").append(goods.getNumber());
        }
        return sb.toString();
    }
}
