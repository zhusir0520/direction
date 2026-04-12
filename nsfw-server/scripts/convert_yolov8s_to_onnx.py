#!/usr/bin/env python3
"""
YOLOv8s PyTorch到ONNX转换脚本

将yolov8s.pt模型转换为ONNX格式，并自动更新Spring Boot应用配置。
确保与现有Java代码兼容。

用法:
    python convert_yolov8s_to_onnx.py [--verify] [--compare] [--simplify] [--no-update]

参数:
    --verify     转换后验证模型兼容性
    --compare    与现有yolov8n.onnx模型比较输出形状
    --simplify   使用onnxsim简化模型（需要安装onnxsim）
    --no-update  不自动更新application.properties
    --help       显示此帮助信息
"""

import os
import sys
import argparse
import subprocess
import shutil
from pathlib import Path
import warnings

# 尝试导入所需库，提供友好的错误信息
try:
    import torch
    import onnx
    import numpy as np
    import onnxruntime as ort
except ImportError as e:
    print(f"错误: 缺少必需的Python库: {e}")
    print("请运行: pip install -r requirements.txt")
    sys.exit(1)

# 项目目录结构
PROJECT_ROOT = Path(__file__).parent.parent
MODELS_DIR = PROJECT_ROOT / "src/main/resources/models"
CONFIG_FILE = PROJECT_ROOT / "src/main/resources/application.properties"

# 模型文件路径
PYTORCH_MODEL = MODELS_DIR / "yolov8s.pt"
ONNX_MODEL_NANO = MODELS_DIR / "yolov8n.onnx"
ONNX_MODEL_SMALL = MODELS_DIR / "yolov8s.onnx"
ONNX_MODEL_SMALL_SIMPLIFIED = MODELS_DIR / "yolov8s_simplified.onnx"

# 模型配置
INPUT_SHAPE = (1, 3, 640, 640)  # 批量大小, 通道, 高度, 宽度
OUTPUT_SHAPE_NANO = (1, 84, 8400)  # yolov8n的输出形状


def check_dependencies():
    """检查所有必需的依赖是否可用"""
    print("=" * 60)
    print("检查依赖...")

    # 检查ultralytics (YOLOv8)
    try:
        import ultralytics
        print(f"✓ ultralytics {ultralytics.__version__}")
    except ImportError:
        print("✗ ultralytics 未安装")
        print("  请运行: pip install ultralytics")
        return False

    # 检查torch
    print(f"✓ torch {torch.__version__}")

    # 检查CUDA可用性
    if torch.cuda.is_available():
        print(f"✓ CUDA可用 (GPU: {torch.cuda.get_device_name(0)})")
    else:
        print("ℹ 使用CPU进行转换")

    # 检查onnxruntime
    print(f"✓ onnxruntime {ort.__version__}")

    # 可选依赖
    try:
        import onnxsim
        print("✓ onnxsim 可用 (用于模型简化)")
    except ImportError:
        print("ℹ onnxsim 未安装 (使用 --simplify 需要)")

    print("依赖检查完成")
    print("=" * 60)
    return True


def check_model_files():
    """检查必要的模型文件是否存在"""
    print("检查模型文件...")

    if not PYTORCH_MODEL.exists():
        print(f"✗ PyTorch模型文件不存在: {PYTORCH_MODEL}")
        return False
    print(f"✓ PyTorch模型文件: {PYTORCH_MODEL} ({PYTORCH_MODEL.stat().st_size / 1024/1024:.1f} MB)")

    if not MODELS_DIR.exists():
        print(f"✗ 模型目录不存在: {MODELS_DIR}")
        return False

    # 检查目标ONNX文件是否已存在
    if ONNX_MODEL_SMALL.exists():
        print(f"⚠ 目标ONNX文件已存在: {ONNX_MODEL_SMALL}")
        response = input("是否覆盖? (y/N): ").strip().lower()
        if response != 'y':
            print("转换已取消")
            return False

    print("模型文件检查完成")
    return True


def convert_to_onnx(use_simplify=False):
    """
    将PyTorch模型转换为ONNX格式

    Args:
        use_simplify: 是否使用onnxsim简化模型

    Returns:
        bool: 转换是否成功
    """
    print("=" * 60)
    print("开始YOLOv8s模型转换...")

    try:
        # 导入YOLO类
        from ultralytics import YOLO

        # 加载PyTorch模型
        print(f"加载PyTorch模型: {PYTORCH_MODEL}")
        model = YOLO(PYTORCH_MODEL)

        # 检查模型类型
        print(f"模型类型: {model.__class__.__name__}")
        print(f"模型类别数: {model.model.nc if hasattr(model.model, 'nc') else '未知'}")

        # 创建虚拟输入用于跟踪
        dummy_input = torch.randn(*INPUT_SHAPE)

        # 导出为ONNX
        print("导出为ONNX格式...")

        # 使用ultralytics的export方法（推荐）
        # 这会自动处理YOLOv8特定的输出格式
        export_result = model.export(
            format='onnx',
            imgsz=640,
            batch=1,
            simplify=use_simplify,
            opset=17,  # 使用较新的opset以获得更好兼容性
            dynamic=False,  # 固定批次大小，但允许Java代码动态处理
            half=False,  # 使用FP32以确保兼容性
        )

        # ultralytics导出可能会直接保存到目标路径或当前目录
        # 首先检查目标文件是否已存在
        if ONNX_MODEL_SMALL.exists():
            print(f"✓ ONNX模型已直接保存到: {ONNX_MODEL_SMALL}")
            print(f"  文件大小: {ONNX_MODEL_SMALL.stat().st_size / 1024/1024:.1f} MB")
        else:
            # 检查当前目录是否有导出的文件
            exported_files = list(Path('.').glob('yolov8s*.onnx'))
            if exported_files:
                exported_file = exported_files[0]
                print(f"模型已导出到当前目录: {exported_file}")

                # 复制到目标位置
                shutil.copy2(exported_file, ONNX_MODEL_SMALL)
                os.remove(exported_file)
                print(f"模型已复制到: {ONNX_MODEL_SMALL}")
            else:
                # 如果导出文件不在当前目录，尝试其他方法
                print("警告: 未找到导出的ONNX文件，尝试手动导出...")

                # 备用方法：直接使用torch.onnx.export
                try:
                    torch.onnx.export(
                        model.model if hasattr(model, 'model') else model,
                        dummy_input,
                        str(ONNX_MODEL_SMALL),
                        input_names=['images'],
                        output_names=['output0'],
                        opset_version=17,
                        dynamic_axes={
                            'images': {0: 'batch_size'},  # 动态批次大小
                            'output0': {0: 'batch_size'}
                        }
                    )
                    print(f"✓ 手动导出成功: {ONNX_MODEL_SMALL}")
                except Exception as e:
                    print(f"✗ 手动导出失败: {e}")
                    print("尝试安装onnxscript: pip install onnxscript")
                    raise

        # 如果启用简化，使用onnxsim进一步优化
        if use_simplify:
            try:
                import onnxsim
                print("使用onnxsim简化模型...")

                # 加载原始模型
                model_onnx = onnx.load(str(ONNX_MODEL_SMALL))

                # 简化模型
                model_simplified, check = onnxsim.simplify(
                    model_onnx,
                    input_shapes={'images': INPUT_SHAPE},
                    dynamic_input_shape=False  # 固定输入形状
                )

                if check:
                    # 保存简化模型
                    onnx.save(model_simplified, str(ONNX_MODEL_SMALL_SIMPLIFIED))
                    print(f"简化模型已保存: {ONNX_MODEL_SMALL_SIMPLIFIED}")

                    # 用简化模型替换原始模型
                    shutil.move(ONNX_MODEL_SMALL_SIMPLIFIED, ONNX_MODEL_SMALL)
                    print("已用简化版本替换原始模型")
                else:
                    print("警告: 模型简化验证失败，使用原始模型")

            except ImportError:
                print("警告: onnxsim未安装，跳过模型简化")
            except Exception as e:
                print(f"警告: 模型简化失败: {e}")

        print(f"✓ ONNX转换成功: {ONNX_MODEL_SMALL}")
        print(f"  文件大小: {ONNX_MODEL_SMALL.stat().st_size / 1024/1024:.1f} MB")
        return True

    except Exception as e:
        print(f"✗ ONNX转换失败: {e}")
        import traceback
        traceback.print_exc()
        return False


def verify_onnx_model():
    """
    验证转换后的ONNX模型

    Returns:
        bool: 验证是否通过
    """
    print("=" * 60)
    print("验证ONNX模型...")

    try:
        # 加载ONNX模型
        model_onnx = onnx.load(str(ONNX_MODEL_SMALL))

        # 基本验证
        onnx.checker.check_model(model_onnx)
        print("✓ ONNX模型语法检查通过")

        # 检查输入输出
        print("\n模型输入:")
        for input in model_onnx.graph.input:
            print(f"  - 名称: {input.name}")
            shape = [dim.dim_value if dim.dim_value > 0 else dim.dim_param
                    for dim in input.type.tensor_type.shape.dim]
            print(f"    形状: {shape}")

        print("\n模型输出:")
        for output in model_onnx.graph.output:
            print(f"  - 名称: {output.name}")
            shape = [dim.dim_value if dim.dim_value > 0 else dim.dim_param
                    for dim in output.type.tensor_type.shape.dim]
            print(f"    形状: {shape}")

            # 检查输出形状是否与预期兼容
            # YOLOv8输出应为3维: [batch, 84, num_predictions]
            if len(shape) == 3:
                print(f"    第二维度(84): {shape[1]}")
                if shape[1] == 84:
                    print("    ✓ 输出维度84 (4坐标 + 80类别) 匹配")
                else:
                    print(f"    ⚠ 第二维度不是84，而是{shape[1]}")

        # 使用ONNX Runtime进行推理测试
        print("\n进行推理测试...")
        ort_session = ort.InferenceSession(str(ONNX_MODEL_SMALL))

        # 准备测试输入
        test_input = np.random.randn(*INPUT_SHAPE).astype(np.float32)

        # 运行推理
        outputs = ort_session.run(None, {'images': test_input})

        print(f"推理输出数量: {len(outputs)}")
        for i, output in enumerate(outputs):
            print(f"  输出{i}: 形状={output.shape}, 数据类型={output.dtype}")

            # 检查输出形状
            if len(output.shape) == 3:
                batch, dim, num_predictions = output.shape
                print(f"    批次: {batch}, 维度: {dim}, 预测数: {num_predictions}")

                if dim == 84:
                    print("    ✓ 维度84匹配YOLOv8格式")
                else:
                    print(f"    ⚠ 维度{dim}可能与YOLOv8格式不匹配")

                # 检查输出范围
                if output.size > 0:
                    min_val = output.min()
                    max_val = output.max()
                    print(f"    值范围: [{min_val:.4f}, {max_val:.4f}]")

        print("✓ ONNX模型验证完成")
        return True

    except Exception as e:
        print(f"✗ ONNX模型验证失败: {e}")
        import traceback
        traceback.print_exc()
        return False


def compare_with_nano_model():
    """
    与现有的yolov8n.onnx模型比较

    Returns:
        bool: 比较是否成功
    """
    print("=" * 60)
    print("与yolov8n模型比较...")

    if not ONNX_MODEL_NANO.exists():
        print("ℹ 未找到yolov8n.onnx文件，跳过比较")
        return True

    try:
        # 加载两个模型
        model_small = onnx.load(str(ONNX_MODEL_SMALL))
        model_nano = onnx.load(str(ONNX_MODEL_NANO))

        print("模型比较:")
        print(f"  yolov8s.onnx: {ONNX_MODEL_SMALL.stat().st_size / 1024/1024:.1f} MB")
        print(f"  yolov8n.onnx: {ONNX_MODEL_NANO.stat().st_size / 1024/1024:.1f} MB")

        # 比较输入
        small_inputs = [input.name for input in model_small.graph.input]
        nano_inputs = [input.name for input in model_nano.graph.input]

        print(f"\n输入名称比较:")
        print(f"  yolov8s: {small_inputs}")
        print(f"  yolov8n: {nano_inputs}")

        # 比较输出
        small_outputs = [output.name for output in model_small.graph.output]
        nano_outputs = [output.name for output in model_nano.graph.output]

        print(f"\n输出名称比较:")
        print(f"  yolov8s: {small_outputs}")
        print(f"  yolov8n: {nano_outputs}")

        # 使用ONNX Runtime比较推理结果
        print("\n推理结果比较:")

        ort_session_small = ort.InferenceSession(str(ONNX_MODEL_SMALL))
        ort_session_nano = ort.InferenceSession(str(ONNX_MODEL_NANO))

        # 相同的测试输入
        test_input = np.random.randn(*INPUT_SHAPE).astype(np.float32)

        # 运行推理
        output_small = ort_session_small.run(None, {'images': test_input})[0]
        output_nano = ort_session_nano.run(None, {'images': test_input})[0]

        print(f"yolov8s输出形状: {output_small.shape}")
        print(f"yolov8n输出形状: {output_nano.shape}")

        if output_small.shape == output_nano.shape:
            print("✓ 输出形状相同")

            # 计算输出差异
            diff = np.abs(output_small - output_nano).mean()
            print(f"  平均绝对差异: {diff:.6f}")

            if diff < 0.1:
                print("  ✓ 输出值相似 (差异 < 0.1)")
            else:
                print("  ⚠ 输出值差异较大，但形状相同")
        else:
            print("⚠ 输出形状不同")

            # 检查维度数量是否相同
            if len(output_small.shape) == len(output_nano.shape):
                print(f"  维度数量相同: {len(output_small.shape)}")

                # 检查第二维度是否为84
                if output_small.shape[1] == 84 and output_nano.shape[1] == 84:
                    print("  ✓ 第二维度都是84 (4坐标 + 80类别)")
                else:
                    print(f"  ⚠ 第二维度不同: yolov8s={output_small.shape[1]}, yolov8n={output_nano.shape[1]}")

        print("✓ 模型比较完成")
        return True

    except Exception as e:
        print(f"✗ 模型比较失败: {e}")
        import traceback
        traceback.print_exc()
        return False


def update_application_config():
    """
    更新application.properties配置文件

    Returns:
        bool: 更新是否成功
    """
    print("=" * 60)
    print("更新应用配置...")

    if not CONFIG_FILE.exists():
        print(f"✗ 配置文件不存在: {CONFIG_FILE}")
        return False

    try:
        # 备份原始配置文件
        backup_file = CONFIG_FILE.with_suffix('.properties.backup')
        shutil.copy2(CONFIG_FILE, backup_file)
        print(f"✓ 配置文件已备份: {backup_file}")

        # 读取配置文件
        with open(CONFIG_FILE, 'r', encoding='utf-8') as f:
            lines = f.readlines()

        # 更新YOLO模型路径
        updated = False
        new_lines = []
        for line in lines:
            stripped = line.strip()

            # 查找YOLO模型路径配置
            if stripped.startswith('nsfw.model.yolo-model-path='):
                original_value = stripped.split('=', 1)[1]
                new_line = 'nsfw.model.yolo-model-path=classpath:models/yolov8s.onnx\n'
                new_lines.append(new_line)

                print(f"更新YOLO模型路径:")
                print(f"  原始: {stripped}")
                print(f"  新: {new_line.strip()}")
                updated = True
            else:
                new_lines.append(line)

        # 如果没有找到配置行，添加新的
        if not updated:
            print("未找到现有YOLO模型路径配置，添加新配置...")
            # 查找合适的插入位置（在YOLO配置部分之后）
            yolo_section_end = -1
            for i, line in enumerate(lines):
                if line.strip().startswith('nsfw.model.yolo-confidence-threshold='):
                    yolo_section_end = i + 1

            if yolo_section_end >= 0:
                lines.insert(yolo_section_end, 'nsfw.model.yolo-model-path=classpath:models/yolov8s.onnx\n')
                new_lines = lines
                updated = True
                print("已在YOLO配置部分添加模型路径")
            else:
                # 添加到文件末尾
                new_lines.append('\n# YOLOv8s模型配置\n')
                new_lines.append('nsfw.model.yolo-model-path=classpath:models/yolov8s.onnx\n')
                updated = True
                print("已在文件末尾添加模型路径")

        # 写入更新后的配置
        if updated:
            with open(CONFIG_FILE, 'w', encoding='utf-8') as f:
                f.writelines(new_lines)

            print(f"✓ 配置文件已更新: {CONFIG_FILE}")

            # 显示更新后的YOLO相关配置
            print("\n更新后的YOLO配置:")
            for line in new_lines:
                if 'yolo' in line.lower():
                    print(f"  {line.strip()}")
        else:
            print("⚠ 未进行任何配置更新")

        return True

    except Exception as e:
        print(f"✗ 配置更新失败: {e}")
        import traceback
        traceback.print_exc()

        # 尝试恢复备份
        if 'backup_file' in locals() and backup_file.exists():
            try:
                shutil.copy2(backup_file, CONFIG_FILE)
                print(f"已从备份恢复配置文件: {backup_file}")
            except:
                print("警告: 无法恢复备份文件")

        return False


def print_summary(success, args):
    """打印转换总结"""
    print("\n" + "=" * 60)
    print("转换总结")
    print("=" * 60)

    if success:
        print("✓ YOLOv8s到ONNX转换成功完成!")

        print("\n生成的文件:")
        if ONNX_MODEL_SMALL.exists():
            size_mb = ONNX_MODEL_SMALL.stat().st_size / 1024 / 1024
            print(f"  • {ONNX_MODEL_SMALL.name}: {size_mb:.1f} MB")

        print("\n下一步:")
        print("  1. 确保Java应用可以加载新模型")
        print("  2. 运行Spring Boot应用测试功能")
        print("  3. 验证物体检测结果正确性")

        if not args.no_update:
            print("\n配置更新:")
            print("  • application.properties已更新为使用yolov8s.onnx")
            print("  • 原始配置已备份为.application.properties.backup")

        print("\n注意事项:")
        print("  • yolov8s模型比yolov8n更大，推理可能稍慢")
        print("  • 确保DJL库能成功加载新模型")
        print("  • 如果遇到问题，可恢复备份配置文件")

    else:
        print("✗ 转换失败")
        print("\n可能的原因:")
        print("  • Python依赖未正确安装")
        print("  • PyTorch模型文件损坏")
        print("  • 磁盘空间不足")
        print("  • 权限问题")

        print("\n故障排除:")
        print("  1. 检查Python环境: python --version")
        print("  2. 安装依赖: pip install -r requirements.txt")
        print("  3. 验证模型文件存在且可读")

    print("=" * 60)


def main():
    """主函数"""
    parser = argparse.ArgumentParser(
        description='将YOLOv8s PyTorch模型转换为ONNX格式并更新应用配置',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  %(prog)s                    # 基本转换
  %(prog)s --verify --compare # 转换并验证
  %(prog)s --simplify         # 转换并简化模型
  %(prog)s --no-update        # 仅转换，不更新配置
        """
    )

    parser.add_argument('--verify', action='store_true',
                       help='转换后验证模型兼容性')
    parser.add_argument('--compare', action='store_true',
                       help='与现有yolov8n.onnx模型比较')
    parser.add_argument('--simplify', action='store_true',
                       help='使用onnxsim简化模型（需要安装onnxsim）')
    parser.add_argument('--no-update', action='store_true',
                       help='不自动更新application.properties配置文件')

    args = parser.parse_args()

    print("YOLOv8s到ONNX转换工具")
    print("=" * 60)

    # 检查依赖
    if not check_dependencies():
        sys.exit(1)

    # 检查模型文件
    if not check_model_files():
        sys.exit(1)

    # 执行转换
    success = convert_to_onnx(use_simplify=args.simplify)

    if success and args.verify:
        success = verify_onnx_model() and success

    if success and args.compare:
        success = compare_with_nano_model() and success

    if success and not args.no_update:
        success = update_application_config() and success

    # 打印总结
    print_summary(success, args)

    sys.exit(0 if success else 1)


if __name__ == '__main__':
    main()