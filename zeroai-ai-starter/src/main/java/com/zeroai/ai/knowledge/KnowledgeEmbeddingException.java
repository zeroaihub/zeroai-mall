package com.zeroai.ai.knowledge;

/**
 * 向量化失败。
 * <p>
 * 单独一种异常类型而不是复用 {@link IllegalStateException}：调用方需要区分
 * 「embedding 服务不可用」（能力降级，建议稍后再试）与「检索逻辑出错」（代码缺陷），
 * 两者的日志级别与提示话术不同。
 */
public class KnowledgeEmbeddingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message 错误说明，不得包含密钥
     */
    public KnowledgeEmbeddingException(String message) {
        super(message);
    }

    /**
     * @param message 错误说明，不得包含密钥
     * @param cause   原始异常
     */
    public KnowledgeEmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
