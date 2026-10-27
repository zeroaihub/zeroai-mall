package com.zeroai.ai.web;

import com.zeroai.ai.knowledge.KnowledgeHit;
import com.zeroai.ai.knowledge.KnowledgeService;
import com.zeroai.util.util.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识检索只读接口。
 * <p>
 * 存在的理由有两个，都不是「给用户看」：
 * <ol>
 *   <li><b>检索评测</b>：评测脚本需要一个稳定入口，才能把「命中率」算成数字，
 *       而不是靠人工在对话里试；</li>
 *   <li><b>排障</b>：用户说「助手说查不到」，可以直接对比同一条查询在检索层有没有命中，
 *       区分「索引没同步」和「检索词不对」。</li>
 * </ol>
 * 只返回公开知识片段，与对话里模型看到的内容完全一致，不额外暴露内部字段。
 */
@RestController
@RequestMapping("ai/knowledge")
@ConditionalOnExpression("${zeroai.ai.enabled:false} && ${zeroai.ai.knowledge.enabled:true}")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);

    private final KnowledgeService knowledgeService;

    /**
     * @param knowledgeService 知识检索门面
     */
    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /**
     * 检索知识片段。
     *
     * @param query 检索问题
     * @param limit 片段数，服务端按 {@code zeroai.ai.knowledge.max-hits} 封顶
     * @return 检索状态与片段列表
     */
    @GetMapping("search")
    public R<SearchResVO> search(@RequestParam("query") String query,
                                 @RequestParam(value = "limit", required = false) Integer limit) {
        KnowledgeService.Result result = knowledgeService.search(query, limit);
        log.debug("知识检索 query={} status={} hits={}", result.query(), result.status(), result.hits().size());
        List<HitVO> items = result.hits().stream().map(HitVO::of).toList();
        return R.success(new SearchResVO(result.status().name(), result.query(), items.size(), items));
    }

    /**
     * 检索响应。
     *
     * @param status 检索状态：OK / NO_MATCH / EMPTY_QUERY / UNAVAILABLE
     * @param query  归一化后的问题
     * @param count  片段数
     * @param items  片段列表
     */
    public record SearchResVO(String status, String query, int count, List<HitVO> items) {
    }

    /**
     * 片段视图。不含检索得分：得分只对排障有意义，且不同查询之间不可比。
     *
     * @param sourceId  来源标识
     * @param title     文档标题
     * @param category  分类
     * @param version   文档版本
     * @param updatedAt 更新时间
     * @param snippet   片段正文
     */
    public record HitVO(String sourceId, String title, String category, int version,
                        String updatedAt, String snippet) {

        /**
         * 从命中记录裁剪。
         *
         * @param hit 命中记录
         * @return 对外视图
         */
        static HitVO of(KnowledgeHit hit) {
            return new HitVO(hit.sourceId(), hit.title(), hit.category(), hit.version(),
                    hit.updatedAt(), hit.snippet());
        }
    }
}
