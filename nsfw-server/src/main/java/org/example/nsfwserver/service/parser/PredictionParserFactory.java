package org.example.nsfwserver.service.parser;

import org.example.nsfwserver.config.ModelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 预测解析器工厂
 * 根据模型类型创建相应的预测解析器
 */
@Component
public class PredictionParserFactory {
    private static final Logger logger = LoggerFactory.getLogger(PredictionParserFactory.class);

    private final ModelConfig modelConfig;

    public PredictionParserFactory(ModelConfig modelConfig) {
        this.modelConfig = modelConfig;
    }

    /**
     * 根据模型类型创建预测解析器
     * @return 对应的预测解析器实例
     */
    public PredictionParser createParser() {
        String modelType = modelConfig.getType();
        logger.info("Creating prediction parser for model type: {}", modelType);

        // 支持动态模型类型，避免硬编码判断
        // 默认映射已知模型类型到相应解析器
        if ("5class".equals(modelType) || "original".equals(modelType)) {
            logger.info("Using FiveClassParser for 5-class model");
            return new FiveClassParser();
        } else if ("2class".equals(modelType) || "falconsai".equals(modelType)) {
            logger.info("Using TwoClassParser for 2-class model");
            return new TwoClassParser();
        } else {
            // 未知模型类型：使用默认解析器并记录警告
            // 用户自定义模型需要确保输出格式与默认解析器兼容
            logger.warn("Unknown model type '{}', defaulting to FiveClassParser. " +
                "Custom models should be configured as '5class' or 'falconsai' for proper parsing.", modelType);
            return new FiveClassParser();
        }
    }

    /**
     * 根据给定的模型类型创建预测解析器（覆盖配置中的类型）
     * @param modelType 模型类型
     * @return 对应的预测解析器实例
     */
    public PredictionParser createParser(String modelType) {
        logger.info("Creating prediction parser for specified model type: {}", modelType);

        // 支持动态模型类型，避免硬编码判断
        // 默认映射已知模型类型到相应解析器
        if ("5class".equals(modelType) || "original".equals(modelType)) {
            logger.info("Using FiveClassParser for 5-class model");
            return new FiveClassParser();
        } else if ("2class".equals(modelType) || "falconsai".equals(modelType)) {
            logger.info("Using TwoClassParser for 2-class model");
            return new TwoClassParser();
        } else {
            // 未知模型类型：使用默认解析器并记录警告
            // 用户自定义模型需要确保输出格式与默认解析器兼容
            logger.warn("Unknown model type '{}', defaulting to FiveClassParser. " +
                "Custom models should be configured as '5class' or 'falconsai' for proper parsing.", modelType);
            return new FiveClassParser();
        }
    }
}