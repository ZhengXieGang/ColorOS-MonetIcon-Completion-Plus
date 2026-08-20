import os

def create_folders_and_list():
    # 获取脚本所在的绝对路径
    script_path = os.path.abspath(__file__)
    current_dir = os.path.dirname(script_path)
    
    source_dir = os.path.join(current_dir, 'icon')
    target_dir = os.path.join(current_dir, 'uxicons')


    # 1. 检查 icon 文件夹是否存在
    if not os.path.exists(source_dir):
        print(f"错误：找不到 'icon' 文件夹！位置：{source_dir}")
        return

    # 2. 创建 uxicons 文件夹
    if not os.path.exists(target_dir):
        os.makedirs(target_dir)

    print(f"开始处理...")
    

    folder_count = 0

    # 3. 遍历并处理
    # 对文件名进行排序，确保生成的 txt 也是按字母顺序排列的
    filenames = sorted(os.listdir(source_dir))

    for filename in filenames:
        file_path = os.path.join(source_dir, filename)

        # 排除隐藏文件和目录
        if os.path.isfile(file_path) and not filename.startswith('.'):
            # 提取包名（去掉后缀）
            package_name = os.path.splitext(filename)[0]
            
            # 创建文件夹
            package_folder_path = os.path.join(target_dir, package_name)
            if not os.path.exists(package_folder_path):
                os.makedirs(package_folder_path)
                folder_count += 1
            




    print("-" * 30)
    print(f"处理完成！新创建文件夹: {folder_count} 个。")
    
    # 修复构建出来的文件夹与文件权限为系统环境安全的 755 / 644，防止装到手机时权限受到影响
    for root, dirs, files in os.walk(target_dir):
        os.chmod(root, 0o755)
        for f in files:
            os.chmod(os.path.join(root, f), 0o644)

if __name__ == "__main__":
    create_folders_and_list()