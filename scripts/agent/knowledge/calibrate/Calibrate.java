/*
 * 售后语料阈值标定（可复现）。
 *
 * 前置：
 *   1. Ollama 已启动，且 `ollama list` 能看到 bge-m3；
 *   2. 已执行过 `mvn -pl zeroai-ai-starter test`（target/classes 含知识资源）。
 *
 * 运行（仓库根目录，Windows Git Bash）：
 *   mvn -pl zeroai-ai-starter dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt -q
 *   CP="zeroai-ai-starter/target/classes;$(cat /tmp/cp.txt)"
 *   javac -proc:none -encoding UTF-8 -cp "$CP" -d target/calib \
 *       scripts/agent/knowledge/calibrate/Calibrate.java
 *   java -Dfile.encoding=UTF-8 -cp "target/calib;$CP" Calibrate
 *
 * 说明：严格复刻生产链路（chunk 500/120/prefix、按块 Top3、按 sourceId 去重）。
 * BM25 分支只是 bigram 代理，仅用于观察字面重叠，不能用来定 ES 的 min-score。
 */
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zeroai.ai.knowledge.KnowledgeCatalog;
import com.zeroai.ai.knowledge.KnowledgeChunker;
import com.zeroai.ai.knowledge.KnowledgeDocument;
import com.zeroai.ai.knowledge.KnowledgeProperties;
import com.zeroai.ai.knowledge.KnowledgeQueryNormalizer;
import com.zeroai.ai.knowledge.OpenAiEmbeddingClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 阈值标定：真实语料 + 真实 bge-m3 + 30 条用例。
 * 严格复刻生产链路：先按块取 Top-N（max-hits=3），再按 sourceId 去重。
 */
public class Calibrate {

    static final int CHUNK_SIZE = 500;
    static final int MIN_CHUNK = 120;
    static final int MAX_HITS = 3;

    record Chunk(String sourceId, int index, String text, float[] vec, List<String> tokens) {
    }

    record Case(String id, String cat, String type, String q, List<String> expect) {
    }

    record Hit(String source, double score) {
    }

    record Row(Case c, List<Hit> vector, List<Hit> bm25) {
    }

    public static void main(String[] args) throws Exception {
        KnowledgeProperties props = new KnowledgeProperties();
        props.setLocation("knowledge");
        props.getVector().setChunkSize(CHUNK_SIZE);
        props.getVector().setMinChunkChars(MIN_CHUNK);
        props.getVector().setPrefixTitle(true);
        KnowledgeProperties.Embedding emb = props.getEmbedding();
        emb.setBaseUrl("http://127.0.0.1:11434");
        emb.setPath("/v1/embeddings");
        emb.setApiKey("ollama");
        emb.setModel("bge-m3");
        emb.setDimension(1024);
        emb.setTimeoutMs(30000);
        emb.setBatchSize(8);

        KnowledgeCatalog.LoadResult catalog = KnowledgeCatalog.load("knowledge");
        List<KnowledgeDocument> docs = catalog.documents();
        List<Chunk> chunks = new ArrayList<>();
        for (KnowledgeDocument doc : docs) {
            for (KnowledgeChunker.Chunk part : KnowledgeChunker.split(doc, CHUNK_SIZE, MIN_CHUNK, true)) {
                chunks.add(new Chunk(doc.sourceId(), 0, part.text(), null, tokenize(part.text())));
            }
        }
        System.out.println("docs=" + docs.size() + " chunks=" + chunks.size());

        Map<String, Integer> df = new HashMap<>();
        int totalLen = 0;
        for (Chunk chunk : chunks) {
            totalLen += chunk.tokens().size();
            for (String token : new HashSet<>(chunk.tokens())) {
                df.merge(token, 1, Integer::sum);
            }
        }
        double avgdl = (double) totalLen / chunks.size();

        OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(emb);
        long started = System.currentTimeMillis();
        List<float[]> vectors = client.embedAll(chunks.stream().map(Chunk::text).toList());
        System.out.println("embedding costMs=" + (System.currentTimeMillis() - started)
                + " dim=" + vectors.get(0).length);

        List<Chunk> indexed = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            indexed.add(new Chunk(c.sourceId(), i, c.text(), normalize(vectors.get(i)), c.tokens()));
        }

        List<Case> cases = loadCases(Path.of("scripts/agent/knowledge/cases-aftersale.jsonl"));
        System.out.println("cases=" + cases.size() + " maxHits=" + MAX_HITS);

        List<Row> rows = new ArrayList<>();
        for (Case c : cases) {
            String q = KnowledgeQueryNormalizer.clean(c.q(), 80);
            float[] qv = normalize(client.embed(q));
            Set<String> queryTerms = new HashSet<>(tokenize(q));
            List<Hit> vectorHits = new ArrayList<>();
            List<Hit> bm25Hits = new ArrayList<>();
            for (Chunk chunk : indexed) {
                vectorHits.add(new Hit(chunk.sourceId(), cosine(qv, chunk.vec())));
                bm25Hits.add(new Hit(chunk.sourceId(),
                        bm25(queryTerms, chunk.tokens(), df, avgdl, indexed.size())));
            }
            rows.add(new Row(c, vectorHits, bm25Hits));
        }

        printDistribution(rows);
        sweepVector(rows);
        printDetail(rows);
    }

    /** 生产语义：按块排序 → 阈值 → TopN → 按 sourceId 去重。 */
    static List<Hit> productionHits(List<Hit> chunks, double minScore) {
        List<Hit> sorted = chunks.stream()
                .filter(h -> h.score() >= minScore)
                .sorted(Comparator.comparingDouble(Hit::score).reversed())
                .limit(MAX_HITS)
                .toList();
        Map<String, Hit> deduped = new LinkedHashMap<>();
        for (Hit hit : sorted) {
            deduped.putIfAbsent(hit.source(), hit);
        }
        return new ArrayList<>(deduped.values());
    }

    static void printDistribution(List<Row> rows) {
        List<Double> in = new ArrayList<>();
        List<Double> out = new ArrayList<>();
        for (Row r : rows) {
            double top = productionHits(r.vector(), 0).get(0).score();
            (r.c().expect().isEmpty() ? out : in).add(top);
        }
        Collections.sort(in);
        Collections.sort(out);
        System.out.println("\n=== 向量 top1（Top3 块去重后）分布 ===");
        System.out.printf("可答 %d: min=%.3f p25=%.3f median=%.3f p75=%.3f max=%.3f%n",
                in.size(), in.get(0), q(in, .25), q(in, .5), q(in, .75), in.get(in.size() - 1));
        System.out.printf("库外 %d: min=%.3f median=%.3f p75=%.3f max=%.3f%n",
                out.size(), out.get(0), q(out, .5), q(out, .75), out.get(out.size() - 1));
    }

    static void sweepVector(List<Row> rows) {
        System.out.println("\n=== vector.min-score 扫描（复刻生产 Top3 + 去重）===");
        System.out.println("阈值 | hit@1 | hit@3 | 有结果 | 库外误召回 | 掉的用例 | 误召回用例");
        for (double t = 0.560; t <= 0.7001; t += 0.005) {
            int hit1 = 0, hit3 = 0, answered = 0, fp = 0, in = 0, out = 0;
            List<String> missed = new ArrayList<>();
            List<String> falsePositive = new ArrayList<>();
            for (Row r : rows) {
                List<Hit> hits = productionHits(r.vector(), t);
                if (r.c().expect().isEmpty()) {
                    out++;
                    if (!hits.isEmpty()) {
                        fp++;
                        falsePositive.add(r.c().id());
                    }
                    continue;
                }
                in++;
                if (!hits.isEmpty()) answered++;
                int pos = rankOfExpected(hits, r.c().expect());
                if (pos == 1) hit1++;
                if (pos >= 1 && pos <= 3) hit3++;
                if (pos < 1) missed.add(r.c().id());
            }
            System.out.printf("%.3f | %5.1f%% | %5.1f%% | %5.1f%% | %d/%d | %s | %s%n",
                    t, 100.0 * hit1 / in, 100.0 * hit3 / in, 100.0 * answered / in,
                    fp, out, missed, falsePositive);
        }
    }

    static void printDetail(List<Row> rows) {
        System.out.println("\n=== 明细（阈值 0.62 时生产会返回什么）===");
        for (Row r : rows) {
            List<Hit> hits = productionHits(r.vector(), 0.62);
            System.out.printf("%s | 期望=%s | 返回=%s | 排名=%d%n",
                    r.c().id(), r.c().expect().isEmpty() ? "无" : r.c().expect(),
                    hits.stream().map(h -> shortId(h.source()) + ":" + String.format("%.3f", h.score())).toList(),
                    rankOfExpected(hits, r.c().expect()));
        }
    }

    static int rankOfExpected(List<Hit> hits, List<String> expect) {
        for (int i = 0; i < hits.size(); i++) {
            if (expect.contains(hits.get(i).source())) return i + 1;
        }
        return -1;
    }

    static String shortId(String id) {
        return id == null ? "-" : id.replace("kb-", "");
    }

    static double q(List<Double> sorted, double p) {
        return sorted.get((int) Math.round(p * (sorted.size() - 1)));
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-9);
    }

    static float[] normalize(float[] v) {
        double n = 0;
        for (float x : v) n += x * x;
        n = Math.sqrt(n);
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / (n + 1e-9));
        return out;
    }

    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cjk = new StringBuilder();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= 0x4E00 && ch <= 0x9FFF) {
                flushWord(word, tokens);
                cjk.append(ch);
            } else if (Character.isLetterOrDigit(ch)) {
                flushCjk(cjk, tokens);
                word.append(Character.toLowerCase(ch));
            } else {
                flushCjk(cjk, tokens);
                flushWord(word, tokens);
            }
        }
        flushCjk(cjk, tokens);
        flushWord(word, tokens);
        return tokens;
    }

    static void flushCjk(StringBuilder buf, List<String> out) {
        String s = buf.toString();
        buf.setLength(0);
        if (s.length() == 1) out.add(s);
        for (int i = 0; i + 1 < s.length(); i++) out.add(s.substring(i, i + 2));
    }

    static void flushWord(StringBuilder buf, List<String> out) {
        if (buf.length() >= 2) out.add(buf.toString());
        buf.setLength(0);
    }

    static double bm25(Set<String> queryTerms, List<String> doc,
                       Map<String, Integer> df, double avgdl, int n) {
        if (doc.isEmpty()) return 0;
        Map<String, Integer> tf = new HashMap<>();
        for (String t : doc) tf.merge(t, 1, Integer::sum);
        double score = 0;
        for (String term : queryTerms) {
            Integer f = tf.get(term);
            if (f == null) continue;
            int d = df.getOrDefault(term, 0);
            double idf = Math.log(1 + (n - d + 0.5) / (d + 0.5));
            double k1 = 1.2, b = 0.75;
            score += idf * f * (k1 + 1) / (f + k1 * (1 - b + b * doc.size() / avgdl));
        }
        return score;
    }

    static List<Case> loadCases(Path path) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<Case> cases = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode node = mapper.readTree(line);
            List<String> expect = new ArrayList<>();
            node.path("expect").forEach(e -> expect.add(e.asText()));
            cases.add(new Case(node.path("id").asText(), node.path("cat").asText(),
                    node.path("type").asText(), node.path("q").asText(), expect));
        }
        return cases;
    }
}
