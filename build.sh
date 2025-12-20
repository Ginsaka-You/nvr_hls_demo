#!/bin/bash

# ================= 配置区域 =================
# 根目录 (根据你提供的信息)
PROJECT_ROOT="/home/linaro/nvr_hls_demo"

# 前端文件夹名称 (确定是这个)
FRONT_DIR_NAME="frontend"

# 后端文件夹名称 (⚠️如果你的后端文件夹叫 server 或 demo，请在这里修改！)
BACK_DIR_NAME="backend" 

# ===========================================

FRONT_PATH="$PROJECT_ROOT/$FRONT_DIR_NAME"
BACK_PATH="$PROJECT_ROOT/$BACK_DIR_NAME"

echo "========================================="
echo "   正在准备构建 NVR HLS DEMO 项目..."
echo "========================================="

# 1. 检查目录是否存在
if [ ! -d "$BACK_PATH" ]; then
    echo "❌ 错误：找不到后端目录: $BACK_PATH"
    echo "   请检查脚本顶部的 BACK_DIR_NAME 变量，修改为你实际的后端文件夹名字。"
    exit 1
fi

# 2. 编译前端
echo ">>> [1/4] 开始编译前端 (Vue/React)..."
cd "$FRONT_PATH"
# 如果没有安装依赖，自动安装 (可选)
if [ ! -d "node_modules" ]; then
    echo "    检测到首次运行，正在安装 npm 依赖..."
    npm install
fi

npm run build

if [ $? -ne 0 ]; then
    echo "❌ 前端编译失败！请检查代码错误。"
    exit 1
fi
echo "✅ 前端编译成功！"

# 3. 搬运资源文件
echo ">>> [2/4] 正在将网页迁移至 SpringBoot..."
TARGET_STATIC="$BACK_PATH/src/main/resources/static"

# 创建目录（如果不存在）
mkdir -p "$TARGET_STATIC"
# 清空旧文件
rm -rf "$TARGET_STATIC"/*
# 复制新文件 (注意：有些项目生成的是 dist，有些是 build，这里默认 dist)
if [ -d "dist" ]; then
    cp -r dist/* "$TARGET_STATIC"/
elif [ -d "build" ]; then
    cp -r build/* "$TARGET_STATIC"/
else
    echo "❌ 错误：在前端目录里没找到 dist 或 build 文件夹！"
    exit 1
fi
echo "✅ 资源迁移完成！"

# 4. 编译后端
echo ">>> [3/4] 开始打包后端 Jar 文件..."
cd "$BACK_PATH"
mvn clean package -DskipTests

if [ $? -ne 0 ]; then
    echo "❌ 后端打包失败！请检查 Maven 日志。"
    exit 1
fi

# 5. 提示运行
JAR_FILE=$(ls target/*.jar | head -n 1)
echo "========================================="
echo "🎉 构建全部完成！"
echo "   生成的 Jar 包位于: $BACK_PATH/$JAR_FILE"
echo "========================================="
echo "   你可以直接使用以下命令运行："
echo "   java -jar $BACK_PATH/$JAR_FILE"
echo "========================================="
