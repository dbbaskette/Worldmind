package com.worldmind.core.llm;

import java.util.List;
import java.util.Map;

public class ModelCatalog {

    public record ModelInfo(
            String id,
            String name,
            String provider,
            String tier,
            double inputPricePer1M,
            double outputPricePer1M,
            int contextWindow,
            String description
    ) {
        public String priceDisplay() {
            return String.format("$%.2f / $%.2f per 1M tokens", inputPricePer1M, outputPricePer1M);
        }
    }

    public static final List<ModelInfo> ANTHROPIC_MODELS = List.of(
            new ModelInfo(
                    "claude-sonnet-4-20250514",
                    "Claude Sonnet 4",
                    "anthropic",
                    "flagship",
                    3.00, 15.00,
                    200000,
                    "Best balance of intelligence and speed"
            ),
            new ModelInfo(
                    "claude-opus-4-20250514",
                    "Claude Opus 4",
                    "anthropic",
                    "premium",
                    15.00, 75.00,
                    200000,
                    "Most capable, best for complex reasoning"
            ),
            new ModelInfo(
                    "claude-haiku-3-5-20241022",
                    "Claude 3.5 Haiku",
                    "anthropic",
                    "fast",
                    0.80, 4.00,
                    200000,
                    "Fastest, most cost-effective"
            )
    );

    public static final List<ModelInfo> OPENAI_MODELS = List.of(
            new ModelInfo(
                    "gpt-4o",
                    "GPT-4o",
                    "openai",
                    "flagship",
                    2.50, 10.00,
                    128000,
                    "Best overall value"
            ),
            new ModelInfo(
                    "gpt-4o-mini",
                    "GPT-4o Mini",
                    "openai",
                    "fast",
                    0.15, 0.60,
                    128000,
                    "Fast and affordable"
            ),
            new ModelInfo(
                    "gpt-5-nano",
                    "GPT-5 Nano",
                    "openai",
                    "fast",
                    0.05, 0.40,
                    400000,
                    "Fastest, most cost-efficient GPT-5"
            ),
            new ModelInfo(
                    "o1",
                    "o1",
                    "openai",
                    "reasoning",
                    15.00, 60.00,
                    200000,
                    "Advanced reasoning model"
            ),
            new ModelInfo(
                    "o3-mini",
                    "o3-mini",
                    "openai",
                    "reasoning",
                    1.10, 4.40,
                    200000,
                    "Efficient reasoning model"
            )
    );

    public static final List<ModelInfo> GOOGLE_MODELS = List.of(
            new ModelInfo(
                    "gemini-2.5-pro",
                    "Gemini 2.5 Pro",
                    "google",
                    "flagship",
                    1.25, 10.00,
                    1000000,
                    "Most capable Gemini model with thinking"
            ),
            new ModelInfo(
                    "gemini-2.5-flash",
                    "Gemini 2.5 Flash",
                    "google",
                    "fast",
                    0.15, 0.60,
                    1000000,
                    "Fast and affordable with thinking"
            ),
            new ModelInfo(
                    "gemini-2.0-flash",
                    "Gemini 2.0 Flash",
                    "google",
                    "fast",
                    0.10, 0.40,
                    1000000,
                    "Previous gen fast model"
            )
    );

    public static final Map<String, List<ModelInfo>> ALL_MODELS = Map.of(
            "anthropic", ANTHROPIC_MODELS,
            "openai", OPENAI_MODELS,
            "google", GOOGLE_MODELS
    );

    public static ModelInfo findModel(String provider, String modelId) {
        List<ModelInfo> models = ALL_MODELS.get(provider);
        if (models == null) return null;
        return models.stream()
                .filter(m -> m.id().equals(modelId))
                .findFirst()
                .orElse(null);
    }

    public static String getDefaultModel(String provider) {
        return switch (provider) {
            case "anthropic" -> "claude-sonnet-4-20250514";
            case "openai" -> "gpt-4o";
            case "google" -> "gemini-2.5-pro";
            default -> null;
        };
    }
}
