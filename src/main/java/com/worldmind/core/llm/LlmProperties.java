package com.worldmind.core.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "worldmind.llm")
public class LlmProperties {

    private String provider = "";
    private String model = "";
    private String anthropicApiKey = "";
    private String openaiApiKey = "";
    private String googleApiKey = "";

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getAnthropicApiKey() {
        return anthropicApiKey;
    }

    public void setAnthropicApiKey(String anthropicApiKey) {
        this.anthropicApiKey = anthropicApiKey;
    }

    public String getOpenaiApiKey() {
        return openaiApiKey;
    }

    public void setOpenaiApiKey(String openaiApiKey) {
        this.openaiApiKey = openaiApiKey;
    }

    public String getGoogleApiKey() {
        return googleApiKey;
    }

    public void setGoogleApiKey(String googleApiKey) {
        this.googleApiKey = googleApiKey;
    }

    public boolean hasAnthropicKey() {
        return anthropicApiKey != null && !anthropicApiKey.isBlank();
    }

    public boolean hasOpenaiKey() {
        return openaiApiKey != null && !openaiApiKey.isBlank();
    }

    public boolean hasGoogleKey() {
        return googleApiKey != null && !googleApiKey.isBlank();
    }
}
