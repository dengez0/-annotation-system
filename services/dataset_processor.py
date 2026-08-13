"""Safe, asynchronous dataset delivery pipeline for the data-processing module."""
import json
import os
import shutil
import tarfile
import tempfile
from datetime import datetime

from PIL import Image, ImageDraw, ImageFile

from services.constants import IMAGE_EXTENSIONS


def _images(root):
    result = []
    for current, _, names in os.walk(root):
        for name in sorted(names):
            if name.lower().endswith(IMAGE_EXTENSIONS):
                result.append(os.path.join(current, name))
    return sorted(result)


def available_labels(project_dir):
    labels = set()
    for image_path in _images(project_dir):
        json_path = os.path.splitext(image_path)[0] + '.json'
        if not os.path.isfile(json_path):
            continue
        try:
            with open(json_path, encoding='utf-8') as handle:
                data = json.load(handle)
            labels.update(shape.get('label') for shape in data.get('shapes', []) if shape.get('label') and shape.get('label') != 'mask')
        except (OSError, ValueError, TypeError):
            continue
    return sorted(labels)


def validate_label_order(labels, project_dir):
    expected = available_labels(project_dir)
    if not isinstance(labels, list) or any(not isinstance(item, str) for item in labels):
        raise ValueError('Labels must be an array of strings')
    if len(labels) != len(set(labels)) or set(labels) != set(expected):
        raise ValueError('Select every non-mask label exactly once before processing')


def _repair_image(source, destination):
    ImageFile.LOAD_TRUNCATED_IMAGES = True
    with Image.open(source) as original:
        image = original.convert('RGB') if original.mode not in ('RGB', 'RGBA') else original.copy()
        extension = os.path.splitext(destination)[1].lower()
        if extension in ('.jpg', '.jpeg') and image.mode == 'RGBA':
            image = image.convert('RGB')
        image.save(destination)
    return image


def _blackout_and_yolo(image, annotation, label_to_id):
    canvas = image.copy()
    draw = ImageDraw.Draw(canvas)
    lines = []
    width, height = canvas.size
    for shape in annotation.get('shapes', []):
        label, points = shape.get('label'), shape.get('points', [])
        valid = [(float(point[0]), float(point[1])) for point in points if isinstance(point, list) and len(point) >= 2]
        if label == 'mask':
            if len(valid) >= 3:
                draw.polygon(valid, fill=0)
            elif len(valid) >= 2:
                x1, x2 = sorted((valid[0][0], valid[1][0])); y1, y2 = sorted((valid[0][1], valid[1][1]))
                draw.rectangle((x1, y1, x2, y2), fill=0)
            continue
        if label not in label_to_id or len(valid) < 2:
            continue
        xs, ys = [point[0] for point in valid], [point[1] for point in valid]
        x1, x2 = max(0, min(xs)), min(width, max(xs))
        y1, y2 = max(0, min(ys)), min(height, max(ys))
        if x2 <= x1 or y2 <= y1:
            continue
        lines.append(f"{label_to_id[label]} {((x1+x2)/2)/width:.6f} {((y1+y2)/2)/height:.6f} {(x2-x1)/width:.6f} {(y2-y1)/height:.6f}")
    return canvas, lines


def _write_report(path, payload):
    with open(path, 'w', encoding='utf-8') as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)


def process_dataset(task_manager, task_id, data_dir, processed_dir, main_folder, subfolder, labels, client_ip, log_writer):
    task = task_manager.get_task(task_id)
    source = os.path.join(data_dir, main_folder, subfolder)
    if not os.path.isdir(source):
        task_manager.update(task_id, status='failed', error='Project not found')
        return
    timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
    target_parent = os.path.join(processed_dir, main_folder)
    output_name = f'{subfolder}_{timestamp}'
    final_dir = os.path.join(target_parent, output_name)
    os.makedirs(target_parent, exist_ok=True)
    staging = tempfile.mkdtemp(prefix=f'.{output_name}-', dir=target_parent)
    dataset = os.path.join(staging, 'dataset')
    images = _images(source)
    report = {'source': f'data/{main_folder}/{subfolder}', 'labels': labels, 'mask_label': 'mask',
              'total_images': len(images), 'delivered': 0, 'empty_labels': 0, 'failed': [], 'repaired': 0}
    try:
        task_manager.update(task_id, total=len(images), progress=0, processed_count=0, stage='backup')
        archive = os.path.join(staging, f'{output_name}.tar.bak')
        with tarfile.open(archive, 'w') as tar:
            tar.add(source, arcname=subfolder)
        os.makedirs(dataset, exist_ok=True)
        label_to_id = {label: index for index, label in enumerate(labels)}
        with open(os.path.join(dataset, 'classes.txt'), 'w', encoding='utf-8') as handle:
            handle.write('\n'.join(labels) + ('\n' if labels else ''))
        for index, image_path in enumerate(images, 1):
            if task_manager.get_task(task_id).get('cancel'):
                task_manager.update(task_id, status='cancelled', stage='cancelled')
                return
            relative = os.path.relpath(image_path, source)
            destination_image = os.path.join(dataset, relative)
            os.makedirs(os.path.dirname(destination_image), exist_ok=True)
            try:
                repaired = _repair_image(image_path, destination_image)
                report['repaired'] += 1
                json_path = os.path.splitext(image_path)[0] + '.json'
                lines = []
                if os.path.isfile(json_path):
                    with open(json_path, encoding='utf-8') as handle:
                        annotation = json.load(handle)
                    processed_image, lines = _blackout_and_yolo(repaired, annotation, label_to_id)
                    processed_image.save(destination_image)
                else:
                    report['empty_labels'] += 1
                with open(os.path.splitext(destination_image)[0] + '.txt', 'w', encoding='utf-8') as handle:
                    handle.write('\n'.join(lines) + ('\n' if lines else ''))
                report['delivered'] += 1
            except Exception as exc:
                if os.path.exists(destination_image): os.remove(destination_image)
                report['failed'].append({'file': relative, 'error': str(exc)})
            task_manager.update(task_id, progress=index, processed_count=report['delivered'], stage='processing')
        _write_report(os.path.join(staging, 'report.json'), report)
        os.rename(staging, final_dir)
        task_manager.update(task_id, status='completed', stage='completed', result_dir=os.path.relpath(final_dir, os.path.dirname(processed_dir)), report=report)
        log_writer('PROCESS_DATASET', client_ip, main_folder, subfolder, target=os.path.relpath(final_dir, os.path.dirname(processed_dir)), count=report['delivered'])
    except Exception as exc:
        task_manager.update(task_id, status='failed', error=str(exc), stage='failed')
    finally:
        if os.path.exists(staging): shutil.rmtree(staging, ignore_errors=True)
