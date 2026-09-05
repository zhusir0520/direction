# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

这是一个包含两个主要项目的 NSFW (Not Safe For Work) 图片检测系统代码库：

1. **nsfw-server**: 基于 Spring Boot 2.6 + DJL 0.29 + ONNX Runtime 的 NSFW 图片检测 REST API 服务（Java 8/9）
2. **android**: Android 客户端应用，使用 MediaProjection API 或无障碍服务（`AccessibilityService.takeScreenshot`）进行屏幕截图、TensorFlow Lite 进行本地 NSFW 分类，可调用 nsfw-server 进行兜底检测，并通过 Shizuku 强制停止前台应用

每个项目的详细文档位于各自的目录中：
- [nsfw-server/CLAUDE.md](nsfw-server/CLAUDE.md) — 配置参数详解、模型管理、架构细节
- [android/CLAUDE.md](android/CLAUDE.md) — Android 组件架构、文件清单、故障排除

## 常用命令

### 后端 (nsfw-server)

```bash
# 构建
cd nsfw-server && mvn clean package -DskipTests

# 运行
mvn spring-boot:run
# 或
java -jar target/nsfw-server-0.0.1-SNAPSHOT.jar

# 运行测试
mvn test

# 调试日志模式运行
mvn spring-boot:run -Dspring-boot.run.arguments=--logging.level.org.example.nsfwserver=DEBUG
```

### Android 客户端

```bash
# 进入 android 目录
cd android

# ⭐ 一键构建 + MD5命名 + 安装（推荐AI使用）
./build-and-name.sh --install --cleanup-old

# 仅构建 + MD5命名（不安装）
./build-and-name.sh

# 运行单元测试
./gradlew test

# 运行特定测试
./gradlew test --tests "*DetectionResultTest"
```

### 设备调试

```bash
# 查看日志（过滤相关标签）
adb logcat | grep -E "(DirectionApplication|NsfwMonitorService|NsfwAccessibilityService|DetectionProcessor)"

# 清除应用数据
adb shell pm clear com.example.direction

# 停止应用
adb shell am force-stop com.example.direction
```

### 模型文件 (Git LFS)

```bash
# 克隆后拉取 LFS 文件
git lfs pull

# 查看 LFS 跟踪状态
git lfs ls-files
```

模型文件位于 `nsfw-server/src/main/resources/models/` 和 `android/app/src/main/assets/models/`。

### 测试后端 API

```bash
curl http://localhost:8082/api/nsfw/health
curl -X POST -F "image=@test.jpg" http://localhost:8082/api/nsfw/detect
```

## 整体系统架构

### 数据流

```
┌──────────────────┐     ┌─────────────────┐     ┌──────────────────┐
│  Android App     │────>│  nsfw-server     │────>│ 本地模型文件      │
│  (MediaProjection│     │  (Spring Boot)   │     │  (ONNX/YOLOv8)   │
│  截图→TFLite分类) │     │  REST API:8082   │     └──────────────────┘
└──────┬───────────┘     └────────┬────────┘
       │                          │
       │ 本地分类器                │ 单模型 + YOLOv8 物体检测兜底
       │ (saved_model.tflite)     │ (5class ONNX / FalconsAI)
       │                          │
       ▼                          │
┌──────────────────┐              │
│ 检测结果处理       │◄────────────┘
│ 通知/震动/悬浮窗   │   兜底检测结果合并
│ Shizuku强制停止    │   (逻辑或: Android || Backend)
│ 历史记录存储       │
└──────────────────┘
```

### 项目间集成

- **兜底检测机制**: 当 Android TFLite 分类结果为 SFW 时，自动调用后端进行二次验证
- **结果合并**: `finalIsNsfw = androidResult.isNSFW || backendResult.backendIsNsfw`
- **后端不可用**: 自动降级为仅使用 Android 检测结果
- **Shizuku 强制停止**: 检测到 NSFW 时，通过 Shizuku 特权服务执行 `am force-stop` 强制停止前台应用（详见 [android/CLAUDE.md](android/CLAUDE.md)）
- **无障碍服务（一次授权持续监控）**: 通过 `AccessibilityService.takeScreenshot()`（API 30+）替代 MediaProjection 单次令牌，系统绑定后开机自启、进程被杀自动重启，无需重复授权（详见 [android/CLAUDE.md](android/CLAUDE.md)）

### 后端 URL 配置

| 环境 | URL |
|------|-----|
| 模拟器 | `http://10.0.2.2:8082/api/nsfw/detect` |
| 真实设备 | `http://<计算机局域网IP>:8082/api/nsfw/detect` |

在 Android 设置页面中可修改后端 URL 和启用/禁用状态。

## 调试工具

- **后端调试 UI**: `nsfw-server/debug-ui.html` — 用于本地调试 NSFW 检测接口的 HTML 页面，支持图片上传和结果可视化
- **Android 日志**: `adb logcat | grep -E "(DirectionApplication|NsfwMonitorService|NsfwAccessibilityService|DetectionProcessor)"`

## 本地配置

项目根目录下的 `local.claude.md` 和 `android/local.claude.md` 包含本地开发环境配置（ADB 路径、SDK 位置、JDK 路径等）。这些文件已加入 `.gitignore`，不会被提交。