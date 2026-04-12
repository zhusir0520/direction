package org.example.nsfwserver.config;

import ai.djl.Device;
import ai.djl.engine.Engine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

@Configuration
public class DjlConfig {
    private static final Logger logger = LoggerFactory.getLogger(DjlConfig.class);

    @PostConstruct
    public void init() {
        logger.info("Initializing DJL configuration...");

        // 记录可用引擎
        String engineName = Engine.getInstance().getEngineName();
        String engineVersion = Engine.getInstance().getVersion();
        logger.info("DJL Engine: {} v{}", engineName, engineVersion);

        // 记录可用设备
        int gpuCount = Engine.getInstance().getGpuCount();
        logger.info("Available GPUs: {}", gpuCount);

        if (gpuCount > 0) {
            logger.info("Using GPU for inference");
            for (int i = 0; i < gpuCount; i++) {
                Device device = Device.gpu(i);
                logger.info("GPU {}: {}", i, device);
            }
        } else {
            logger.info("No GPU available, using CPU for inference");
            logger.info("CPU Device: {}", Device.cpu());
        }

        // 记录引擎配置
        logger.info("Engine default device: {}", Engine.getInstance().defaultDevice());

        logger.info("DJL configuration initialized");
    }
}