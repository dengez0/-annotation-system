# -*- coding: utf-8 -*-
import os
import json
from PIL import Image

def convert():
    lbl_dir = r'E:\贵州船舱\攀爬\3_24\3_23'
    img_dir = r'E:\贵州船舱\攀爬\3_24\3_23'
    
    classes = {0: 'class_0', 1: 'class_1'}
    
    converted = 0
    errors = 0
    
    for filename in os.listdir(lbl_dir):
        if not filename.endswith('.txt') or filename == 'classes.txt':
            continue
            
        txt_path = os.path.join(lbl_dir, filename)
        base_name = os.path.splitext(filename)[0]
        
        # 寻找图片
        img_path = None
        for ext in ['.jpg', '.jpeg', '.png', '.bmp', '.JPG', '.PNG']:
            temp = os.path.join(img_dir, base_name + ext)
            if os.path.exists(temp):
                img_path = temp
                break
                
        if not img_path:
            print(f"Warning: No image found for {filename}")
            errors += 1
            continue
            
        try:
            with Image.open(img_path) as img:
                img_width, img_height = img.size
                
            shapes = []
            with open(txt_path, 'r', encoding='utf-8') as f:
                for line in f:
                    parts = line.strip().split()
                    if len(parts) >= 5:
                        class_id = int(parts[0])
                        x_center = float(parts[1])
                        y_center = float(parts[2])
                        w = float(parts[3])
                        h = float(parts[4])
                        
                        x1 = max(0, (x_center - w / 2) * img_width)
                        y1 = max(0, (y_center - h / 2) * img_height)
                        x2 = min(img_width, (x_center + w / 2) * img_width)
                        y2 = min(img_height, (y_center + h / 2) * img_height)
                        
                        label_name = classes.get(class_id, f'class_{class_id}')
                        
                        shapes.append({
                            'label': label_name,
                            'points': [[x1, y1], [x2, y2]],
                            'group_id': None,
                            'shape_type': 'rectangle',
                            'flags': {}
                        })
                        
            json_data = {
                'version': '5.2.1',
                'flags': {},
                'shapes': shapes,
                'imagePath': os.path.basename(img_path),
                'imageData': None,
                'imageHeight': img_height,
                'imageWidth': img_width
            }
            
            json_path = os.path.join(lbl_dir, base_name + '.json')
            with open(json_path, 'w', encoding='utf-8') as f:
                json.dump(json_data, f, indent=2, ensure_ascii=False)
                
            converted += 1
        except Exception as e:
            print(f"Error processing {filename}: {e}")
            errors += 1
            
    print(f"Successfully converted {converted} files. Errors: {errors}")

if __name__ == '__main__':
    convert()
