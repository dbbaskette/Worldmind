package com.worldmind.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the deployer agent.
 * Bound from {@code deployer.*} in application YAML.
 */
@Component
@ConfigurationProperties(prefix = "deployer")
public class DeployerProperties {

    private int healthTimeout = 300;
    private DeployerDefaults defaults = new DeployerDefaults();

    public int getHealthTimeout() {
        return healthTimeout;
    }

    public void setHealthTimeout(int healthTimeout) {
        this.healthTimeout = healthTimeout;
    }

    public DeployerDefaults getDefaults() {
        return defaults;
    }

    public void setDefaults(DeployerDefaults defaults) {
        this.defaults = defaults;
    }

    public static class DeployerDefaults {
        private String memory = "1G";
        private int instances = 1;
        private String healthCheckType = "http";
        private String healthCheckPath = "/actuator/health";
        private String buildpack = "java_buildpack_offline";
        private int javaVersion = 21;

        public String getMemory() {
            return memory;
        }

        public void setMemory(String memory) {
            this.memory = memory;
        }

        public int getInstances() {
            return instances;
        }

        public void setInstances(int instances) {
            this.instances = instances;
        }

        public String getHealthCheckType() {
            return healthCheckType;
        }

        public void setHealthCheckType(String healthCheckType) {
            this.healthCheckType = healthCheckType;
        }

        public String getHealthCheckPath() {
            return healthCheckPath;
        }

        public void setHealthCheckPath(String healthCheckPath) {
            this.healthCheckPath = healthCheckPath;
        }

        public String getBuildpack() {
            return buildpack;
        }

        public void setBuildpack(String buildpack) {
            this.buildpack = buildpack;
        }

        public int getJavaVersion() {
            return javaVersion;
        }

        public void setJavaVersion(int javaVersion) {
            this.javaVersion = javaVersion;
        }
    }
}
