package org.example.nsfwserver.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.nsfwserver.config.ModelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class LabelLoader {
    private static final Logger logger = LoggerFactory.getLogger(LabelLoader.class);

    private final ModelConfig config;
    private String[] cachedLabels;

    public LabelLoader(ModelConfig config) {
        this.config = config;
    }

    public String[] loadLabels() {
        if (cachedLabels != null) {
            return cachedLabels;
        }

        String labelsPath = config.getEffectiveLabelsPath();
        String modelType = config.getType();

        try {
            // 优先尝试从文件加载标签
            if (labelsPath != null && !labelsPath.trim().isEmpty()) {
                cachedLabels = loadLabelsFromFile(labelsPath);
                if (cachedLabels != null && cachedLabels.length > 0) {
                    logger.info("Labels loaded from file: {} ({} labels)", labelsPath, cachedLabels.length);
                    return cachedLabels;
                }
            }

            // 如果文件加载失败或无路径，使用基于模型类型的默认标签
            // 支持动态模型类型，避免硬编码判断
            if ("5class".equals(modelType) || "original".equals(modelType)) {
                cachedLabels = new String[]{"drawing", "hentai", "neutral", "porn", "sexy"};
                logger.info("Using default 5-class labels for model type: {}", modelType);
            } else if ("2class".equals(modelType) || "falconsai".equals(modelType)) {
                cachedLabels = new String[]{"nsfw", "normal"};
                logger.info("Using default 2-class labels for model type: {}", modelType);
            } else {
                // 未知模型类型：使用通用默认标签
                logger.warn("Unknown model type '{}', using generic default labels. " +
                    "Custom models should provide label files or be configured as '5class' or 'falconsai'.", modelType);
                // 创建通用标签：class_0, class_1, ..., class_9
                cachedLabels = new String[10];
                for (int i = 0; i < cachedLabels.length; i++) {
                    cachedLabels[i] = "class_" + i;
                }
            }

            return cachedLabels;
        } catch (Exception e) {
            logger.error("Failed to load labels for model type: {}", modelType, e);
            throw new RuntimeException("Failed to load labels for model type: " + modelType, e);
        }
    }

    private String[] loadLabelsFromFile(String filePath) {
        try {
            InputStream inputStream;
            if (filePath.startsWith("classpath:")) {
                String resourcePath = filePath.substring("classpath:".length());
                logger.debug("Loading labels from classpath: {}", resourcePath);
                inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath);
            } else {
                logger.debug("Loading labels from filesystem: {}", filePath);
                inputStream = Files.newInputStream(Paths.get(filePath));
            }

            if (inputStream == null) {
                logger.warn("Label file not found: {}", filePath);
                return null;
            }

            // 尝试多种格式加载标签
            String[] labels = null;

            // 首先尝试 JSON 格式
            labels = tryLoadJsonLabels(inputStream);
            if (labels != null) {
                logger.debug("Loaded {} labels from JSON file: {}", labels.length, filePath);
                return labels;
            }

            // 如果 JSON 失败，重置流并尝试文本格式
            if (filePath.startsWith("classpath:")) {
                String resourcePath = filePath.substring("classpath:".length());
                inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath);
            } else {
                inputStream = Files.newInputStream(Paths.get(filePath));
            }

            labels = tryLoadTextLabels(inputStream);
            if (labels != null) {
                logger.debug("Loaded {} labels from text file: {}", labels.length, filePath);
                return labels;
            }

            logger.warn("Failed to load labels from file: {} (unsupported format)", filePath);
            return null;
        } catch (IOException e) {
            logger.warn("Failed to load labels from file: {}", filePath, e);
            return null; // 返回null，让调用方使用默认标签
        }
    }

    private String[] tryLoadJsonLabels(InputStream inputStream) {
        try {
            // 使用 Jackson ObjectMapper 解析 JSON
            ObjectMapper objectMapper = new ObjectMapper();

            // 读取整个流为字符串以检测 JSON
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }

            String jsonContent = content.toString().trim();
            if (jsonContent.isEmpty()) {
                return null;
            }

            // 检查是否为 JSON 对象（以 { 开头）
            if (!jsonContent.startsWith("{") && !jsonContent.startsWith("[")) {
                return null;
            }

            // 解析 JSON
            Map<String, String> labelMap = objectMapper.readValue(jsonContent,
                objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));

            if (labelMap.isEmpty()) {
                return null;
            }

            // 将 Map 转换为有序数组
            // 假设键是数字字符串 "0", "1", "2", ...
            int maxIndex = -1;
            for (String key : labelMap.keySet()) {
                try {
                    int index = Integer.parseInt(key);
                    if (index > maxIndex) {
                        maxIndex = index;
                    }
                } catch (NumberFormatException e) {
                    // 如果键不是数字，按字母顺序处理
                }
            }

            if (maxIndex >= 0) {
                // 数字键：按数字顺序排序
                String[] labels = new String[maxIndex + 1];
                for (Map.Entry<String, String> entry : labelMap.entrySet()) {
                    try {
                        int index = Integer.parseInt(entry.getKey());
                        if (index >= 0 && index <= maxIndex) {
                            labels[index] = entry.getValue();
                        }
                    } catch (NumberFormatException e) {
                        // 忽略非数字键
                    }
                }
                return labels;
            } else {
                // 非数字键：按字母顺序排序
                List<String> labelList = new ArrayList<>(labelMap.values());
                return labelList.toArray(new String[0]);
            }

        } catch (Exception e) {
            logger.debug("Failed to parse labels as JSON: {}", e.getMessage());
            return null;
        }
    }

    private String[] tryLoadTextLabels(InputStream inputStream) {
        try {
            List<String> labels = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        labels.add(trimmed);
                    }
                }
            }

            if (labels.isEmpty()) {
                return null;
            }

            return labels.toArray(new String[0]);
        } catch (Exception e) {
            logger.debug("Failed to parse labels as text: {}", e.getMessage());
            return null;
        }
    }

    public void clearCache() {
        cachedLabels = null;
        logger.debug("Cleared label cache");
    }
}