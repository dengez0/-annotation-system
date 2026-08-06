import json
import os

from services.constants import IMAGE_EXTENSIONS
from services.yolo_backend import create_detector, load_custom_names, run_detector
from services.yolo_result_parser import parse_shapes_from_results, resolve_label_name


def _image_size_from_file(img_path):
    try:
        from PIL import Image
        with Image.open(img_path) as img:
            return img.size
    except Exception:
        return 0, 0


def _labelme_payload(img_name, shapes, width, height):
    return {
        "version": "5.2.1",
        "flags": {},
        "shapes": shapes,
        "imagePath": img_name,
        "imageData": None,
        "imageHeight": height,
        "imageWidth": width,
    }


def _save_annotation(project_path, img_name, json_data):
    json_name = os.path.splitext(img_name)[0] + '.json'
    save_path = os.path.join(project_path, json_name)
    with open(save_path, 'w', encoding='utf-8') as f:
        json.dump(json_data, f, indent=2, ensure_ascii=False)


def _process_image(detector, project_path, img_name, models_dir, model_name, conf):
    img_path = os.path.join(project_path, img_name)
    results = run_detector(detector, img_path, conf)
    custom_names = load_custom_names(models_dir, model_name)
    shapes, height, width = parse_shapes_from_results(detector, results, custom_names=custom_names)

    if not shapes:
        return False

    if height is None or width is None:
        width, height = _image_size_from_file(img_path)

    json_data = _labelme_payload(img_name, shapes, width, height)
    _save_annotation(project_path, img_name, json_data)
    return True


def background_auto_label(task_manager, task_id, base_dir, data_dir, models_dir, project_name, model_name, conf, backend='auto', custom_repo=None):
    with task_manager.lock:
        task = task_manager.tasks.get(task_id)
    if not task:
        return

    try:
        model_path = os.path.join(models_dir, model_name)
        detector = create_detector(base_dir, model_path, model_name, backend=backend, custom_repo=custom_repo)
        task['backend'] = detector['backend']

        project_path = os.path.join(data_dir, project_name)
        images = [f for f in os.listdir(project_path) if f.lower().endswith(IMAGE_EXTENSIONS)]

        task['total'] = len(images)
        saved_count = 0

        for index, img_name in enumerate(images):
            if task.get('cancel'):
                break

            if _process_image(detector, project_path, img_name, models_dir, model_name, conf):
                saved_count += 1

            with task_manager.lock:
                task['progress'] = index + 1
                task['processed_count'] = saved_count

        if not task.get('cancel'):
            with task_manager.lock:
                task['status'] = 'completed'

    except Exception as e:
        print(f"Auto Label Error: {e}")
        with task_manager.lock:
            task['status'] = 'failed'
            task['error'] = str(e)
