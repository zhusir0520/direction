# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

这是一个包含两个主要项目的代码库：

1. **nsfw-server**: 基于 Spring Boot 和 DJL (Deep Java Library) 的 NSFW (Not Safe For Work) 图片检测 REST API 服务
2. **android**: Android 客户端应用，与 nsfw-server 后端交互进行 NSFW 检测

## 详细文档

每个项目的详细文档位于各自的目录中：

- **nsfw-server**: 参见 [nsfw-server/CLAUDE.md](nsfw-server/CLAUDE.md)
- **android**: 参见 [android/CLAUDE.md](android/CLAUDE.md)

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

## 本地配置参考

项目根目录下的 `local.claude.md` 文件包含详细的本地开发配置模板。请根据您的环境更新此文件，特别是 ADB 路径和后端 URL 配置。