package org.example.nsfwserver.service;

import ai.djl.ModelException;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelZoo;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.Batchifier;
import ai.djl.translate.TranslateException;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import org.example.nsfwserver.config.ModelConfig;
import org.example.nsfwserver.dto.NsfwResponse;
import org.example.nsfwserver.manager.InstantModelManager;
import org.example.nsfwserver.manager.TempFileManager;
import org.example.nsfwserver.service.parser.PredictionParser;
import org.example.nsfwserver.service.parser.PredictionParserFactory;
import org.example.nsfwserver.util.ImagePreprocessor;
import org.example.nsfwserver.util.LabelLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Comparator;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import java.util.function.Supplier;

@Service
public class NsfwDetectionService {
    private static final Logger logger = LoggerFactory.getLogger(NsfwDetectionService.class);

    private final ModelConfig modelConfig;
    private final LabelLoader labelLoader;
    private final PredictionParserFactory parserFactory;
    private final ImagePreprocessor imagePreprocessor;
    private final InstantModelManager modelManager;
    private final TempFileManager tempFileManager;
    private final YoloObjectDetector yoloObjectDetector;

    // 注意：移除了模型字段，改为即时加载模式
    private PredictionParser predictionParser;
    private String[] classLabels;




    public NsfwDetectionService(ModelConfig modelConfig, LabelLoader labelLoader,
                               PredictionParserFactory parserFactory, ImagePreprocessor imagePreprocessor,
                               InstantModelManager modelManager, TempFileManager tempFileManager,
                               YoloObjectDetector yoloObjectDetector) {
        this.modelConfig = modelConfig;
        this.labelLoader = labelLoader;
        this.parserFactory = parserFactory;
        this.imagePreprocessor = imagePreprocessor;
        this.modelManager = modelManager;
        this.tempFileManager = tempFileManager;
        this.yoloObjectDetector = yoloObjectDetector;
    }

    @PostConstruct
    public void init() {
        logger.info("Initializing Single-Model NSFW Detection Service with Instant Loading Mode...");
        logger.info("Main model configuration: type={}, threshold={}, mean={}, std={}",
            modelConfig.getType(), modelConfig.getThreshold(), Arrays.toString(modelConfig.getMeanArray()), Arrays.toString(modelConfig.getStdArray()));

        try {
            // 1. 初始化标签（用于主模型） - 仍然需要加载标签
            classLabels = labelLoader.loadLabels();
            logger.info("Loaded {} class labels: {}", classLabels.length, Arrays.toString(classLabels));

            // 2. 初始化解析器 - 仍然需要
            predictionParser = parserFactory.createParser();

            // 3. 根据模型加载模式预加载模型
            String modelLoadMode = modelConfig.getModelLoadMode();
            if ("eager".equals(modelLoadMode)) {
                logger.info("Eager model loading mode detected, preloading models...");
                // 预加载主模型（根据配置类型）
                InstantModelManager.ModelType mainModelType = getMainModelType();
                logger.info("Preloading main model: {}", mainModelType.getName());
                try {
                    // 使用通用模型加载器，不硬编码模型类型判断
                    modelManager.preloadModel(mainModelType, this::loadModel);
                    logger.info("Main model ({}) preloaded successfully", mainModelType.getName());
                } catch (Exception e) {
                    logger.warn("Failed to preload main model, will fallback to instant loading", e);
                }
            } else {
                logger.info("Instant model loading mode, models will be loaded on-demand");
            }

            // 记录YOLO加载模式
            logger.info("YOLO detection enabled: {}, model load mode: {}",
                modelConfig.isYoloEnabled(), modelLoadMode);
            if (modelConfig.isYoloEnabled() && "eager".equals(modelLoadMode)) {
                logger.info("YOLO model will be loaded eagerly by YoloObjectDetector");
            }

            logger.info("Single-Model NSFW Detection Service initialized successfully");
            logger.info("Models will be loaded on-demand and released immediately after inference");

        } catch (Exception e) {
            logger.error("Failed to initialize NSFW Detection Service.", e);
            // 不抛出异常，允许应用启动，但检测会失败
            logger.error("NSFW detection may not be available.");
        }
    }

    /**
     * 获取模型文件路径（使用TempFileManager进行幂等拷贝）
     * @param modelPath 模型路径，支持 classpath: 前缀
     * @param modelName 模型名称（用于生成文件名）
     */
    private Path getModelFilePath(String modelPath, String modelName) throws IOException {
        if (modelPath.startsWith("classpath:")) {
            // 从类路径加载资源到临时文件（使用TempFileManager）
            String resourcePath = modelPath.substring("classpath:".length());
            logger.debug("Loading model from classpath: {}", resourcePath);

            // 使用TempFileManager进行幂等拷贝
            return tempFileManager.copyResourceToTemp(resourcePath, modelName);
        } else {
            // 从文件系统加载
            return Paths.get(modelPath);
        }
    }

    /**
     * 获取主模型文件路径
     */
    private Path getPrimaryModelFilePath() throws IOException {
        String modelPath = modelConfig.getEffectiveModelPath();
        return getModelFilePath(modelPath, "main");
    }

    /**
     * 获取次模型文件路径（用于兼容性，实际使用主模型路径）
     */
    private Path getSecondaryModelFilePath() throws IOException {
        String modelPath = modelConfig.getEffectiveModelPath();
        return getModelFilePath(modelPath, "secondary");
    }

    /**
     * 使用指定模型进行检测
     * @param imageBytes 图片字节数组
     * @param manager NDManager
     * @param predictor 模型预测器
     * @param parser 预测解析器
     * @param threshold 阈值
     * @param mean 归一化均值数组
     * @param std 归一化标准差数组
     * @param debugImageConsumer 调试图片消费者，用于收集预处理后的图片（可为null）
     * @return 检测结果数组 [isNsfw, confidence]
     */
    private Object[] detectWithModel(byte[] imageBytes, NDManager manager,
                                     Predictor<NDArray, float[]> predictor,
                                     PredictionParser parser,
                                     double threshold,
                                     float[] mean, float[] std,
                                     Consumer<BufferedImage> debugImageConsumer) {
        try {
            // 使用自定义归一化参数预处理图片
            NDArray preprocessed = imagePreprocessor.preprocess(imageBytes, manager, mean, std, debugImageConsumer);
            float[] predictions = predictor.predict(preprocessed);
            return parser.parse(predictions, threshold);
        } catch (Exception e) {
            logger.error("Error during model detection", e);
            throw new RuntimeException("Failed to perform model detection", e);
        }
    }

    /**
     * 生成模型输出JSON字符串
     * @param modelName 模型名称
     * @param isNsfw 是否NSFW
     * @param confidence 置信度
     * @param threshold 模型阈值
     * @return JSON对象字符串
     */
    private String buildModelOutputJson(String modelName, boolean isNsfw, double confidence, double threshold) {
        // 构建简单的JSON对象，不使用外部库以保持轻量
        return String.format("{\"model\":\"%s\",\"isNsfw\":%s,\"confidence\":%.4f,\"threshold\":%.1f}",
                modelName, isNsfw ? "true" : "false", confidence, threshold);
    }

    /**
     * 单模型检测核心逻辑（使用即时加载模式）
     * @param imageBytes 图片字节数组
     * @param debugImages 调试图片Map，用于收集预处理后的图片（可为null）
     * @return 包含检测结果的数组：[isNsfw, confidence, modelOutput]
     */
    private Object[] dualModelDetect(byte[] imageBytes, Map<String, BufferedImage> debugImages) {
        // 检查解析器是否已初始化（模型将即时加载）
        if (predictionParser == null) {
            throw new RuntimeException("No NSFW detection parser is initialized. Please check if model files are placed correctly.");
        }

        boolean isNsfw = false;
        double confidence = 0.0;
        boolean success = false;

        // 获取主模型类型和配置参数
        InstantModelManager.ModelType mainModelType = getMainModelType();
        double threshold = modelConfig.getThreshold();
        float[] mean = modelConfig.getMeanArray();
        float[] std = modelConfig.getStdArray();
        String modelName = modelConfig.getType(); // 5class, falconsai, etc.

        // 检测主模型 - 使用即时加载模式
        try {
            Consumer<BufferedImage> debugConsumer = (debugImages != null) ?
                image -> debugImages.put(modelName + "_preprocessed", image) : null;
            Object[] result = detectWithInstantModel(imageBytes, mainModelType,
                    predictionParser, threshold,
                    mean, std, debugConsumer);
            isNsfw = (boolean) result[0];
            confidence = (double) result[1];
            success = true;
            logger.info(String.format("Main model detection: isNsfw=%s, confidence=%.4f", isNsfw, confidence));
        } catch (Exception e) {
            logger.error("Main model detection failed", e);
            throw new RuntimeException("Main model detection failed", e);
        }

        if (!success) {
            throw new RuntimeException("Main model detection failed");
        }

        // 生成modelOutput JSON数组（单模型）
        String modelOutput = "[" + buildModelOutputJson(modelName, isNsfw, confidence, threshold) + "]";

        logger.info(String.format("Single-model detection result: isNsfw=%s, confidence=%.4f, modelOutput=%s",
                isNsfw, confidence, modelOutput));

        return new Object[]{isNsfw, confidence, modelOutput};
    }

    /**
     * 触发YOLOv8n检测和裁剪区域二次检测
     * @param originalImageBytes 原始图像字节数组
     * @param currentResponse 当前响应对象
     * @return 更新后的响应对象
     */
    private NsfwResponse triggerYoloDetection(byte[] originalImageBytes, NsfwResponse currentResponse) {
        try {
            // 获取主模型配置
            InstantModelManager.ModelType mainModelType = getMainModelType();
            double threshold = modelConfig.getThreshold();
            float[] mean = modelConfig.getMeanArray();
            float[] std = modelConfig.getStdArray();
            YoloObjectDetector yoloDetector = this.yoloObjectDetector;

            // 1. 加载原始图像
            BufferedImage originalImage = ImageIO.read(new ByteArrayInputStream(originalImageBytes));
            if (originalImage == null) {
                logger.error("无法加载原始图像");
                // 创建YOLO结果对象，表示YOLO已触发但未检测到物体
                NsfwResponse.YoloResult yoloResult = new NsfwResponse.YoloResult(
                    true,  // triggered
                    false, // detectedObjects
                    null,  // unionBox
                    null,  // backendIsNsfw
                    null,  // backendConfidence
                    null   // backendRawScores
                );
                currentResponse.setYoloResult(yoloResult);
                return currentResponse;
            }

            // 2. YOLOv8n物体检测
            YoloObjectDetector.YoloResult yoloResult = yoloDetector.detectObjects(originalImage);

            // 初始化调试图片Map（如果为null）
            if (currentResponse.getDebugImages() == null) {
                currentResponse.setDebugImages(new HashMap<>());
            }

            // 3. 生成YOLOv8n检测框调试图片（Base64） - 无论是否检测到物体都生成
            try {
                // 创建带检测框的调试图片（boxes可能为空）
                BufferedImage debugImageWithBoxes = yoloDetector.createDebugImageWithDetections(originalImage,
                    yoloResult.getBoxes() != null ? yoloResult.getBoxes() : new ArrayList<>());
                NsfwResponse.DebugImage detectionDebugImage = createDebugImage(
                    debugImageWithBoxes,
                    "jpg",
                    yoloResult.getBoxes() != null && !yoloResult.getBoxes().isEmpty() ?
                        "YOLO检测框" :
                        "YOLO检测框（无物体）"
                );
                currentResponse.getDebugImages().put("yolo_detections", detectionDebugImage);
                logger.info("YOLOv8n检测框调试图片已生成Base64");
            } catch (Exception e) {
                logger.warn("无法生成YOLOv8n检测框调试图片: {}", e.getMessage());
            }

            if (!yoloResult.isDetected() || yoloResult.getUnionBox() == null) {
                logger.info("YOLOv8n未检测到物体");
                // 创建YOLO结果对象，表示YOLO已触发但未检测到物体
                NsfwResponse.YoloResult yoloResultObj = new NsfwResponse.YoloResult(
                    true,  // triggered
                    false, // detectedObjects
                    null,  // unionBox
                    null,  // backendIsNsfw
                    null,  // backendConfidence
                    null   // backendRawScores
                );
                currentResponse.setYoloResult(yoloResultObj);
                return currentResponse;
            }

            logger.info("YOLOv8n检测到 {} 个物体，并集框: [{}, {}, {}, {}]",
                yoloResult.getBoxes().size(),
                yoloResult.getUnionBox()[0], yoloResult.getUnionBox()[1],
                yoloResult.getUnionBox()[2], yoloResult.getUnionBox()[3]);

            // 4. 裁剪并缩放
            BufferedImage croppedImage = yoloDetector.cropToUnionBox(originalImage, yoloResult.getBoxes());

            // 5. 生成裁剪后的调试图片（Base64）- 先添加到响应，如果FalconsAI检测为NSFW则保留，否则移除
            try {
                NsfwResponse.DebugImage croppedDebugImage = createDebugImage(
                    croppedImage,
                    "jpg",
                    "yolo命中区域送检图片"
                );
                currentResponse.getDebugImages().put("yoloHitImage", croppedDebugImage);
                logger.info("YOLOv8n裁剪图像已生成Base64，尺寸: {}x{}", croppedImage.getWidth(), croppedImage.getHeight());
            } catch (IOException e) {
                logger.warn("无法生成裁剪调试图像: {}", e.getMessage());
            }

            // 6. 将裁剪后的图像转换为byte[]
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(croppedImage, "jpg", baos);
            byte[] croppedBytes = baos.toByteArray();

            // 7. 使用主模型重新检测裁剪区域
            Object[] mainResult;
            try (NDManager manager = NDManager.newBaseManager()) {
                // 根据用户需求，不返回yolo_cropped_preprocessed图片，将Consumer设为null
                // 如果需要后续恢复，可以取消注释下面的Consumer代码
                /*
                Consumer<BufferedImage> yoloCroppedDebugConsumer = image -> {
                    if (currentResponse.getDebugImages() != null) {
                        try {
                             NsfwResponse.DebugImage debugImage = createDebugImage(
                                image, "jpg", "YOLO裁剪区域预处理 - 送检主模型前"
                            );
                            currentResponse.getDebugImages().put("yolo_cropped_preprocessed", debugImage);
                        } catch (IOException e) {
                            logger.warn("无法创建YOLO裁剪区域预处理调试图片: {}", e.getMessage());
                        }
                    }
                };
                */
                Consumer<BufferedImage> yoloCroppedDebugConsumer = null; // 不收集预处理图片
                mainResult = detectWithInstantModel(croppedBytes, manager,
                        mainModelType, predictionParser,
                        threshold, mean, std, yoloCroppedDebugConsumer);
            }

            boolean yoloBackendIsNsfw = (boolean) mainResult[0];
            double yoloBackendConfidence = (double) mainResult[1];

            // 8. 如果并集框检测为SFW，尝试检测person框
            if (!yoloBackendIsNsfw) {
                // 获取所有person框，按置信度降序排序
                List<YoloObjectDetector.DetectionBox> personBoxes = yoloResult.getBoxes().stream()
                        .filter(box -> box.getClassId() == 0) // person类ID为0
                        .sorted(Comparator.comparing(YoloObjectDetector.DetectionBox::getConfidence).reversed())
                        .collect(Collectors.toList());

                logger.info("并集框检测为SFW，开始循环检测{}个person框", personBoxes.size());

                for (YoloObjectDetector.DetectionBox personBox : personBoxes) {
                    try {
                        // 裁剪单个person框
                        BufferedImage personCroppedImage = yoloDetector.cropToUnionBox(originalImage,
                                Collections.singletonList(personBox));

                        // 转换为byte[]
                        ByteArrayOutputStream personBaos = new ByteArrayOutputStream();
                        ImageIO.write(personCroppedImage, "jpg", personBaos);
                        byte[] personCroppedBytes = personBaos.toByteArray();

                        // 使用主模型检测person框
                        Object[] personResult;
                        try (NDManager manager = NDManager.newBaseManager()) {
                            personResult = detectWithInstantModel(personCroppedBytes, manager,
                                    mainModelType, predictionParser,
                                    threshold, mean, std, null);
                        }

                        boolean personIsNsfw = (boolean) personResult[0];
                        double personConfidence = (double) personResult[1];

                        if (personIsNsfw) {
                            // person框检测为NSFW，更新结果
                            yoloBackendIsNsfw = true;
                            yoloBackendConfidence = personConfidence;
                            logger.info("person框检测为NSFW，置信度: {}. 停止循环", String.format("%.4f", personConfidence));

                            // 生成person框裁剪图作为yoloHitImage
                            try {
                                NsfwResponse.DebugImage personDebugImage = createDebugImage(
                                    personCroppedImage,
                                    "jpg",
                                    "yolo命中区域送检图片（person框）"
                                );
                                // 移除旧的yoloHitImage（如果有），添加新的
                                if (currentResponse.getDebugImages() != null) {
                                    currentResponse.getDebugImages().remove("yoloHitImage");
                                    currentResponse.getDebugImages().put("yoloHitImage", personDebugImage);
                                }
                                logger.info("已更新yoloHitImage为person框裁剪图");
                            } catch (IOException e) {
                                logger.warn("无法生成person框调试图像: {}", e.getMessage());
                            }

                            break; // 跳出循环，只取第一个检测为NSFW的person框
                        } else {
                            logger.info("person框检测为SFW，置信度: {}", String.format("%.4f", personConfidence));
                        }
                    } catch (Exception e) {
                        logger.error("检测person框时出错: {}", e.getMessage(), e);
                        // 继续检测下一个框
                    }
                }
            }

            // 9. 合并结果：逻辑或
            boolean finalIsNsfw = currentResponse.getIsNsfw() || yoloBackendIsNsfw;

            // 10. 更新响应
            currentResponse.setIsNsfw(finalIsNsfw);
            // 如果YOLOv8n检测为NSFW，更新置信度为最大值
            if (yoloBackendIsNsfw && yoloBackendConfidence > currentResponse.getConfidence()) {
                currentResponse.setConfidence(yoloBackendConfidence);
            }

            // 创建结构化YOLO结果
            NsfwResponse.YoloResult structuredYoloResult = new NsfwResponse.YoloResult(
                true,
                true,
                formatUnionBox(yoloResult.getUnionBox()),
                yoloBackendIsNsfw,
                (float) yoloBackendConfidence,
                null // 可以添加rawScores如果需要
            );
            currentResponse.setYoloResult(structuredYoloResult);

            // 如果YOLO送检主模型没命中（即检测结果为SFW），不返回yoloHitImage图片
            if (!yoloBackendIsNsfw && currentResponse.getDebugImages() != null) {
                currentResponse.getDebugImages().remove("yoloHitImage");
                logger.info("YOLO送检主模型未命中，已移除yoloHitImage图片");
            }

            logger.info("YOLOv8n检测完成: yoloBackendIsNsfw={}, yoloBackendConfidence={}, finalIsNsfw={}",
                    yoloBackendIsNsfw, String.format("%.4f", yoloBackendConfidence), finalIsNsfw);

            return currentResponse;

        } catch (Exception e) {
            logger.error("YOLOv8n检测失败", e);
            // 创建YOLO结果对象，表示YOLO已触发但检测失败
            NsfwResponse.YoloResult yoloResult = new NsfwResponse.YoloResult(
                true,  // triggered
                false, // detectedObjects (假设检测失败)
                null,  // unionBox
                null,  // backendIsNsfw
                null,  // backendConfidence
                null   // backendRawScores
            );
            currentResponse.setYoloResult(yoloResult);
            return currentResponse;
        }
    }

    /**
     * 格式化并集框坐标为字符串
     * @param unionBox 归一化坐标数组 [left, top, right, bottom]
     * @return 格式化字符串 "left,top,right,bottom"
     */
    private String formatUnionBox(float[] unionBox) {
        if (unionBox == null || unionBox.length != 4) {
            return null;
        }
        return String.format("%.4f,%.4f,%.4f,%.4f", unionBox[0], unionBox[1], unionBox[2], unionBox[3]);
    }

    /**
     * 检测图片是否为NSFW内容
     * @param imageStream 图片输入流
     * @return 包含检测结果的数组：[isNsfw, confidence, modelOutput]
     */
    public Object[] detect(InputStream imageStream) {
        try {
            byte[] imageBytes = imageStream.readAllBytes();
            return dualModelDetect(imageBytes, null);
        } catch (Exception e) {
            logger.error("Error reading image stream", e);
            throw new RuntimeException("Failed to read image stream", e);
        }
    }

    /**
     * 检测图片是否为NSFW内容（字节数组版本）
     * @param imageBytes 图片字节数组
     * @return 包含检测结果的数组：[isNsfw, confidence, modelOutput]
     */
    public Object[] detect(byte[] imageBytes) {
        return dualModelDetect(imageBytes, null);
    }

    /**
     * 检测图片是否为NSFW内容（包含YOLOv8n兜底检测，字节数组版本）
     * @param imageBytes 图片字节数组
     * @return 包含检测结果的NsfwResponse对象
     */
    public NsfwResponse detectWithYolo(byte[] imageBytes) {
        logger.info("开始双模型+YOLOv8n检测流程");

        // 1. 创建调试图片Map，用于收集预处理图片
        Map<String, BufferedImage> debugImagesMap = new HashMap<>();

        // 2. 执行双模型检测，传递调试图片Map
        Object[] dualModelResult = dualModelDetect(imageBytes, debugImagesMap);
        boolean finalIsNsfw = (boolean) dualModelResult[0];
        double finalConfidence = (double) dualModelResult[1];
        String modelOutput = dualModelResult.length > 2 ? (String) dualModelResult[2] : null;

        // 3. 创建初始响应
        NsfwResponse response = new NsfwResponse(finalIsNsfw, finalConfidence);
        response.setModelOutput(modelOutput);

        // 4. 初始化调试图片Map
        response.setDebugImages(new HashMap<>());

        // 5. 根据用户需求修改调试图片返回逻辑：
        //    - 双模型检测成功（finalIsNsfw为true）时不返回任何图片
        //    - 双模型未识别（finalIsNsfw为false）且触发YOLO时，根据YOLO结果返回
        //       * YOLO命中（检测到物体）：返回yolo_detections和yoloHitImage
        //       * YOLO未命中：只返回yolo_detections
        // 暂时注释掉预处理图片的返回逻辑，后续可能会用
        /*
        for (Map.Entry<String, BufferedImage> entry : debugImagesMap.entrySet()) {
            try {
                String key = entry.getKey();
                BufferedImage image = entry.getValue();
                String description;
                if ("5class_preprocessed".equals(key)) {
                    description = "5class模型预处理 - 填充缩放后的图像（输入模型前）";
                } else if ("falconsai_preprocessed".equals(key)) {
                    description = "falconsai模型预处理 - 填充缩放后的图像（输入模型前）";
                } else {
                    description = "预处理图像";
                }
                NsfwResponse.DebugImage debugImage = createDebugImage(image, "jpg", description);
                response.getDebugImages().put(key, debugImage);
                logger.info("已添加调试图片: {}, 尺寸: {}x{}", key, image.getWidth(), image.getHeight());
            } catch (Exception e) {
                logger.warn("无法转换调试图片 {}: {}", entry.getKey(), e.getMessage());
            }
        }
        */

        // 6. 检查是否触发YOLOv8n检测
        // 条件：主模型检测为SFW，且YOLOv8n功能启用
        // 简化逻辑：如果finalIsNsfw为false且YOLOv8n启用，则触发
        // 启用YOLOv8n进行调试
        boolean yoloEnabled = true; // 重新启用进行调试
        if (!finalIsNsfw && yoloEnabled) {
            logger.info("触发YOLOv8n检测层");
            response = triggerYoloDetection(imageBytes, response);
        } else {
            logger.info("跳过YOLOv8n检测层: finalIsNsfw={}, yoloEnabled={}", finalIsNsfw, yoloEnabled);
        }

        return response;
    }

    /**
     * 检测图片是否为NSFW内容（包含YOLOv8n兜底检测，输入流版本）
     * @param imageStream 图片输入流
     * @return 包含检测结果的NsfwResponse对象
     */
    public NsfwResponse detectWithYolo(InputStream imageStream) {
        try {
            byte[] imageBytes = imageStream.readAllBytes();
            return detectWithYolo(imageBytes);
        } catch (Exception e) {
            logger.error("Error reading image stream", e);
            throw new RuntimeException("Failed to read image stream", e);
        }
    }

    /**
     * 自定义Translator，处理模型输入输出
     */
    private static class NsfwTranslator implements Translator<NDArray, float[]> {
        private static final Logger logger = LoggerFactory.getLogger(NsfwTranslator.class);

        @Override
        public NDList processInput(TranslatorContext ctx, NDArray input) {
            // 输入已经是预处理好的NDArray，直接返回
            return new NDList(input);
        }

        @Override
        public float[] processOutput(TranslatorContext ctx, NDList list) {
            // 输出是一个形状为[1, n]的NDArray，需要提取为float数组
            NDArray output = list.singletonOrThrow();

            // 记录输出形状和数据类型用于调试
            logger.debug("Model output shape: {}, data type: {}", output.getShape(), output.getDataType());

            // 直接转换为float数组，避免使用get(0)操作（OnnxRuntime引擎可能不支持）
            float[] result = output.toFloatArray();

            // 记录原始输出值用于调试
            logger.debug("Raw model output values: {}", Arrays.toString(result));

            // 检查数组长度，返回所有元素（解析器会验证长度）
            return result;
        }

        @Override
        public Batchifier getBatchifier() {
            // 不支持批处理，避免OnnxRuntime引擎的stack操作问题
            return null;
        }
    }

    /**
     * 将BufferedImage转换为Base64字符串
     * @param image 图片对象
     * @param format 图片格式（如"jpg", "png"）
     * @return Base64编码的字符串
     */
    private String bufferedImageToBase64(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, format, baos);
        byte[] bytes = baos.toByteArray();
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * 创建调试图片对象
     * @param image 图片对象
     * @param format 图片格式
     * @param description 图片描述
     * @return DebugImage对象
     */
    private NsfwResponse.DebugImage createDebugImage(BufferedImage image, String format, String description) throws IOException {
        String base64 = bufferedImageToBase64(image, format);
        return new NsfwResponse.DebugImage(base64, image.getWidth(), image.getHeight(), description, format);
    }

    /**
     * 加载主模型（5class）- 兼容性方法，实际调用通用加载器
     */
    private ZooModel<NDArray, float[]> loadPrimaryModel() {
        logger.debug("loadPrimaryModel called, using generic model loader");
        return loadModel();
    }

    /**
     * 加载次模型（falconsai）- 兼容性方法，实际调用通用加载器
     */
    private ZooModel<NDArray, float[]> loadSecondaryModel() {
        logger.debug("loadSecondaryModel called, using generic model loader");
        return loadModel();
    }

    /**
     * 通用模型加载器，不硬编码模型类型
     * 根据配置的模型路径加载任意模型
     */
    private ZooModel<NDArray, float[]> loadModel() {
        logger.debug("Creating generic model loader for model type: {}", modelConfig.getType());
        try {
            // 使用配置的有效模型路径，支持任意模型文件
            String modelPath = modelConfig.getEffectiveModelPath();
            Path modelFilePath = getModelFilePath(modelPath, "generic");

            Criteria<NDArray, float[]> criteria = Criteria.builder()
                    .setTypes(NDArray.class, float[].class)
                    .optModelPath(modelFilePath)
                    .optTranslator(new NsfwTranslator())
                    .optEngine("OnnxRuntime")
                    .build();
            return ModelZoo.loadModel(criteria);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load model: " + modelConfig.getType(), e);
        }
    }

    /**
     * 使用即时加载模式检测单模型
     * @param imageBytes 图片字节数组
     * @param modelType 模型类型
     * @param parser 预测解析器
     * @param threshold 阈值
     * @param mean 归一化均值
     * @param std 归一化标准差
     * @param debugImageConsumer 调试图片消费者
     * @return 检测结果数组 [isNsfw, confidence]
     */
    private Object[]  detectWithInstantModel(byte[] imageBytes, InstantModelManager.ModelType modelType,
                                           PredictionParser parser, double threshold,
                                           float[] mean, float[] std,
                                           Consumer<BufferedImage> debugImageConsumer) {
        try (NDManager manager = NDManager.newBaseManager()) {
            return detectWithInstantModel(imageBytes, manager, modelType, parser, threshold, mean, std, debugImageConsumer);
        } catch (Exception e) {
            logger.error("Error during instant model detection", e);
            throw new RuntimeException("Failed to perform instant model detection", e);
        }
    }

    /**
     * 使用即时加载模式检测单模型（使用外部NDManager）
     * @param imageBytes 图片字节数组
     * @param manager 外部NDManager（调用方负责关闭）
     * @param modelType 模型类型
     * @param parser 预测解析器
     * @param threshold 阈值
     * @param mean 归一化均值
     * @param std 归一化标准差
     * @param debugImageConsumer 调试图片消费者
     * @return 检测结果数组 [isNsfw, confidence]
     */
    private Object[]  detectWithInstantModel(byte[] imageBytes, NDManager manager,
                                           InstantModelManager.ModelType modelType,
                                           PredictionParser parser, double threshold,
                                           float[] mean, float[] std,
                                           Consumer<BufferedImage> debugImageConsumer) {
        // 通用模型加载器，不硬编码模型类型判断
        // 所有模型都使用相同的加载逻辑，根据配置的模型路径加载
        Supplier<ZooModel<NDArray, float[]>> modelSupplier = this::loadModel;

        // 使用即时模型管理器执行推理（自动加载和释放模型）
        try {
            return modelManager.executeWithModel(modelType, modelSupplier, predictor -> {
                NDArray preprocessed = imagePreprocessor.preprocess(imageBytes, manager, mean, std, debugImageConsumer);
                float[] predictions = predictor.predict(preprocessed);
                return parser.parse(predictions, threshold);
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute model inference", e);
        }
    }

    /**
     * 获取主模型类型（根据配置类型）
     * 支持动态模型类型映射，避免硬编码模型名称判断
     */
    private InstantModelManager.ModelType getMainModelType() {
        String type = modelConfig.getType();

        // 使用模型配置中的类型映射，避免硬编码判断
        // 默认支持的类型映射（可以在配置中扩展）
        switch (type) {
            case "5class":
            case "original":
                return InstantModelManager.ModelType.PRIMARY_5CLASS;
            case "2class":
            case "falconsai":
                return InstantModelManager.ModelType.SECONDARY_FALCONSAI;
            default:
                // 尝试将未知类型映射到默认类型，使用PRIMARY_5CLASS作为兜底
                // 用户可以通过配置使用现有模型类型处理自定义模型
                logger.warn("Unknown model type '{}', defaulting to PRIMARY_5CLASS. " +
                    "To use a custom model, configure it as one of the supported types or extend the mapping.", type);
                return InstantModelManager.ModelType.PRIMARY_5CLASS;
        }
    }

    @PreDestroy
    public void cleanup() {
        logger.info("Cleaning up NSFW Detection Service (Instant Loading Mode)...");
        // 注意：在即时加载模式下，模型已在每次推理后释放
        // 这里只需要清理其他资源（如果有的话）
        logger.info("NSFW Detection Service cleaned up");
    }

    // Getters for service information

    public double getThreshold() {
        return modelConfig.getThreshold();
    }

    public int getInputWidth() {
        return modelConfig.getInputWidth();
    }

    public int getInputHeight() {
        return modelConfig.getInputHeight();
    }

    public String[] getClassLabels() {
        return classLabels != null ? classLabels.clone() : new String[0];
    }

    public String getModelType() {
        return modelConfig.getType();
    }

    public String getNormalizationType() {
        return modelConfig.getNormalizationType();
    }

    public String getMean() {
        return modelConfig.getMean();
    }

    public String getStd() {
        return modelConfig.getStd();
    }
}