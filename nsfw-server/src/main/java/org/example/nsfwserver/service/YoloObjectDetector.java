package org.example.nsfwserver.service;

import ai.djl.ModelException;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelZoo;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.Batchifier;
import ai.djl.translate.TranslateException;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import org.example.nsfwserver.manager.InstantModelManager;
import org.example.nsfwserver.manager.TempFileManager;
import org.example.nsfwserver.config.ModelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * YOLOv8n物体检测器
 * 用于检测图像中的物体，并计算所有检测框的并集区域
 */
@Service
public class YoloObjectDetector implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(YoloObjectDetector.class);

    // YOLOv8n模型配置
    private static final int INPUT_SIZE = 640;
    private static final int NUM_CLASSES = 80; // COCO数据集80个类别
    private static final float CONFIDENCE_THRESHOLD = 0.25f;
    private static final float IOU_THRESHOLD = 0.7f;

    // COCO数据集类别名称 (80个类别)
    private static final String[] COCO_CLASS_NAMES = {
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light",
            "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow",
            "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard",
            "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
            "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone",
            "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors", "teddy bear",
            "hair drier", "toothbrush"
    };

    private final String modelPath;
    private final float confidenceThreshold;
    private final float iouThreshold;
    private final TempFileManager tempFileManager; // 添加TempFileManager
    private final ModelConfig modelConfig; // 模型配置
    private final InstantModelManager instantModelManager; // 即时模型管理器

    // 模型实例（即时加载模式）
    private ZooModel<NDArray, float[][]> model;
    private Predictor<NDArray, float[][]> predictor;
    private NDManager manager;

    // 注意：modelPath, confidenceThreshold, iouThreshold 字段已在类中其他位置定义
    // 这里不再重复定义

    // 模型加载状态
    private boolean modelLoaded = false;

    /**
     * Letterbox预处理信息
     * 记录缩放比例和填充偏移，用于坐标反算
     */
    public static class LetterboxInfo {
        private final float gain;          // 缩放比例 (原图到输入尺寸)
        private final float padX;          // 水平填充 (像素)
        private final float padY;          // 垂直填充 (像素)
        private final int originalWidth;   // 原图宽度
        private final int originalHeight;  // 原图高度
        private final int paddedWidth;     // 填充后宽度 (输入尺寸)
        private final int paddedHeight;    // 填充后高度 (输入尺寸)

        public LetterboxInfo(float gain, float padX, float padY, int originalWidth, int originalHeight, int paddedWidth, int paddedHeight) {
            this.gain = gain;
            this.padX = padX;
            this.padY = padY;
            this.originalWidth = originalWidth;
            this.originalHeight = originalHeight;
            this.paddedWidth = paddedWidth;
            this.paddedHeight = paddedHeight;
        }

        public float getGain() {
            return gain;
        }

        public float getPadX() {
            return padX;
        }

        public float getPadY() {
            return padY;
        }

        public int getOriginalWidth() {
            return originalWidth;
        }

        public int getOriginalHeight() {
            return originalHeight;
        }

        public int getPaddedWidth() {
            return paddedWidth;
        }

        public int getPaddedHeight() {
            return paddedHeight;
        }

        /**
         * 将模型输出坐标（像素坐标，相对于填充后图像）反算回原图坐标
         *
         * @param x      模型输出x坐标（像素坐标，相对于填充后图像）
         * @param y      模型输出y坐标（像素坐标，相对于填充后图像）
         * @param width  模型输出宽度（像素坐标，相对于填充后图像）
         * @param height 模型输出高度（像素坐标，相对于填充后图像）
         * @return 原图上的归一化坐标 [left, top, right, bottom]
         */
        public float[] mapToOriginal(float x, float y, float width, float height) {
            // 去除填充，应用缩放比例
            float x1 = (x - padX) / gain;
            float y1 = (y - padY) / gain;
            float x2 = (x + width - padX) / gain;
            float y2 = (y + height - padY) / gain;

            // 归一化到[0,1]（相对于原图尺寸）
            float left = Math.max(0, Math.min(1, x1 / originalWidth));
            float top = Math.max(0, Math.min(1, y1 / originalHeight));
            float right = Math.max(0, Math.min(1, x2 / originalWidth));
            float bottom = Math.max(0, Math.min(1, y2 / originalHeight));

            return new float[]{left, top, right, bottom};
        }

        /**
         * 检查检测框是否主要在填充区域内
         *
         * @param centerX 检测框中心x坐标（像素，相对于填充后图像）
         * @param centerY 检测框中心y坐标（像素，相对于填充后图像）
         * @return true如果检测框中心在有效图像区域内，false如果在填充区域内
         */
        public boolean isInValidArea(float centerX, float centerY) {
            // 计算有效图像区域边界
            float scaledWidth = originalWidth * gain;
            float scaledHeight = originalHeight * gain;
            float validLeft = padX;
            float validRight = padX + scaledWidth;
            float validTop = padY;
            float validBottom = padY + scaledHeight;

            // 检查中心坐标是否在有效区域内
            boolean inValidArea = (centerX >= validLeft && centerX <= validRight &&
                    centerY >= validTop && centerY <= validBottom);

            return inValidArea;
        }

        /**
         * 获取有效图像区域信息（用于调试）
         */
        public String getValidAreaInfo() {
            float scaledWidth = originalWidth * gain;
            float scaledHeight = originalHeight * gain;
            return String.format("有效区域: x[%.1f, %.1f], y[%.1f, %.1f], 尺寸: %.1fx%.1f",
                    padX, padX + scaledWidth, padY, padY + scaledHeight, scaledWidth, scaledHeight);
        }

        @Override
        public String toString() {
            return String.format("LetterboxInfo[gain=%.4f, padX=%.1f, padY=%.1f, orig=%dx%d, padded=%dx%d]",
                    gain, padX, padY, originalWidth, originalHeight, paddedWidth, paddedHeight);
        }
    }

    /**
     * Sigmoid激活函数
     */
    private static float sigmoid(float x) {
        return (float) (1.0 / (1.0 + Math.exp(-x)));
    }

    /**
     * 检测框结果
     */
    public static class DetectionBox {
        private final float left;    // 归一化坐标 [0,1]
        private final float top;
        private final float right;
        private final float bottom;
        private final float confidence;
        private final int classId;

        public DetectionBox(float left, float top, float right, float bottom, float confidence, int classId) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.confidence = confidence;
            this.classId = classId;
        }

        public float getLeft() {
            return left;
        }

        public float getTop() {
            return top;
        }

        public float getRight() {
            return right;
        }

        public float getBottom() {
            return bottom;
        }

        public float getConfidence() {
            return confidence;
        }

        public int getClassId() {
            return classId;
        }

        public float getWidth() {
            return right - left;
        }

        public float getHeight() {
            return bottom - top;
        }

        @Override
        public String toString() {
            return String.format("DetectionBox[class=%d, conf=%.3f, box=(%.3f,%.3f,%.3f,%.3f)]",
                    classId, confidence, left, top, right, bottom);
        }
    }

    /**
     * YOLOv8n检测结果
     */
    public static class YoloResult {
        private final boolean detected;
        private final List<DetectionBox> boxes;
        private final float[] unionBox; // [left, top, right, bottom] 归一化坐标
        private final String error;

        public YoloResult(boolean detected, List<DetectionBox> boxes, float[] unionBox, String error) {
            this.detected = detected;
            this.boxes = boxes;
            this.unionBox = unionBox;
            this.error = error;
        }

        public boolean isDetected() {
            return detected;
        }

        public List<DetectionBox> getBoxes() {
            return boxes;
        }

        public float[] getUnionBox() {
            return unionBox;
        }

        public String getError() {
            return error;
        }

        public static YoloResult success(List<DetectionBox> boxes, float[] unionBox) {
            return new YoloResult(true, boxes, unionBox, null);
        }

        public static YoloResult noDetection() {
            return new YoloResult(false, new ArrayList<>(), null, "No objects detected");
        }

        public static YoloResult error(String error) {
            return new YoloResult(false, new ArrayList<>(), null, error);
        }
    }

    /**
     * Spring依赖注入构造函数（使用ModelConfig配置）
     */
    @org.springframework.beans.factory.annotation.Autowired
    public YoloObjectDetector(ModelConfig modelConfig, TempFileManager tempFileManager, InstantModelManager instantModelManager) {
        this(modelConfig.getYoloModelPath(), modelConfig.getYoloConfidenceThreshold(),
                modelConfig.getYoloIouThreshold(), tempFileManager, modelConfig, instantModelManager);
        // 根据模型加载模式决定是否立即加载
        if ("eager".equals(modelConfig.getModelLoadMode()) && modelConfig.isYoloEnabled()) {
            logger.info("Eager loading mode for YOLO detected, loading model now...");
            logger.info("YOLO configuration: modelPath={}, confidenceThreshold={}, iouThreshold={}",
                modelConfig.getYoloModelPath(), modelConfig.getYoloConfidenceThreshold(), modelConfig.getYoloIouThreshold());
            loadModel();
        } else {
            logger.info("YOLO will use {} loading mode (yoloEnabled={})",
                modelConfig.getModelLoadMode(), modelConfig.isYoloEnabled());
        }
    }

    /**
     * 使用默认配置创建检测器（带TempFileManager）
     */
    public YoloObjectDetector(TempFileManager tempFileManager) {
        this("classpath:models/yolov8n.onnx", CONFIDENCE_THRESHOLD, IOU_THRESHOLD, tempFileManager);
        // modelConfig already set to null by delegated constructor
    }

    /**
     * 使用默认配置创建检测器
     */
    public YoloObjectDetector() {
        this("classpath:models/yolov8n.onnx", CONFIDENCE_THRESHOLD);
    }

    /**
     * 使用自定义配置创建检测器
     *
     * @param modelPath           模型路径
     * @param confidenceThreshold 置信度阈值
     */
    public YoloObjectDetector(String modelPath, float confidenceThreshold) {
        this(modelPath, confidenceThreshold, IOU_THRESHOLD);
    }

    /**
     * 使用自定义配置创建检测器（带TempFileManager）
     *
     * @param modelPath           模型路径
     * @param confidenceThreshold 置信度阈值
     * @param iouThreshold        IoU阈值，用于NMS去重
     * @param tempFileManager     临时文件管理器
     */
    public YoloObjectDetector(String modelPath, float confidenceThreshold, float iouThreshold, TempFileManager tempFileManager) {
        this(modelPath, confidenceThreshold, iouThreshold, tempFileManager, null, null);
    }

    /**
     * 使用自定义配置创建检测器（带TempFileManager和ModelConfig）
     *
     * @param modelPath           模型路径
     * @param confidenceThreshold 置信度阈值
     * @param iouThreshold        IoU阈值，用于NMS去重
     * @param tempFileManager     临时文件管理器
     * @param modelConfig         模型配置
     * @param instantModelManager 即时模型管理器
     */
    public YoloObjectDetector(String modelPath, float confidenceThreshold, float iouThreshold,
                              TempFileManager tempFileManager, ModelConfig modelConfig,
                              InstantModelManager instantModelManager) {
        this.modelPath = modelPath;
        this.confidenceThreshold = confidenceThreshold;
        this.iouThreshold = iouThreshold;
        this.tempFileManager = tempFileManager;
        this.modelConfig = modelConfig;
        this.instantModelManager = instantModelManager;
        // 注意：init()调用已移除，改为懒加载模式
        logger.info("YOLOv8n检测器已创建（懒加载模式，使用TempFileManager）");
        logger.info("配置: 模型路径={}, 置信度阈值={}, IoU阈值={}, 模型加载模式={}, yoloEnabled={}",
            modelPath, confidenceThreshold, iouThreshold,
            modelConfig != null ? modelConfig.getModelLoadMode() : "null",
            modelConfig != null ? modelConfig.isYoloEnabled() : "null");
        logger.debug("YOLO constructor: this.modelConfig={}, this.instantModelManager={}",
            this.modelConfig != null ? "not null" : "null",
            this.instantModelManager != null ? "not null" : "null");
    }

    /**
     * 使用自定义配置创建检测器
     *
     * @param modelPath           模型路径
     * @param confidenceThreshold 置信度阈值
     * @param iouThreshold        IoU阈值，用于NMS去重
     */
    public YoloObjectDetector(String modelPath, float confidenceThreshold, float iouThreshold) {
        this(modelPath, confidenceThreshold, iouThreshold, null);
    }


    /**
     * 加载模型（懒加载）
     */
    private synchronized void loadModel() {
        if (modelLoaded) {
            return;
        }

        try {
            logger.info("加载YOLOv8n模型: {}", modelPath);
            long startTime = System.currentTimeMillis();

            manager = NDManager.newBaseManager();

            Criteria<NDArray, float[][]> criteria = Criteria.builder()
                    .setTypes(NDArray.class, float[][].class)
                    .optModelPath(getModelFilePath(modelPath))
                    .optTranslator(new YoloTranslator())
                    .optEngine("OnnxRuntime")
                    .build();

            model = ModelZoo.loadModel(criteria);
            predictor = model.newPredictor();
            modelLoaded = true;

            long loadTime = System.currentTimeMillis() - startTime;
            logger.info("YOLOv8n模型加载成功: {} ({}ms)", modelPath, loadTime);
            logger.info("输入尺寸: {}x{}", INPUT_SIZE, INPUT_SIZE);
            logger.info("置信度阈值: {}", confidenceThreshold);
            logger.info("IoU阈值: {}", iouThreshold);

        } catch (Exception e) {
            logger.error("YOLOv8n模型加载失败", e);
            throw new RuntimeException("Failed to load YOLOv8n model", e);
        }
    }

    /**
     * 卸载模型（释放资源）
     */
    private synchronized void unloadModel() {
        if (!modelLoaded) {
            return;
        }

        try {
            logger.debug("卸载YOLOv8n模型资源");
            if (predictor != null) {
                predictor.close();
                predictor = null;
            }
            if (model != null) {
                model.close();
                model = null;
            }
            if (manager != null) {
                manager.close();
                manager = null;
            }
            modelLoaded = false;
            logger.debug("YOLOv8n模型资源已释放");
        } catch (Exception e) {
            logger.warn("卸载YOLOv8n模型时出错", e);
        }
    }

    /**
     * 获取模型文件路径
     *
     * @param modelPath 模型路径，支持classpath:前缀
     * @return 模型文件路径
     */
    private Path getModelFilePath(String modelPath) throws IOException {
        if (modelPath.startsWith("classpath:")) {
            String resourcePath = modelPath.substring("classpath:".length());
            logger.info("从类路径加载YOLOv8n模型: {}", resourcePath);

            // 如果tempFileManager可用，使用它进行幂等拷贝
            if (tempFileManager != null) {
                try {
                    return tempFileManager.copyResourceToTemp(resourcePath, "yolov8n");
                } catch (Exception e) {
                    logger.warn("Failed to use TempFileManager for YOLO model, falling back to manual copy: {}", e.getMessage());
                    // 继续使用手动拷贝
                }
            }

            InputStream resourceStream = getClass().getClassLoader().getResourceAsStream(resourcePath);
            if (resourceStream == null) {
                throw new IOException("YOLOv8n模型资源未找到: " + resourcePath);
            }

            Path tempFile = Files.createTempFile("yolov8n_model_", ".onnx");
            tempFile.toFile().deleteOnExit();
            Files.copy(resourceStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            resourceStream.close();

            logger.info("YOLOv8n模型已加载到临时文件: {}", tempFile);
            return tempFile;
        } else {
            return Paths.get(modelPath);
        }
    }

    /**
     * 检测图像中的物体
     *
     * @param originalImage 原始图像
     * @return 检测结果
     */
    public YoloResult detectObjects(BufferedImage originalImage) {
        // 懒加载模型
        loadModel();

        try (NDManager detectManager = manager.newSubManager()) {
            // 记录原始图像信息
            logger.info("原始图像: 类型={}, 尺寸={}x{}, 颜色模型={}",
                    originalImage.getType(),
                    originalImage.getWidth(), originalImage.getHeight(),
                    originalImage.getColorModel());

            // 1. 临时使用拉伸代替Letterbox（调试）
            boolean useLetterbox = true; // 启用Letterbox（等比缩放）
            BufferedImage processedImage;
            LetterboxInfo letterboxInfo;

            if (useLetterbox) {
                // Letterbox预处理：等比例缩放并填充黑边到640×640
                Object[] letterboxResult = letterboxImage(originalImage, INPUT_SIZE, INPUT_SIZE);
                processedImage = (BufferedImage) letterboxResult[0];
                letterboxInfo = (LetterboxInfo) letterboxResult[1];
            } else {
                // 拉伸预处理：简单拉伸到640×640
                processedImage = resizeImage(originalImage, INPUT_SIZE, INPUT_SIZE);
                logger.info("拉伸后图像: 类型={}, 尺寸={}x{}, 颜色模型={}, 样本大小={}",
                        processedImage.getType(),
                        processedImage.getWidth(), processedImage.getHeight(),
                        processedImage.getColorModel(),
                        processedImage.getSampleModel().getSampleSize(0));
                // 创建拉伸对应的LetterboxInfo
                int originalWidth = originalImage.getWidth();
                int originalHeight = originalImage.getHeight();
                float gainX = (float) INPUT_SIZE / originalWidth;
                float gainY = (float) INPUT_SIZE / originalHeight;
                // 使用宽度gain作为统一gain（近似）
                float gain = gainX;
                // 计算垂直方向的补偿偏移（因为gain不同）
                float effectivePadY = 0; // 拉伸无填充
                float effectivePadX = 0;
                letterboxInfo = new LetterboxInfo(gain, effectivePadX, effectivePadY,
                        originalWidth, originalHeight, INPUT_SIZE, INPUT_SIZE);
                logger.info("使用拉伸预处理: {}x{} -> {}x{}, gainX={}, gainY={}, 使用gain={}",
                        originalWidth, originalHeight, INPUT_SIZE, INPUT_SIZE, gainX, gainY, gain);
            }

            // 调试: 预处理图片不再保存到本地文件，将通过Base64返回

            // 2. 预处理图像
            NDArray preprocessed = preprocessImage(processedImage, detectManager);

            // 3. 运行推理
            float[][] output = predictor.predict(preprocessed);
            logger.info("YOLOv8n推理完成，输出形状: {}x{}", output.length, output[0].length);

            // 4. 解析输出（使用Letterbox信息进行坐标反算）
            logger.info("开始调用parseOutput处理输出...");
            logger.info("使用Letterbox信息进行坐标反算: {}", letterboxInfo);
            List<DetectionBox> boxes;
            try {
                boxes = parseOutput(output, confidenceThreshold, letterboxInfo);
            } catch (Exception e) {
                logger.error("parseOutput调用失败: {}", e.getMessage(), e);
                logger.info("返回空检测结果");
                return YoloResult.noDetection();
            }

            // 5. 计算并集框
            float[] unionBox = calculateUnionBox(boxes);

            if (boxes.isEmpty()) {
                logger.info("YOLOv8n未检测到物体");
                return YoloResult.noDetection();
            }

            logger.info("YOLOv8n检测到 {} 个物体，并集框: [{}, {}, {}, {}]",
                    boxes.size(), unionBox[0], unionBox[1], unionBox[2], unionBox[3]);

            return YoloResult.success(boxes, unionBox);

        } catch (Exception e) {
            logger.error("YOLOv8n检测失败", e);
            return YoloResult.error("YOLOv8n detection failed: " + e.getMessage());
        }
    }

    /**
     * 缩放图像（简单拉伸，不保持纵横比）
     *
     * @deprecated 使用{@link #letterboxImage(BufferedImage, int, int)}保持纵横比
     */
    private BufferedImage resizeImage(BufferedImage original, int targetWidth, int targetHeight) {
        BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = resized.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(original, 0, 0, targetWidth, targetHeight, null);
        } finally {
            g.dispose();
        }
        return resized;
    }

    /**
     * Letterbox预处理：等比例缩放并填充黑边到目标尺寸
     *
     * @param original     原始图像
     * @param targetWidth  目标宽度
     * @param targetHeight 目标高度
     * @return 包含预处理后图像和Letterbox信息的对象数组 [BufferedImage, LetterboxInfo]
     */
    private Object[] letterboxImage(BufferedImage original, int targetWidth, int targetHeight) {
// 1. 获取原始尺寸
        int originalWidth = original.getWidth();
        int originalHeight = original.getHeight();

        // 2. 计算缩放比例 (gain)
        float gain = Math.min((float) targetWidth / originalWidth, (float) targetHeight / originalHeight);
        int newWidth = Math.round(originalWidth * gain);
        int newHeight = Math.round(originalHeight * gain);

        // 3. 计算填充偏移 (pad) 使图像居中
        float effectivePadX = (targetWidth - newWidth) / 2f;
        float effectivePadY = (targetHeight - newHeight) / 2f;

        // 4. 在内存中创建目标画布
        BufferedImage processedImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = processedImage.createGraphics();

        try {
            // --- 核心修复：开启高质量渲染，防止马赛克导致模型识别失败 ---
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // 填充 YOLO 标准灰色背景 (114, 114, 114)
            g.setColor(new Color(114, 114, 114));
            g.fillRect(0, 0, targetWidth, targetHeight);

            // 绘制缩放后的图像
            g.drawImage(original, (int)effectivePadX, (int)effectivePadY, newWidth, newHeight, null);
        } finally {
            // 释放资源，防止内存泄漏
            g.dispose();
        }

        // 5. 封装信息用于后续坐标反算（请确保你的 LetterboxInfo 构造函数顺序一致）
        LetterboxInfo letterboxInfo = new LetterboxInfo(
                gain,
                effectivePadX,
                effectivePadY,
                originalWidth,
                originalHeight,
                targetWidth,
                targetHeight
        );

        return new Object[]{processedImage, letterboxInfo};
    }

    /**
     * 预处理图像：转换为NDArray，归一化
     */
    private NDArray preprocessImage(BufferedImage image, NDManager manager) {
        int width = image.getWidth();
        int height = image.getHeight();
        int numPixels = width * height;
        float[] floatData = new float[numPixels * 3];

        // 使用 getRGB 获取标准像素数组，彻底避开 DataBuffer 布局不一致的问题
        int[] rgbData = new int[numPixels];
        image.getRGB(0, 0, width, height, rgbData, 0, width);

        for (int i = 0; i < numPixels; i++) {
            int pixel = rgbData[i];

            // 显式提取 R, G, B 通道
            float r = ((pixel >> 16) & 0xFF) / 255.0f;
            float g = ((pixel >> 8) & 0xFF) / 255.0f;
            float b = (pixel & 0xFF) / 255.0f;

            // 严格按照 CHW 顺序排列 (R...G...B...)
            floatData[i] = r;                 // R 通道层
            floatData[numPixels + i] = g;     // G 通道层
            floatData[2 * numPixels + i] = b; // B 通道层
        }

        // 返回 [1, 3, 640, 640]
        return manager.create(floatData, new Shape(1, 3, height, width));
    }

    /**
     * 计算IoU（交并比）
     *
     * @param box1 检测框1
     * @param box2 检测框2
     * @return IoU值（0-1之间）
     */
    private float calculateIoU(DetectionBox box1, DetectionBox box2) {
        float interLeft = Math.max(box1.getLeft(), box2.getLeft());
        float interTop = Math.max(box1.getTop(), box2.getTop());
        float interRight = Math.min(box1.getRight(), box2.getRight());
        float interBottom = Math.min(box1.getBottom(), box2.getBottom());

        if (interRight <= interLeft || interBottom <= interTop) {
            return 0.0f;
        }

        float interArea = (interRight - interLeft) * (interBottom - interTop);
        float area1 = box1.getWidth() * box1.getHeight();
        float area2 = box2.getWidth() * box2.getHeight();
        float unionArea = area1 + area2 - interArea;

        return interArea / unionArea;
    }

    /**
     * 非极大值抑制（NMS）
     *
     * @param boxes        原始检测框列表
     * @param iouThreshold IoU阈值
     * @return 经过NMS过滤后的检测框列表
     */
    private List<DetectionBox> nonMaximumSuppression(List<DetectionBox> boxes, float iouThreshold) {
        logger.info("开始nonMaximumSuppression - 输入: {}个框, IoU阈值: {}", boxes.size(), iouThreshold);

        if (boxes.isEmpty()) {
            logger.info("输入框列表为空，直接返回");
            return boxes;
        }

        logger.info("按置信度降序排序...");
        // 按置信度降序排序
        boxes.sort((b1, b2) -> Float.compare(b2.getConfidence(), b1.getConfidence()));

        List<DetectionBox> selected = new ArrayList<>();
        boolean[] suppressed = new boolean[boxes.size()];
        int totalSuppressed = 0;

        logger.info("开始NMS主循环...");
        for (int i = 0; i < boxes.size(); i++) {
            if (suppressed[i]) {
                continue;
            }

            DetectionBox currentBox = boxes.get(i);
            selected.add(currentBox);

            int suppressedThisRound = 0;

            // 抑制与当前框IoU大于阈值的所有框
            for (int j = i + 1; j < boxes.size(); j++) {
                if (suppressed[j]) {
                    continue;
                }

                DetectionBox otherBox = boxes.get(j);
                float iou = calculateIoU(currentBox, otherBox);

                if (iou > iouThreshold) {
                    suppressed[j] = true;
                    suppressedThisRound++;
                    totalSuppressed++;
                }
            }

            if (suppressedThisRound > 0) {
                logger.debug("第{}轮NMS: 当前框置信度={}, 抑制了{}个框", i, currentBox.getConfidence(), suppressedThisRound);
            }

            // 每10个选中的框记录一次进度
            if (selected.size() % 10 == 0) {
                logger.debug("NMS进度: 已选中{}个框, 已抑制{}个框", selected.size(), totalSuppressed);
            }
        }

        logger.info("NMS完成: 原始{}个框，过滤后{}个框，总共抑制{}个框",
                boxes.size(), selected.size(), totalSuppressed);
        return selected;
    }

    /**
     * 按类别分组并应用NMS
     *
     * @param boxes        原始检测框列表
     * @param iouThreshold IoU阈值
     * @return 经过类别NMS过滤后的检测框列表
     */
    private List<DetectionBox> applyClassWiseNMS(List<DetectionBox> boxes, float iouThreshold) {
        logger.info("开始applyClassWiseNMS - 输入: {}个框, IoU阈值: {}", boxes.size(), iouThreshold);

        if (boxes.isEmpty()) {
            logger.info("输入框列表为空，直接返回");
            return boxes;
        }

        // 按类别ID分组
        Map<Integer, List<DetectionBox>> boxesByClass = new HashMap<>();
        for (DetectionBox box : boxes) {
            boxesByClass.computeIfAbsent(box.getClassId(), k -> new ArrayList<>()).add(box);
        }

        logger.info("按类别分组完成: 共{}个类别", boxesByClass.size());
        for (Map.Entry<Integer, List<DetectionBox>> entry : boxesByClass.entrySet()) {
            logger.debug("类别{}: {}个框", entry.getKey(), entry.getValue().size());
        }

        List<DetectionBox> result = new ArrayList<>();
        int totalFiltered = 0;

        for (Map.Entry<Integer, List<DetectionBox>> entry : boxesByClass.entrySet()) {
            int classId = entry.getKey();
            List<DetectionBox> classBoxes = entry.getValue();

            logger.info("处理类别{}: 原始{}个框", classId, classBoxes.size());

            // 安全限制：如果某个类别的框数量过多，跳过NMS以避免堆栈溢出
            if (classBoxes.size() > 100) {
                logger.warn("类别{}框数量过多({})，跳过NMS以避免堆栈溢出，只取前30个高置信度框", classId, classBoxes.size());
                // 按置信度排序并取前30个
                classBoxes.sort((b1, b2) -> Float.compare(b2.getConfidence(), b1.getConfidence()));
                if (classBoxes.size() > 30) {
                    classBoxes = classBoxes.subList(0, 30);
                }
                result.addAll(classBoxes);
                totalFiltered += (entry.getValue().size() - classBoxes.size());
                logger.info("类别{}简化处理完成: 原始{}个框，简化后{}个框",
                        classId, entry.getValue().size(), classBoxes.size());
            } else {
                try {
                    List<DetectionBox> filtered = nonMaximumSuppression(classBoxes, iouThreshold);
                    result.addAll(filtered);
                    totalFiltered += (classBoxes.size() - filtered.size());
                    logger.info("类别{} NMS完成: 原始{}个框，过滤后{}个框，移除{}个框",
                            classId, classBoxes.size(), filtered.size(), classBoxes.size() - filtered.size());
                } catch (StackOverflowError e) {
                    logger.error("类别{} NMS时发生堆栈溢出错误! 原始框数量: {}", classId, classBoxes.size(), e);
                    logger.warn("类别{}跳过NMS，保留原始{}个框", classId, classBoxes.size());
                    result.addAll(classBoxes);
                } catch (Exception e) {
                    logger.error("类别{} NMS时发生异常: {}", classId, e.getMessage(), e);
                    logger.warn("类别{}跳过NMS，保留原始{}个框", classId, classBoxes.size());
                    result.addAll(classBoxes);
                }
            }
        }

        // 按置信度降序排序最终结果
        logger.info("开始按置信度排序结果...");
        result.sort((b1, b2) -> Float.compare(b2.getConfidence(), b1.getConfidence()));

        logger.info("类别NMS完成: 原始{}个框，过滤后{}个框，总共移除{}个框",
                boxes.size(), result.size(), totalFiltered);
        return result;
    }

    /**
     * 解析YOLOv8n输出（使用Letterbox信息进行坐标反算）
     * YOLOv8n输出形状通常为[1, 84, 8400]，其中84=4边界框坐标+80类别概率
     *
     * @param output              模型输出（84×8400）
     * @param confidenceThreshold 置信度阈值
     * @param letterboxInfo       Letterbox预处理信息，用于坐标反算
     * @return 检测框列表（归一化到原图坐标）
     */
    private List<DetectionBox> parseOutput(float[][] output, float confidenceThreshold, LetterboxInfo letterboxInfo) {
        List<DetectionBox> candidateBoxes = new ArrayList<>();

        // YOLOv8 输出格式通常是 [84][8400]
        // dim1 = 84 (cx, cy, w, h + classes)
        // dim2 = 8400 (检测框的数量)
        int numAttributes = output.length;
        int numBoxes = output[0].length;

        // 我们需要遍历每一个“框”（即第 2 维）
        for (int j = 0; j < numBoxes; j++) {
            // 1. 寻找该框中概率最大的类别
            float maxProb = 0;
            int classId = -1;

            // 从索引 4 开始是类别分数
            for (int c = 4; c < numAttributes; c++) {
                if (output[c][j] > maxProb) {
                    maxProb = output[c][j];
                    classId = c - 4;
                }
            }

            // 2. 如果置信度达标，进行坐标还原
            if (maxProb > confidenceThreshold) {
                // 注意：从对应的行里取当前框 j 的属性
                float cx = output[0][j];
                float cy = output[1][j];
                float w = output[2][j];
                float h = output[3][j];

                // --- 核心反算公式 ---
                // 减去 Letterbox 的黑边偏移，再除以缩放比例 gain
                float realCx = (cx - letterboxInfo.getPadX()) / letterboxInfo.getGain();
                float realCy = (cy - letterboxInfo.getPadY()) / letterboxInfo.getGain();
                float realW = w / letterboxInfo.getGain();
                float realH = h / letterboxInfo.getGain();

                // 转换为原图的归一化坐标 [0, 1]
                float left = (realCx - realW / 2f) / letterboxInfo.getOriginalWidth();
                float top = (realCy - realH / 2f) / letterboxInfo.getOriginalHeight();
                float right = (realCx + realW / 2f) / letterboxInfo.getOriginalWidth();
                float bottom = (realCy + realH / 2f) / letterboxInfo.getOriginalHeight();

                // 边界约束
                left = Math.max(0, Math.min(1, left));
                top = Math.max(0, Math.min(1, top));
                right = Math.max(0, Math.min(1, right));
                bottom = Math.max(0, Math.min(1, bottom));

                candidateBoxes.add(new DetectionBox(left, top, right, bottom,maxProb,classId));
            }
        }

        // 3. 最后应用 NMS（非极大值抑制）去除重复框
        return applyClassWiseNMS(candidateBoxes, iouThreshold);
    }

    /**
     * 解析YOLOv8n输出（向后兼容，使用默认Letterbox信息）
     * 注意：此方法假设图像已拉伸到640×640，不保持纵横比
     *
     * @deprecated 使用{@link #parseOutput(float[][], float, LetterboxInfo)}以支持Letterbox预处理
     */
    private List<DetectionBox> parseOutput(float[][] output, float confidenceThreshold) {
        // 创建默认LetterboxInfo（假设原图已经是640×640，无缩放无填充）
        LetterboxInfo defaultInfo = new LetterboxInfo(
                1.0f,      // gain: 无缩放
                0,         // padX: 无填充
                0,         // padY: 无填充
                INPUT_SIZE, // 原图宽度
                INPUT_SIZE, // 原图高度
                INPUT_SIZE, // 填充后宽度
                INPUT_SIZE  // 填充后高度
        );
        logger.warn("使用默认LetterboxInfo（拉伸模式），建议改用Letterbox预处理以保持纵横比");
        // 记录堆栈跟踪以查看调用者
        logger.warn("调用堆栈:", new Exception("调试堆栈跟踪"));

        // 添加详细调试信息
        int numRows = output.length; // 应该是84
        int numCols = output[0].length; // 应该是8400
        logger.info("parseOutput(float[][], float) - 输出形状: {}x{}", numRows, numCols);

        // 打印前5个预测的原始值用于调试
        for (int i = 0; i < Math.min(5, numCols); i++) {
            float cx = output[0][i];
            float cy = output[1][i];
            float w = output[2][i];
            float h = output[3][i];

            // 找出前5个类别的概率
            float maxProb = 0;
            int maxClassId = -1;
            for (int c = 0; c < Math.min(5, NUM_CLASSES); c++) {
                float prob = output[4 + c][i];
                if (prob > maxProb) {
                    maxProb = prob;
                    maxClassId = c;
                }
            }

            logger.info("预测{}: cx={}, cy={}, w={}, h={}, 最大类别概率: {}({})={}",
                    i, cx, cy, w, h, maxClassId, getClassName(maxClassId), maxProb);
        }

        return parseOutput(output, confidenceThreshold, defaultInfo);
    }

    /**
     * 解析YOLOv8n输出（NDArray版本，使用Letterbox信息进行坐标反算）
     * YOLOv8n输出形状为[84, 8400]，其中84=4边界框坐标+80类别概率
     *
     * @param output              模型输出NDArray [84, 8400]（已去除批次维度）
     * @param confidenceThreshold 置信度阈值
     * @param letterboxInfo       Letterbox预处理信息，用于坐标反算
     * @return 检测框列表（归一化到原图坐标）
     */
    private List<DetectionBox> parseOutput(NDArray output, float confidenceThreshold, LetterboxInfo letterboxInfo) {
        logger.info("开始parseOutput - 输入参数: confidenceThreshold={}, letterboxInfo={}", confidenceThreshold, letterboxInfo);

        // NDArray切片操作已被移除，使用数组模式直接访问数据
        // 保留变量声明以兼容finally块
        NDArray bboxData = null;
        NDArray classData = null;
        try {
            List<DetectionBox> boxes = new ArrayList<>();

            // 检查输出形状
            Shape shape = output.getShape();
            int dims = shape.dimension();
            int numRows, numCols;
            if (dims == 3) {
                // 形状为 [batch_size, 84, 8400]，批次大小通常为1
                int batchSize = (int) shape.get(0);
                numRows = (int) shape.get(1); // 84
                numCols = (int) shape.get(2); // 8400
                logger.info("YOLOv8n输出形状: {}x{}x{} (批次大小={}, 置信度阈值: {})", batchSize, numRows, numCols, batchSize, confidenceThreshold);
                // 如果批次大小>1，我们只处理第一个批次
                if (batchSize != 1) {
                    logger.warn("批次大小不为1: {}，只处理第一个批次", batchSize);
                }
            } else if (dims == 2) {
                // 形状为 [84, 8400]
                numRows = (int) shape.get(0); // 84
                numCols = (int) shape.get(1); // 8400
                logger.info("YOLOv8n输出形状: {}x{} (置信度阈值: {})", numRows, numCols, confidenceThreshold);
            } else {
                logger.error("不支持的输出维度: {}, 形状: {}", dims, shape);
                return new ArrayList<>();
            }
            logger.info("Letterbox信息: {}", letterboxInfo);
            logger.info(letterboxInfo.getValidAreaInfo());

            // 安全限制：处理最大预测数量，避免处理所有8400个预测
            int maxPredictionsToProcess = Math.min(numCols, 500);
            logger.info("限制处理数量: 从{}个预测中处理{}个", numCols, maxPredictionsToProcess);

            logger.info("开始获取数据（数组模式）...");
            // 使用数组模式避免get()切片操作（不支持的ONNX Runtime操作）
            float[] dataArray = output.toFloatArray();
            logger.info("数据数组获取完成: {} 个浮点数", dataArray.length);

            // 数据布局：对于2D形状[84, 8400]，索引公式: rowIdx * numCols + colIdx
            // 对于3D形状[batch, 84, 8400]，索引公式: (batchIdx * numRows + rowIdx) * numCols + colIdx
            // 验证数组大小
            int batchSize = dims == 3 ? (int) shape.get(0) : 1;
            int expectedSize = batchSize * numRows * numCols;
            if (dataArray.length != expectedSize) {
                logger.warn("数组大小不匹配: 期望{}（批次{}×行{}×列{}），实际{}，继续处理",
                        expectedSize, batchSize, numRows, numCols, dataArray.length);
            }

            logger.info("数据数组准备完成: dataArray长度={}, 布局: {}批次×{}行×{}列",
                    dataArray.length, batchSize, numRows, numCols);

            int validDetections = 0;
            int lowConfidenceCount = 0;
            int paddingAreaFilteredCount = 0;

            logger.info("开始处理预测数据（数组模式）...");
            // 打印前5个预测的原始坐标用于调试
            for (int debugIdx = 0; debugIdx < Math.min(5, maxPredictionsToProcess); debugIdx++) {
                float debugCx = dataArray[0 * numCols + debugIdx];
                float debugCy = dataArray[1 * numCols + debugIdx];
                float debugW = dataArray[2 * numCols + debugIdx];
                float debugH = dataArray[3 * numCols + debugIdx];
                logger.info("预测[{}] 原始坐标: cx={}, cy={}, w={}, h={}",
                        debugIdx, debugCx, debugCy, debugW, debugH);
            }

            for (int i = 0; i < maxPredictionsToProcess; i++) {
                // 从dataArray读取边界框坐标（2D布局: rowIdx * numCols + colIdx）
                float cx = dataArray[0 * numCols + i];
                float cy = dataArray[1 * numCols + i];
                float w = dataArray[2 * numCols + i];
                float h = dataArray[3 * numCols + i];

                // 调试：记录原始坐标值
                if (i < 3) {
                    logger.debug("预测[{}] 原始值 - cx={}, cy={}, w={}, h={}", i, cx, cy, w, h);
                }

                // 从dataArray查找最大类别概率（第4行开始是类别概率logits）
                float maxClassProb = 0;
                int classId = -1;

                // 检查所有80个类别，应用sigmoid激活
                for (int c = 0; c < NUM_CLASSES; c++) {
                    float logit = dataArray[(4 + c) * numCols + i];
                    float prob = sigmoid(logit); // YOLOv8输出logits，需要sigmoid激活
                    if (prob > maxClassProb) {
                        maxClassProb = prob;
                        classId = c;
                    }
                }

                float confidence = maxClassProb;

                // 调试：打印前几个预测的详细信息
                if (i < 5) {
                    logger.info("预测[{}] 置信度分析 - raw cx={}, cy={}, w={}, h={}, confidence={}, classId={}, class={}",
                            i, cx, cy, w, h, confidence, classId, getClassName(classId));

                    // 打印前几个类别的logit和概率值
                    for (int c = 0; c < Math.min(3, NUM_CLASSES); c++) {
                        float logit = dataArray[(4 + c) * numCols + i];
                        float prob = sigmoid(logit);
                        logger.info("  类别[{}]({}): logit={}, prob={}", c, getClassName(c), logit, prob);
                    }
                }

                // 过滤低置信度检测
                if (confidence < confidenceThreshold || classId < 0) {
                    lowConfidenceCount++;
                    continue;
                }

                // 过滤填充区域内的虚假检测
                if (!letterboxInfo.isInValidArea(cx, cy)) {
                    paddingAreaFilteredCount++;
                    if (i < 5) {
                        logger.info("预测[{}] 被过滤 - 检测框中心在填充区域内: cx={}, cy={}, padX={}, padY={}",
                                i, cx, cy, letterboxInfo.getPadX(), letterboxInfo.getPadY());
                    }
                    continue;
                }

                validDetections++;

                // 将中心坐标(cx,cy)和宽高(w,h)转换为左上角坐标
                // YOLO格式: [center_x, center_y, width, height]
                // 需要转换为: [left, top, width, height] 其中 left = center_x - width/2, top = center_y - height/2
                float leftX = cx - w / 2.0f;
                float topY = cy - h / 2.0f;

                logger.debug("预测[{}] 转换前 - cx={}, cy={}, w={}, h={}", i, cx, cy, w, h);
                logger.debug("预测[{}] 转换后 - leftX={}, topY={}, w={}, h={}", i, leftX, topY, w, h);

                // 使用Letterbox信息将坐标反算回原图
                float[] mappedBox = letterboxInfo.mapToOriginal(leftX, topY, w, h);
                float left = mappedBox[0];
                float top = mappedBox[1];
                float right = mappedBox[2];
                float bottom = mappedBox[3];

                logger.debug("预测[{}] 映射后 - left={}, top={}, right={}, bottom={}", i, left, top, right, bottom);

                boxes.add(new DetectionBox(left, top, right, bottom, confidence, classId));

                // 每100个预测记录一次进度
                if (validDetections % 100 == 0) {
                    logger.debug("处理进度: {}个预测, {}个有效检测", i + 1, validDetections);
                }
            }

            logger.info("预测处理完成: 共处理{}个预测，有效检测{}个，低置信度过滤{}个，填充区域过滤{}个",
                    maxPredictionsToProcess, validDetections, lowConfidenceCount, paddingAreaFilteredCount);

            // 释放临时NDArray资源
            // 无需释放切片NDArray资源（使用数组模式）

            logger.info("YOLOv8n解析结果: 处理{}个预测，找到{}个检测框", maxPredictionsToProcess, boxes.size());

            // 应用非极大值抑制（NMS）去除重叠框 - 临时跳过进行调试
            if (!boxes.isEmpty()) {
                logger.info("跳过NMS进行调试 - 直接返回{}个原始检测框", boxes.size());
                // 只按置信度排序，不进行NMS
                boxes.sort((b1, b2) -> Float.compare(b2.getConfidence(), b1.getConfidence()));
                logger.info("按置信度排序完成");
            } else {
                logger.info("未检测到有效目标");
            }

            logger.info("parseOutput完成，返回{}个检测框", boxes.size());
            return boxes;

        } catch (Throwable e) {
            logger.error("批量解析YOLOv8n输出时出错（错误类型: {}），尝试回退解析", e.getClass().getSimpleName(), e);
            // 回退到fallback解析
            logger.info("开始回退到parseOutputFallback...");
            try {
                return parseOutputFallback(output, confidenceThreshold, letterboxInfo);
            } catch (Throwable e2) {
                logger.error("回退解析也失败（错误类型: {}），返回空列表", e2.getClass().getSimpleName(), e2);
                return new ArrayList<>();
            }
        } finally {
            // 确保NDArray资源被释放
            if (bboxData != null) {
                try {
                    bboxData.close();
                } catch (Exception e) {
                    logger.warn("关闭bboxData时出错: {}", e.getMessage());
                }
            }
            if (classData != null) {
                try {
                    classData.close();
                } catch (Exception e) {
                    logger.warn("关闭classData时出错: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 极简解析方法：处理最少量的数据，避免触发instrumentation错误
     */
    private List<DetectionBox> parseOutputMinimal(NDArray output, float confidenceThreshold, LetterboxInfo letterboxInfo) {
        List<DetectionBox> boxes = new ArrayList<>();
        Shape shape = output.getShape();
        int numRows = (int) shape.get(0);
        int numCols = (int) shape.get(1);

        logger.info("极简解析 - 输出形状: {}x{}", numRows, numCols);

        // 获取数据数组
        float[] dataArray;
        try {
            dataArray = output.toFloatArray();
            logger.info("极简解析 - 数据数组大小: {} 个浮点数", dataArray.length);
        } catch (Exception e) {
            logger.error("极简解析 - 获取数据数组失败: {}", e.getMessage());
            return boxes;
        }

        // 只处理前500个预测，并且只检查第一个类别
        int maxPredictions = Math.min(numCols, 500);

        for (int i = 0; i < maxPredictions; i++) {
            try {
                // 从dataArray读取边界框坐标（2D布局: rowIdx * numCols + colIdx）
                float cx = dataArray[0 * numCols + i];
                float cy = dataArray[1 * numCols + i];
                float w = dataArray[2 * numCols + i];
                float h = dataArray[3 * numCols + i];

                // 只检查第一个类别的概率（应用sigmoid激活）
                float logit = dataArray[4 * numCols + i]; // 第一个类别的logit
                float confidence = sigmoid(logit); // 应用sigmoid得到概率
                int classId = 0;

                if (confidence >= confidenceThreshold) {
                    float[] mappedBox = letterboxInfo.mapToOriginal(cx, cy, w, h);
                    boxes.add(new DetectionBox(mappedBox[0], mappedBox[1], mappedBox[2], mappedBox[3], confidence, classId));
                }
            } catch (Exception e) {
                logger.warn("极简解析预测{}时出错: {}", i, e.getMessage());
                continue;
            }
        }

        logger.info("极简解析完成: 处理{}个预测，找到{}个检测框", maxPredictions, boxes.size());
        return boxes;
    }

    /**
     * 回退解析方法：逐元素访问NDArray
     */
    private List<DetectionBox> parseOutputFallback(NDArray output, float confidenceThreshold, LetterboxInfo letterboxInfo) {
        logger.info("开始parseOutputFallback - 数组解析模式");
        List<DetectionBox> boxes = new ArrayList<>();
        Shape shape = output.getShape();
        int dims = shape.dimension();
        logger.info("输出维度: {}, 形状: {}", dims, shape);

        // 根据维度调整索引
        boolean is3D = dims == 3;
        int batchIdx = 0; // 对于3维，批次索引总是0
        int rowsIdx = is3D ? 1 : 0;
        int colsIdx = is3D ? 2 : 1;

        int numRows = (int) shape.get(rowsIdx);
        int numCols = (int) shape.get(colsIdx);
        int numPredictions = numCols;
        logger.info("输出形状: {}x{}, 总预测数: {}", numRows, numCols, numPredictions);

        // 将NDArray转换为Java数组以避免getFloat()兼容性问题
        logger.info("将NDArray转换为Java数组...");
        float[] data;
        try {
            data = output.toFloatArray();
            logger.info("数据数组大小: {} 个浮点数", data.length);

            // 调试：打印前几个值以验证数据布局
            if (data.length > 0) {
                logger.info("数据前10个值: {}", Arrays.toString(Arrays.copyOfRange(data, 0, Math.min(10, data.length))));
                // 打印一些关键位置的值
                if (is3D) {
                    // 检查3D布局
                    int idx0 = (batchIdx * numRows + 0) * numCols + 0;
                    int idx1 = (batchIdx * numRows + 1) * numCols + 0;
                    int idx2 = (batchIdx * numRows + 2) * numCols + 0;
                    int idx3 = (batchIdx * numRows + 3) * numCols + 0;
                    if (idx0 < data.length && idx1 < data.length && idx2 < data.length && idx3 < data.length) {
                        logger.info("3D布局检查 - 预测0: cx={}, cy={}, w={}, h={}",
                                data[idx0], data[idx1], data[idx2], data[idx3]);
                    }
                } else {
                    // 2D布局
                    if (0 * numCols + 0 < data.length && 1 * numCols + 0 < data.length &&
                            2 * numCols + 0 < data.length && 3 * numCols + 0 < data.length) {
                        logger.info("2D布局检查 - 预测0: cx={}, cy={}, w={}, h={}",
                                data[0 * numCols + 0], data[1 * numCols + 0],
                                data[2 * numCols + 0], data[3 * numCols + 0]);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("转换为浮点数数组失败: {}", e.getMessage(), e);
            return boxes; // 返回空列表
        }

        // 限制处理数量，避免性能问题 - 大幅减少以提高速度
        int maxPredictions = Math.min(numPredictions, 100);
        logger.info("限制处理数量: 处理前{}个预测 (原{}个)", maxPredictions, numPredictions);

        int validDetections = 0;
        int lowConfidenceCount = 0;
        int errorCount = 0;

        logger.info("开始数组解析预测...");
        for (int i = 0; i < maxPredictions; i++) {
            try {
                // 计算基础索引：对于3D，索引公式: (batchIdx * numRows + rowIdx) * numCols + colIdx
                // 对于2D，索引公式: rowIdx * numCols + colIdx
                // 这里我们为每一行计算索引
                float cx, cy, w, h;
                if (is3D) {
                    // 第0行: 中心点x
                    int idxCx = (batchIdx * numRows + 0) * numCols + i;
                    cx = data[idxCx];
                    // 第1行: 中心点y
                    int idxCy = (batchIdx * numRows + 1) * numCols + i;
                    cy = data[idxCy];
                    // 第2行: 宽度
                    int idxW = (batchIdx * numRows + 2) * numCols + i;
                    w = data[idxW];
                    // 第3行: 高度
                    int idxH = (batchIdx * numRows + 3) * numCols + i;
                    h = data[idxH];
                } else {
                    // 2D形状: rowIdx * numCols + colIdx
                    cx = data[0 * numCols + i];
                    cy = data[1 * numCols + i];
                    w = data[2 * numCols + i];
                    h = data[3 * numCols + i];
                }

                float maxClassProb = 0;
                int classId = -1;
                for (int c = 0; c < Math.min(NUM_CLASSES, 30); c++) { // 限制类别检查
                    float logit;
                    if (is3D) {
                        int idxProb = (batchIdx * numRows + (4 + c)) * numCols + i;
                        logit = data[idxProb];
                    } else {
                        logit = data[(4 + c) * numCols + i];
                    }
                    float prob = sigmoid(logit); // 应用sigmoid激活
                    if (prob > maxClassProb) {
                        maxClassProb = prob;
                        classId = c;
                    }
                }

                float confidence = maxClassProb;
                if (confidence >= confidenceThreshold && classId >= 0) {
                    validDetections++;
                    float[] mappedBox = letterboxInfo.mapToOriginal(cx, cy, w, h);
                    boxes.add(new DetectionBox(mappedBox[0], mappedBox[1], mappedBox[2], mappedBox[3], confidence, classId));

                    // 每50个有效检测记录一次
                    if (validDetections % 50 == 0) {
                        logger.info("有效检测进度: {}个预测, {}个有效检测", i + 1, validDetections);
                    }
                } else {
                    lowConfidenceCount++;
                    // 调试：前10个预测的置信度信息
                    if (i < 10) {
                        logger.info("预测{}: 置信度={}, 类别={}, cx={}, cy={}, w={}, h={}, 阈值={}",
                                i, confidence, classId, cx, cy, w, h, confidenceThreshold);
                    }
                }

                // 每500个预测记录一次总体进度
                if (i > 0 && i % 500 == 0) {
                    logger.info("数组解析进度: {}/{}个预测 ({}个有效, {}个低置信度, {}个错误)",
                            i, maxPredictions, validDetections, lowConfidenceCount, errorCount);
                }
            } catch (Exception e) {
                errorCount++;
                logger.warn("解析预测{}时出错: {}", i, e.getMessage());
                continue;
            }
        }

        logger.info("数组解析完成: 共处理{}个预测，有效检测{}个，低置信度过滤{}个，错误{}个",
                maxPredictions, validDetections, lowConfidenceCount, errorCount);

        logger.info("parseOutputFallback完成，返回{}个检测框", boxes.size());
        return boxes;
    }

    /**
     * 计算所有检测框的并集
     */
    private float[] calculateUnionBox(List<DetectionBox> boxes) {
        if (boxes.isEmpty()) {
            return null;
        }

        float left = Float.MAX_VALUE;
        float top = Float.MAX_VALUE;
        float right = Float.MIN_VALUE;
        float bottom = Float.MIN_VALUE;

        for (DetectionBox box : boxes) {
            left = Math.min(left, box.getLeft());
            top = Math.min(top, box.getTop());
            right = Math.max(right, box.getRight());
            bottom = Math.max(bottom, box.getBottom());
        }

        // 限制在[0,1]范围内
        return new float[]{
                Math.max(0, Math.min(1, left)),
                Math.max(0, Math.min(1, top)),
                Math.max(0, Math.min(1, right)),
                Math.max(0, Math.min(1, bottom))
        };
    }

    /**
     * 根据并集框裁剪图像区域
     *
     * @param original 原始图像
     * @param unionBox 归一化并集框坐标 [left, top, right, bottom]
     * @return 裁剪并缩放到224×224的图像
     */
    public BufferedImage cropToUnionBox(BufferedImage original, List<DetectionBox> boxes) {
        // 1. 筛选出所有的 person 框 (假设 person 的 classId 是 0，根据你的标签文件对齐)
        List<DetectionBox> personBoxes = boxes.stream()
                .filter(box -> box.getClassId() == 0)
                .collect(Collectors.toList());

        if (personBoxes.isEmpty()) {
            logger.warn("未检测到 person 类别，跳过裁剪，返回原图缩放版");
            // 如果没抓到人，至少给模型一张正常的缩放图，不要直接返回原图
            return (BufferedImage) letterboxImage(original, 224, 224)[0];
        }

        // 2. 计算这些 person 框的并集 (Union)
        float leftNorm = 1.0f, topNorm = 1.0f, rightNorm = 0.0f, bottomNorm = 0.0f;
        for (DetectionBox box : personBoxes) {
            leftNorm = Math.min(leftNorm, box.getLeft());
            topNorm = Math.min(topNorm, box.getTop());
            rightNorm = Math.max(rightNorm, box.getRight());
            bottomNorm = Math.max(bottomNorm, box.getBottom());
        }

        // 3. 在归一化空间进行 10% 的外扩（防止切掉头发或脚）
        float widthNorm = rightNorm - leftNorm;
        float heightNorm = bottomNorm - topNorm;
        float marginX = widthNorm * 0.1f;
        float marginY = heightNorm * 0.1f;

        leftNorm = Math.max(0.0f, leftNorm - marginX);
        topNorm = Math.max(0.0f, topNorm - marginY);
        rightNorm = Math.min(1.0f, rightNorm + marginX);
        bottomNorm = Math.min(1.0f, bottomNorm + marginY);

        // 4. 映射到原图像素坐标进行裁剪
        int imgW = original.getWidth();
        int imgH = original.getHeight();
        int x = (int) (leftNorm * imgW);
        int y = (int) (topNorm * imgH);
        int w = (int) ((rightNorm - leftNorm) * imgW);
        int h = (int) ((bottomNorm - topNorm) * imgH);

        // 确保裁剪区域合法
        w = Math.max(1, Math.min(imgW - x, w));
        h = Math.max(1, Math.min(imgH - y, h));

        // 执行裁剪
        BufferedImage cropped = original.getSubimage(x, y, w, h);
        logger.info("已裁剪 Person 并集区域: {}x{} @({},{})", w, h, x, y);

        // 5. --- 核心改变：调用 Letterbox 逻辑，拒绝拉伸 ---
        // targetWidth/Height 固定为 224，保证 FalconsAI 看到的是不缩水的比例
        Object[] result = letterboxImage(cropped, 224, 224);

        return (BufferedImage) result[0];
    }

    /**
     * 在无头环境中手动填充黑色并复制图像
     *
     * @param target  目标图像（黑色背景）
     * @param source  源图像
     * @param offsetX 目标X偏移
     * @param offsetY 目标Y偏移
     * @param width   要复制的宽度
     * @param height  要复制的高度
     */
    private void fillBlackAndCopyImage(BufferedImage target, BufferedImage source, int offsetX, int offsetY, int width, int height) {
        int targetWidth = target.getWidth();
        int targetHeight = target.getHeight();

        // 填充黑色背景（默认BufferedImage创建时就是黑色，但为了确保安全）
        // 对于TYPE_3BYTE_BGR，黑色像素是(0,0,0)

        // 缩放源图像到目标尺寸
        BufferedImage scaledSource = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);

        // 手动缩放：简单的最近邻缩放（在无头环境中使用简单算法）
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // 计算源图像中对应的坐标
                int srcX = x * source.getWidth() / width;
                int srcY = y * source.getHeight() / height;

                // 确保坐标在范围内
                srcX = Math.min(srcX, source.getWidth() - 1);
                srcY = Math.min(srcY, source.getHeight() - 1);

                // 获取源像素
                int rgb = source.getRGB(srcX, srcY);

                // 设置目标像素
                scaledSource.setRGB(x, y, rgb);
            }
        }

        // 将缩放后的图像复制到目标位置
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (offsetX + x >= 0 && offsetX + x < targetWidth &&
                        offsetY + y >= 0 && offsetY + y < targetHeight) {
                    int rgb = scaledSource.getRGB(x, y);
                    target.setRGB(offsetX + x, offsetY + y, rgb);
                }
            }
        }

        logger.debug("无头环境图像处理完成: 源图像{}x{} -> 缩放{}x{} -> 目标位置({},{})",
                source.getWidth(), source.getHeight(), width, height, offsetX, offsetY);
    }

    /**
     * YOLOv8n模型Translator
     */
    private static class YoloTranslator implements Translator<NDArray, float[][]> {
        @Override
        public NDList processInput(TranslatorContext ctx, NDArray input) {
            return new NDList(input);
        }

        @Override
        public float[][] processOutput(TranslatorContext ctx, NDList list) {
            NDArray output = list.singletonOrThrow();
            Shape shape = output.getShape();

            // YOLOv8n输出形状为[1, 84, 8400]
            logger.debug("YOLOv8n原始输出形状: {}", shape);

            // 转换为float[][]以便解析
            float[] flatArray = output.toFloatArray();
            int dim1 = (int) shape.get(1); // 84
            int dim2 = (int) shape.get(2); // 8400

            float[][] result = new float[dim1][dim2];
            for (int i = 0; i < dim1; i++) {
                for (int j = 0; j < dim2; j++) {
                    result[i][j] = flatArray[i * dim2 + j];
                }
            }

            logger.debug("YOLOv8n输出转换为: {}x{}", dim1, dim2);
            return result;
        }

        @Override
        public Batchifier getBatchifier() {
            return null;
        }
    }

    /**
     * 获取类别名称
     *
     * @param classId 类别ID (0-79)
     * @return 类别名称，如果ID无效则返回"unknown"
     */
    public static String getClassName(int classId) {
        if (classId >= 0 && classId < COCO_CLASS_NAMES.length) {
            return COCO_CLASS_NAMES[classId];
        }
        return "unknown";
    }

    /**
     * 绘制检测框到原始图像（不再保存为调试图片，调试图片通过Base64返回）
     *
     * @param originalImage 原始图像
     * @param boxes         检测框列表
     * @param outputPath    输出文件路径（不再使用，保留参数以保持API兼容性）
     * @throws IOException 如果绘图失败
     */
    public void drawDetections(BufferedImage originalImage, List<DetectionBox> boxes, String outputPath) throws IOException {
        try {
            logger.info("准备绘制检测框，数量: {}，路径: {}", boxes.size(), outputPath);

            // 1. 创建内存画布（TYPE_INT_RGB 在服务器上最稳定）
            BufferedImage debugImage = new BufferedImage(
                    originalImage.getWidth(),
                    originalImage.getHeight(),
                    BufferedImage.TYPE_INT_RGB
            );

            Graphics2D g2d = debugImage.createGraphics();

            try {
                // 2. 基础绘制（不依赖系统高级特性）
                g2d.drawImage(originalImage, 0, 0, null);

                // 3. 极其简易的绘图（避开 Font 和 Stroke 导致的 Headless 崩溃）
                g2d.setColor(Color.RED);

                for (DetectionBox box : boxes) {
                    // 1. 获取坐标
                    int x = (int) (box.getLeft() * originalImage.getWidth());
                    int y = (int) (box.getTop() * originalImage.getHeight());
                    int w = (int) (box.getWidth() * originalImage.getWidth());
                    int h = (int) (box.getHeight() * originalImage.getHeight());

                    // 2. 映射中文标签
                    String labelName = "未知";
                    // 注意：这里的 classLabels 是你在 Service 里加载的那个 String[] 数组
                    if (COCO_CLASS_NAMES != null && box.getClassId() >= 0 && box.getClassId() < COCO_CLASS_NAMES.length) {
                        labelName = COCO_CLASS_NAMES[box.getClassId()];
                    }
                    String fullLabel = String.format("%s %.2f", labelName, box.getConfidence());

                    // 3. 绘制框
                    g2d.setColor(Color.RED);
                    g2d.setStroke(new BasicStroke(Math.max(2, originalImage.getWidth() / 400)));
                    g2d.drawRect(x, y, w, h);

                    // 4. 设置支持中文的字体
                    // "Microsoft YaHei" (Windows), "SimSun" (Linux常见), "Dialog" (Java通用)
                    Font chineseFont = new Font("Microsoft YaHei", Font.BOLD, Math.max(14, originalImage.getWidth() / 50));
                    // 如果系统没有微软雅黑，Java会自动回退到能显示中文的默认逻辑字体
                    g2d.setFont(chineseFont);

                    // 5. 绘制标签背景和文字
                    FontMetrics fm = g2d.getFontMetrics();
                    int textWidth = fm.stringWidth(fullLabel);
                    int textHeight = fm.getHeight();

                    // 绘制实心背景以便看清文字
                    g2d.setColor(new Color(255, 0, 0, 220));
                    g2d.fillRect(x, Math.max(0, y - textHeight), textWidth + 6, textHeight);

                    // 绘制白色标签文字
                    g2d.setColor(Color.WHITE);
                    g2d.drawString(fullLabel, x + 3, Math.max(textHeight - 5, y - 5));
                }
                logger.info("内存绘图完成（调试图片通过Base64返回，不保存本地文件）");

            } catch (Exception e) {
                logger.error("Graphics2D 绘图过程发生异常: {}", e.getMessage(), e);
            } finally {
                g2d.dispose();
            }

            // 调试图片不再保存到本地文件，将通过Base64返回
            logger.debug("调试图片已生成（{}x{}），将通过Base64返回接口",
                debugImage.getWidth(), debugImage.getHeight());

        } catch (Throwable t) {
            // 使用 Throwable 捕获包括 Error 在内的所有问题
            logger.error("绘制调试图片时发生严重错误: {}", t.getMessage(), t);
        }
    }

    /**
     * 创建带检测框的调试图片（返回BufferedImage，不保存文件）
     * @param originalImage 原始图像
     * @param boxes         检测框列表
     * @return 带检测框的调试图片
     */
    public BufferedImage createDebugImageWithDetections(BufferedImage originalImage, List<DetectionBox> boxes) {
        try {
            logger.info("准备绘制检测框到内存，数量: {}", boxes.size());

            // 1. 创建内存画布（TYPE_INT_RGB 在服务器上最稳定）
            BufferedImage debugImage = new BufferedImage(
                    originalImage.getWidth(),
                    originalImage.getHeight(),
                    BufferedImage.TYPE_INT_RGB
            );

            Graphics2D g2d = debugImage.createGraphics();

            try {
                // 2. 基础绘制（不依赖系统高级特性）
                g2d.drawImage(originalImage, 0, 0, null);

                // 3. 极其简易的绘图（避开 Font 和 Stroke 导致的 Headless 崩溃）
                g2d.setColor(Color.RED);

                for (DetectionBox box : boxes) {
                    // 1. 获取坐标
                    int x = (int) (box.getLeft() * originalImage.getWidth());
                    int y = (int) (box.getTop() * originalImage.getHeight());
                    int w = (int) (box.getWidth() * originalImage.getWidth());
                    int h = (int) (box.getHeight() * originalImage.getHeight());

                    // 2. 映射中文标签
                    String labelName = "未知";
                    // 注意：这里的 classLabels 是你在 Service 里加载的那个 String[] 数组
                    if (COCO_CLASS_NAMES != null && box.getClassId() >= 0 && box.getClassId() < COCO_CLASS_NAMES.length) {
                        labelName = COCO_CLASS_NAMES[box.getClassId()];
                    }
                    String fullLabel = String.format("%s %.2f", labelName, box.getConfidence());

                    // 3. 绘制框
                    g2d.setColor(Color.RED);
                    g2d.setStroke(new BasicStroke(Math.max(2, originalImage.getWidth() / 400)));
                    g2d.drawRect(x, y, w, h);

                    // 4. 设置支持中文的字体
                    // "Microsoft YaHei" (Windows), "SimSun" (Linux常见), "Dialog" (Java通用)
                    Font chineseFont = new Font("Microsoft YaHei", Font.BOLD, Math.max(14, originalImage.getWidth() / 50));
                    // 如果系统没有微软雅黑，Java会自动回退到能显示中文的默认逻辑字体
                    g2d.setFont(chineseFont);

                    // 5. 绘制标签背景和文字
                    FontMetrics fm = g2d.getFontMetrics();
                    int textWidth = fm.stringWidth(fullLabel);
                    int textHeight = fm.getHeight();

                    // 绘制实心背景以便看清文字
                    g2d.setColor(new Color(255, 0, 0, 220));
                    g2d.fillRect(x, Math.max(0, y - textHeight), textWidth + 6, textHeight);

                    // 绘制白色标签文字
                    g2d.setColor(Color.WHITE);
                    g2d.drawString(fullLabel, x + 3, Math.max(textHeight - 5, y - 5));
                }
                logger.info("内存绘图完成，返回调试图片");

            } catch (Exception e) {
                logger.error("Graphics2D 绘图过程发生异常: {}", e.getMessage(), e);
            } finally {
                g2d.dispose();
            }

            return debugImage;

        } catch (Throwable t) {
            // 使用 Throwable 捕获包括 Error 在内的所有问题
            logger.error("创建调试图片时发生严重错误: {}", t.getMessage(), t);
            return null;
        }
    }

    @Override
    @PreDestroy
    public void close() {
        if (predictor != null) {
            predictor.close();
        }
        if (model != null) {
            model.close();
        }
        if (manager != null) {
            manager.close();
        }
        logger.info("YOLOv8n检测器资源已释放");
    }
}