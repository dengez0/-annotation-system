import os
import json

def format_jsons(dir_path):
    count = 0
    for filename in os.listdir(dir_path):
        if filename.endswith('.json'):
            file_path = os.path.join(dir_path, filename)
            
            with open(file_path, 'r', encoding='utf-8') as f:
                try:
                    data = json.load(f)
                except json.JSONDecodeError:
                    print(f"Error reading {filename}")
                    continue
            
            # Fix imagePath to match the actual filename (resolves encoding issues in the string)
            base_name = os.path.splitext(filename)[0]
            data['imagePath'] = base_name + '.jpg'
            
            # Remove 'text' field if it exists to match the target format
            if 'text' in data:
                del data['text']
                
            # Write back with indent=2 and ensure_ascii=False
            with open(file_path, 'w', encoding='utf-8') as f:
                json.dump(data, f, indent=2, ensure_ascii=False)
                
            count += 1
            
    print(f"Successfully formatted {count} JSON files in {dir_path}")

if __name__ == '__main__':
    target_dir = r'D:\救生衣\1\3_25'
    format_jsons(target_dir)
