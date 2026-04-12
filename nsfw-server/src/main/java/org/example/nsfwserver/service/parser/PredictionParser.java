package org.example.nsfwserver.service.parser;

/**
 * 模型预测结果解析器接口
 * 负责将模型输出的原始预测值解析为可用的检测结果
 */
public interface PredictionParser {
    /**
     * 解析模型预测结果
     * @param predictions 模型输出的原始预测值数组
     * @param threshold NSFW判定阈值
     * @return 包含检测结果的数组：[isNsfw, confidence]
     *         isNsfw: boolean类型，表示是否为NSFW内容
     *         confidence: double类型，表示NSFW置信度（0-1之间）
     */
    Object[] parse(float[] predictions, double threshold);
}