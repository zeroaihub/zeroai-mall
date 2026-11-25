package com.zeroai.ai.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 知识库配置。
 * <p>
 * 知识库是<b>可选扩展</b>，可以整体关闭：关闭后不注册任何 Bean，
 * Agent 的工具面里也不会出现 {@code search_knowledge}，行为与未接入知识库时一致。
 * <p>
 * 配置分成四组，对应四种「可以独立出错的东西」：
 * <ul>
 *   <li>通用项（条数、截断、索引名）：与检索引擎无关；</li>
 *   <li>{@code bm25.*} 相关（{@code min-score}、{@code min-should-match}）：只对 ES 生效；</li>
 *   <li>{@link Embedding}：向量化服务；</li>
 *   <li>{@link Vector}、{@link Hybrid}：向量检索与融合。</li>
 * </ul>
 * 分开的原因很实际：BM25 的分数是没有量纲的相关度（本项目实测正确命中 2.4 以上），
 * 余弦相似度是 0..1（正确命中 0.6 以上），两者阈值不可互换。
 * 把阈值放在各自的命名空间里，切换模式时不会出现「用 BM25 的阈值过滤向量分数」这种静默全空。
 */
@ConfigurationProperties(prefix = "zeroai.ai.knowledge")
public class KnowledgeProperties {

    /**
     * 知识库开关。关闭后对话里不会出现政策类查询工具。
     */
    private boolean enabled = true;

    /**
     * 检索模式：{@code bm25}（默认）/ {@code vector} / {@code hybrid}。
     * <p>
     * 默认 {@code bm25}：不依赖任何 embedding 服务即可回答政策问题，
     * 是「部署方什么都不配」时唯一确定可用的模式。
     */
    private String mode = KnowledgeMode.BM25.code();

    /**
     * 知识索引名。商品实时数据仍在 {@code goods} 索引，这里只存规则类文档，两者不混用。
     */
    private String index = "zeroai_ai_kb";

    /**
     * 知识文档的 classpath 位置（jar 内目录）。
     */
    private String location = "knowledge";

    /**
     * 是否在应用启动时同步索引。同步是幂等的：内容未变的文档也会覆盖写入，
     * 成本是几十次 bulk 写入，换来「代码即知识源」的一致语义。
     */
    private boolean syncOnStartup = true;

    /**
     * 单次检索返回的最大片段数。片段越多上下文越贵，且后半段往往是噪声。
     */
    private int maxHits = 3;

    /**
     * 单个片段允许的最大字符数。超出按句截断，避免一篇长文挤占整个上下文。
     */
    private int maxSnippetChars = 600;

    /**
     * 单次检索允许的最大问题长度。超长输入对检索没有帮助，只会放大查询开销。
     */
    private int maxQueryChars = 80;

    /**
     * BM25 检索得分下限，低于该值的片段直接丢弃。
     * <p>
     * BM25 分数没有统一量纲，这个值只能按语料实测调：设太高会把「换个说法问」的正常命中滤掉，
     * 设 0 表示不启用。默认 8.5 来自 2026-11-25 真实 ES 7.14 + IK + 18 篇售后语料的实测：
     * 可答问题 top1 中位 18.6，库外最高 12.4，8.0~9.0 是零误召回平台区，取其中点。
     * <p>
     * <b>这个默认值是为 hybrid 设计的</b>：BM25 只报高置信命中，长尾召回交给向量分支。
     * 向量服务不可用时退回纯 BM25，召回会明显下降（实测训练集 13/22），这是有意的取舍。
     * <p>
     * 只对 {@link KnowledgeMode#BM25} 与 {@link KnowledgeMode#HYBRID} 的 BM25 分支生效；
     * 向量分支用 {@link Vector#getMinScore()}。
     */
    private double minScore = 8.5D;

    /**
     * 查询词覆盖率要求，直接透传给 Elasticsearch 的 {@code minimum_should_match}。
     * <p>
     * 默认 {@code 2<100% 4<60%} 是<b>阶梯写法</b>：2~3 个词元要求全部命中，4 个以上放宽到 60%。
     * <p>
     * 为什么不是简单的百分比：中文口语问句常含「要 / 收 / 多少 / 时候」这类碎片词元，
     * 百分比会把它们算进分母——「安装师傅上门要收多少钱」被 ik 切成 6 个词，
     * {@code 2<70%} 要求命中 5 个，实测直接零命中；而它的核心词只有「安装 / 师傅 / 上门」。
     * 阶梯写法让长查询只要求核心词命中，短查询仍保持「两个词都要对得上」的精度。
     * <p>
     * 标定数据：30 条训练集 + 14 条留出集，在 {@code 2<100% 4<60%} 下训练集 hit@3 22/22、
     * 库外误召回 0/8，留出集 hit@3 8/10、误召回 0/4。对比 {@code 3<70%} 留出集只有 7/10。
     */
    private String minShouldMatch = "2<100% 4<60%";

    /** 向量化配置。 */
    private Embedding embedding = new Embedding();

    /** 向量检索配置。 */
    private Vector vector = new Vector();

    /** 混合检索配置。 */
    private Hybrid hybrid = new Hybrid();

    /**
     * 解析后的检索模式。
     *
     * @return 模式，无法识别时回落 BM25
     */
    public KnowledgeMode resolveMode() {
        return KnowledgeMode.from(mode);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getIndex() {
        return index;
    }

    public void setIndex(String index) {
        this.index = index;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public boolean isSyncOnStartup() {
        return syncOnStartup;
    }

    public void setSyncOnStartup(boolean syncOnStartup) {
        this.syncOnStartup = syncOnStartup;
    }

    public int getMaxHits() {
        return maxHits;
    }

    public void setMaxHits(int maxHits) {
        this.maxHits = maxHits;
    }

    public int getMaxSnippetChars() {
        return maxSnippetChars;
    }

    public void setMaxSnippetChars(int maxSnippetChars) {
        this.maxSnippetChars = maxSnippetChars;
    }

    public int getMaxQueryChars() {
        return maxQueryChars;
    }

    public void setMaxQueryChars(int maxQueryChars) {
        this.maxQueryChars = maxQueryChars;
    }

    public double getMinScore() {
        return minScore;
    }

    public void setMinScore(double minScore) {
        this.minScore = minScore;
    }

    public String getMinShouldMatch() {
        return minShouldMatch;
    }

    public void setMinShouldMatch(String minShouldMatch) {
        this.minShouldMatch = minShouldMatch;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public void setEmbedding(Embedding embedding) {
        this.embedding = embedding;
    }

    public Vector getVector() {
        return vector;
    }

    public void setVector(Vector vector) {
        this.vector = vector;
    }

    public Hybrid getHybrid() {
        return hybrid;
    }

    public void setHybrid(Hybrid hybrid) {
        this.hybrid = hybrid;
    }

    /**
     * 向量化服务配置。
     * <p>
     * 只支持 <b>OpenAI 兼容</b> 的 {@code /v1/embeddings} 协议（请求体
     * {@code {model, input}}，响应 {@code data[].embedding}）。这不是为了通用而通用：
     * 阿里百炼、硅基流动、智谱、Ollama（0.1.36+）、vLLM 都提供这个协议，
     * 一个客户端就能覆盖，不必为每家写适配器。DeepSeek 目前不提供 embedding 接口，
     * 所以这里必须是独立于 {@code zeroai.ai.*} 的配置，不能复用对话模型那套密钥。
     */
    public static class Embedding {

        /**
         * 接口根地址，不含 {@code /v1/embeddings}。
         * <p>
         * 例：{@code https://dashscope.aliyuncs.com/compatible-mode}、
         * {@code https://api.siliconflow.cn}、{@code http://127.0.0.1:11434}。
         */
        private String baseUrl = "";

        /**
         * 向量化接口路径。Ollama 等同源兼容实现也走这个默认值。
         */
        private String path = "/v1/embeddings";

        /**
         * 密钥。Ollama 等本地服务留空即可（空值时不发 Authorization 头）。
         */
        private String apiKey = "";

        /**
         * 模型名，例如 {@code text-embedding-v3}、{@code BAAI/bge-m3}、
         * {@code nomic-embed-text}。
         */
        private String model = "text-embedding-v3";

        /**
         * 期望维度。
         * <p>
         * 不是「想切多少就切多少」的旋钮，而是<b>校验值</b>：启动时若服务返回的维度与此不符，
         * 直接失败而不是继续建索引。维度配错导致的后果极隐蔽——检索不报错，
         * 只是永远返回不相关的结果。
         */
        private int dimension = 1024;

        /**
         * 单次请求超时。启动建索引是批量串行调用，单次超时太长会让启动明显变慢。
         */
        private int timeoutMs = 5000;

        /**
         * 单条文本送往向量化前截断到多少字符。
         * <p>
         * 多数 embedding 模型有输入上限（常见的 512 / 8192 token）。知识块本身已按
         * {@code vector.chunk-size} 切分，这里只是兜底：超长输入会让部分服务直接报 400。
         */
        private int maxChars = 1000;

        /**
         * 单次请求最多带多少条文本。
         * <p>
         * 建索引时几十个知识块要向量化，批量能把往返次数降一个数量级。
         * 不要设太大：多数服务对 input 数组长度有上限（常见 10~2048），
         * 超限是整个请求 400，而不是自动拆批。16 是各家通吃的保守值。
         */
        private int batchSize = 16;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getDimension() {
            return dimension;
        }

        public void setDimension(int dimension) {
            this.dimension = dimension;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getMaxChars() {
            return maxChars;
        }

        public void setMaxChars(int maxChars) {
            this.maxChars = maxChars;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }

    /** 向量检索配置。 */
    public static class Vector {

        /**
         * 向量存储实现：{@code memory}（进程内索引，默认）/ {@code pgvector}（PG 持久化）。
         * <p>
         * 两个实现互斥装配：路由用 {@code ObjectProvider.getIfAvailable()} 取
         * {@link VectorKnowledgePort}，容器里同时存在两个候选会直接抛
         * {@code NoUniqueBeanDefinitionException}，所以不能靠「都注册、按名字选」。
         * <p>
         * 默认 {@code memory}：不依赖任何数据库即可完成向量检索，
         * 是「部署方只配了 embedding」时唯一确定可用的模式。切到 {@code pgvector}
         * 需要入口模块提供 PG 连接配置，见 {@code zeroai.ai.knowledge.pg.*}。
         */
        private String store = "memory";

        /**
         * 余弦相似度下限，低于该值的块直接丢弃。
         * <p>
         * 默认 0.65 来自 2026-11-25 真实 bge-m3 + 18 篇售后语料 + 30 条用例的实测：
         * 可答 22 条 top1 中位 0.726，库外 8 条最高 0.648。取 0.65 时库外零误召回；
         * 0.60~0.64 会把「可以开发票吗」「支持货到付款吗」放进结果，
         * 0.66 起开始丢掉正常的语义命中。余弦相似度的量纲固定 0..1，
         * 比 BM25 分数更容易跨语料迁移，但换 embedding 模型仍必须重新标定。
         * <p>
         * 设 0 表示不启用——只有在你确认「宁可不相关也别查不到」时才这么做。
         */
        private double minScore = 0.65D;

        /**
         * 切块目标长度（字符）。
         * <p>
         * 一篇政策文档整篇算一个向量时，向量表示的是「这篇文档大致讲什么」，
         * 而用户问的往往是其中一个细节（退款到账要几天）。切块让向量对准细节，
         * 召回精度明显提升——这也是「向量检索必须先分块」的根本原因，不是可选项。
         * 300 字约等于中文 2~4 个段落，能容纳一条完整规则而不至于把一条规则切成两半。
         */
        private int chunkSize = 300;

        /**
         * 单块最小长度。短于此长度的尾块并入上一块，避免产出「七、其他」这种没有信息量的块。
         */
        private int minChunkChars = 60;

        /**
         * 切块时是否在每块前拼上文档标题与所在小节标题。
         * <p>
         * 打开后块向量带上了上下文（「退款与售后政策 / 退款到账时间：……」），
         * 对「多久到账」这类只有几个字的短问句提升明显；代价是块内文本变长、
         * 送给模型的片段里也会带上标题前缀——这恰好是希望模型看到的。
         */
        private boolean prefixTitle = true;

        public double getMinScore() {
            return minScore;
        }

        public void setMinScore(double minScore) {
            this.minScore = minScore;
        }

        public String getStore() {
            return store;
        }

        public void setStore(String store) {
            this.store = store;
        }

        public int getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
        }

        public int getMinChunkChars() {
            return minChunkChars;
        }

        public void setMinChunkChars(int minChunkChars) {
            this.minChunkChars = minChunkChars;
        }

        public boolean isPrefixTitle() {
            return prefixTitle;
        }

        public void setPrefixTitle(boolean prefixTitle) {
            this.prefixTitle = prefixTitle;
        }
    }

    /** 混合检索配置。 */
    public static class Hybrid {

        /**
         * 倒数排名融合的平滑常数，业界默认 60。
         * <p>
         * RRF 只用「排名」不用「分数」，因此天然解决两个分数量纲不可比的问题
         * （BM25 2.4 和余弦 0.72 谁更相关，没有客观答案；各自排第几却有）。
         * k 越大，排名靠后的结果被拉平得越厉害；60 是在「前几名要拉开差距」
         * 与「第 10 名之后别一票否决」之间的常用折中。
         */
        private int rrfK = 60;

        /**
         * 融合分数下限，默认 0（不启用）。
         * <p>
         * RRF 分数的量纲是 {@code 1/(k+rank)} 之和：两路都排最后约 0.032，
         * 只在一路排第一约 0.016。看起来「设个 0.02 只留两路都认可的」很合理，
         * 但那会把「只有向量检索命中、BM25 完全没命中」的语义召回全部丢掉——
         * 而这恰恰是引入向量检索的理由。两路各自的 {@code min-score} /
         * {@code vector.min-score} 已经做过相关性过滤，这里再设阈值属于重复惩罚。
         * 保留这个配置项只是为了在特定语料上做实验。
         */
        private double minScore = 0D;

        /**
         * 向量分支不可用时是否自动退化为纯 BM25。
         * <p>
         * 默认 true：混合模式的本意是「在 BM25 之上加一层语义召回」，
         * 语义那层挂掉时退回 BM25 仍然是有用且正确的检索，比整库不可用好。
         * 设 false 则会明确报「检索不可用」，用于你想要「语义能力缺失必须被发现」的场合。
         */
        private boolean fallbackToBm25 = true;

        /**
         * BM25 一路零命中时，向量分支单独成立所需的最低余弦相似度。
         * <p>
         * <b>为什么需要这个而 {@link #minScore} 不能替代</b>：RRF 只看排名、丢掉分数，
         * 融合分无法区分「0.63 的弱相关」和「0.78 的强相关」，两路各一个命中时融合分一样。
         * 也就是说 {@code vector.min-score=0.55} 之后，任何沾边的文档都能凑出一条命中，
         * 库外问题就会被硬凑成「有答案」。
         * <p>
         * <b>0.65 的依据</b>：2026-11-25 真实 ES + 真实 bge-m3 + 18 篇售后语料实测，
         * 库外 8 条最高余弦 0.648，可答中位 0.726。取 0.65 时库外零误召回；
         * 与 {@link Vector#getMinScore()} 同值是刻意的——向量只有在「自己足够自信」时
         * 才能单独支撑一个答案。降到 0.60 会把「可以开发票吗」这类库外问题放进结果。
         * 两边分布本身是重叠的（可答最低 0.605 低于库外最高 0.648），单靠余弦无法分开；
         * 但「BM25 零命中」这个特征能把它们切开——可答问题普遍有字面命中。
         * <p>
         * 因此这条规则只在 BM25 零命中时生效：此时向量是唯一证据，要求更强才算数；
         * BM25 有命中说明字面证据已成立，向量分多低都不该否决它。
         * 设 0 表示关闭该门禁（退回「向量命中即算有答案」的旧行为）。
         */
        private double vectorOnlyMinScore = 0.65;

        public int getRrfK() {
            return rrfK;
        }

        public void setRrfK(int rrfK) {
            this.rrfK = rrfK;
        }

        public double getMinScore() {
            return minScore;
        }

        public void setMinScore(double minScore) {
            this.minScore = minScore;
        }

        public boolean isFallbackToBm25() {
            return fallbackToBm25;
        }

        public void setFallbackToBm25(boolean fallbackToBm25) {
            this.fallbackToBm25 = fallbackToBm25;
        }

        public double getVectorOnlyMinScore() {
            return vectorOnlyMinScore;
        }

        public void setVectorOnlyMinScore(double vectorOnlyMinScore) {
            this.vectorOnlyMinScore = vectorOnlyMinScore;
        }
    }
}
