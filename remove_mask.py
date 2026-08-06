import os
import json

def remove_mask_labels(dir_path):
    if not os.path.exists(dir_path):
        print(f"Error: Directory {dir_path} does not exist.")
        return

    count = 0
    modified_count = 0
    
    for filename in os.listdir(dir_path):
        if filename.endswith('.json'):
            file_path = os.path.join(dir_path, filename)
            
            try:
                with open(file_path, 'r', encoding='utf-8') as f:
                    data = json.load(f)
            except Exception as e:
                print(f"Error reading {filename}: {e}")
                continue
            
            if 'shapes' in data:
                original_len = len(data['shapes'])
                # Filter out shapes with label 'mask'
                data['shapes'] = [shape for shape in data['shapes'] if shape.get('label') != 'mask']
                
                if len(data['shapes']) != original_len:
                    # Write back only if modified
                    try:
                        with open(file_path, 'w', encoding='utf-8') as f:
                            json.dump(data, f, indent=2, ensure_ascii=False)
                        modified_count += 1
                    except Exception as e:
                        print(f"Error writing {filename}: {e}")
            count += 1
            
    print(f"Successfully processed {count} JSON files.")
    print(f"Modified {modified_count} files (removed 'mask' labels).")

if __name__ == '__main__':
    target_dir = r'E:\整船算法素材\3_26\3_26睁眼闭眼'
    remove_mask_labels(target_dir)
