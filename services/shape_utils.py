def compute_iou(box1, box2):
    x1 = max(box1[0], box2[0])
    y1 = max(box1[1], box2[1])
    x2 = min(box1[2], box2[2])
    y2 = min(box1[3], box2[3])

    inter_area = max(0, x2 - x1) * max(0, y2 - y1)
    box1_area = (box1[2] - box1[0]) * (box1[3] - box1[1])
    box2_area = (box2[2] - box2[0]) * (box2[3] - box2[1])
    union_area = box1_area + box2_area - inter_area

    if union_area == 0:
        return 0
    return inter_area / union_area


def _shape_box(shape):
    points = shape['points']
    return [points[0][0], points[0][1], points[2][0], points[2][1]]


def nms_for_shapes(shapes, iou_threshold=0.7):
    if not shapes:
        return []

    shapes_with_area = []
    for shape in shapes:
        points = shape['points']
        width = points[1][0] - points[0][0]
        height = points[2][1] - points[1][1]
        shapes_with_area.append((shape, width * height))

    shapes_with_area.sort(key=lambda item: item[1], reverse=True)

    final_shapes = []
    while shapes_with_area:
        current, _current_area = shapes_with_area.pop(0)
        final_shapes.append(current)
        current_box = _shape_box(current)

        keep = []
        for other, area in shapes_with_area:
            if compute_iou(current_box, _shape_box(other)) <= iou_threshold:
                keep.append((other, area))
        shapes_with_area = keep

    return final_shapes
