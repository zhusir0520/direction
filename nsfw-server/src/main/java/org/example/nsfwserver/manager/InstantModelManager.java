package org.example.nsfwserver.manager;

import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDManager;
import ai.djl.repository.zoo.ZooModel;
import org.example.nsfwserver.config.ModelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import javax.annotation.PreDestroy;

/**
 * 即时模型管理器
 * 根据用户最终要求：每次推理后完全释放模型，下次推理重新加载
 * 提供模型会话（ModelSession）模式，确保模型资源在使用后立即释放
 */
@Component
public class InstantModelManager {
    private static final Logger logger = LoggerFactory.getLogger(InstantModelManager.class);

    // 模型类型枚举
    public enum ModelType {
        PRIMARY_5CLASS("5class"),
        SECONDARY_FALCONSAI("falconsai"),
        YOLO_V8N("yolov8n"),
        YOLO_V8S("yolov8s");

        private final String name;

        ModelType(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    // 模型加载器（线程安全，支持并发加载不同模型类型）
    private static class ModelLoader<I, O> {
        private final ReentrantLock loadLock = new ReentrantLock();
        private final Logger logger = LoggerFactory.getLogger(ModelLoader.class);

        /**
         * 加载模型并返回Predictor
         */
        public Predictor<I, O> loadModel(ModelType type, Supplier<ZooModel<I, O>> modelSupplier) {
            loadLock.lock();
            try {
                long startTime = System.currentTimeMillis();
                logger.info("Loading {} model...", type.getName());

                ZooModel<I, O> model = modelSupplier.get();
                Predictor<I, O> predictor = model.newPredictor();

                long loadTime = System.currentTimeMillis() - startTime;
                logger.info("Loaded {} model in {}ms", type.getName(), loadTime);

                return predictor;

            } catch (Exception e) {
                logger.error("Failed to load {} model", type.getName(), e);
                throw new RuntimeException("Model loading failed: " + type.getName(), e);

            } finally {
                loadLock.unlock();
            }
        }

        /**
         * 释放模型资源
         */
        public void releaseModel(Predictor<I, O> predictor, ZooModel<I, O> model) {
            loadLock.lock();
            try {
                long startTime = System.currentTimeMillis();
                logger.debug("Releasing {} model resources...", model != null ? model.getName() : "unknown");

                if (predictor != null) {
                    try {
                        predictor.close();
                    } catch (Exception e) {
                        logger.warn("Error closing predictor", e);
                    }
                }

                if (model != null) {
                    try {
                        model.close();
                        // 1. 获取模型背后的管理器
                        NDManager modelManager = model.getNDManager();
                        // 2. 正常关闭模型
                        // 3. 暴力关闭管理器（确保销毁所有关联的 Native 内存）
                        if (modelManager != null) {
                            modelManager.close();
                        }
//                        model.close();
                    } catch (Exception e) {
                        logger.warn("Error closing model", e);
                    }
                }

                long releaseTime = System.currentTimeMillis() - startTime;
                logger.debug("Released model resources in {}ms", releaseTime);

            } finally {
                loadLock.unlock();
                System.gc();
            }
        }
    }

    // 模型加载器缓存（每个类型一个加载器）
    private final Map<ModelType, ModelLoader<?, ?>> loaderCache = new ConcurrentHashMap<>();

    // eager模式下的模型缓存
    private final Map<ModelType, ZooModel<?, ?>> eagerModelCache = new ConcurrentHashMap<>();
    private final Map<ModelType, Predictor<?, ?>> eagerPredictorCache = new ConcurrentHashMap<>();

    // 临时文件管理器（解决磁盘爆炸问题）
    private final TempFileManager tempFileManager;
    private final ModelConfig modelConfig;

    public InstantModelManager(TempFileManager tempFileManager, ModelConfig modelConfig) {
        this.tempFileManager = tempFileManager;
        this.modelConfig = modelConfig;
        logger.info("InstantModelManager initialized with model load mode: {}", modelConfig.getModelLoadMode());
    }

    /**
     * 预加载模型（eager模式使用）
     */
    public <I, O> void preloadModel(ModelType type, Supplier<ZooModel<I, O>> modelSupplier) {
        if (!"eager".equals(modelConfig.getModelLoadMode())) {
            logger.debug("Skipping preload for model {} because model load mode is not eager", type.getName());
            return;
        }
        if (eagerModelCache.containsKey(type)) {
            logger.debug("Model {} already preloaded", type.getName());
            return;
        }
        logger.info("Preloading model {} in eager mode", type.getName());
        try {
            ZooModel<I, O> model = modelSupplier.get();
            ModelLoader<I, O> loader = getLoader(type);
            Predictor<I, O> predictor = loader.loadModel(type, () -> model);
            eagerModelCache.put(type, model);
            eagerPredictorCache.put(type, predictor);
            logger.info("Model {} preloaded successfully", type.getName());
        } catch (Exception e) {
            logger.error("Failed to preload model {}", type.getName(), e);
            // 清理可能已创建的资源
            eagerModelCache.remove(type);
            eagerPredictorCache.remove(type);
            throw new RuntimeException("Failed to preload model: " + type.getName(), e);
        }
    }

    /**
     * 获取模型加载器（线程安全）
     */
    @SuppressWarnings("unchecked")
    private <I, O> ModelLoader<I, O> getLoader(ModelType type) {
        return (ModelLoader<I, O>) loaderCache.computeIfAbsent(type,
            k -> new ModelLoader<I, O>());
    }

    /**
     * 创建模型会话（AutoCloseable确保释放）
     * 使用示例：
     * try (ModelSession<NDArray, float[]> session = modelManager.createModelSession(
     *         ModelType.PRIMARY_5CLASS,
     *         () -> loadModelFunction())) {
     *     Predictor<NDArray, float[]> predictor = session.getPredictor();
     *     // 执行推理...
     * } // 自动释放模型
     */
    public <I, O> ModelSession<I, O> createModelSession(ModelType type,
                                                       Supplier<ZooModel<I, O>> modelSupplier) {
        // 检查是否为eager模式且已预加载
        if ("eager".equals(modelConfig.getModelLoadMode()) && eagerModelCache.containsKey(type)) {
            logger.debug("Using eager-loaded model {} from cache", type.getName());
            // 从缓存获取模型和预测器
            @SuppressWarnings("unchecked")
            ZooModel<I, O> model = (ZooModel<I, O>) eagerModelCache.get(type);
            @SuppressWarnings("unchecked")
            Predictor<I, O> predictor = (Predictor<I, O>) eagerPredictorCache.get(type);

            // 返回一个空的close操作的会话（缓存模型不释放）
            return new ModelSession<I, O>() {
                private boolean closed = false;

                @Override
                public Predictor<I, O> getPredictor() {
                    if (closed) {
                        throw new IllegalStateException("ModelSession already closed");
                    }
                    return predictor;
                }

                @Override
                public void close() {
                    // eager模式下不释放模型，仅标记关闭
                    closed = true;
                    logger.debug("Model session closed (eager mode, model kept in cache)");
                }
            };
        }

        // instant模式或eager模式但未预加载（降级为即时加载）
        ModelLoader<I, O> loader = getLoader(type);
        // 使用final数组包装，以便在内部类中引用
        final ZooModel<I, O>[] modelHolder = new ZooModel[1];
        final Predictor<I, O>[] predictorHolder = new Predictor[1];

        try {
            // 加载模型
            modelHolder[0] = modelSupplier.get();
            predictorHolder[0] = loader.loadModel(type, () -> modelHolder[0]);

            // 返回会话对象
            return new ModelSession<I, O>() {
                private final ZooModel<I, O> m = modelHolder[0];
                private final Predictor<I, O> p = predictorHolder[0];
                private final ModelLoader<I, O> l = loader;
                private boolean closed = false;

                @Override
                public Predictor<I, O> getPredictor() {
                    if (closed) {
                        throw new IllegalStateException("ModelSession already closed");
                    }
                    return p;
                }

                @Override
                public void close() {
                    if (!closed) {
                        closed = true;
                        l.releaseModel(p, m);
                    }
                }
            };

        } catch (Exception e) {
            // 如果创建会话失败，清理已分配的资源
            if (predictorHolder[0] != null) {
                try { predictorHolder[0].close(); } catch (Exception ex) { /* ignore */ }
            }
            if (modelHolder[0] != null) {
                try { modelHolder[0].close(); } catch (Exception ex) { /* ignore */ }
            }
            throw e;
        }
    }

    /**
     * 执行模型推理（简化版，自动管理会话生命周期）
     * 使用示例：
     * Object result = modelManager.executeWithModel(
     *     ModelType.PRIMARY_5CLASS,
     *     () -> loadModelFunction(),
     *     predictor -> {
     *         // 执行推理
     *         return predictor.predict(input);
     *     }
     * );
     */
    public <I, O, R> R executeWithModel(ModelType type,
                                        Supplier<ZooModel<I, O>> modelSupplier,
                                        InferenceFunction<I, O, R> inferenceFunction) throws Exception {
        ModelSession<I, O> session = createModelSession(type, modelSupplier);
        try {
            return inferenceFunction.apply(session.getPredictor());
        } finally {
            try {
                session.close();
            } catch (Exception e) {
                logger.warn("Error closing model session", e);
            }
        }
    }

    /**
     * 模型会话接口（AutoCloseable确保释放）
     */
    public interface ModelSession<I, O> extends AutoCloseable {
        Predictor<I, O> getPredictor();
    }

    /**
     * 推理函数接口
     */
    @FunctionalInterface
    public interface InferenceFunction<I, O, R> {
        R apply(Predictor<I, O> predictor) throws Exception;
    }

    /**
     * 获取临时文件管理器
     */
    public TempFileManager getTempFileManager() {
        return tempFileManager;
    }

    /**
     * 清理缓存资源（应用关闭时调用）
     */
    @PreDestroy
    public void cleanup() {
        logger.info("Cleaning up eager model cache ({} models)", eagerModelCache.size());
        // 释放所有预测器
        for (Map.Entry<ModelType, Predictor<?, ?>> entry : eagerPredictorCache.entrySet()) {
            try {
                entry.getValue().close();
                logger.debug("Closed predictor for model {}", entry.getKey().getName());
            } catch (Exception e) {
                logger.warn("Error closing predictor for model {}", entry.getKey().getName(), e);
            }
        }
        // 释放所有模型
        for (Map.Entry<ModelType, ZooModel<?, ?>> entry : eagerModelCache.entrySet()) {
            try {
                entry.getValue().close();
                logger.debug("Closed model {}", entry.getKey().getName());
            } catch (Exception e) {
                logger.warn("Error closing model {}", entry.getKey().getName(), e);
            }
        }
        eagerPredictorCache.clear();
        eagerModelCache.clear();
        logger.info("Eager model cache cleaned up");
    }

    /**
     * 统计信息（可选）
     */
    public void printStatistics() {
        logger.info("InstantModelManager statistics: {} model loaders cached, {} eager models cached",
            loaderCache.size(), eagerModelCache.size());
    }
}