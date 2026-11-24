package com.zeroai.ai.engine.langchain4j.tool;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具入参「ID 列表」解析器（LangChain4j 版，与 AgentScope 版内容一致）。
 * <p>
 * 工具参数不用集合类型：模型按 JSON 数组传值时，框架的参数绑定可能直接失败
 * （AgentScope 已实际踩坑；LangChain4j 走 Gson 绑定，集合可绑定但行为未充分验证，
 * 为与 AgentScope 版行为完全对齐，统一用字符串 + 本解析器）。
 * 模型写成 {@code 1,2,3}、{@code [1,2,3]}、{@code 1 2 3} 都能兼容。
 * 非数字片段忽略而不是抛异常：空结果由调用方给出格式提示，比整轮失败更有用。
 */
final class IdListParser {

    private IdListParser() {
    }

    /**
     * 解析模型传来的 ID 串。
     *
     * @param raw 原始入参，可为 null
     * @return 解析出的 ID 列表，保持原顺序；无法解析时返回空列表
     */
    static List<Long> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String normalized = raw.replaceAll("[\\[\\]\"']", ",");
        List<Long> ids = new ArrayList<>();
        for (String part : normalized.split("[,，、;；/\\s]+")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(token));
            } catch (NumberFormatException ignored) {
                // 非数字片段（如误传商品名）忽略，交给空结果分支提示格式
            }
        }
        return ids;
    }
}
