LABEL_MAP = {
    "unwear_lifejacket": "unwear_life",
    "life_jacket": "life",
    "smoking_held": "smoking",
    "eyes_closed": "closeeyes",
}

VALID_LABELS = [
    "without_helmet",
    "helmet",
    "smoking",
    "walkie",
    "cup",
    "mobilephone",
    "mask",
    "closeeyes",
    "yawn",
    "unwear_uniform",
    "uniform",
    "fire",
    "deckopen",
    "deckclose",
    "life",
    "unwear_life",
    "extinguisher",
]


def _point_pairs(points):
    if points and isinstance(points[0], (int, float)):
        pairs = []
        for index in range(0, len(points), 2):
            if index + 1 < len(points):
                pairs.append([points[index], points[index + 1]])
        return pairs

    return [point[:2] for point in points if isinstance(point, list) and len(point) >= 2]


def restore_coordinates_to_4points(llm_shape, real_width, real_height, input_width, input_height):
    final_shapes = []
    for shape in llm_shape:
        if not isinstance(shape, dict):
            continue

        points = shape.get('points', [])
        if not isinstance(points, list):
            continue

        restored_points = []
        for point in _point_pairs(points):
            restored_points.append([
                (point[0] / input_width) * real_width,
                (point[1] / input_height) * real_height,
            ])

        if len(restored_points) == 2:
            x1, y1 = restored_points[0]
            x2, y2 = restored_points[1]
            final_points = [[x1, y1], [x2, y1], [x2, y2], [x1, y2]]
        elif len(restored_points) >= 3:
            final_points = restored_points
        else:
            continue

        final_shapes.append({
            "label": shape.get('label'),
            "points": final_points,
            "group_id": None,
            "description": "",
            "difficult": False,
            "shape_type": "rectangle",
            "flags": {},
            "attributes": {},
        })

    return final_shapes


def filter_valid_shapes(shapes):
    processed_shapes = []
    for shape in shapes:
        label = shape.get('label', '').lower().strip()
        label = LABEL_MAP.get(label, label)
        if label not in VALID_LABELS:
            continue

        shape['label'] = label
        processed_shapes.append(shape)

    return processed_shapes


def labelme_payload(img_name, shapes, width, height):
    return {
        "version": "2.2.0",
        "flags": {},
        "shapes": shapes,
        "imagePath": img_name,
        "imageData": None,
        "imageHeight": height,
        "imageWidth": width,
    }
