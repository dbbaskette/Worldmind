package com.worldmind.dispatch.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.worldmind.core.llm.LlmProperties;
import com.worldmind.core.llm.ModelCatalog;
import com.worldmind.core.llm.ModelCatalog.ModelInfo;
import com.worldmind.sandbox.SandboxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.*;

@RestController
@RequestMapping("/api/v1/llm")
public class LlmController {

    private static final Logger log = LoggerFactory.getLogger(LlmController.class);
    private static final String SESSION_PROVIDER_KEY = "worldmind_llm_provider";
    private static final String SESSION_MODEL_KEY = "worldmind_llm_model";

    private final LlmProperties llmProperties;
    private final SandboxProperties sandboxProperties;

    public LlmController(LlmProperties llmProperties, SandboxProperties sandboxProperties) {
        this.llmProperties = llmProperties;
        this.sandboxProperties = sandboxProperties;
    }

    @GetMapping("/providers")
    public ResponseEntity<Map<String, Object>> getProviders(HttpSession session) {
        List<Map<String, Object>> providers = new ArrayList<>();

        // Always show all providers - mark as available/unavailable based on API key
        providers.add(buildProviderInfo("anthropic", "Anthropic", ModelCatalog.ANTHROPIC_MODELS, llmProperties.hasAnthropicKey()));
        providers.add(buildProviderInfo("openai", "OpenAI", ModelCatalog.OPENAI_MODELS, llmProperties.hasOpenaiKey()));
        providers.add(buildProviderInfo("google", "Google", ModelCatalog.GOOGLE_MODELS, llmProperties.hasGoogleKey()));

        // Add bound service if present
        Map<String, String> boundService = resolveBoundService();
        if (boundService != null) {
            Map<String, Object> genaiProvider = new LinkedHashMap<>();
            genaiProvider.put("id", "genai");
            genaiProvider.put("name", "Bound Service");
            genaiProvider.put("available", true);

            Map<String, Object> boundModel = new LinkedHashMap<>();
            boundModel.put("id", boundService.getOrDefault("model", "unknown"));
            boundModel.put("name", boundService.getOrDefault("model", "Bound Model"));
            boundModel.put("provider", "genai");
            boundModel.put("tier", "bound");
            boundModel.put("inputPricePer1M", 0.0);
            boundModel.put("outputPricePer1M", 0.0);
            boundModel.put("contextWindow", 0);
            boundModel.put("description", "Model from bound " + boundService.getOrDefault("provider", "genai") + " service");
            boundModel.put("priceDisplay", "Included with service");

            genaiProvider.put("models", List.of(boundModel));
            providers.add(0, genaiProvider); // Add bound service first
        }

        String currentProvider = (String) session.getAttribute(SESSION_PROVIDER_KEY);
        String currentModel = (String) session.getAttribute(SESSION_MODEL_KEY);

        if (currentProvider == null || currentProvider.isBlank()) {
            currentProvider = llmProperties.getProvider();
        }
        if (currentModel == null || currentModel.isBlank()) {
            currentModel = llmProperties.getModel();
        }

        if ((currentProvider == null || currentProvider.isBlank()) && !providers.isEmpty()) {
            Map<String, Object> firstProvider = providers.get(0);
            currentProvider = (String) firstProvider.get("id");
            currentModel = ModelCatalog.getDefaultModel(currentProvider);
            if (currentModel == null && firstProvider.get("models") instanceof List<?> models && !models.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> firstModel = (Map<String, Object>) models.get(0);
                currentModel = (String) firstModel.get("id");
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("providers", providers);
        result.put("currentProvider", currentProvider);
        result.put("currentModel", currentModel);

        return ResponseEntity.ok(result);
    }

    @PostMapping("/select")
    public ResponseEntity<Map<String, Object>> selectModel(
            @RequestBody SelectModelRequest request,
            HttpSession session) {

        String provider = request.provider();
        String model = request.model();

        if (!isProviderAvailable(provider)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Provider not available or API key not configured: " + provider
            ));
        }

        session.setAttribute(SESSION_PROVIDER_KEY, provider);
        session.setAttribute(SESSION_MODEL_KEY, model);

        log.info("User selected LLM provider={}, model={}", provider, model);

        ModelInfo modelInfo = ModelCatalog.findModel(provider, model);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", provider);
        result.put("model", model);
        if (modelInfo != null) {
            result.put("modelName", modelInfo.name());
            result.put("priceDisplay", modelInfo.priceDisplay());
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/current")
    public ResponseEntity<Map<String, Object>> getCurrentSelection(HttpSession session) {
        String provider = (String) session.getAttribute(SESSION_PROVIDER_KEY);
        String model = (String) session.getAttribute(SESSION_MODEL_KEY);

        if (provider == null || provider.isBlank()) {
            provider = llmProperties.getProvider();
            model = llmProperties.getModel();
        }

        if (provider == null || provider.isBlank()) {
            Map<String, String> boundService = resolveBoundService();
            if (boundService != null) {
                provider = "genai";
                model = boundService.getOrDefault("model", "unknown");
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", provider);
        result.put("model", model);

        ModelInfo modelInfo = ModelCatalog.findModel(provider, model);
        if (modelInfo != null) {
            result.put("modelName", modelInfo.name());
            result.put("tier", modelInfo.tier());
            result.put("priceDisplay", modelInfo.priceDisplay());
        } else if ("genai".equals(provider)) {
            result.put("modelName", model);
            result.put("tier", "bound");
            result.put("priceDisplay", "Included with service");
        }

        return ResponseEntity.ok(result);
    }

    private Map<String, Object> buildProviderInfo(String id, String name, List<ModelInfo> models, boolean available) {
        Map<String, Object> provider = new LinkedHashMap<>();
        provider.put("id", id);
        provider.put("name", name);
        provider.put("available", available);

        List<Map<String, Object>> modelList = new ArrayList<>();
        for (ModelInfo m : models) {
            Map<String, Object> modelMap = new LinkedHashMap<>();
            modelMap.put("id", m.id());
            modelMap.put("name", m.name());
            modelMap.put("provider", m.provider());
            modelMap.put("tier", m.tier());
            modelMap.put("inputPricePer1M", m.inputPricePer1M());
            modelMap.put("outputPricePer1M", m.outputPricePer1M());
            modelMap.put("contextWindow", m.contextWindow());
            modelMap.put("description", m.description());
            modelMap.put("priceDisplay", m.priceDisplay());
            modelList.add(modelMap);
        }
        provider.put("models", modelList);

        return provider;
    }

    private boolean isProviderAvailable(String provider) {
        return switch (provider) {
            case "anthropic" -> llmProperties.hasAnthropicKey();
            case "openai" -> llmProperties.hasOpenaiKey();
            case "google" -> llmProperties.hasGoogleKey();
            case "genai" -> resolveBoundService() != null;
            default -> false;
        };
    }

    private Map<String, String> resolveBoundService() {
        String vcapServices = System.getenv("VCAP_SERVICES");
        if (vcapServices == null || vcapServices.isBlank()) {
            return null;
        }
        try {
            String serviceName = sandboxProperties.getGooseServiceName();
            if (serviceName == null || serviceName.isBlank()) {
                return null;
            }
            
            JsonNode root = new ObjectMapper().readTree(vcapServices);
            var labels = root.fields();
            while (labels.hasNext()) {
                var label = labels.next();
                String labelKey = label.getKey().toLowerCase();
                
                // Skip non-LLM services (databases, caches, etc.)
                if (labelKey.contains("postgres") || labelKey.contains("mysql") || 
                    labelKey.contains("redis") || labelKey.contains("rabbit") ||
                    labelKey.contains("mongo") || labelKey.contains("sql")) {
                    continue;
                }
                
                for (JsonNode svc : label.getValue()) {
                    String name = svc.has("name") ? svc.get("name").asText() : "";
                    if (!name.equals(serviceName)) {
                        continue;
                    }

                    Map<String, String> resolved = new LinkedHashMap<>();
                    JsonNode creds = svc.get("credentials");
                    if (creds != null) {
                        // Only consider it a valid LLM service if it has LLM-specific credentials
                        boolean hasLlmCredentials = creds.has("model_provider") || 
                                                    creds.has("model_name") || 
                                                    creds.has("model") ||
                                                    creds.has("api_key") ||
                                                    creds.has("api_base");
                        if (!hasLlmCredentials) {
                            continue;
                        }
                        
                        if (creds.has("model_provider")) {
                            resolved.put("provider", creds.get("model_provider").asText());
                        }
                        String modelName = creds.has("model_name") ? creds.get("model_name").asText()
                                : creds.has("model") ? creds.get("model").asText() : null;
                        if (modelName != null) {
                            resolved.put("model", modelName);
                        }
                    }

                    if (!resolved.containsKey("model") && svc.has("plan")) {
                        resolved.put("model", svc.get("plan").asText());
                    }
                    if (!resolved.containsKey("provider")) {
                        resolved.put("provider", label.getKey());
                    }

                    if (!resolved.isEmpty()) {
                        return resolved;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to resolve bound service: {}", e.getMessage());
        }
        return null;
    }

    public record SelectModelRequest(String provider, String model) {}
}
