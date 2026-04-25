# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

这是一个包含两个主要项目的 NSFW (Not Safe For Work) 图片检测系统代码库：

1. **nsfw-server**: 基于 Spring Boot 2.6 + DJL 0.29 + ONNX Runtime 的 NSFW 图片检测 REST API 服务（Java 8/9）
2. **android**: Android 客户端应用（包名 `com.example.direction`），使用 MediaProjection API 进行屏幕截图、TensorFlow Lite 进行本地 NSFW 分类，并可调用 nsfw-server 进行兜底检测

每个项目的详细文档位于各自的目录中：
- [nsfw-server/CLAUDE.md](nsfw-server/CLAUDE.md) — 配置参数详解、模型管理、架构细节
- [android/CLAUDE.md](android/CLAUDE.md) — Android 组件架构、文件清单、故障排除

## 常用命令

### nsfw-server (Java 后端)

```bash
# 进入后端目录
cd nsfw-server

# 构建
mvn clean package -DskipTests

# 运行
mvn spring-boot:run

# 或使用 JAR 运行
java -jar target/nsfw-server-0.0.1-SNAPSHOT.jar

# 运行测试
mvn test
```

### android (Android 客户端)

```bash
# 进入 android 目录
cd android

# 构建项目
./gradlew build

# 安装调试版本到连接的设备
./gradlew installDebug

# 生成 APK
./gradlew assembleDebug
./gradlew assembleRelease

# 运行单元测试
./gradlew test

# 运行特定测试类
./gradlew test --tests "*DetectionResultTest"

# 运行 lint 检查
./gradlew lint

# 清理构建
./gradlew clean
```

### 设备调试

```bash
# 检查连接的设备
adb devices

# 查看应用日志（过滤相关标签）
adb logcat | grep -E "(DirectionApplication|NsfwMonitorService)"

# 清除应用数据
adb shell pm clear com.example.direction

# 启动主 Activity
adb shell am start -n com.example.direction/.MainActivity

# 停止应用
adb shell am force-stop com.example.direction
```

### 测试后端 API

```bash
# 健康检查
curl http://localhost:8082/api/nsfw/health

# NSFW 检测
curl -X POST -F "image=@test.jpg" http://localhost:8082/api/nsfw/detect
```

## 模型文件管理 (Git LFS)

模型文件通过 Git LFS 管理。当前跟踪的模型文件在 `nsfw-server/src/main/resources/models/` 和 `android/app/src/main/assets/models/` 目录下：

```bash
# 克隆后拉取 LFS 文件
git lfs pull

# 查看 LFS 跟踪状态
git lfs ls-files
```

模型配置文件位于 `nsfw-server/src/main/resources/application.properties`。

## 调试工具

- **后端调试 UI**: `nsfw-server/debug-ui.html` — 用于本地调试 NSFW 检测接口的 HTML 页面，支持图片上传和结果可视化
- **Android 日志**: `adb logcat | grep -E "(DirectionApplication|NsfwMonitorService)"`

## 整体系统架构

### 数据流

```
┌──────────────────┐     ┌─────────────────┐     ┌──────────────────┐
│  Android App     │────>│  nsfw-server     │────>│ 本地模型文件      │
│  (MediaProjection│     │  (Spring Boot)   │     │  (ONNX/YOLOv8)   │
│  截图→TFLite分类) │     │  REST API:8082   │     └──────────────────┘
└──────┬───────────┘     └────────┬────────┘
       │                          │
       │ 本地分类器                │ 双模型架构 + YOLO 物体检测
       │ (saved_model.tflite)     │ (5class ONNX + FalconsAI)
       │                          │
       ▼                          │
┌──────────────────┐              │
│ 检测结果处理       │◄────────────┘
│ 通知/震动/悬浮窗   │   兜底检测结果合并
│ 历史记录存储       │   (逻辑或: Android || Backend)
└──────────────────┘
```

### Android 内部架构

UI 层 (Jetpack Compose) → 业务逻辑层 → 数据层

**关键组件**:
- **MainActivity**: Clash for Android 风格主界面，管理 MediaProjection 权限和悬浮窗权限
- **NsfwMonitorService**: 前台服务，管理 MediaProjection/VirtualDisplay/ImageReader，周期性截图检测
- **NSFWClassifier**: TFLite 分类器，5 类别 (Drawing/Hentai/Neutral/Porn/Sexy)，默认阈值 95%
- **BackendNsfwDetector**: HTTP 客户端，当本地检测为 SFW 时调用后端兜底验证
- **FloatingWindowManager**: 悬浮窗管理，支持侧边停靠和位置持久化
- **ScreenshotManager**: 截图文件管理，三级清理策略（时间30天/数量1000/大小500MB）
- **DetectionRepository**: 检测历史存储 (SharedPreferences + Gson)
- **SettingsRepository**: 设置管理 (DataStore Preferences)

### nsfw-server 内部架构

控制器层 → 服务层 → 模型管理层

**关键组件**:
- **NsfwController**: REST API (detect/health/info)
- **NsfwDetectionService**: 业务逻辑，双模型架构 + YOLOv8 物体检测兜底
- **InstantModelManager**: 支持 eager（预加载）和 instant（按需加载）两种模式
- **ModelConfig**: 通过 `nsfw.model.*` 配置模型类型、归一化参数、阈值等
- **PredictionParser**: 模型输出解析器（FiveClassParser / TwoClassParser）
- **ImagePreprocessor**: 图片预处理 Pipeline (Resize→ToTensor→Normalize)

## 项目间集成

### Android 与后端通信

- **兜底检测机制**: 当 Android TFLite 分类结果为 SFW 时，自动调用后端进行二次验证
- **结果合并**: `finalIsNsfw = androidResult.isNSFW || backendResult.backendIsNsfw`
- **后端不可用**: 自动降级为仅使用 Android 检测结果

### 后端 URL 配置

| 环境 | URL |
|------|-----|
| 模拟器 | `http://10.0.2.2:8082/api/nsfw/detect` |
| 真实设备 | `http://<计算机局域网IP>:8082/api/nsfw/detect` |

在 Android 设置页面中可修改后端 URL 和启用/禁用状态。

## 本地配置

项目根目录下的 `local.claude.md` 和 `android/local.claude.md` 包含本地开发环境配置（ADB 路径、SDK 位置、JDK 路径等）。这些文件已加入 `.gitignore`，不会被提交。