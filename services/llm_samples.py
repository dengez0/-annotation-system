import json
import os

from services.llm_image import Image, encode_image_file, smart_resize_qwen2_5_vl


def normalize_sample_for_prompt(json_data, img_width, img_height, input_width, input_height):
    processed_shapes = []
    for shape in json_data.get('shapes', []):
        norm_points = []
        for point in shape['points']:
            if len(point) >= 2:
                norm_points.append([
                    (point[0] / img_width) * input_width,
                    (point[1] / img_height) * input_height,
                ])
        processed_shapes.append({
            "label": shape['label'],
            "points": norm_points,
        })
    return json.dumps({"shapes": processed_shapes}, ensure_ascii=False)


def prepare_multi_samples(sample_dir):
    sample_messages = []
    for file_name in os.listdir(sample_dir):
        if not file_name.endswith(".jpg"):
            continue

        img_base = os.path.splitext(file_name)[0]
        json_path = os.path.join(sample_dir, f"{img_base}.json")
        img_path = os.path.join(sample_dir, file_name)
        if not os.path.exists(json_path):
            continue

        with Image.open(img_path) as sample_img:
            sample_width, sample_height = sample_img.size
            input_height, input_width = smart_resize_qwen2_5_vl(sample_img)
            sample_b64 = encode_image_file(img_path)

        with open(json_path, 'r', encoding='utf-8') as f:
            raw_data = json.load(f)

        normalized_json = normalize_sample_for_prompt(
            raw_data,
            sample_width,
            sample_height,
            input_width,
            input_height,
        )
        sample_messages.append({"role": "user", "content": [
            {"type": "text", "text": f"Reference Image ({file_name}) (Model input size: {input_width}x{input_height}):"},
            {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{sample_b64}"}},
        ]})
        sample_messages.append({"role": "assistant", "content": normalized_json})

    return sample_messages
