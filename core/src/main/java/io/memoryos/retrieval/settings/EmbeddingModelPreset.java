package io.memoryos.retrieval.settings;

import java.util.List;

/**
 * A known embedding model whose dimensions and prefixes the administration page fills in. Instruction-tuned models
 * (Qwen3-Embedding, multilingual-e5-instruct) take an instruction before the question and nothing before passages.
 */
public record EmbeddingModelPreset(String model, String label, int dimensions, String queryPrefix, String documentPrefix,
        int maxInputTokens) {

    static final String QUESTION_INSTRUCTION = "Instruct: Given a question, retrieve passages that answer it\nQuery: ";

    public static final List<EmbeddingModelPreset> KNOWN = List.of(
            new EmbeddingModelPreset("Qwen/Qwen3-Embedding-0.6B", "Qwen3-Embedding 0.6B", 1024, QUESTION_INSTRUCTION, "", 32768),
            new EmbeddingModelPreset("Qwen/Qwen3-Embedding-4B", "Qwen3-Embedding 4B", 2560, QUESTION_INSTRUCTION, "", 32768),
            new EmbeddingModelPreset("Qwen/Qwen3-Embedding-8B", "Qwen3-Embedding 8B", 4096, QUESTION_INSTRUCTION, "", 32768),
            new EmbeddingModelPreset("BAAI/bge-m3", "BGE-M3", 1024, "", "", 8192),
            new EmbeddingModelPreset("text-embedding-3-small", "OpenAI text-embedding-3-small", 1536, "", "", 8191),
            new EmbeddingModelPreset("text-embedding-3-large", "OpenAI text-embedding-3-large", 3072, "", "", 8191),
            new EmbeddingModelPreset("intfloat/multilingual-e5-large-instruct", "multilingual-e5-large-instruct", 1024,
                    QUESTION_INSTRUCTION, "", 512));
}
