def resolve_label_name(cls_id, custom_names=None, model_names=None):
    label = str(cls_id)

    if custom_names:
        if isinstance(custom_names, dict):
            return str(custom_names.get(cls_id, custom_names.get(str(cls_id), label)))
        if isinstance(custom_names, list) and 0 <= cls_id < len(custom_names):
            return str(custom_names[cls_id])

    if model_names:
        if isinstance(model_names, dict):
            return str(model_names.get(cls_id, model_names.get(str(cls_id), label)))
        if isinstance(model_names, list) and 0 <= cls_id < len(model_names):
            return str(model_names[cls_id])

    return label


def _rectangle_shape(label, x1, y1, x2, y2):
    return {
        "label": label,
        "points": [[x1, y1], [x2, y1], [x2, y2], [x1, y2]],
        "group_id": None,
        "description": "",
        "difficult": False,
        "shape_type": "rectangle",
        "flags": {},
        "attributes": {},
    }


def _parse_ultralytics_results(results, custom_names, model_names):
    shapes = []
    if not results:
        return shapes, None, None

    img_h, img_w = results[0].orig_shape
    for result in results:
        for box in result.boxes:
            coords = box.xyxy[0].tolist()
            x1 = max(0, min(img_w, coords[0]))
            y1 = max(0, min(img_h, coords[1]))
            x2 = max(0, min(img_w, coords[2]))
            y2 = max(0, min(img_h, coords[3]))

            if x2 <= x1 + 1 or y2 <= y1 + 1:
                continue

            cls_id = int(box.cls[0])
            label = resolve_label_name(cls_id, custom_names=custom_names, model_names=model_names)
            shapes.append(_rectangle_shape(label, x1, y1, x2, y2))

    return shapes, img_h, img_w


def _parse_yolov5_hub_results(results, custom_names, model_names):
    shapes = []
    img_h, img_w = None, None

    if not hasattr(results, 'xyxy') or not results.xyxy:
        return shapes, img_h, img_w

    try:
        if hasattr(results, 'ims') and results.ims:
            img_h, img_w = results.ims[0].shape[:2]
    except Exception:
        pass

    rows = results.xyxy[0]
    rows = rows.tolist() if hasattr(rows, 'tolist') else rows
    model_names = getattr(results, 'names', model_names)

    for row in rows:
        if len(row) < 6:
            continue

        x1, y1, x2, y2, _score, cls_raw = row[:6]
        cls_id = int(cls_raw)
        label = resolve_label_name(cls_id, custom_names=custom_names, model_names=model_names)

        if img_w is not None and img_h is not None:
            x1 = max(0, min(img_w, x1))
            y1 = max(0, min(img_h, y1))
            x2 = max(0, min(img_w, x2))
            y2 = max(0, min(img_h, y2))

        if x2 <= x1 + 1 or y2 <= y1 + 1:
            continue

        shapes.append(_rectangle_shape(label, x1, y1, x2, y2))

    return shapes, img_h, img_w


def parse_shapes_from_results(detector, results, custom_names=None):
    backend = detector['backend']
    model_names = detector.get('model_names')

    if backend == 'ultralytics':
        return _parse_ultralytics_results(results, custom_names, model_names)

    if backend == 'yolov5_hub':
        return _parse_yolov5_hub_results(results, custom_names, model_names)

    return [], None, None
