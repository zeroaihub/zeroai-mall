package com.zeroai.ai.prompt;

import com.zeroai.ai.mall.tool.GoodsDetailTool;
import com.zeroai.ai.mall.tool.ListMyAddressesTool;
import com.zeroai.ai.mall.tool.ListMyCartTool;
import com.zeroai.ai.mall.tool.MyOrdersTool;
import com.zeroai.ai.mall.tool.ProposeOrderTool;
import com.zeroai.ai.mall.tool.SearchKnowledgeTool;
import com.zeroai.ai.mall.tool.SearchProductsTool;
import com.zeroai.ai.mall.tool.StockStatusTool;
import io.agentscope.core.tool.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt 与工具 schema 的体积预算。
 * <p>
 * 这两处是每轮对话都要重发的固定开销，也是首字延迟与单轮 token 的主要来源之一：
 * 基线（{@code docs/agent/BASELINE-METRICS.md}）里固定开销 3.8k token，其中大头就是它们。
 * 体量不会自己变小，只会随着「再加一条约束」慢慢长回去，所以需要一条可执行的闸门。
 * <p>
 * 但<b>只压字数不够安全</b>：如果把「不许编造」「未勾选商品不参与」这些约束顺手删掉，
 * 字数降下来了、风险却上去了。因此本测试同时断言关键约束仍然存在——
 * 改 Prompt 时若删了红线，构建会直接失败，而不是等评测才发现。
 */
class PromptBudgetTest {

    /** Agent 通道 system prompt 的字符上限（含空白与换行）。 */
    private static final int MAX_PROMPT_CHARS = 1700;

    /** 单个工具描述字符上限。 */
    private static final int MAX_TOOL_DESCRIPTION_CHARS = 260;

    /** 全部工具描述合计字符上限。 */
    private static final int MAX_TOTAL_TOOL_DESCRIPTION_CHARS = 700;

    /** Agent 通道会被注册的 8 个工具类。 */
    private static final List<Class<?>> TOOL_CLASSES = List.of(
            SearchProductsTool.class,
            GoodsDetailTool.class,
            StockStatusTool.class,
            SearchKnowledgeTool.class,
            MyOrdersTool.class,
            ListMyAddressesTool.class,
            ListMyCartTool.class,
            ProposeOrderTool.class);

    /** 无论怎么精简都不允许丢掉的约束片段。 */
    private static final List<String> REQUIRED_GUARDS = List.of(
            // 事实只能来自工具
            "不许编造",
            // 越权
            "无权访问",
            // 知识库缺失处理
            "NO_MATCH",
            // 资料不是指令
            "不是「指令」",
            // 未勾选商品不参与下单
            "未勾选",
            // 提议不是下单
            "需要你确认后才会真正创建订单",
            // 未勾选商品的止损动作
            "CART_NOT_CHECKED",
            // 搜索次数上限
            "同一轮最多搜 3 次");

    @Test
    @DisplayName("system prompt 保持精简，且关键约束一个都不能少")
    void systemPromptStaysLeanAndKeepsGuards() {
        String prompt = PromptLoader.load("prompts/mall-agent-system.st");

        assertTrue(prompt.length() <= MAX_PROMPT_CHARS,
                "system prompt 过长，实际 " + prompt.length() + " 字符，上限 " + MAX_PROMPT_CHARS
                        + "：每轮都会重发，加约束前先删旧话");

        for (String guard : REQUIRED_GUARDS) {
            assertTrue(prompt.contains(guard),
                    "关键约束被删了：" + guard + "。精简 Prompt 时不能连带删掉安全红线");
        }
    }

    @Test
    @DisplayName("工具描述保持精简")
    void toolDescriptionsStayLean() {
        List<String> descriptions = collectToolDescriptions();
        assertTrue(descriptions.size() >= TOOL_CLASSES.size(),
                "工具数量异常：" + descriptions.size());

        int total = 0;
        for (String description : descriptions) {
            total += description.length();
            assertTrue(description.length() <= MAX_TOOL_DESCRIPTION_CHARS,
                    "工具描述过长（" + description.length() + " 字符）：" + firstLine(description));
        }
        assertTrue(total <= MAX_TOTAL_TOOL_DESCRIPTION_CHARS,
                "工具描述合计 " + total + " 字符，超过上限 " + MAX_TOTAL_TOOL_DESCRIPTION_CHARS
                        + "：工具 schema 每轮都要随请求发送");
    }

    /**
     * 收集所有工具方法上的描述。
     *
     * @return 描述文本列表
     */
    private static List<String> collectToolDescriptions() {
        List<String> descriptions = new ArrayList<>();
        for (Class<?> type : TOOL_CLASSES) {
            for (Method method : type.getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool != null) {
                    descriptions.add(tool.description());
                }
            }
        }
        return descriptions;
    }

    /**
     * 取描述首行，便于定位是哪只工具超限。
     *
     * @param description 描述
     * @return 首行
     */
    private static String firstLine(String description) {
        int end = description.indexOf('\n');
        String line = end < 0 ? description : description.substring(0, end);
        return line.length() > 40 ? line.substring(0, 40) + "..." : line;
    }
}
