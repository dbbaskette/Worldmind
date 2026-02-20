package com.worldmind.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "worldmind")
public class SandboxProperties {

    private Sandbox sandbox = new Sandbox();
    private Goose goose = new Goose();

    // -- Sandbox accessors (delegate to nested) --
    public String getProvider() { return sandbox.provider; }
    public int getTimeoutSeconds() { return sandbox.timeoutSeconds; }
    public int getMemoryLimitMb() { return sandbox.memoryLimitMb; }
    public int getCpuCount() { return sandbox.cpuCount; }
    public int getMaxParallel() { return sandbox.maxParallel; }
    public int getWaveCooldownSeconds() { return sandbox.waveCooldownSeconds; }
    public String getImage() { return sandbox.image; }
    public String getImageRegistry() { return sandbox.imageRegistry; }
    public String getImagePrefix() { return sandbox.imagePrefix; }
    public boolean isWorktreesEnabled() { return sandbox.worktreesEnabled; }

    // -- Goose accessors (delegate to nested) --

    /**
     * Returns true when the Goose provider was explicitly configured via
     * {@code GOOSE_PROVIDER} env var or application property.
     * When false, agents should resolve provider/model from VCAP_SERVICES
     * (CF service bindings) instead of receiving overrides from the orchestrator.
     */
    public boolean isGooseProviderConfigured() {
        return goose.provider != null && !goose.provider.isBlank();
    }

    public String getGooseProvider() {
        if (goose.provider != null && !goose.provider.isBlank()) {
            return goose.provider;
        }
        // Auto-detect from API key env vars when GOOSE_PROVIDER isn't set
        String anthropicKey = System.getenv("ANTHROPIC_API_KEY");
        if (anthropicKey != null && !anthropicKey.isBlank()) return "anthropic";
        String googleKey = System.getenv("GOOGLE_API_KEY");
        if (googleKey != null && !googleKey.isBlank()) return "google";
        return "openai";
    }
    public String getGooseModel() { return goose.model; }
    public String getGooseServiceName() { return goose.serviceName; }
    public String getLmStudioUrl() { return goose.lmStudioUrl; }

    public Sandbox getSandbox() { return sandbox; }
    public void setSandbox(Sandbox sandbox) { this.sandbox = sandbox; }
    public Goose getGoose() { return goose; }
    public void setGoose(Goose goose) { this.goose = goose; }

    public static class Sandbox {
        private String provider = "docker";
        private int maxParallel = 1;
        private int timeoutSeconds = 300;
        private int waveCooldownSeconds = 60;
        private int memoryLimitMb = 4096;
        private int cpuCount = 2;
        private String imageRegistry = "ghcr.io/dbbaskette";
        private String imagePrefix = "sandbox";
        private String image = "ghcr.io/dbbaskette/agent-coder:latest";
        private boolean worktreesEnabled = false;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public int getMaxParallel() { return maxParallel; }
        public void setMaxParallel(int maxParallel) { this.maxParallel = maxParallel; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getWaveCooldownSeconds() { return waveCooldownSeconds; }
        public void setWaveCooldownSeconds(int waveCooldownSeconds) { this.waveCooldownSeconds = waveCooldownSeconds; }
        public int getMemoryLimitMb() { return memoryLimitMb; }
        public void setMemoryLimitMb(int memoryLimitMb) { this.memoryLimitMb = memoryLimitMb; }
        public int getCpuCount() { return cpuCount; }
        public void setCpuCount(int cpuCount) { this.cpuCount = cpuCount; }
        public String getImageRegistry() { return imageRegistry; }
        public void setImageRegistry(String imageRegistry) { this.imageRegistry = imageRegistry; }
        public String getImagePrefix() { return imagePrefix; }
        public void setImagePrefix(String imagePrefix) { this.imagePrefix = imagePrefix; }
        public String getImage() { return image; }
        public void setImage(String image) { this.image = image; }
        public boolean isWorktreesEnabled() { return worktreesEnabled; }
        public void setWorktreesEnabled(boolean worktreesEnabled) { this.worktreesEnabled = worktreesEnabled; }
    }

    public static class Goose {
        private String provider = "";
        private String model = "";
        private String serviceName = "";
        private String lmStudioUrl = "";

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        public String getLmStudioUrl() { return lmStudioUrl; }
        public void setLmStudioUrl(String lmStudioUrl) { this.lmStudioUrl = lmStudioUrl; }
    }
}
