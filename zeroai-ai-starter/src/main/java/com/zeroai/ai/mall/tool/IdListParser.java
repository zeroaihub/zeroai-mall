package com.zeroai.ai.mall.tool;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具入参「ID 列表」解析器。
 * <p>
 * <b>为什么工具参数不用 {@code List<Long>}</b>：AgentScope 的参数绑定对集合类型不稳定，
 * 模型按 JSON 数组传值时会在进入方法前就失败（工具方法体不执行，日志里连一行「工具调用」都没有），
 * 表现为模型反复重试、最后告诉用户「操作暂时失败」。
 * 改成字符串后由本类解析，模型写成 {@code 1,2,3}、{@code [1,2,3]}、{@code 1 2 3} 都能兼容。
 * <p>
 * 非数字片段会被忽略而不是抛异常：模型偶尔会把商品名一起塞进来，
 * 此时让调用方在结果为空时给出格式提示，比整轮失败更有用。
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
