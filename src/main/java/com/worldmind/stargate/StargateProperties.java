package com.worldmind.stargate;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "worldmind")
public class StargateProperties {

    private Stargate stargate = new Stargate();
    private Goose goose = new Goose();

    // -- Stargate accessors (delegate to nested) --
    public String getProvider() { return stargate.provider; }
    public int getTimeoutSeconds() { return stargate.timeoutSeconds; }
    public int getMemoryLimitMb() { return stargate.memoryLimitMb; }
    public int getCpuCount() { return stargate.cpuCount; }
    public int getMaxParallel() { return stargate.maxParallel; }
    public int getWaveCooldownSeconds() { return stargate.waveCooldownSeconds; }
    public String getImage() { return stargate.image; }

    // -- Goose accessors (delegate to nested) --
    public String getGooseProvider() { return goose.provider; }
    public String getGooseModel() { return goose.model; }
    public String getLmStudioUrl() { return goose.lmStudioUrl; }

    public Stargate getStargate() { return stargate; }
    public void setStargate(Stargate stargate) { this.stargate = stargate; }
    public Goose getGoose() { return goose; }
    public void setGoose(Goose goose) { this.goose = goose; }

    public static class Stargate {
        private String provider = "docker";
        private int maxParallel = 1;
        private int timeoutSeconds = 300;
        private int waveCooldownSeconds = 60;
        private int memoryLimitMb = 4096;
        private int cpuCount = 2;
        private String image = "worldmind/centurion-forge:latest";

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
