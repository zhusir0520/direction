package org.example.nsfwserver.util;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import org.example.nsfwserver.config.ModelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.util.function.Consumer;

@Component
public class ImagePreprocessor {
    private static final Logger logger = LoggerFactory.getLogger(ImagePreprocessor.class);

    private final ModelConfig modelConfig;
    private final int inputWidth;
    private final int inputHeight;

    public ImagePreprocessor(ModelConfig modelConfig) {
        this.modelConfig = modelConfig;
        this.inputWidth = modelConfig.getInputWidth();
        this.inputHeight = modelConfig.getInputHeight();

        logger.info("ImagePreprocessor initialized with:");
        logger.info("  Input size: {}x{}", inputWidth, inputHeight);
        logger.info("  Normalization type: {}", modelConfig.getNormalizationType());
        logger.info("  Mean: {}", modelConfig.getMean());
        logger.info("  Std: {}", modelConfig.getStd());
        logger.info("  Effective mean array: [{}, {}, {}]",
            modelConfig.getMeanArray()[0], modelConfig.getMeanArray()[1], modelConfig.getMeanArray()[2]);
        logger.info("  Effective std array: [{}, {}, {}]",
            modelConfig.getStdArray()[0], modelConfig.getStdArray()[1], modelConfig.getStdArray()[2]);
    }

    /**
     * 预处理图片：调整尺寸为指定大小，应用归一化
     * @param inputStream 图片输入流
     * @param manager NDManager用于创建NDArray
     * @return 预处理后的NDArray，形状为[1, 3, height, width]
     */
    public NDArray preprocess(InputStream inputStream, NDManager manager) {
        try {
            // 将InputStream转换为byte[]，然后使用byte[]版本的处理方法
            byte[] imageBytes = inputStream.readAllBytes();
            return preprocess(imageBytes, manager);
        } catch (Exception e) {
            logger.error("Error preprocessing image from InputStream", e);
            throw new RuntimeException("Failed to preprocess image from InputStream", e);
        }
    }

    /**
     * 预处理图片并返回批处理格式
     * @param imageBytes 图片字节数组
     * @param manager NDManager用于创建NDArray
     * @return 预处理后的NDArray，形状为[1, 3, height, width]
     */
    public NDArray preprocess(byte[] imageBytes, NDManager manager) {
        try {
            // 1. 加载图片，确保是BufferedImage
            BufferedImage bufferedImage;
            try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(imageBytes)) {
                bufferedImage = ImageIO.read(bais);
                if (bufferedImage == null) {
                    throw new IllegalArgumentException("Unsupported image format or corrupt image data");
                }
            }

            // 2. 转换为RGB格式（确保是3通道）
            if (bufferedImage.getType() != BufferedImage.TYPE_3BYTE_BGR &&
                bufferedImage.getType() != BufferedImage.TYPE_INT_RGB) {
                BufferedImage converted = new BufferedImage(
                    bufferedImage.getWidth(),
                    bufferedImage.getHeight(),
                    BufferedImage.TYPE_3BYTE_BGR
                );
                // 检查是否是无头环境
                if (GraphicsEnvironment.isHeadless()) {
                    // 无头环境：手动复制像素
                    for (int y = 0; y < bufferedImage.getHeight(); y++) {
                        for (int x = 0; x < bufferedImage.getWidth(); x++) {
                            int rgb = bufferedImage.getRGB(x, y);
                            converted.setRGB(x, y, rgb);
                        }
                    }
                } else {
                    // 有显示环境：使用Graphics2D
                    Graphics2D g = converted.createGraphics();
                    g.drawImage(bufferedImage, 0, 0, null);
                    g.dispose();
                }
                bufferedImage = converted;
            }

            // 3. 调整尺寸到目标大小
            BufferedImage resizedImage = new BufferedImage(inputWidth, inputHeight, BufferedImage.TYPE_3BYTE_BGR);

            // 检查是否是无头环境
            if (GraphicsEnvironment.isHeadless()) {
                // 无头环境：手动缩放
                for (int y = 0; y < inputHeight; y++) {
                    for (int x = 0; x < inputWidth; x++) {
                        // 计算源图像中对应的坐标
                        int srcX = x * bufferedImage.getWidth() / inputWidth;
                        int srcY = y * bufferedImage.getHeight() / inputHeight;

                        // 确保坐标在范围内
                        srcX = Math.min(srcX, bufferedImage.getWidth() - 1);
                        srcY = Math.min(srcY, bufferedImage.getHeight() - 1);

                        // 获取源像素
                        int rgb = bufferedImage.getRGB(srcX, srcY);

                        // 设置目标像素
                        resizedImage.setRGB(x, y, rgb);
                    }
                }
            } else {
                // 有显示环境：使用Graphics2D进行高质量缩放
                Graphics2D g2d = resizedImage.createGraphics();
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2d.drawImage(bufferedImage, 0, 0, inputWidth, inputHeight, null);
                g2d.dispose();
            }

            // 4. 获取归一化参数
            float[] mean = modelConfig.getMeanArray();
            float[] std = modelConfig.getStdArray();

            // 记录使用的归一化参数
            logger.info("DEBUG - ImagePreprocessor normalization parameters:");
            logger.info("DEBUG -   mean: [{}, {}, {}]", mean[0], mean[1], mean[2]);
            logger.info("DEBUG -   std: [{}, {}, {}]", std[0], std[1], std[2]);
            logger.info("DEBUG -   normalization type: {}", modelConfig.getNormalizationType());

            // 记录使用的归一化参数
            logger.debug("Using normalization - mean: [{}, {}, {}], std: [{}, {}, {}]",
                    mean[0], mean[1], mean[2], std[0], std[1], std[2]);

            // 5. 获取像素数据并预处理（归一化）
            // 图像数据存储为BGR格式（BufferedImage.TYPE_3BYTE_BGR）
            byte[] pixelData = ((java.awt.image.DataBufferByte) resizedImage.getRaster().getDataBuffer()).getData();
            // 像素数据长度为 width * height * 3 (BGR顺序)
            int numPixels = inputWidth * inputHeight;
            float[] floatData = new float[numPixels * 3];

            // 将BGR转换为RGB，并进行归一化
            for (int i = 0; i < numPixels; i++) {
                // BGR顺序：blue = pixelData[i*3], green = pixelData[i*3+1], red = pixelData[i*3+2]
                float b = (pixelData[i * 3] & 0xFF) / 255.0f;
                float g = (pixelData[i * 3 + 1] & 0xFF) / 255.0f;
                float r = (pixelData[i * 3 + 2] & 0xFF) / 255.0f;

                // 应用归一化: (pixel/255 - mean) / std
                floatData[i] = (r - mean[0]) / std[0];                     // R channel
                floatData[numPixels + i] = (g - mean[1]) / std[1];         // G channel
                floatData[2 * numPixels + i] = (b - mean[2]) / std[2];     // B channel
            }

            // 6. 创建NDArray，形状为[1, 3, height, width]
            // 数据已经是CHW格式：3个通道，每个通道是height*width的连续数组
            NDArray array = manager.create(floatData, new Shape(1, 3, inputHeight, inputWidth));
            return array;

        } catch (Exception e) {
            logger.error("Error preprocessing image", e);
            throw new RuntimeException("Failed to preprocess image", e);
        }
    }

    /**
     * 预处理图片并返回批处理格式（使用自定义归一化参数）
     * @param imageBytes 图片字节数组
     * @param manager NDManager用于创建NDArray
     * @param mean 自定义均值数组 [R, G, B]
     * @param std 自定义标准差数组 [R, G, B]
     * @param debugImageConsumer 调试图片消费者，如果非null则接收预处理后的图片（不保存到文件）
     * @return 预处理后的NDArray，形状为[1, 3, height, width]
     */
    public NDArray preprocess(byte[] imageBytes, NDManager manager, float[] mean, float[] std, Consumer<BufferedImage> debugImageConsumer) {
        try {
            // 1. 加载图片
            BufferedImage original;
            try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(imageBytes)) {
                original = ImageIO.read(bais);
                if (original == null) {
                    throw new IllegalArgumentException("Unsupported image format or corrupt image data");
                }
            }

            // 2. --- 核心改变：使用填充（Letterbox）逻辑替代硬拉伸 ---
            // 这里的 inputWidth 和 inputHeight 是你类中定义的模型输入尺寸（如 224 或 640）
            int origW = original.getWidth();
            int origH = original.getHeight();

            float gain = Math.min((float) inputWidth / origW, (float) inputHeight / origH);
            int newW = Math.round(origW * gain);
            int newH = Math.round(origH * gain);
            int padX = (inputWidth - newW) / 2;
            int padY = (inputHeight - newH) / 2;

            // 创建目标画布
            BufferedImage paddedImage = new BufferedImage(inputWidth, inputHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = paddedImage.createGraphics();
            try {
                // 开启高质量插值，防止产生干扰模型的锯齿
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                // 填充灰色背景（YOLO/Classification通用标准：114, 114, 114）
                g2d.setColor(new Color(114, 114, 114));
                g2d.fillRect(0, 0, inputWidth, inputHeight);

                // 居中绘制原图
                g2d.drawImage(original, padX, padY, newW, newH, null);
            } finally {
                g2d.dispose();
            }

            // 调试图片处理
            if (debugImageConsumer != null) {
                // 将预处理后的图片传递给消费者
                debugImageConsumer.accept(paddedImage);
                logger.info(">>> 预处理调试图已传递给消费者，尺寸: {}x{}", paddedImage.getWidth(), paddedImage.getHeight());
            } else {
                // 调试图片消费者为null，不保存文件，仅记录日志
                logger.debug("调试图片消费者为null，跳过预处理图片保存");
            }

            // 3. 提取像素数据并进行通道归一化
            // 注意：由于上面用了 TYPE_INT_RGB，我们手动通过 getRGB 获取像素以确保 R-G-B 通道顺序准确
            float[] floatData = new float[3 * inputHeight * inputWidth];
            int numPixels = inputHeight * inputWidth;

            for (int y = 0; y < inputHeight; y++) {
                for (int x = 0; x < inputWidth; x++) {
                    int rgb = paddedImage.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;

                    int pixelIdx = y * inputWidth + x;
                    // 按 [C, H, W] 格式填充
                    floatData[pixelIdx] = (r / 255.0f - mean[0]) / std[0];                 // R Channel
                    floatData[numPixels + pixelIdx] = (g / 255.0f - mean[1]) / std[1];     // G Channel
                    floatData[2 * numPixels + pixelIdx] = (b / 255.0f - mean[2]) / std[2]; // B Channel
                }
            }

            // 4. 创建 NDArray，形状为 [1, 3, height, width]
            return manager.create(floatData, new Shape(1, 3, inputHeight, inputWidth));

        } catch (Exception e) {
            logger.error("Error during image preprocessing with letterbox", e);
            throw new RuntimeException("Failed to preprocess image", e);
        }
    }

    /**
     * 预处理图片并返回批处理格式（使用自定义归一化参数）- 向后兼容版本
     * @param imageBytes 图片字节数组
     * @param manager NDManager用于创建NDArray
     * @param mean 自定义均值数组 [R, G, B]
     * @param std 自定义标准差数组 [R, G, B]
     * @return 预处理后的NDArray，形状为[1, 3, height, width]
     */
    public NDArray preprocess(byte[] imageBytes, NDManager manager, float[] mean, float[] std) {
        return preprocess(imageBytes, manager, mean, std, null);
    }

    public int getInputWidth() {
        return inputWidth;
    }

    public int getInputHeight() {
        return inputHeight;
    }
}