package com.worldmind.starblaster;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "worldmind")
public class StarblasterProperties {

    private Starblaster starblaster = new Starblaster();
    private Goose goose = new Goose();

    // -- Starblaster accessors (delegate to nested) --
    public String getProvider() { return starblaster.provider; }
    public int getTimeoutSeconds() { return starblaster.timeoutSeconds; }
    public int getMemoryLimitMb() { return starblaster.memoryLimitMb; }
    public int getCpuCount() { return starblaster.cpuCount; }
    public int getMaxParallel() { return starblaster.maxParallel; }
    public int getWaveCooldownSeconds() { return starblaster.waveCooldownSeconds; }
    public String getImage() { return starblaster.image; }
    public String getImageRegistry() { return starblaster.imageRegistry; }
    public String getImagePrefix() { return starblaster.imagePrefix; }

    // -- Goose accessors (delegate to nested) --
    public String getGooseProvider() { return goose.provider; }
    public String getGooseModel() { return goose.model; }
    public String getLmStudioUrl() { return goose.lmStudioUrl; }

    public Starblaster getStarblaster() { return starblaster; }
    public void setStarblaster(Starblaster starblaster) { this.starblaster = starblaster; }
    public Goose getGoose() { return goose; }
    public void setGoose(Goose goose) { this.goose = goose; }

    public static class Starblaster {
        private String provider = "docker";
        private int maxParallel = 1;
        private int timeoutSeconds = 300;
        private int waveCooldownSeconds = 60;
        private int memoryLimitMb = 4096;
        private int cpuCount = 2;
        private String imageRegistry = "ghcr.io/dbbaskette";
        private String imagePrefix = "starblaster";
        private String image = "ghcr.io/dbbaskette/centurion-forge:latest";

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
    }

    public static class Goose {
        private String provider = "openai";
        private String model = "qwen2.5-coder-32b";
        private String lmStudioUrl = "http://host.docker.internal:1234/v1";

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getLmStudioUrl() { return lmStudioUrl; }
        public void setLmStudioUrl(String lmStudioUrl) { this.lmStudioUrl = lmStudioUrl; }
    }
}
