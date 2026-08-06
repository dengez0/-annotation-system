import os
import json
import xml.etree.ElementTree as ET

def xml_to_labelme_json(dir_path):
    if not os.path.exists(dir_path):
        print(f"Error: Directory {dir_path} does not exist.")
        return

    count = 0
    error_count = 0
    
    for filename in os.listdir(dir_path):
        if filename.endswith('.xml'):
            xml_path = os.path.join(dir_path, filename)
            
            try:
                tree = ET.parse(xml_path)
                root = tree.getroot()
                
                # 获取图片名称
                filename_node = root.find('filename')
                if filename_node is not None and filename_node.text:
                    img_filename = filename_node.text
                else:
                    img_filename = filename.replace('.xml', '.jpg')
                
                # 获取图片尺寸
                size_node = root.find('size')
                if size_node is not None:
                    width = int(size_node.find('width').text)
                    height = int(size_node.find('height').text)
                else:
                    width = 1920  # 默认后备尺寸
                    height = 1080
                    
                shapes = []
                # 获取所有的标注框
                for obj in root.findall('object'):
                    name_node = obj.find('name')
                    label = name_node.text if name_node is not None else "unknown"
                    
                    bndbox = obj.find('bndbox')
                    if bndbox is not None:
                        xmin = float(bndbox.find('xmin').text)
                        ymin = float(bndbox.find('ymin').text)
                        xmax = float(bndbox.find('xmax').text)
                        ymax = float(bndbox.find('ymax').text)
                        
                        shape = {
                            "label": label,
                            "points": [
                                [xmin, ymin],
                                [xmax, ymax]
                            ],
                            "group_id": None,
                            "shape_type": "rectangle",
                            "flags": {}
                        }
                        shapes.append(shape)
                    
                # 组装为LabelMe的JSON格式
                json_data = {
                    "version": "5.2.1",
                    "flags": {},
                    "shapes": shapes,
                    "imagePath": img_filename,
                    "imageData": None,
                    "imageHeight": height,
                    "imageWidth": width
                }
                
                json_filename = os.path.splitext(filename)[0] + '.json'
                json_path = os.path.join(dir_path, json_filename)
                
                with open(json_path, 'w', encoding='utf-8') as f:
                    json.dump(json_data, f, indent=2, ensure_ascii=False)
                    
                count += 1
            except Exception as e:
                print(f"Error processing {filename}: {e}")
                error_count += 1
                
    print(f"Conversion completed.")
    print(f"Successfully converted: {count} files.")
    if error_count > 0:
        print(f"Errors encountered: {error_count} files.")

if __name__ == '__main__':
    target_dir = r'D:\救生衣\1\转化\jpg'
    xml_to_labelme_json(target_dir)
