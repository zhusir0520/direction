# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

**nsfw-server**: 基于 Spring Boot 和 DJL (Deep Java Library) 的 NSFW (Not Safe For Work) 图片检测 REST API 服务

## 常用命令

### nsfw-server (Java 后端)

#### 测试 API
```bash
# 手动测试接口
curl http://localhost:8082/api/nsfw/health
curl http://localhost:8082/api/nsfw/info
curl -X POST -F "image=@test.jpg" http://localhost:8082/api/nsfw/detect
```

#### 模型管理
```bash
# 转换 YOLOv8 模型（需要 Python 环境）
#（本地已提供oonx文件，如果资源是pt文件才需要进行转换,因为djl对oonx文件兼容性更好)
cd nsfw-server/scripts
python convert_yolov8s_to_onnx.py --verify --compare
```

## 架构概述

### nsfw-server 架构

#### 核心组件
1. **控制器层** (`controller/NsfwController.java`): REST API 接口
   - `POST /api/nsfw/detect`: NSFW 图片检测
   - `GET /api/nsfw/health`: 健康检查
   - `GET /api/nsfw/info`: 服务信息

2. **服务层** (`service/NsfwDetectionService.java`): 业务逻辑
   - 单模型检测系统：根据配置使用一个模型（5class、falconsai或自定义模型）
   - YOLOv8 物体检测兜底机制，检测区域使用当前配置的主模型进行二次检测
   - 即时模型加载模式（支持按需加载）

3. **模型管理** (`manager/` 目录):
   - `InstantModelManager`: 即时模型加载器
   - `TempFileManager`: 临时文件管理

4. **解析器** (`service/parser/` 目录):
   - `FiveClassParser`: 五分类模型解析器
   - `TwoClassParser`: 二分类模型解析器
   - `PredictionParserFactory`: 解析器工厂

5. **工具类** (`util/` 目录):
   - `ImagePreprocessor`: 图片预处理
   - `LabelLoader`: 标签加载器

#### 模型配置
- **主模型**: 五分类 NSFW 检测模型 (`models/model.onnx`)
- **次模型**: FalconsAI 二分类模型 (`models/falconsai.onnx`)
- **自定义模型**: 支持任意ONNX格式模型，通过 `nsfw.model.type=my_custom_model` 配置
- **YOLO 模型**: YOLOv8n/s 物体检测模型 (`models/yolov8n.onnx` 或 `models/yolov8s.onnx`)
- **配置文件**: `src/main/resources/application.properties`

#### 关键特性
- 支持多种模型类型（5class, falconsai, original）和**自定义模型**（如my_custom_model）
- 可配置的归一化参数（ImageNet, ViT, 自定义）
- YOLOv8 物体检测兜底机制，检测区域使用**当前配置的主模型**进行二次检测
- 即时模型加载模式（减少内存占用，使用完一个模型即清除session和调用System.gc()）
- 完整的错误处理和输入验证
- 向后兼容：现有配置无需修改，未知模型类型自动使用FiveClassParser

## 开发注意事项

### 模型文件管理
1. **必需模型文件**:
   - `nsfw-server/src/main/resources/models/model.onnx` (五分类 NSFW 模型)
   - `nsfw-server/src/main/resources/models/falconsai.onnx` (FalconsAI 模型)
   - `nsfw-server/src/main/resources/models/yolov8n.onnx` 或 `yolov8s.onnx` (YOLO 模型)

2. **模型来源**:
   - NSFW 模型: Hugging Face `acaciabengo/nsfw_image_detection`
   - YOLO 模型: yolov8官方模型使用 scripts/convert_yolov8s_to_onnx.py 转换

### 后端配置参数详解

所有配置参数定义在 `nsfw-server/src/main/resources/application.properties` 文件中。

#### 1. 服务器配置
| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `server.port` | 8082 | 服务监听端口 |
| `spring.servlet.multipart.max-file-size` | 10MB | 上传文件最大大小 |
| `spring.servlet.multipart.max-request-size` | 10MB | 请求最大大小 |

#### 2. 模型基础配置（旧配置，向后兼容）
| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `nsfw.model.path` | `classpath:models/falconsai.onnx` | 模型文件路径（支持 `classpath:` 前缀） |
| `nsfw.model.input-width` | 224 | 模型输入图像宽度 |
| `nsfw.model.input-height` | 224 | 模型输入图像高度 |
| `nsfw.model.threshold` | 0.5 | NSFW判定阈值 |

#### 3. 扩展模型配置（新配置）
| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `nsfw.model.type` | `falconsai` | 模型类型：`5class`（五分类）、`2class`/`falconsai`（二分类）、`original`（同5class）、`my_custom_model`（自定义模型） |
| `nsfw.model.labels-path` | (自动选择) | 标签文件路径，未指定时根据 `type` 自动选择：<br>- `5class`/`original`: `classpath:models/labels.txt`<br>- `2class`/`falconsai`: `classpath:models/labels.json`<br>- 自定义模型: 尝试 `classpath:models/labels_<type>.txt` |
| `nsfw.model.normalization.type` | `custom` | 归一化类型：`vit`（ViT标准）、`imagenet`（ImageNet标准）、`custom`（自定义） |
| `nsfw.model.mean` | `0.0,0.0,0.0` | 自定义归一化均值（RGB三个通道，`normalization.type=custom` 时生效） |
| `nsfw.model.std` | `1.0,1.0,1.0` | 自定义归一化标准差（RGB三个通道，`normalization.type=custom` 时生效） |

**模型类型说明**：
- **`5class`/`original`**: 五分类 NSFW 检测模型，输出 5 个类别：`drawing`（绘画）、`hentai`（日本成人动漫）、`neutral`（中性内容）、`porn`（色情内容）、`sexy`（性感内容）
- **`2class`/`falconsai`**: 二分类模型，直接输出 SFW/NSFW 二分类结果
- **自定义模型**（如 `my_custom_model`）：支持用户自定义 ONNX 模型，系统会自动尝试加载 `classpath:models/<type>.onnx` 和 `classpath:models/labels_<type>.txt` 文件

**归一化配置说明**：
- **`vit`**: ViT 标准归一化，均值 `[0.5, 0.5, 0.5]`，标准差 `[0.5, 0.5, 0.5]`
- **`imagenet`**: ImageNet 标准归一化，均值 `[0.485, 0.456, 0.406]`，标准差 `[0.229, 0.224, 0.225]`
- **`custom`**: 自定义归一化，使用 `nsfw.model.mean` 和 `nsfw.model.std` 指定的参数

#### 4. YOLO 配置
| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `nsfw.model.yolo-enabled` | `true` | 启用/禁用 YOLO 兜底检测机制 |
| `nsfw.model.yolo-confidence-threshold` | 0.25 | YOLO 检测置信度阈值（0-1） |
| `nsfw.model.yolo-iou-threshold` | 0.7 | NMS（非极大值抑制）交并比阈值（0-1） |
| `nsfw.model.yolo-model-path` | `classpath:models/yolov8s.onnx` | YOLO 模型路径，支持 `yolov8n.onnx`（小模型）或 `yolov8s.onnx`（标准模型） |

**YOLO集成说明**：
- YOLO检测到的区域（并集框）会使用**当前配置的主模型**进行二次检测，不再硬编码使用特定模型
- 如果并集框检测为SFW，系统会循环检测YOLO识别到的所有`person`框（classId=0），直到找到NSFW内容或所有框检测完毕
- 最终结果为主模型检测结果与YOLO检测结果的逻辑或：`finalIsNsfw = 主模型结果 || YOLO检测结果`

#### 5. 模型加载模式配置
| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `nsfw.model.model-load-mode` | `eager` | 模型加载模式：<br>- `instant`（懒加载）：每次推理时加载模型，推理完成后立即释放，减少内存占用但每次请求有加载开销<br>- `eager`（贪婪加载）：应用启动时预加载模型到缓存，首次请求更快但占用更多内存 |

#### 6. 日志配置
| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `logging.level.org.example.nsfwserver` | `DEBUG` | 应用日志级别 |
| `logging.level.ai.djl` | `WARN` | DJL 库日志级别 |
| `logging.file.name` | `nsfw-server.log` | 日志文件名称 |

#### 配置示例

**FalconsAI 模型完整配置示例**：
```properties
nsfw.model.type=falconsai
nsfw.model.path=classpath:models/falconsai.onnx
nsfw.model.labels-path=classpath:models/labels_falconsai.txt
nsfw.model.normalization.type=custom
nsfw.model.mean=0.0,0.0,0.0
nsfw.model.std=1.0,1.0,1.0
nsfw.model.threshold=0.5
nsfw.model.model-load-mode=eager
nsfw.model.yolo-enabled=true
```

**五分类模型配置示例**：
```properties
nsfw.model.type=5class
nsfw.model.path=classpath:models/model.onnx
nsfw.model.labels-path=classpath:models/labels.txt
nsfw.model.normalization.type=vit
nsfw.model.threshold=0.9
```

**自定义模型配置示例**：
```properties
# 使用自定义模型
nsfw.model.type=my_custom_model
# 可选：显式指定路径，否则尝试加载 classpath:models/my_custom_model.onnx
# nsfw.model.path=classpath:models/my_custom_model.onnx
# 可选：显式指定标签文件，否则尝试 classpath:models/labels_my_custom_model.txt
# nsfw.model.labels-path=classpath:models/labels_my_custom_model.txt
# 根据自定义模型的训练配置设置归一化参数
nsfw.model.normalization.type=custom
nsfw.model.mean=0.0,0.0,0.0
nsfw.model.std=1.0,1.0,1.0
nsfw.model.threshold=0.75
nsfw.model.model-load-mode=eager
nsfw.model.yolo-enabled=true
```

#### 配置优先级说明
1. **模型路径优先级**：如果显式配置了 `nsfw.model.path`，则使用该路径；否则根据 `nsfw.model.type` 选择默认模型
2. **标签路径优先级**：如果显式配置了 `nsfw.model.labels-path`，则使用该路径；否则根据 `nsfw.model.type` 选择默认标签文件
3. **归一化参数**：`nsfw.model.mean` 和 `nsfw.model.std` 仅在 `nsfw.model.normalization.type=custom` 时生效

#### 向后兼容性说明
- **现有配置兼容**：现有配置（`type=5class` 或 `type=falconsai`）继续正常工作，无需修改
- **自定义模型支持**：用户可以通过设置 `type=my_custom_model` 使用自定义ONNX模型，系统会自动尝试加载对应文件
- **路径自动选择**：如果用户不指定模型路径（`nsfw.model.path`），系统会根据 `type` 选择默认路径：
  - `5class`/`original`: `classpath:models/model.onnx`
  - `2class`/`falconsai`: `classpath:models/falconsai.onnx`
  - 自定义类型（如 `my_custom_model`）: `classpath:models/my_custom_model.onnx`
- **标签文件兼容**：类似地，标签文件路径也会根据类型自动选择
- **解析器兼容**：未知模型类型默认使用 `FiveClassParser` 作为解析器，确保基本功能可用

#### 模型文件自动选择逻辑
`ModelConfig.getEffectiveModelPath()` 方法实现以下逻辑：
```java
public String getEffectiveModelPath() {
    if (path != null && !path.trim().isEmpty()) {
        return path;
    }
    // 根据模型类型选择默认模型，支持自定义模型
    switch (type) {
        case "5class":
        case "original":
            return "classpath:models/model.onnx";
        case "2class":
        case "falconsai":
            return "classpath:models/falconsai.onnx";
        default:
            // 未知模型类型：尝试通用路径，如果文件不存在会由加载器处理
            String defaultPath = "classpath:models/" + type + ".onnx";
            logger.info("Unknown model type '{}', trying default path: {}", type, defaultPath);
            return defaultPath;
    }
}
```

`ModelConfig.getEffectiveLabelsPath()` 方法实现以下逻辑：
```java
public String getEffectiveLabelsPath() {
    if (labelsPath != null && !labelsPath.trim().isEmpty()) {
        return labelsPath;
    }
    // 根据模型类型选择默认标签文件，支持自定义模型
    switch (type) {
        case "5class":
        case "original":
            return "classpath:models/labels.txt";
        case "2class":
        case "falconsai":
            // 优先尝试 labels.json，如果不存在则使用 labels_falconsai.txt
            return "classpath:models/labels.json";
        default:
            // 未知模型类型：尝试通用标签文件，如果文件不存在会由加载器处理
            String defaultPath = "classpath:models/labels_" + type + ".txt";
            logger.info("Unknown model type '{}', trying default labels path: {}", type, defaultPath);
            return defaultPath;
    }
}
```

### 模型加载架构详细说明

#### 核心组件
1. **InstantModelManager** (`manager/InstantModelManager.java`): 模型加载管理器
   - 支持两种加载模式: `instant` (懒加载) 和 `eager` (贪婪加载)
   - 提供线程安全的模型加载和释放机制
   - 支持模型会话 (`ModelSession`) 模式，确保资源正确释放

2. **ModelConfig** (`config/ModelConfig.java`): 配置管理
   - 通过 `@ConfigurationProperties(prefix = "nsfw.model")` 绑定 application.properties 配置
   - 提供默认值和配置验证
   - 支持动态模型路径和标签文件选择

#### 加载模式实现
1. **Eager 模式 (贪婪加载)**:
   ```java
   // 应用启动时预加载模型
   @PostConstruct
   public void init() {
       if ("eager".equals(modelConfig.getModelLoadMode())) {
           modelManager.preloadModel(InstantModelManager.ModelType.SECONDARY_FALCONSAI, this::loadSecondaryModel);
       }
   }
   ```
   - 启动时加载模型到缓存 (`eagerModelCache` 和 `eagerPredictorCache`)
   - 后续请求直接从缓存获取预测器，无加载开销
   - 应用关闭时释放所有缓存模型

2. **Instant 模式 (懒加载)**:
   ```java
   // 每次推理时动态加载和释放
   public <I, O, R> R executeWithModel(ModelType type, Supplier<ZooModel<I, O>> modelSupplier, InferenceFunction<I, O, R> inferenceFunction) {
       ModelSession<I, O> session = createModelSession(type, modelSupplier);
       try {
           return inferenceFunction.apply(session.getPredictor());
       } finally {
           session.close(); // 自动释放模型资源
       }
   }
   ```
   - 每次请求创建新的模型会话
   - 推理完成后立即关闭会话，释放模型和 NDManager
   - 调用 `System.gc()` 确保 Native 内存回收

#### 模型会话生命周期
1. **创建会话**: 根据配置模式创建相应会话
   - Eager 模式: 返回缓存模型的会话，close() 仅标记关闭，不释放模型
   - Instant 模式: 加载新模型，close() 释放所有资源

2. **执行推理**: 通过 `ModelSession.getPredictor()` 获取预测器并执行

3. **资源释放**: 通过 try-with-resources 或 finally 块确保 `session.close()` 被调用

#### 临时文件管理
- **TempFileManager**: 管理模型文件从 classpath 到临时文件的幂等拷贝
- 避免重复拷贝相同资源
- 应用重启时清理临时文件

#### 内存管理
- Instant 模式每次推理后完全释放模型，适合内存受限环境
- Eager 模式保持模型常驻内存，适合频繁请求场景
- YOLO 模型可独立配置加载模式

### 调试工具
- **后端调试 UI**: `nsfw-server/debug-ui.html` - 本地调试 NSFW 检测接口的 HTML 页面
- **详细日志**: 设置 `logging.level.org.example.nsfwserver=DEBUG`

## 项目间集成

### Android 与后端通信
1. **后端 URL 配置**: Android 应用需要配置 nsfw-server 的 URL
   - 模拟器: `http://10.0.2.2:8082/api/nsfw/detect`
   - 真实设备: 计算机局域网 IP，如 `http://192.168.x.x:8082/api/nsfw/detect`

2. **兜底检测机制**: 当 Android 本地 TFLite 分类器置信度不达标时，调用后端服务进行二次验证

### 数据流
1. Android 应用捕获屏幕截图
2. 本地 TFLite 模型进行初步分类
3. 如果置信度不足，发送图片到 nsfw-server
4. nsfw-server 使用配置的主模型 + YOLO 进行综合检测
5. 返回结果给 Android 应用，触发相应通知/警告

## 故障排除

### 常见问题
1. **模型文件找不到**: 确保模型文件放置在正确的 `src/main/resources/models/` 目录
2. **内存不足**: 增加 JVM 堆内存 `-Xmx4g`（falconsai+yolo的内存要求为2g）
3. **端口冲突**: 修改 `server.port` 配置（默认 8082）
4. **YOLO 模型转换失败**: 检查 Python 环境和依赖安装

### 日志查看
```bash
# 启用详细日志
mvn spring-boot:run -Dspring-boot.run.arguments=--logging.level.org.example.nsfwserver=DEBUG

# 查看日志文件
tail -f nsfw-server.log
```

## 扩展开发

### 添加新模型
1. 将 ONNX 模型文件放入 `models/` 目录（如 `my_custom_model.onnx`）
2. 创建对应的标签文件（如 `labels_my_custom_model.txt`）
3. 修改 `application.properties` 中的模型配置：
   ```properties
   nsfw.model.type=my_custom_model
   # 可选显式指定路径和标签文件
   # nsfw.model.path=classpath:models/my_custom_model.onnx
   # nsfw.model.labels-path=classpath:models/labels_my_custom_model.txt
   ```
4. 自定义模型默认使用 `FiveClassParser` 解析器，如需特殊解析逻辑可创建对应的 `PredictionParser` 实现

### API 扩展
1. **批量处理**: 扩展 `/detect` 接口支持多图上传
2. **统计接口**: 添加检测统计和历史查询
3. **模型管理**: 支持动态加载/切换不同模型