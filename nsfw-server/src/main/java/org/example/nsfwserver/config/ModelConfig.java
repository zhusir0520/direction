package org.example.nsfwserver.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Arrays;

@Component
@ConfigurationProperties(prefix = "nsfw.model")
public class ModelConfig {
    private static final Logger logger = LoggerFactory.getLogger(ModelConfig.class);
    // 模型类型配置
    private String type = "5class";  // 默认使用五分类模型，可选: 5class, 2class, falconsai, original
    private String path;  // 模型文件路径，如未指定则根据类型自动选择
    private String labelsPath;  // 标签文件路径，如未指定则根据类型自动选择
    private double threshold = 0.75;  // NSFW判定阈值

    // 归一化配置
    private String normalizationType = "vit";  // 可选: vit, imagenet, custom
    private String mean = "0.5,0.5,0.5";  // 逗号分隔的三个浮点数，对应RGB通道
    private String std = "0.5,0.5,0.5";   // 逗号分隔的三个浮点数，对应RGB通道

    // 预处理配置
    private int inputWidth = 224;  // 输入图像宽度
    private int inputHeight = 224; // 输入图像高度

    // YOLOv8n配置
    private boolean yoloEnabled = true;
    private float yoloConfidenceThreshold = 0.4f;
    private float yoloIouThreshold = 0.7f;
    private String yoloModelPath = "classpath:models/yolov8n.onnx";

    // 模型加载模式配置
    private String modelLoadMode = "eager";  // 可选值: instant(按需加载), eager(启动加载)

    // 辅助字段（缓存解析结果）
    private float[] meanArray;
    private float[] stdArray;

    @PostConstruct
    public void init() {
        // 初始化时解析数组
        meanArray = parseFloatArray(mean);
        stdArray = parseFloatArray(std);
    }

    // 辅助方法：将字符串转换为float数组
    public float[] getMeanArray() {
        if (meanArray == null) {
            meanArray = parseFloatArray(mean);
        }
        return meanArray;
    }

    public float[] getStdArray() {
        if (stdArray == null) {
            stdArray = parseFloatArray(std);
        }
        return stdArray;
    }

    private float[] parseFloatArray(String str) {
        if (str == null || str.trim().isEmpty()) {
            // 返回默认值
            return new float[]{0.5f, 0.5f, 0.5f};
        }
        String[] parts = str.split(",");
        float[] array = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            array[i] = Float.parseFloat(parts[i].trim());
        }
        return array;
    }

    // 获取模型路径（如果未指定，根据类型选择默认值）
    public String getEffectiveModelPath() {
        if (path != null && !path.trim().isEmpty()) {
            return path;
        }
        // 根据模型类型选择默认模型
        // 支持已知模型类型，对未知类型尝试通用路径
        switch (type) {
            case "5class":
            case "original":
                return "classpath:models/model.onnx";
            case "2class":
            case "falconsai":
                return "classpath:models/falconsai_yolov9_nsfw_model.pt";
            default:
                // 未知模型类型：尝试通用路径，如果文件不存在会由加载器处理
                String defaultPath = "classpath:models/" + type + ".onnx";
                logger.info("Unknown model type '{}', trying default path: {}", type, defaultPath);
                return defaultPath;
        }
    }

    // 获取标签路径（如果未指定，根据类型选择默认值）
    public String getEffectiveLabelsPath() {
        if (labelsPath != null && !labelsPath.trim().isEmpty()) {
            return labelsPath;
        }
        // 根据模型类型选择默认标签文件
        // 支持已知模型类型，对未知类型尝试通用路径
        switch (type) {
            case "5class":
            case "original":
                return "classpath:models/labels.txt";
            case "2class":
            case "falconsai":
                // 优先尝试 labels.json，如果不存在则使用 labels_falconsai.txt
                return "classpath:models/labels.json";
            default:
                // 未知模型类型：尝试通用标签文件，如果文件不存在会由加载器处理
                String defaultPath = "classpath:models/labels_" + type + ".txt";
                logger.info("Unknown model type '{}', trying default labels path: {}", type, defaultPath);
                return defaultPath;
        }
    }

    // Getters 和 Setters
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getLabelsPath() {
        return labelsPath;
    }

    public void setLabelsPath(String labelsPath) {
        this.labelsPath = labelsPath;
    }

    public double getThreshold() {
        return threshold;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public String getNormalizationType() {
        return normalizationType;
    }

    public void setNormalizationType(String normalizationType) {
        this.normalizationType = normalizationType;
    }

    public String getMean() {
        return mean;
    }

    public void setMean(String mean) {
        this.mean = mean;
        this.meanArray = null; // 清空缓存
    }

    public String getStd() {
        return std;
    }

    public void setStd(String std) {
        this.std = std;
        this.stdArray = null; // 清空缓存
    }

    public int getInputWidth() {
        return inputWidth;
    }

    public void setInputWidth(int inputWidth) {
        this.inputWidth = inputWidth;
    }

    public int getInputHeight() {
        return inputHeight;
    }

    public void setInputHeight(int inputHeight) {
        this.inputHeight = inputHeight;
    }

    // YOLOv8n配置的getter和setter
    public boolean isYoloEnabled() {
        return yoloEnabled;
    }

    public void setYoloEnabled(boolean yoloEnabled) {
        this.yoloEnabled = yoloEnabled;
    }

    public float getYoloConfidenceThreshold() {
        return yoloConfidenceThreshold;
    }

    public void setYoloConfidenceThreshold(float yoloConfidenceThreshold) {
        this.yoloConfidenceThreshold = yoloConfidenceThreshold;
    }

    public String getYoloModelPath() {
        return yoloModelPath;
    }

    public void setYoloModelPath(String yoloModelPath) {
        this.yoloModelPath = yoloModelPath;
    }

    public float getYoloIouThreshold() {
        return yoloIouThreshold;
    }

    public void setYoloIouThreshold(float yoloIouThreshold) {
        this.yoloIouThreshold = yoloIouThreshold;
    }

    public String getModelLoadMode() {
        return modelLoadMode;
    }

    public void setModelLoadMode(String modelLoadMode) {
        this.modelLoadMode = modelLoadMode;
    }

    @Override
    public String toString() {
        return "ModelConfig{" +
                "type='" + type + '\'' +
                ", path='" + path + '\'' +
                ", labelsPath='" + labelsPath + '\'' +
                ", threshold=" + threshold +
                ", normalizationType='" + normalizationType + '\'' +
                ", mean='" + mean + '\'' +
                ", std='" + std + '\'' +
                ", inputWidth=" + inputWidth +
                ", inputHeight=" + inputHeight +
                ", yoloEnabled=" + yoloEnabled +
                ", yoloConfidenceThreshold=" + yoloConfidenceThreshold +
                ", yoloIouThreshold=" + yoloIouThreshold +
                ", yoloModelPath='" + yoloModelPath + '\'' +
                ", modelLoadMode='" + modelLoadMode + '\'' +
                ", meanArray=" + Arrays.toString(getMeanArray()) +
                ", stdArray=" + Arrays.toString(getStdArray()) +
                '}';
    }
}