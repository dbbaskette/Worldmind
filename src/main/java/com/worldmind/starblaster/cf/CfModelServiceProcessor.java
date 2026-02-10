package com.worldmind.starblaster.cf;

import io.pivotal.cfenv.core.CfCredentials;
import io.pivotal.cfenv.core.CfService;
import io.pivotal.cfenv.spring.boot.CfEnvProcessor;
import io.pivotal.cfenv.spring.boot.CfEnvProcessorProperties;

import java.util.Map;

/**
 * Maps the {@code worldmind-model} CF service binding to Spring AI OpenAI properties.
 * <p>
 * The bound service provides an OpenAI-compatible endpoint (URL + API key).
 * This processor extracts those credentials from VCAP_SERVICES and sets:
 * <ul>
 *   <li>{@code spring.ai.openai.base-url} — the service API URL</li>
 *   <li>{@code spring.ai.openai.api-key} — the service API key</li>
 * </ul>
 */
public class CfModelServiceProcessor implements CfEnvProcessor {

    private static final String SERVICE_NAME = "worldmind-model";

    @Override
    public boolean accept(CfService service) {
        return SERVICE_NAME.equals(service.getName());
    }

    @Override
    public void process(CfCredentials credentials, Map<String, Object> properties) {
        var credMap = credentials.getMap();

        // Extract API URL — services may use "uri", "url", or "api_url"
        var apiUrl = firstNonNull(credMap, "uri", "url", "api_url", "api_base");
        if (apiUrl != null) {
            properties.put("spring.ai.openai.base-url", apiUrl.toString());
        }

        // Extract API key — services may use "api_key", "apiKey", or "key"
        var apiKey = firstNonNull(credMap, "api_key", "apiKey", "key", "api-key");
        if (apiKey != null) {
            properties.put("spring.ai.openai.api-key", apiKey.toString());
        }

        // Model name from credentials or GOOSE_MODEL env var
        var model = firstNonNull(credMap, "model", "model_name");
        if (model != null) {
            properties.put("spring.ai.openai.chat.options.model", model.toString());
        } else {
            var envModel = System.getenv("GOOSE_MODEL");
            if (envModel != null && !envModel.isBlank()) {
                properties.put("spring.ai.openai.chat.options.model", envModel);
            }
        }
    }

    @Override
    public CfEnvProcessorProperties getProperties() {
        return CfEnvProcessorProperties.builder()
                .propertyPrefixes("spring.ai.openai")
                .serviceName(SERVICE_NAME)
                .build();
    }

    private static Object firstNonNull(Map<String, Object> map, String... keys) {
        for (var key : keys) {
            var value = map.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
