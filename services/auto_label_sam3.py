import json
import os

from services.constants import IMAGE_EXTENSIONS
from services.sam3_prompting import build_sample_index, masks_to_rectangles, scaled_boxes_for_image
from services.shape_utils import compute_iou, nms_for_shapes


def _resolve_model_path(models_dir, model_name):
    model_path = os.path.join(models_dir, model_name)
    if os.path.exists(model_path):
        return model_path

    fallback = os.path.join(models_dir, "sam3.pt")
    if os.path.exists(fallback):
        return fallback

    raise FileNotFoundError(f"Model {model_name} not found in {models_dir}")


def _create_predictor(model_path, conf):
    from ultralytics.models.sam import SAM3SemanticPredictor

    print(f"SAM3: Loading model from {model_path} with conf={conf}")
    overrides = dict(
        conf=conf,
        task="segment",
        mode="predict",
        model=model_path,
        half=True,
        save=False,
        imgsz=644,
    )
    return SAM3SemanticPredictor(overrides=overrides)


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
    save_path = os.path.join(project_path, os.path.splitext(img_name)[0] + '.json')
    with open(save_path, 'w', encoding='utf-8') as f:
        json.dump(json_data, f, indent=2, ensure_ascii=False)


def _predict_label_shapes(predictor, label, conf, image_area, min_area_ratio, cv2, np, prompt_boxes):
    label_shapes = []
    box_prompt_success = False

    if prompt_boxes:
        results = predictor(bboxes=prompt_boxes)
        if results:
            result = results[0]
            label_shapes.extend(
                masks_to_rectangles(
                    result,
                    label,
                    conf,
                    image_area,
                    min_area_ratio,
                    cv2,
                    np,
                    prompt_boxes=prompt_boxes,
                )
            )
            box_prompt_success = bool(label_shapes)

    if not box_prompt_success:
        results = predictor(text=[label])
        if results:
            result = results[0]
            label_shapes.extend(
                masks_to_rectangles(result, label, conf, image_area, min_area_ratio, cv2, np)
            )

    return nms_for_shapes(label_shapes, iou_threshold=0.7)


def _process_image(predictor, project_path, img_name, labels_list, sample_index, conf, cv2, np):
    img_path = os.path.join(project_path, img_name)
    img = cv2.imread(img_path)
    height, width = img.shape[:2]
    image_area = height * width
    min_area_ratio = 0.0005

    predictor.set_image(img_path)
    image_base = os.path.splitext(img_name)[0]
    sample_boxes_map = scaled_boxes_for_image(sample_index, image_base, width, height)

    all_shapes = []
    for label in labels_list:
        all_shapes.extend(
            _predict_label_shapes(
                predictor,
                label,
                conf,
                image_area,
                min_area_ratio,
                cv2,
                np,
                sample_boxes_map.get(label),
            )
        )

    if all_shapes:
        _save_annotation(project_path, img_name, _labelme_payload(img_name, all_shapes, width, height))


def background_auto_label_sam3(task_manager, task_id, data_dir, models_dir, project_name, sample_project_name, model_name, conf):
    import cv2
    import numpy as np

    with task_manager.lock:
        task = task_manager.tasks.get(task_id)
    if not task:
        return

    try:
        project_path = os.path.join(data_dir, project_name)
        sample_path = os.path.join(data_dir, sample_project_name)
        if not os.path.exists(project_path):
            raise FileNotFoundError(f"Project path missing: {project_path}")
        if not os.path.exists(sample_path):
            raise FileNotFoundError(f"Sample path missing: {sample_path}")

        predictor = _create_predictor(_resolve_model_path(models_dir, model_name), conf)
        labels_list, sample_index = build_sample_index(sample_path)

        target_images = [f for f in os.listdir(project_path) if f.lower().endswith(IMAGE_EXTENSIONS)]
        task['total'] = len(target_images)
        processed_count = 0

        for img_name in target_images:
            if task.get('cancel'):
                break

            try:
                _process_image(predictor, project_path, img_name, labels_list, sample_index, conf, cv2, np)
            except Exception as e:
                print(f"SAM3 Error on {img_name}: {e}")

            processed_count += 1
            with task_manager.lock:
                task['progress'] = processed_count
                task['processed_count'] = processed_count

        if not task.get('cancel'):
            with task_manager.lock:
                task['status'] = 'completed'

    except ImportError:
        with task_manager.lock:
            task['status'] = 'failed'
            task['error'] = "Failed to import SAM3SemanticPredictor from ultralytics.models.sam. Please ensure ultralytics is installed/updated."
    except Exception as e:
        print(f"Auto Label SAM3 Error: {e}")
        with task_manager.lock:
            task['status'] = 'failed'
            task['error'] = str(e)
