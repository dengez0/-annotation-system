import json
import os

try:
    from PIL import Image
    HAS_PIL = True
except ImportError:
    Image = None
    HAS_PIL = False

from services.constants import IMAGE_EXTENSIONS


# Label colors are UI-only project preferences.  Keeping them outside LabelMe
# annotation payloads preserves compatibility with existing annotations/tools.
LABEL_COLORS_FILENAME = '.label_colors.json'
LABEL_COLORS = {
    '#8B4513', '#000080', '#006400', '#FF4444', '#44FF44',
    '#4488FF', '#FFDD44', '#FF44FF', '#44FFFF', '#FF8844',
}


def project_path(data_dir, main_folder, subfolder):
    return os.path.join(data_dir, main_folder, subfolder)


def list_main_folders(data_dir):
    main_folders = []
    if not os.path.exists(data_dir):
        return main_folders

    for main_name in sorted(os.listdir(data_dir)):
        main_path = os.path.join(data_dir, main_name)
        if not os.path.isdir(main_path):
            continue

        subfolders = []
        total_images = 0
        for sub_name in sorted(os.listdir(main_path)):
            sub_path = os.path.join(main_path, sub_name)
            if os.path.isdir(sub_path):
                images = [
                    f for f in os.listdir(sub_path)
                    if f.lower().endswith(IMAGE_EXTENSIONS)
                ]
                annotated_count = sum(
                    os.path.exists(os.path.join(sub_path, os.path.splitext(image)[0] + '.json'))
                    for image in images
                )
                subfolders.append({
                    'name': sub_name,
                    'count': len(images),
                    'annotated_count': annotated_count,
                })
                total_images += len(images)

        main_folders.append({
            'name': main_name,
            'subfolders': subfolders,
            'total_images': total_images,
            'total_annotated': sum(folder['annotated_count'] for folder in subfolders),
            'sub_count': len(subfolders),
        })

    return main_folders


def list_projects(data_dir):
    if not os.path.exists(data_dir):
        return []

    projects = []
    for name in sorted(os.listdir(data_dir)):
        path = os.path.join(data_dir, name)
        if os.path.isdir(path):
            projects.append(name)
    return sorted(projects)


def list_subfolders(data_dir, main_folder):
    main_path = os.path.join(data_dir, main_folder)
    if not os.path.exists(main_path):
        return []

    subfolders = []
    for name in sorted(os.listdir(main_path)):
        sub_path = os.path.join(main_path, name)
        if os.path.isdir(sub_path):
            images = [
                f for f in os.listdir(sub_path)
                if f.lower().endswith(IMAGE_EXTENSIONS)
            ]
            annotated_count = sum(
                os.path.exists(os.path.join(sub_path, os.path.splitext(image)[0] + '.json'))
                for image in images
            )
            subfolders.append({
                'name': name,
                'count': len(images),
                'annotated_count': annotated_count,
            })
    return subfolders


def list_images(data_dir, main_folder, subfolder):
    path = project_path(data_dir, main_folder, subfolder)
    if not os.path.exists(path):
        return []

    images = []
    for filename in sorted(os.listdir(path)):
        if filename.lower().endswith(IMAGE_EXTENSIONS):
            json_name = os.path.splitext(filename)[0] + '.json'
            has_json = os.path.exists(os.path.join(path, json_name))
            images.append({'name': filename, 'processed': has_json})
    return images


def list_labels(data_dir, main_folder, subfolder=None):
    if subfolder is None:
        path = os.path.join(data_dir, main_folder)
    else:
        path = project_path(data_dir, main_folder, subfolder)

    if not os.path.exists(path):
        return []

    labels = set()
    for filename in os.listdir(path):
        if not filename.endswith('.json'):
            continue
        try:
            with open(os.path.join(path, filename), 'r', encoding='utf-8') as f:
                data = json.load(f)
            for shape in data.get('shapes', []):
                if shape.get('label'):
                    labels.add(shape['label'])
        except Exception:
            pass

    return sorted(list(labels))


def get_label_colors(data_dir, main_folder, subfolder):
    """Return the valid, project-level label color preferences."""
    path = os.path.join(project_path(data_dir, main_folder, subfolder), LABEL_COLORS_FILENAME)
    if not os.path.exists(path):
        return {}

    try:
        with open(path, 'r', encoding='utf-8') as f:
            colors = json.load(f)
    except (OSError, json.JSONDecodeError):
        return {}

    if not isinstance(colors, dict):
        return {}
    return {
        label: color
        for label, color in colors.items()
        if isinstance(label, str) and isinstance(color, str) and color.upper() in LABEL_COLORS
    }


def save_label_color(data_dir, main_folder, subfolder, label, color):
    """Save one label's project-level color preference."""
    if not isinstance(label, str) or not label.strip():
        raise ValueError('Label is required')
    if not isinstance(color, str) or color.upper() not in LABEL_COLORS:
        raise ValueError('Unsupported label color')

    colors = get_label_colors(data_dir, main_folder, subfolder)
    colors[label] = color.upper()
    path = os.path.join(project_path(data_dir, main_folder, subfolder), LABEL_COLORS_FILENAME)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(colors, f, indent=2, ensure_ascii=False, sort_keys=True)
    return colors


def save_annotation(data_dir, main_folder, subfolder, filename, json_data):
    json_filename = os.path.splitext(filename)[0] + '.json'
    save_path = os.path.join(
        project_path(data_dir, main_folder, subfolder),
        json_filename,
    )

    with open(save_path, 'w', encoding='utf-8') as f:
        json.dump(json_data, f, indent=2, ensure_ascii=False)


def create_empty_jsons(data_dir, main_folder, subfolder):
    path = project_path(data_dir, main_folder, subfolder)
    if not os.path.exists(path):
        return None

    created = 0
    skipped = 0
    errors = []

    for fname in os.listdir(path):
        if not fname.lower().endswith(IMAGE_EXTENSIONS):
            continue

        json_name = os.path.splitext(fname)[0] + '.json'
        json_path = os.path.join(path, json_name)

        if os.path.exists(json_path):
            skipped += 1
            continue

        img_path = os.path.join(path, fname)
        try:
            if HAS_PIL:
                with Image.open(img_path) as img:
                    w, h = img.size
            else:
                w, h = 0, 0
        except Exception:
            errors.append(fname)
            continue

        json_data = {
            "version": "5.2.1",
            "flags": {},
            "shapes": [],
            "imagePath": fname,
            "imageData": None,
            "imageHeight": h,
            "imageWidth": w,
        }

        try:
            with open(json_path, 'w', encoding='utf-8') as f:
                json.dump(json_data, f, indent=2, ensure_ascii=False)
            created += 1
        except Exception:
            errors.append(fname)

    return {
        'status': 'success',
        'created': created,
        'skipped': skipped,
        'errors': len(errors),
    }
