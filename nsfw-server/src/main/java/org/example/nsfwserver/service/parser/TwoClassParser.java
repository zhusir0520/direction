package org.example.nsfwserver.service.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * 二分类模型预测解析器
 * 处理 FalconsAI 等二分类模型 (nsfw, normal) 的输出
 */
public class TwoClassParser implements PredictionParser {
    private static final Logger logger = LoggerFactory.getLogger(TwoClassParser.class);

    @Override
    public Object[] parse(float[] predictions, double threshold) {
        // FalconsAI 逻辑：验证数组长度为2
        if (predictions == null || predictions.length != 2) {
            logger.error("Two-class model expects 2 predictions, got: {}",
                (predictions == null ? "null" : predictions.length));
            throw new IllegalArgumentException("Two-class model expects 2 predictions, got: " +
                (predictions == null ? "null" : predictions.length));
        }

        // 记录原始预测值
        logger.debug("Raw predictions: {}", Arrays.toString(predictions));
        logger.debug("Class 0 (nsfw) raw value: {}", String.format("%.6f", predictions[0]));
        logger.debug("Class 1 (normal) raw value: {}", String.format("%.6f", predictions[1]));

        float nsfwProbability;

        // 检查是否是logits（值不在[0,1]范围内）
        boolean needsSigmoid = false;
        for (float val : predictions) {
            if (val < 0 || val > 1) {
                needsSigmoid = true;
                logger.debug("Value {} out of [0,1] range, will apply sigmoid", val);
                break;
            }
        }

        if (needsSigmoid) {
            logger.info("Output appears to be logits (not probabilities), applying sigmoid");
            // 应用sigmoid：1 / (1 + exp(-x))
            // 根据用户说明：第一个输出固定是nsfw，第二个是normal
            nsfwProbability = 1.0f / (1.0f + (float)Math.exp(-predictions[0]));
            logger.info("Logits[0]={} -> NSFW probability={}",
                String.format("%.6f", predictions[0]), String.format("%.6f", nsfwProbability));
        } else {
            // 已经是概率，根据用户说明：第一个输出固定是nsfw
            nsfwProbability = predictions[0];
            logger.info("Probability[0]={} used as NSFW probability", String.format("%.6f", nsfwProbability));
        }

        boolean isNsfw = nsfwProbability >= threshold;

        logger.info("Two-class model - NSFW probability: {}, Threshold: {}, Result: {}",
                String.format("%.4f", nsfwProbability), String.format("%.2f", threshold),
                isNsfw ? "NSFW" : "SFW");

        return new Object[]{isNsfw, (double)nsfwProbability};
    }
}