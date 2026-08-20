import os
import sys

# 尝试导入 Pillow 库
try:
    from PIL import Image
except ImportError:
    print("错误：未安装 Pillow 库。")
    print("请先在终端运行：pip install pillow")
    input("按回车键退出...")
    sys.exit()

def process_icons():
    # 获取脚本所在目录
    script_path = os.path.abspath(__file__)
    current_dir = os.path.dirname(script_path)
    
    # 目标目录
    target_dir = os.path.join(current_dir, 'uxicons')

    if not os.path.exists(target_dir):
        print(f"错误：找不到 'uxicons' 文件夹。请确保它和脚本在同一目录下。")
        return

    print(f"开始处理：按分辨率重命名及格式修复(RGBA)...")
    print(f"目标目录: {target_dir}")
    print("-" * 30)
    
    renamed_count = 0
    cleaned_count = 0
    skipped_count = 0
    error_count = 0

    # 定义分辨率规则 (宽, 高) -> 目标文件名(不含后缀)
    size_rules = {
        (240, 240): "monochrome",
        (240, 820): "monochrome_1x2",
        (820, 240): "monochrome_2x1",
        (704, 704): "monochrome_2x2"
    }

    # 获取所有子目录
    try:
        package_names = sorted(os.listdir(target_dir))
    except Exception as e:
        print(f"无法读取目录: {e}")
        return

    for package_name in package_names:
        package_path = os.path.join(target_dir, package_name)

        # 确保处理的是文件夹
        if not os.path.isdir(package_path):
            continue

        # 获取文件夹内的文件列表
        try:
            files = os.listdir(package_path)
        except Exception:
            continue
        
        # 过滤掉系统隐藏文件
        valid_files = [f for f in files if not f.startswith('.')]

        if not valid_files:
            continue
        
        for file_name in valid_files:
            current_file_path = os.path.join(package_path, file_name)
            name, ext = os.path.splitext(file_name)
            
            # 仅处理常见图片格式，跳过非图片文件
            if ext.lower() not in ['.png', '.jpg', '.jpeg', '.bmp']:
                continue

            # --- 步骤 1: 读取分辨率并确定是否需要重命名 ---
            target_name_base = None
            
            try:
                # 打开图片读取尺寸 (使用 with 确保文件句柄立即关闭，以便后续重命名)
                with Image.open(current_file_path) as img:
                    width, height = img.size
                
                # 检查尺寸是否在我们的规则中
                if (width, height) in size_rules:
                    target_name_base = size_rules[(width, height)]
                else:
                    # 如果尺寸不匹配任何规则，跳过重命名，也不进行后续处理（根据需求可调整）
                    # print(f"跳过 ({width}x{height}): {package_name}/{file_name}")
                    continue

            except Exception as e:
                print(f"无法读取图片 {package_name}/{file_name}: {e}")
                error_count += 1
                continue

            # 构建新文件名
            new_filename = target_name_base + ".png" # 强制统一为 .png
            new_file_path = os.path.join(package_path, new_filename)
            final_process_path = current_file_path

            # 执行重命名逻辑
            if file_name != new_filename:
                # 检查目标文件是否已存在（避免覆盖同名文件）
                if os.path.exists(new_file_path):
                    print(f"目标文件已存在，跳过重命名: {package_name}/{new_filename}")
                    # 依然对已存在的那个文件进行RGBA处理吗？这里选择处理当前的 current_file_path
                    # 但如果不重命名，后面的路径就不对了。这里逻辑设为：如果目标存在，就不覆盖，仅报错/跳过。
                    error_count += 1
                    continue 
                
                try:
                    os.rename(current_file_path, new_file_path)
                    print(f"[{width}x{height}] 重命名: {package_name}/{file_name} -> {new_filename}")
                    renamed_count += 1
                    final_process_path = new_file_path
                except Exception as e:
                    print(f"重命名失败: {e}")
                    error_count += 1
                    continue
            else:
                skipped_count += 1
                # 虽然不用重命名，但需要更新路径变量以供下方处理
                final_process_path = current_file_path


            # --- 步骤 2: 清理/修复图片 (统一转为 RGBA PNG) ---
            try:
                # 1. 打开文件 (此时 final_process_path 指向正确的文件名)
                img = Image.open(final_process_path)
                img.load() # 强制加载数据

                # 2. 创建一张全新的 RGBA 画布
                clean_img = Image.new("RGBA", img.size)
                
                # 3. 将原图转为 RGBA 并贴上去 (去除索引颜色、修复透明度问题)
                clean_img.paste(img.convert("RGBA"), (0, 0))

                # 4. 覆盖保存
                clean_img.save(final_process_path, "PNG", optimize=True, compress_level=9)
                cleaned_count += 1
                
            except Exception as e:
                print(f"图片RGBA修复失败 {final_process_path}: {e}")
                error_count += 1

    print("-" * 30)
    print(f"全部完成！")
    
    # 将处理后的文件及目录权限重置为标准的 755(目录) / 644(文件)
    for root, dirs, files in os.walk(target_dir):
        os.chmod(root, 0o755)
        for target_file in files:
            os.chmod(os.path.join(root, target_file), 0o644)
    
    print(f"执行重命名: {renamed_count} 个")
    print(f"⏭名字已正确: {skipped_count} 个")
    print(f"格式修复(PNG): {cleaned_count} 个")
    
    if error_count > 0:
        print(f"发生错误: {error_count} 个")
    else:
        print(f"所有目标图标处理完毕。")

if __name__ == "__main__":
    process_icons()
