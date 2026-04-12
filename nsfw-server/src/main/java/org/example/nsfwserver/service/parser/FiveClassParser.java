package org.example.nsfwserver.service.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * 五分类模型预测解析器
 * 处理原五分类模型 (drawing, hentai, neutral, porn, sexy) 的输出
 * NSFW概率计算为 hentai + porn + sexy 之和
 */
public class FiveClassParser implements PredictionParser {
    private static final Logger logger = LoggerFactory.getLogger(FiveClassParser.class);

    @Override
    public Object[] parse(float[] predictions, double threshold) {
        logger.info("FiveClassParser.parse() called with threshold: {}", threshold);

        if (predictions == null || predictions.length != 5) {
            logger.error("Five-class model expects 5 predictions, got: {}",
                (predictions == null ? "null" : predictions.length));
            throw new IllegalArgumentException("Five-class model expects 5 predictions, got: " +
                (predictions == null ? "null" : predictions.length));
        }

        // 记录各个类别的原始值
        logger.info("Raw predictions array: {}", Arrays.toString(predictions));
        logger.debug("Raw predictions before processing: {}", Arrays.toString(predictions));
        String[] classLabels = {"drawing", "hentai", "neutral", "porn", "sexy"};
        for (int i = 0; i < classLabels.length; i++) {
            logger.info("{} raw value: {}", classLabels[i], String.format("%.6f", predictions[i]));
            logger.debug("{} raw value: {}", classLabels[i], String.format("%.6f", predictions[i]));
        }

        // 检查是否是logits（可能是未归一化的值）
        // 如果值不在[0,1]范围内，可能是logits，需要应用softmax
        boolean needsSoftmax = false;
        for (float val : predictions) {
            if (val < 0 || val > 1) { // 概率应该在0-1之间，超出范围可能是logits
                needsSoftmax = true;
                logger.debug("Value {} out of [0,1] range, will apply softmax", val);
                break;
            }
        }

        // 额外检查：如果所有值都是负数，也应用softmax（logits可能是负数）
        boolean allNegative = true;
        for (float val : predictions) {
            if (val >= 0) {
                allNegative = false;
                break;
            }
        }
        if (allNegative) {
            needsSoftmax = true;
            logger.debug("All values are negative, will apply softmax");
        }

        // 额外检查：如果值在[0,1]范围内但和不接近1.0，也应用softmax
        // 这对于某些输出近似概率但不是正确softmax的模型很重要
        if (!needsSoftmax) {
            float sum = 0.0f;
            for (float val : predictions) {
                sum += val;
            }
            if (Math.abs(sum - 1.0f) > 0.1f) { // 和不在0.9-1.1范围内
                needsSoftmax = true;
                logger.debug("Values in [0,1] range but sum={} not close to 1.0, will apply softmax", sum);
            } else {
                logger.debug("Values in [0,1] range and sum={} close to 1.0, no softmax needed", sum);
            }
        }

        float[] processedPredictions = predictions;
        if (needsSoftmax) {
            logger.debug("Applying softmax to logits");
            processedPredictions = applySoftmax(predictions);
            logger.debug("After softmax: {}", Arrays.toString(processedPredictions));
            for (int i = 0; i < classLabels.length; i++) {
                logger.debug("{} probability: {}", classLabels[i],
                    String.format("%.6f", processedPredictions[i]));
            }
        }

        // 记录处理后的预测值
        logger.debug("Processed predictions for NSFW calculation: {}",
            Arrays.toString(processedPredictions));
        logger.debug("Drawing: {}, Hentai: {}, Neutral: {}, Porn: {}, Sexy: {}",
                String.format("%.6f", processedPredictions[0]),
                String.format("%.6f", processedPredictions[1]),
                String.format("%.6f", processedPredictions[2]),
                String.format("%.6f", processedPredictions[3]),
                String.format("%.6f", processedPredictions[4]));

        // 计算NSFW概率：hentai + porn + sexy
        double nsfwProbability = processedPredictions[1] + processedPredictions[3] + processedPredictions[4];
        double sfwProbability = processedPredictions[0] + processedPredictions[2];

        // 总概率应该接近1.0，进行归一化检查
        double total = nsfwProbability + sfwProbability;
        if (Math.abs(total - 1.0) > 0.1) {
            logger.warn("Probabilities don't sum to 1.0: {}", total);
            // 使用NSFW概率作为置信度，不进行归一化
        }

        // 应用阈值判定
        boolean isNsfw = nsfwProbability >= threshold;

        logger.info("NSFW probability: {}, Threshold: {}, Result: {}",
                String.format("%.4f", nsfwProbability), String.format("%.2f", threshold),
                isNsfw ? "NSFW" : "SFW");

        return new Object[]{isNsfw, nsfwProbability};
    }

    /**
     * 应用softmax函数将logits转换为概率
     * @param logits 原始logits值
     * @return 归一化后的概率值
     */
    private float[] applySoftmax(float[] logits) {
        float[] probabilities = new float[logits.length];
        float maxLogit = Float.NEGATIVE_INFINITY;

        // 找到最大值以提高数值稳定性
        for (float logit : logits) {
            if (logit > maxLogit) {
                maxLogit = logit;
            }
        }

        float sum = 0.0f;
        for (int i = 0; i < logits.length; i++) {
            probabilities[i] = (float) Math.exp(logits[i] - maxLogit);
            sum += probabilities[i];
        }

        // 归一化
        for (int i = 0; i < probabilities.length; i++) {
            probabilities[i] /= sum;
        }

        return probabilities;
    }
}