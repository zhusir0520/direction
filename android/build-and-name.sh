#!/bin/bash
# APK构建与MD5命名脚本
# 用法: ./build-and-name.sh [--install] [--cleanup-old]
#   --install      构建后安装到设备
#   --cleanup-old  清理旧的 direction-*.apk 文件
#
# AI使用: 每次修改代码后运行此脚本生成带MD5标识的APK

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m' # No Color

INSTALL=false
CLEANUP=false

for arg in "$@"; do
    case "$arg" in
        --install) INSTALL=true ;;
        --cleanup-old) CLEANUP=true ;;
    esac
done

echo -e "${YELLOW}🔨 构建APK...${NC}"

# 1. 构建
./gradlew assembleDebug
echo -e "${GREEN}✅ 构建成功${NC}"

# 2. 计算MD5
APK="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK" ]; then
    echo -e "${RED}❌ APK文件不存在: $APK${NC}"
    exit 1
fi

# 计算MD5 (兼容Windows/Linux/Mac)
if command -v certutil &> /dev/null; then
    # Windows
    MD5=$(certutil -hashfile "$APK" MD5 | grep -v "MD5" | tr -d ' \r\n')
elif command -v md5sum &> /dev/null; then
    # Linux
    MD5=$(md5sum "$APK" | cut -d' ' -f1)
elif command -v md5 &> /dev/null; then
    # Mac
    MD5=$(md5 -q "$APK")
else
    echo -e "${RED}❌ 找不到MD5计算工具${NC}"
    exit 1
fi

APK_NAME="direction-${MD5}.apk"
cp "$APK" "$APK_NAME"

echo -e "${GREEN}✅ APK已命名: ${APK_NAME}${NC}"
echo -e "${GREEN}   MD5: ${MD5}${NC}"
echo -e "${GREEN}   大小: $(du -h "$APK_NAME" | cut -f1)${NC}"

# 3. 清理旧APK
if [ "$CLEANUP" = true ]; then
    OLD_COUNT=$(ls -1 direction-*.apk 2>/dev/null | wc -l)
    # 保留当前文件，删除其他
    for old in direction-*.apk; do
        if [ "$old" != "$APK_NAME" ]; then
            rm -f "$old"
        fi
    done
    NEW_COUNT=$(ls -1 direction-*.apk 2>/dev/null | wc -l)
    CLEANED=$((OLD_COUNT - NEW_COUNT))
    echo -e "${YELLOW}🧹 已清理 ${CLEANED} 个旧APK${NC}"
fi

# 4. 安装到设备
if [ "$INSTALL" = true ]; then
    # 尝试多种ADB路径
    ADB=""
    for adb_path in \
        "/usr/local/bin/adb" \
        "/usr/bin/adb" \
        "$ANDROID_HOME/platform-tools/adb" \
        "$ANDROID_HOME/platform-tools/adb.exe" \
        "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" \
        "D:/DEV/AndroidSDK/platform-tools/adb.exe"; do
        if [ -f "$adb_path" ]; then
            ADB="$adb_path"
            break
        fi
    done

    if [ -z "$ADB" ]; then
        # 尝试在PATH中查找
        ADB=$(command -v adb 2>/dev/null || echo "")
    fi

    if [ -n "$ADB" ]; then
        echo -e "${YELLOW}📱 安装到设备...${NC}"
        if "$ADB" install -r "$APK" 2>&1; then
            echo -e "${GREEN}✅ 安装成功${NC}"
        else
            echo -e "${RED}⚠️  安装失败（设备可能未连接）${NC}"
        fi
    else
        echo -e "${YELLOW}⚠️  未找到ADB，跳过安装${NC}"
    fi
fi

echo -e "${GREEN}🎉 完成!${NC}"
