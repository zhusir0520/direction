package org.example.nsfwserver.controller;

import org.example.nsfwserver.dto.NsfwResponse;
import org.example.nsfwserver.service.NsfwDetectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;

@RestController
@RequestMapping("/api/nsfw")
@CrossOrigin(origins = "*")
public class NsfwController {
    private static final Logger logger = LoggerFactory.getLogger(NsfwController.class);

    private final NsfwDetectionService detectionService;

    @Autowired
    public NsfwController(NsfwDetectionService detectionService) {
        this.detectionService = detectionService;
    }

    /**
     * 健康检查端点
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("NSFW Detection Service is running");
    }

    /**
     * NSFW图片检测端点
     */
    @PostMapping(value = "/detect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<NsfwResponse> detectNsfw(
            @RequestParam("image") MultipartFile image) {

        logger.info("Received image upload: {} ({} bytes)",
                image.getOriginalFilename(), image.getSize());

        // 验证文件
        if (image.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(NsfwResponse.error("Uploaded file is empty"));
        }

        // 验证文件类型
        String contentType = image.getContentType();
        if (contentType == null || !isImageContentType(contentType)) {
            return ResponseEntity.badRequest()
                    .body(NsfwResponse.error("Invalid file type. Please upload an image file (JPEG, PNG, etc.)"));
        }

        try {
            // 执行NSFW检测（包含YOLOv8n兜底检测）
            NsfwResponse response = detectionService.detectWithYolo(image.getInputStream());

            // 记录YOLO结果信息
            NsfwResponse.YoloResult yoloResult = response.getYoloResult();
            Boolean yoloTriggered = yoloResult != null ? yoloResult.getTriggered() : null;
            Boolean yoloDetectedObjects = yoloResult != null ? yoloResult.getDetectedObjects() : null;
            String yoloUnionBox = yoloResult != null ? yoloResult.getUnionBox() : null;
            Boolean yoloBackendIsNsfw = yoloResult != null ? yoloResult.getBackendIsNsfw() : null;

            logger.info(String.format("Detection completed: isNsfw=%s, confidence=%.4f, yoloTriggered=%s, yoloDetectedObjects=%s, yoloUnionBox=%s, yoloBackendIsNsfw=%s",
                    response.getIsNsfw(), response.getConfidence(),
                    yoloTriggered, yoloDetectedObjects, yoloUnionBox, yoloBackendIsNsfw));

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            logger.error("Error reading uploaded file", e);
            return ResponseEntity.internalServerError()
                    .body(NsfwResponse.error("Error processing image file"));
        } catch (Exception e) {
            logger.error("Error during NSFW detection", e);
            return ResponseEntity.internalServerError()
                    .body(NsfwResponse.error("NSFW detection failed: " + e.getMessage()));
        }
    }

    /**
     * 检查是否为图片文件类型
     */
    private boolean isImageContentType(String contentType) {
        String[] allowedTypes = {
                "image/jpeg",
                "image/png",
                "image/jpg",
                "image/gif",
                "image/bmp",
                "image/webp"
        };
        return Arrays.asList(allowedTypes).contains(contentType.toLowerCase());
    }

    /**
     * 获取服务信息
     */
    @GetMapping("/info")
    public ResponseEntity<String> getServiceInfo() {
        try {
            String[] classLabels = detectionService.getClassLabels();
            String info = String.format(
                    "NSFW Detection Service\n" +
                    "Model type: %s\n" +
                    "Model threshold: %.2f\n" +
                    "Input size: %dx%d\n" +
                    "Normalization: %s (mean: %s, std: %s)\n" +
                    "Class labels: %s\n" +
                    "Number of classes: %d\n" +
                    "Supported image types: JPEG, PNG, GIF, BMP, WebP",
                    detectionService.getModelType(),
                    detectionService.getThreshold(),
                    detectionService.getInputWidth(),
                    detectionService.getInputHeight(),
                    detectionService.getNormalizationType(),
                    detectionService.getMean(),
                    detectionService.getStd(),
                    Arrays.toString(classLabels),
                    classLabels.length
            );
            return ResponseEntity.ok(info);
        } catch (Exception e) {
            logger.error("Error getting service info", e);
            return ResponseEntity.ok("NSFW Detection Service\n" +
                    "Model type: Unknown (service not fully initialized)\n" +
                    "Please check if the model file is properly configured.");
        }
    }
}