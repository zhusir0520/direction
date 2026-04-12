package org.example.nsfwserver.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.List;
import java.util.HashMap;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class NsfwResponse {
    private Boolean isNsfw;
    private Double confidence;
    private String error;
    private String modelOutput; // 保持向后兼容的原始JSON字符串

    // 结构化模型结果
    private List<ModelResult> modelResults;


    // 结构化YOLO结果
    private YoloResult yoloResult;

    // 调试图片
    private Map<String, DebugImage> debugImages;

    public NsfwResponse() {
    }

    public NsfwResponse(Boolean isNsfw, Double confidence) {
        this.isNsfw = isNsfw;
        this.confidence = confidence;
    }

    public NsfwResponse(String error) {
        this.error = error;
    }

    public static NsfwResponse success(boolean isNsfw, double confidence) {
        return new NsfwResponse(isNsfw, confidence);
    }

    public static NsfwResponse success(boolean isNsfw, double confidence, String modelOutput) {
        NsfwResponse response = new NsfwResponse(isNsfw, confidence);
        response.setModelOutput(modelOutput);
        return response;
    }

    public static NsfwResponse error(String error) {
        return new NsfwResponse(error);
    }

    // Getters and Setters
    public Boolean getIsNsfw() {
        return isNsfw;
    }

    public void setIsNsfw(Boolean isNsfw) {
        this.isNsfw = isNsfw;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getModelOutput() {
        return modelOutput;
    }

    public void setModelOutput(String modelOutput) {
        this.modelOutput = modelOutput;
    }


    // 新字段的getter和setter
    public List<ModelResult> getModelResults() {
        return modelResults;
    }

    public void setModelResults(List<ModelResult> modelResults) {
        this.modelResults = modelResults;
    }

    public YoloResult getYoloResult() {
        return yoloResult;
    }

    public void setYoloResult(YoloResult yoloResult) {
        this.yoloResult = yoloResult;
    }

    public Map<String, DebugImage> getDebugImages() {
        return debugImages;
    }

    public void setDebugImages(Map<String, DebugImage> debugImages) {
        this.debugImages = debugImages;
    }

    // 内部类：模型结果
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ModelResult {
        private String model;
        private Boolean isNsfw;
        private Double confidence;
        private Double threshold;

        public ModelResult() {
        }

        public ModelResult(String model, Boolean isNsfw, Double confidence, Double threshold) {
            this.model = model;
            this.isNsfw = isNsfw;
            this.confidence = confidence;
            this.threshold = threshold;
        }

        // Getters and Setters
        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Boolean getIsNsfw() {
            return isNsfw;
        }

        public void setIsNsfw(Boolean isNsfw) {
            this.isNsfw = isNsfw;
        }

        public Double getConfidence() {
            return confidence;
        }

        public void setConfidence(Double confidence) {
            this.confidence = confidence;
        }

        public Double getThreshold() {
            return threshold;
        }

        public void setThreshold(Double threshold) {
            this.threshold = threshold;
        }
    }

    // 内部类：YOLO结果
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class YoloResult {
        private Boolean triggered;
        private Boolean detectedObjects;
        private String unionBox; // 格式："left,top,right,bottom"（归一化坐标）
        private Boolean backendIsNsfw;
        private Float backendConfidence;
        private Map<String, Float> backendRawScores;

        public YoloResult() {
        }

        public YoloResult(Boolean triggered, Boolean detectedObjects, String unionBox,
                         Boolean backendIsNsfw, Float backendConfidence, Map<String, Float> backendRawScores) {
            this.triggered = triggered;
            this.detectedObjects = detectedObjects;
            this.unionBox = unionBox;
            this.backendIsNsfw = backendIsNsfw;
            this.backendConfidence = backendConfidence;
            this.backendRawScores = backendRawScores;
        }

        // Getters and Setters
        public Boolean getTriggered() {
            return triggered;
        }

        public void setTriggered(Boolean triggered) {
            this.triggered = triggered;
        }

        public Boolean getDetectedObjects() {
            return detectedObjects;
        }

        public void setDetectedObjects(Boolean detectedObjects) {
            this.detectedObjects = detectedObjects;
        }

        public String getUnionBox() {
            return unionBox;
        }

        public void setUnionBox(String unionBox) {
            this.unionBox = unionBox;
        }

        public Boolean getBackendIsNsfw() {
            return backendIsNsfw;
        }

        public void setBackendIsNsfw(Boolean backendIsNsfw) {
            this.backendIsNsfw = backendIsNsfw;
        }

        public Float getBackendConfidence() {
            return backendConfidence;
        }

        public void setBackendConfidence(Float backendConfidence) {
            this.backendConfidence = backendConfidence;
        }

        public Map<String, Float> getBackendRawScores() {
            return backendRawScores;
        }

        public void setBackendRawScores(Map<String, Float> backendRawScores) {
            this.backendRawScores = backendRawScores;
        }
    }

    // 内部类：调试图片
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DebugImage {
        private String base64; // Base64编码的图片数据
        private Integer width; // 图片宽度
        private Integer height; // 图片高度
        private String description; // 图片用途描述
        private String format = "jpg"; // 图片格式，默认jpg

        public DebugImage() {
        }

        public DebugImage(String base64, Integer width, Integer height, String description) {
            this.base64 = base64;
            this.width = width;
            this.height = height;
            this.description = description;
        }

        public DebugImage(String base64, Integer width, Integer height, String description, String format) {
            this.base64 = base64;
            this.width = width;
            this.height = height;
            this.description = description;
            this.format = format;
        }

        // Getters and Setters
        public String getBase64() {
            return base64;
        }

        public void setBase64(String base64) {
            this.base64 = base64;
        }

        public Integer getWidth() {
            return width;
        }

        public void setWidth(Integer width) {
            this.width = width;
        }

        public Integer getHeight() {
            return height;
        }

        public void setHeight(Integer height) {
            this.height = height;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }
    }
}