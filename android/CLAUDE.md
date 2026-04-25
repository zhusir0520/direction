# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

"Direction" 是一个 Android 应用程序 (包名: `com.example.direction`)，24/7 自动检测屏幕上的 NSFW (Not Safe For Work) 内容。该应用使用 MediaProjection API 进行屏幕截图，TensorFlow Lite 进行内容分类，以及前台服务进行周期性检测调度。

## 常用命令

项目使用 Gradle Wrapper。常用命令：

### 构建和运行
```bash
# 进入 android 目录
cd android

# 构建项目
./gradlew build

# 安装调试版本到连接的设备
./gradlew installDebug

# 生成调试/发布 APK
./gradlew assembleDebug
./gradlew assembleRelease

# 清理构建
./gradlew clean

# ⭐ 一键构建+MD5命名+安装（推荐AI使用）
./build-and-name.sh --install --cleanup-old
# 仅构建+MD5命名（不安装）
./build-and-name.sh
```

### 测试
```bash
# 运行所有单元测试
./gradlew test

# 运行特定单元测试类
./gradlew test --tests "*DetectionResultTest"

# 运行所有仪器测试（需要设备）
./gradlew connectedAndroidTest

# 运行特定仪器测试类
./gradlew connectedAndroidTest --tests "*TimeWindowManagerTest"
```

### 代码质量
```bash
# 运行 lint 检查
./gradlew lint

# 查看依赖关系
./gradlew dependencies
```

### 设备调试
```bash
# 检查连接的设备
adb devices

# 清除应用数据
adb shell pm clear com.example.direction

# 查看应用日志（过滤相关标签）
adb logcat -d | grep -E "(DirectionApplication|NsfwMonitorService)"

# 查看完整日志
adb logcat -d > log.txt
```

> **个人环境配置**: ADB路径、SDK位置等个人开发环境设置位于 `local.claude.md` 中。

## 高层架构

### 核心架构模式
应用遵循分层架构，关注点分离清晰：

```
UI层 (Compose) → 业务逻辑层 → 数据层
     ↓                    ↓               ↓
  活动界面        管理器/工作者        存储库
  可组合组件      服务                DataStore/SharedPreferences
                 检测器               ScreenshotManager
```

### 关键组件交互

#### 1. 主界面 (Clash for Android 样式)
- **`MainActivity.kt`** 采用 Clash for Android 风格 UI，包含三个主要功能卡片和底部选项列表
  - **录屏按钮卡片**: 切换 MediaProjection 权限状态（绿色表示已授权，灰色表示未授权）
  - **检测卡片**: 启动图片选择器进行实时 NSFW 检测
  - **记录卡片**: 跳转到历史记录页面
  - **底部选项**: 日志、设置、关于（分别对应日志对话框、设置页面、关于信息）
- 权限管理: 直接处理 MediaProjection 权限请求，结果传递给 `NsfwMonitorService` 启动前台服务
- 悬浮窗权限: 如果悬浮窗功能启用，在录屏权限授予后自动请求悬浮窗权限
- 广播接收: 监听 `NsfwMonitorService` 发送的 MediaProjection 停止和 NSFW 检测广播

#### 2. 时间窗口管理 (24/7 模式)
- **`TimeWindowManager.kt`** 已修改为 24/7 运行；`isInDetectionWindow()` 始终返回 `true`
- 关键方法: `shouldRequestPermission()` 检查是否需要请求权限（简化版本）
- 应用现在持续运行，不再限制在 22:00-02:00 时间窗口内

#### 3. 前台服务检测系统
- **`NsfwMonitorService.kt`** 简化的前台服务，管理 MediaProjection、VirtualDisplay 和 ImageReader
- 关键功能：周期性屏幕截图、NSFW 内容检测、结果存储和通知发送
- 服务使用 `Handler` 和 `Runnable` 进行周期性检测；检测间隔可以从设置动态调整
- 使用 `MediaProjection` API 捕获屏幕，`ImageReader` 获取图像数据
- **进程恢复**: 使用 `START_STICKY`，进程被系统杀死后会自动重启服务（但 MediaProjection 需要重新授权）
- **NSFW 处理**: 检测到 NSFW 时不释放录屏也不停止服务，而是直接打开 `DetectionResultActivity` 详情页显示结果，服务继续运行

#### 4. 内容分类流水线
- **`NSFWClassifier.kt`** 基于 TensorFlow Lite 的 NSFW 内容分类器（使用 GantMan 的 saved_model.tflite）
- 接受 Bitmap 输入，输出检测结果，包含5个类别的置信度分数：Drawing, Hentai, Neutral, Porn, Sexy
- 模型文件: `app/src/main/assets/models/saved_model.tflite`；标签文件: `labels.txt`
- **阈值配置**: 默认 NSFW 阈值为 95% (Hentai + Porn + Sexy 概率之和 > 95%)，可通过设置调整
- **阈值持久化**: 检测结果中包含阈值信息，在检测详情页面显示（新记录显示实际阈值，旧记录显示默认值）
- **向后兼容**: 通过 `effectiveThreshold` 属性处理旧记录（threshold < 0.01f 时返回默认值 0.95）

#### 5. 后端兜底检测机制
- **`BackendNsfwDetector.kt`** 后端 NSFW 检测器，当本地分类器置信度不达标时调用后端服务进行兜底检测
- **兜底条件**: 当 Android 检测结果为 SFW（安全）时触发后端检测进行二次验证（最新修改）
- **后端架构**: 服务使用双模型架构（5分类ONNX模型 + FalconsAI模型）和YOLOv8s物体检测，YOLOv8s检测不到物体的问题已修复，精确度符合预期
- **HTTP 通信**: 使用 multipart/form-data 上传图片到后端服务，解析 JSON 响应
- **结果合并**: 最终 NSFW 判断采用逻辑或：`finalIsNsfw = androidResult.isNSFW || backendResult.backendIsNsfw == true`
- **设置配置**: 通过 `SettingsRepository` 管理后端启用状态、URL（默认 `http://10.0.2.2:8082/api/nsfw/detect`）、阈值容差（默认 5%）
- **数据扩展**: `DetectionResult` 模型包含后端检测字段（`backendIsNsfw`, `backendConfidence`, `backendThreshold`, `backendRawScores`）
- **向后兼容**: 后端不可用时使用 Android 检测结果，不影响核心功能

#### 6. 数据存储层
- **`SettingsRepository.kt`** 使用 DataStore Preferences 管理应用设置（检测间隔、通知设置、悬浮窗设置、后端设置等）
- **`DetectionRepository.kt`** 使用 SharedPreferences 和 Gson 序列化存储检测历史，与 **`ScreenshotManager`** 集成进行截图文件管理
- 注意：浮点值存储为整数（乘以100）

#### 7. 截图管理
- **`ScreenshotManager.kt`** 负责保存、加载、删除和管理截图文件的存储空间
- 提供三级清理策略：时间（默认30天）、数量（默认1000）、大小（默认500MB）
- 截图保存为 JPEG，可配置质量（默认70%）和最大宽度（默认800px）
- 自动回收 Bitmap 资源以防止内存泄漏

#### 8. 悬浮窗管理
- **`FloatingWindowManager.kt`** 创建、显示、隐藏悬浮窗并显示 NSFW 警告
- 需要 `SYSTEM_ALERT_WINDOW` 权限（与 MediaProjection 权限一起请求）
- **侧边停靠功能**: 悬浮窗可拖拽到屏幕边缘并部分可见（最少露出20像素），允许部分隐藏但保持可访问性
- **智能位置调整**: 自动检测状态栏高度，避免悬浮窗被状态栏遮挡；默认位置 Y=300
- **位置持久化**: 窗口位置通过 `SettingsRepository` 在应用重启时持久化；拖拽后自动保存新位置
- **NSFW 警告**: 检测到 NSFW 内容时显示警告文本"想想你该干什么！" 5 秒
- **兼容性处理**: 支持不同 Android 版本（API 26+ 使用 `TYPE_APPLICATION_OVERLAY`，旧版本使用 `TYPE_PHONE`）
- **广播通信**: 通过 `LocalBroadcastManager` 接收 `NsfwMonitorService` 发送的 NSFW 检测广播

#### 9. 设置管理系统
- **`SettingsActivity.kt`** 专门的设置页面，包含以下配置部分：
  - **时间窗口设置**: 启用/禁用时间窗口，设置开始和结束时间（当前为24/7模式，但界面保留）
  - **通知设置**: 启用/禁用通知，请求通知权限（Android 13+）
  - **悬浮窗设置**: 启用/禁用悬浮窗，显示NSFW警告开关
  - **后端设置**: 启用/禁用后端兜底检测，配置后端URL和阈值容差
  - **截图设置**: 截图质量、最大宽度、清理策略
  - **震动设置**: 启用/禁用震动，震动模式（3次震动，每次1000ms，间隔250ms）
- **`SettingsViewModel.kt`** 管理设置状态，与 `SettingsRepository` 交互

#### 10. 历史记录和详情查看
- **`HistoryActivity.kt`** 显示检测历史记录列表，支持下拉刷新、删除记录、查看详情
- **`HistoryDetailActivity.kt`** 显示单个检测记录的详细信息，包括截图、分类分数、后端检测结果
- **`DetectionResultActivity.kt`** 显示实时检测结果的专门页面，与历史详情界面一致但数据来自实时检测

#### 11. 实时检测结果展示
- **`DetectionResultActivity.kt`** 实时检测结果页面，显示与历史记录详情相同的界面
- 通过 Intent 传递检测结果数据和截图，支持查看详细分类分数和后端检测信息
- 返回主界面后自动清理数据，防止内存泄漏

### 核心数据流
1. **权限请求** – 用户通过 `MainActivity` 录屏卡片授予 MediaProjection 权限
2. **服务启动** – `MainActivity` 启动 `NsfwMonitorService` 前台服务并传递权限结果
3. **周期性检测** – `NsfwMonitorService` 使用 `Handler` 调度检测任务
4. **屏幕截图** – 服务通过 `MediaProjection` 和 `ImageReader` 捕获屏幕
5. **内容分类** – `NSFWClassifier` 使用 TensorFlow Lite 处理截图，计算 NSFW/SFW 概率
6. **后端兜底检测（可选）** – 当 Android 检测结果为 SFW 时触发后端检测，使用双模型架构和 YOLOv8s 物体检测进行二次验证
7. **截图保存** – 截图通过 `ScreenshotManager` 保存到应用私有目录；路径记录在检测结果中
8. **结果处理** – 通过 `NotificationUtils` 发送通知（仅针对 NSFW）；结果由 `DetectionRepository` 存储（包括截图路径和后端检测数据）；NSFW 时打开 `DetectionResultActivity` 详情页，服务继续运行不释放录屏
9. **悬浮窗警告** – 如果悬浮窗启用，`FloatingWindowManager` 显示警告文本 5 秒（从 `NsfwMonitorService` 发送广播）
10. **实时检测流程** – 用户通过主界面检测卡片选择图片，触发实时检测流程，结果在 `DetectionResultActivity` 中显示
11. **历史记录查看** – 用户通过记录卡片进入 `HistoryActivity`，可选择记录查看详细结果（`HistoryDetailActivity`）

### 关键依赖
- **TensorFlow Lite (2.14.0)** – ML 推理（包括 GPU 支持）
- **Lifecycle Service (2.6.2)** – 前台服务生命周期管理
- **DataStore (1.0.0)** – 偏好设置管理
- **Compose BOM (2024.09.00)** – UI 框架
- **Gson (2.10.1)** – JSON 序列化

### 重要实现细节
1. **MediaProjection 权限**: 权限直接通过 `MainActivity` 请求，结果传递给 `NsfwMonitorService` 以启动前台服务
2. **24/7 运行模式**: `TimeWindowManager` 修改为持续运行；`isInDetectionWindow()` 始终返回 `true`
3. **前台服务调度**: `NsfwMonitorService` 使用 `Handler` 和 `Runnable` 进行周期性检测；检测间隔可以从设置动态调整
4. **TensorFlow Lite 模型**: 使用 GantMan 的 `saved_model.tflite` 模型，支持5个类别，NSFW 阈值可配置（默认95%）；检测结果包含阈值信息用于详情显示
5. **截图管理**: `ScreenshotManager` 提供自动截图文件管理，具有三级清理策略（时间、数量、大小），以防止存储无限增长
6. **悬浮窗功能**: 悬浮窗需要 `SYSTEM_ALERT_WINDOW` 权限；在 NSFW 检测时显示警告 5 秒；支持边缘停靠（允许拖到屏幕外但最少露出20像素）；智能位置调整避免状态栏遮挡；位置通过 `SettingsRepository` 持久化；通过广播接收 NSFW 检测事件
7. **震动模式**: 使用 3 次震动模式（每次 1000ms，间隔 250ms），当设置中启用震动时直接由 `Vibrator` API 触发
8. **后端兜底检测**: 当 Android 检测结果为 SFW 时触发后端检测；使用双模型架构和 YOLOv8s 物体检测进行兜底验证；YOLOv8s 检测不到物体的问题已修复，精确度符合预期；结果合并采用逻辑或（Android 或后端任一检测为 NSFW 即判定为 NSFW）；后端设置（启用状态、URL、阈值容差）通过 `SettingsRepository` 管理
9. **UI 架构**: 采用 Clash for Android 卡片式设计，主界面简洁明了，功能分离清晰；设置页面独立，提供完整的配置选项；历史记录和详情页面专门化，提供良好的用户体验
10. **进程稳定性**:
    - `AndroidManifest.xml` 设置 `android:largeHeap="true"` 请求更大堆内存
    - `NsfwMonitorService` 使用 `START_STICKY`，进程被杀后系统自动重启
    - 截图保持原始分辨率（不缩放），不影响 TFLite 分类准确度
    - 后端 debug 图片的 base64 数据在收到响应后立即保存到文件并清理内存，防止 OOM
    - `DetectionRepository.saveResultInternal` 使用 `commit()` 而非 `apply()` 同步写入 SharedPreferences，防止进程被杀导致数据丢失
    - `BackendNsfwDetector` 设置连接超时 3s、读取超时 5s，防止网络请求挂起

## 后端服务器概述

项目包含一个独立的 Spring Boot 后端服务（`nsfw-server/`），为 Android 应用提供兜底 NSFW 检测。该服务使用双模型架构（5分类 ONNX 模型和 FalconsAI 模型）和 YOLOv8s 物体检测，提高了检测准确性。

### 关键特性
- **双模型架构**：主模型为 5 分类 ONNX 模型，辅模型为 FalconsAI 二分类模型
- **YOLOv8s 物体检测**：当主模型置信度不高时，使用 YOLOv8s 检测特定物体进行兜底判断
- **触发条件**：当 Android 检测结果为 SFW（安全）时触发后端检测进行二次验证
- **REST API**：提供 `/api/nsfw/detect` 端点接收 multipart/form-data 图片上传

### 构建和运行
```bash
# 进入后端目录
cd ../nsfw-server

# 方式一：使用 Maven 直接运行
mvn spring-boot:run

# 方式二：打包后运行
mvn clean package -DskipTests
java -jar target/nsfw-server-0.0.1-SNAPSHOT.jar
```

### 服务端点
- 健康检查：`GET http://localhost:8082/api/nsfw/health`
- 服务信息：`GET http://localhost:8082/api/nsfw/info`
- NSFW 检测：`POST http://localhost:8082/api/nsfw/detect`

### 配置说明
- 默认端口：8082（可在 `application.properties` 中修改）
- 模型文件：`src/main/resources/models/` 目录下的 ONNX 模型
- 详细文档：参见 `../nsfw-server/CLAUDE.md`

## 主要文件位置
```
app/src/main/java/com/example/direction/
├── DirectionApplication.kt          # 应用类，全局组件管理
├── MainActivity.kt                  # 主 Compose 活动，Clash for Android 样式 UI
├── SettingsActivity.kt              # 设置页面，管理所有应用配置
├── HistoryActivity.kt               # 检测历史记录列表
├── HistoryDetailActivity.kt         # 单个检测记录详情页面
├── DetectionResultActivity.kt       # 实时检测结果页面（与历史详情界面一致）
├── DetectionActivity.kt             # 遗留文件（可能未使用）
├── manager/                         # 业务逻辑管理器
│   ├── TimeWindowManager.kt        # 时间窗口逻辑
│   ├── PermissionManager.kt        # 权限管理（当前未使用）
│   └── FloatingWindowManager.kt    # 悬浮窗管理（NSFW 警告，拖拽和停靠）
├── service/                         # Android 服务
│   └── NsfwMonitorService.kt       # NSFW 监控前台服务（MediaProjection，周期性检测）
├── detector/classifier/             # 检测组件
│   ├── NSFWClassifier.kt           # NSFW 分类器（TensorFlow Lite）
│   └── BackendNsfwDetector.kt      # 后端兜底检测器（HTTP 客户端）
├── model/                           # 数据模型
│   ├── DetectionResult.kt          # 检测结果数据类
│   └── BackendResponseDto.kt       # 后端响应 DTO
├── repository/                      # 数据存储
│   ├── SettingsRepository.kt       # 设置管理（DataStore）
│   └── DetectionRepository.kt      # 检测历史存储（SharedPreferences + Gson）
└── utils/                          # 工具类
    ├── NotificationUtils.kt        # 通知管理
    ├── ScreenshotManager.kt        # 截图文件管理（保存、加载、清理）
    ├── DateTimeUtils.kt            # 日期时间工具
    └── LogUtils.kt                 # 日志工具
```

## 故障排除

### 构建问题
1. **依赖下载慢或失败**
   - 项目已配置阿里云镜像加速，但若网络有问题可尝试：
   - 检查 `settings.gradle.kts` 中的镜像地址可访问性
   - 临时切换到其他镜像或使用 VPN

2. **构建超时**
   - 增加 Gradle 超时设置（在 `gradle.properties` 或 `~/.gradle/gradle.properties`）：
     ```properties
     systemProp.org.gradle.internal.http.socketTimeout=120000
     systemProp.org.gradle.internal.http.connectionTimeout=120000
     ```

3. **内存不足**
   - 增加 Gradle 堆内存（在 `gradle.properties`）：
     ```properties
     org.gradle.jvmargs=-Xmx4096m
     ```

### 运行时问题
1. **悬浮窗无法拖动或位置异常**
   - 检查 `SYSTEM_ALERT_WINDOW` 权限是否已授予
   - 检查悬浮窗是否被状态栏遮挡；应用会自动调整位置
   - 尝试拖动悬浮窗到屏幕边缘（支持侧边停靠，最少露出20像素）

2. **NSFW 检测不工作**
   - 确认 MediaProjection 权限已授予（录屏卡片变为绿色）
   - 检查前台服务 `NsfwMonitorService` 是否正常运行
   - 查看日志：`adb logcat -d | grep -E "(DirectionApplication|NsfwMonitorService)"`

3. **截图保存失败**
   - 检查存储权限（Android 11+ 需要 `MANAGE_EXTERNAL_STORAGE`）
   - 查看 `ScreenshotManager` 清理策略是否过于严格

4. **后端检测失败**
   - 检查网络连接和后端服务是否运行（默认 URL: `http://10.0.2.2:8082`）
   - 验证后端 URL 配置（设置页面可修改）
   - 查看后端服务日志：`tail -f nsfw-server.log`

### 测试问题
1. **单元测试失败**
   - 使用 `./gradlew test --tests "*TestClassName"` 运行特定测试
   - 检查 Robolectric 配置（Android SDK 路径等）

2. **仪器测试失败**
   - 确保已连接 Android 设备或模拟器
   - 使用 `./gradlew connectedAndroidTest --tests "*TestClassName"` 运行特定测试

### 设备调试
- **ADB 设备未识别**: 重启 ADB 服务：`adb kill-server && adb start-server`
- **应用无法安装**: 清除旧版本数据：`adb shell pm clear com.example.direction`
- **查看完整日志**: `adb logcat -d > log.txt` 导出日志文件

> 更多个人环境配置参见 `local.claude.md` 文件。