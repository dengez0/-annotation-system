import json
import os

try:
    from PIL import Image
    HAS_PIL = True
except ImportError:
    Image = None
    HAS_PIL = False

from services.constants import IMAGE_EXTENSIONS


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
                subfolders.append({'name': sub_name, 'count': len(images)})
                total_images += len(images)

        main_folders.append({
            'name': main_name,
            'subfolders': subfolders,
            'total_images': total_images,
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
            subfolders.append({'name': name, 'count': len(images)})
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
