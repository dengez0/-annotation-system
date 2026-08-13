import json
import os
import shutil
import tempfile

try:
    from PIL import Image
    HAS_PIL = True
except ImportError:
    Image = None
    HAS_PIL = False


IMAGE_EXTENSIONS = ('.jpg', '.jpeg', '.png', '.bmp')


def validate_yolo_label_order(selected_labels, project_labels):
    """Require one ordered occurrence of every label currently used by the project."""
    if not isinstance(selected_labels, list) or any(
        not isinstance(label, str) for label in selected_labels
    ):
        raise ValueError('Labels must be an array of strings')
    if not selected_labels:
        raise ValueError('No labels selected')
    if len(selected_labels) != len(set(selected_labels)):
        raise ValueError('Duplicate labels are not allowed')
    if len(selected_labels) != len(project_labels) or set(selected_labels) != set(project_labels):
        raise ValueError(
            'Label list is incomplete or out of date. Reload and select every project label.'
        )


def _get_image_size(project_path, image_name, json_data):
    img_w = json_data.get('imageWidth', 0)
    img_h = json_data.get('imageHeight', 0)

    if img_w > 0 and img_h > 0:
        return img_w, img_h

    if not HAS_PIL:
        return 0, 0

    img_path = os.path.join(project_path, image_name)
    if not os.path.exists(img_path):
        return 0, 0

    try:
        with Image.open(img_path) as img:
            return img.size
    except Exception:
        return 0, 0


def _shape_to_yolo_line(shape, label_to_id, img_w, img_h):
    label = shape.get('label', '')
    if label not in label_to_id:
        return None

    pts = shape.get('points', [])
    if len(pts) < 2:
        return None

    xs = [p[0] for p in pts if len(p) >= 2]
    ys = [p[1] for p in pts if len(p) >= 2]
    if not xs or not ys:
        return None

    x1, y1 = min(xs), min(ys)
    x2, y2 = max(xs), max(ys)

    x_center = ((x1 + x2) / 2) / img_w
    y_center = ((y1 + y2) / 2) / img_h
    bbox_w = (x2 - x1) / img_w
    bbox_h = (y2 - y1) / img_h

    x_center = max(0, min(1, x_center))
    y_center = max(0, min(1, y_center))
    bbox_w = max(0, min(1, bbox_w))
    bbox_h = max(0, min(1, bbox_h))

    class_id = label_to_id[label]
    return f"{class_id} {x_center} {y_center} {bbox_w} {bbox_h}"


def export_yolo_annotations(project_path, selected_labels, output_dir):
    """Export into a new root-level destination without touching project files."""
    if os.path.exists(output_dir):
        raise FileExistsError(f'Export destination already exists: {output_dir}')

    output_parent = os.path.dirname(output_dir)
    os.makedirs(output_parent, exist_ok=True)
    staging_dir = tempfile.mkdtemp(
        prefix=f'.{os.path.basename(output_dir)}-', dir=output_parent
    )

    label_to_id = {label: i for i, label in enumerate(selected_labels)}

    exported = 0
    skipped = 0
    errors = []

    try:
        for fname in os.listdir(project_path):
            if not fname.lower().endswith(IMAGE_EXTENSIONS):
                continue

            json_name = os.path.splitext(fname)[0] + '.json'
            json_path = os.path.join(project_path, json_name)

            if not os.path.exists(json_path):
                skipped += 1
                continue

            try:
                with open(json_path, 'r', encoding='utf-8') as f:
                    json_data = json.load(f)

                img_w, img_h = _get_image_size(project_path, fname, json_data)
                if img_w <= 0 or img_h <= 0:
                    errors.append(fname)
                    continue

                lines = []
                for shape in json_data.get('shapes', []):
                    line = _shape_to_yolo_line(shape, label_to_id, img_w, img_h)
                    if line:
                        lines.append(line)

                txt_name = os.path.splitext(fname)[0] + '.txt'
                txt_path = os.path.join(staging_dir, txt_name)
                with open(txt_path, 'w', encoding='utf-8') as f:
                    f.write('\n'.join(lines))
                    if lines:
                        f.write('\n')

                exported += 1

            except Exception:
                errors.append(fname)

        classes_path = os.path.join(staging_dir, 'classes.txt')
        with open(classes_path, 'w', encoding='utf-8') as f:
            for label in selected_labels:
                f.write(label + '\n')

        os.rename(staging_dir, output_dir)
    except Exception:
        shutil.rmtree(staging_dir, ignore_errors=True)
        raise

    return {
        'status': 'success',
        'exported': exported,
        'skipped': skipped,
        'errors': len(errors),
        'labels_dir': f"labels/{os.path.basename(output_dir)}",
        'class_count': len(selected_labels),
    }
