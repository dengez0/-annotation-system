import json
import os

from services.shape_utils import compute_iou


def rectangle_shape(label, x, y, width, height):
    return {
        "label": label,
        "points": [[x, y], [x + width, y], [x + width, y + height], [x, y + height]],
        "shape_type": "rectangle",
        "flags": {},
    }


def build_sample_index(sample_path):
    unique_labels = set()
    sample_index = {}

    sample_files = [f for f in os.listdir(sample_path) if f.lower().endswith('.json')]
    for json_file in sample_files:
        try:
            with open(os.path.join(sample_path, json_file), 'r', encoding='utf-8') as f:
                data = json.load(f)

            img_name = data.get('imagePath') or os.path.splitext(json_file)[0]
            base = os.path.splitext(os.path.basename(img_name))[0]
            sample_index.setdefault(base, {
                'size': (data.get('imageWidth'), data.get('imageHeight')),
                'labels': {},
            })

            for shape in data.get('shapes', []):
                label = shape.get('label')
                points = shape.get('points', [])
                if not label or not points:
                    continue

                unique_labels.add(label)
                xs = [p[0] for p in points]
                ys = [p[1] for p in points]
                sample_index[base]['labels'].setdefault(label, []).append([
                    min(xs),
                    min(ys),
                    max(xs),
                    max(ys),
                ])
        except Exception as e:
            print(f"Error reading sample {json_file}: {e}")

    if not unique_labels:
        unique_labels.add("object")

    return sorted(unique_labels), sample_index


def scaled_boxes_for_image(sample_index, image_base, width, height):
    if image_base not in sample_index:
        return {}

    sample_width, sample_height = sample_index[image_base]['size']
    if not sample_width or not sample_height or sample_width <= 0 or sample_height <= 0:
        return {}

    scale_x = width / sample_width
    scale_y = height / sample_height
    return {
        label: [[box[0] * scale_x, box[1] * scale_y, box[2] * scale_x, box[3] * scale_y] for box in boxes]
        for label, boxes in sample_index[image_base]['labels'].items()
    }


def masks_to_rectangles(result, label, conf, image_area, min_area_ratio, cv2, np, prompt_boxes=None):
    shapes = []
    if not result.masks:
        return shapes

    for index, mask in enumerate(result.masks.data):
        score = 1.0
        if result.boxes and result.boxes.conf is not None and len(result.boxes.conf) > index:
            score = float(result.boxes.conf[index])
        if score < conf:
            continue

        mask_np = mask.cpu().numpy().astype(np.uint8)
        contours, _ = cv2.findContours(mask_np, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        for contour in contours:
            if cv2.contourArea(contour) / image_area < min_area_ratio:
                continue

            x, y, width, height = cv2.boundingRect(contour)
            if prompt_boxes and index < len(prompt_boxes):
                prompt_box = prompt_boxes[index]
                mask_box = [x, y, x + width, y + height]
                if compute_iou(mask_box, prompt_box) < 0.3:
                    continue

            shapes.append(rectangle_shape(label, x, y, width, height))

    return shapes
