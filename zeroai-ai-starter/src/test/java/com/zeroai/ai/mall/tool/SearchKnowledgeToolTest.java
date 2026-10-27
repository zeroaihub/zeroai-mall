package com.zeroai.ai.mall.tool;

import com.zeroai.ai.knowledge.KnowledgeHit;
import com.zeroai.ai.knowledge.KnowledgeProperties;
import com.zeroai.ai.knowledge.KnowledgeSearchPort;
import com.zeroai.ai.knowledge.KnowledgeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识检索工具的输出契约测试。
 * <p>
 * 工具返回的是模型读的 JSON，措辞直接影响模型行为，因此逐字断言关键提示：
 * 无命中时必须出现「查不到」的引导，检索不可用时必须出现「不要编造」，
 * 有命中时必须带 sourceId 供回答引用。
 */
class SearchKnowledgeToolTest {

    private static KnowledgeService service(KnowledgeSearchPort port) {
        return new KnowledgeService(port, new KnowledgeProperties());
    }

    @Test
    @DisplayName("命中：输出 sourceId / title / snippet，供回答引用")
    void shouldReturnCitationFields() {
        SearchKnowledgeTool tool = new SearchKnowledgeTool(service((query, limit) -> List.of(
                new KnowledgeHit("kb-after-sale-refund", "退款与售后政策", "after-sale", 2,
                        "2026-10-27", "退款请在订单页提交申请。", 8.5))));

        String json = tool.searchKnowledge("退款怎么申请", 3);

        assertTrue(json.contains("\"status\":\"OK\""), "实际=" + json);
        assertTrue(json.contains("\"sourceId\":\"kb-after-sale-refund\""), "必须带来源标识，实际=" + json);
        assertTrue(json.contains("\"title\":\"退款与售后政策\""), "必须带标题，实际=" + json);
        assertTrue(json.contains("\"version\":2"), "必须带版本，实际=" + json);
        assertTrue(json.contains("\"updatedAt\":\"2026-10-27\""), "必须带更新时间，实际=" + json);
    }

    @Test
    @DisplayName("无命中：明确要求说「查不到」并禁止编造")
    void shouldInstructNotToFabricateOnNoMatch() {
        SearchKnowledgeTool tool = new SearchKnowledgeTool(service((query, limit) -> List.of()));

        String json = tool.searchKnowledge("支持七天无理由退货吗", 3);

        assertTrue(json.contains("\"status\":\"NO_MATCH\""), "实际=" + json);
        assertTrue(json.contains("查不到"), "必须给出「查不到」的话术引导，实际=" + json);
        assertTrue(json.contains("不要"), "必须禁止推断，实际=" + json);
    }

    @Test
    @DisplayName("检索不可用：提示稍后再试且不许凭印象回答")
    void shouldInstructOnUnavailable() {
        SearchKnowledgeTool tool = new SearchKnowledgeTool(service(null));

        String json = tool.searchKnowledge("运费怎么算", 3);

        assertTrue(json.contains("\"status\":\"UNAVAILABLE\""), "实际=" + json);
        assertTrue(json.contains("不要凭印象"), "实际=" + json);
    }

    @Test
    @DisplayName("空问题：要求先明确问题")
    void shouldHandleEmptyQuery() {
        SearchKnowledgeTool tool = new SearchKnowledgeTool(service((query, limit) -> List.of()));

        String json = tool.searchKnowledge("  ", 3);

        assertTrue(json.contains("\"status\":\"EMPTY_QUERY\""), "实际=" + json);
        assertFalse(json.contains("\"items\""), "空问题不应带结果列表，实际=" + json);
    }

    @Test
    @DisplayName("未装配知识库：工具自身也要给出安全兜底")
    void shouldFallbackWhenServiceMissing() {
        String json = new SearchKnowledgeTool(null).searchKnowledge("退款", 1);

        assertTrue(json.contains("UNAVAILABLE"), "实际=" + json);
        assertTrue(json.contains("不要编造"), "实际=" + json);
    }

    @Test
    @DisplayName("片段必须做 JSON 转义，引号不会破坏结构")
    void shouldEscapeSnippet() {
        SearchKnowledgeTool tool = new SearchKnowledgeTool(service((query, limit) -> List.of(
                new KnowledgeHit("kb-quote", "带\"引号\"的政策", "help", 1, "2026-10-27",
                        "这句话里有\"双引号\"，后半句仍然保留。", 3.0))));

        String json = tool.searchKnowledge("引号", 1);

        assertTrue(json.contains("\\\"双引号\\\""), "实际=" + json);
        assertTrue(json.contains("后半句仍然保留"), "换行与切句不应丢内容，实际=" + json);
    }
}
