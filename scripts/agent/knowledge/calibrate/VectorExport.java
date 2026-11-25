import com.fasterxml.jackson.databind.ObjectMapper;
import com.zeroai.ai.knowledge.KnowledgeCatalog;
import com.zeroai.ai.knowledge.KnowledgeChunker;
import com.zeroai.ai.knowledge.KnowledgeDocument;
import com.zeroai.ai.knowledge.KnowledgeProperties;
import com.zeroai.ai.knowledge.OpenAiEmbeddingClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 导出「每条用例 × 每个知识块」的余弦分数，供 Python 侧做向量阈值 + hybrid RRF 扫描。
 * 切块参数与生产一致（VectorKnowledgeStore 的 chunk-size / min-chunk-chars / prefix-title）。
 */
public class VectorExport {

    static final int CHUNK_SIZE = 500;
    static final int MIN_CHUNK = 120;

    record Scored(String sourceId, double score) {
    }

    record CaseScores(String id, String type, String q, List<String> expect,
                      List<Scored> chunks, List<String> chunkTexts) {
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

        List<String> texts = new ArrayList<>();
        List<String> sourceIds = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        for (KnowledgeDocument doc : docs) {
            for (KnowledgeChunker.Chunk part : KnowledgeChunker.split(doc, CHUNK_SIZE, MIN_CHUNK, true)) {
                texts.add(part.text());
                sourceIds.add(doc.sourceId());
                titles.add(doc.title());
            }
        }
        System.out.println("docs=" + docs.size() + " chunks=" + texts.size());

        OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(emb);
        long started = System.currentTimeMillis();
        List<float[]> vectors = client.embedAll(texts);
        System.out.println("embedding costMs=" + (System.currentTimeMillis() - started));

        List<float[]> normalized = new ArrayList<>(vectors.size());
        for (float[] v : vectors) {
            normalized.add(normalize(v));
        }

        // 用例：与 BM25 标定同一批；args 可覆盖（留出验证用）
        Path casePath = Path.of(args.length > 0 ? args[0]
                : "scripts/agent/knowledge/cases-aftersale.jsonl");
        Path outPath = Path.of(args.length > 1 ? args[1] : "target/knowledge-calibration/vector_scores.json");
        List<CaseScores> out = new ArrayList<>();
        for (String line : Files.readAllLines(casePath, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            var node = new ObjectMapper().readTree(line);
            String id = node.get("id").asText();
            String type = node.get("type").asText();
            String q = node.get("q").asText();
            List<String> expect = new ArrayList<>();
            node.get("expect").forEach(n -> expect.add(n.asText()));

            float[] qv = normalize(client.embed(q));
            List<Scored> scored = new ArrayList<>(normalized.size());
            for (int i = 0; i < normalized.size(); i++) {
                scored.add(new Scored(sourceIds.get(i), cosine(qv, normalized.get(i))));
            }
            scored.sort(Comparator.comparingDouble(Scored::score).reversed());
            out.add(new CaseScores(id, type, q, expect, scored, null));
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("chunks", texts.size());
        result.put("cases", out);
        new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValue(outPath.toFile(), result);
        System.out.println("written " + outPath + " cases=" + out.size());
    }

    static float[] normalize(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0) {
            return v;
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / norm);
        }
        return out;
    }

    static double cosine(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }
}
