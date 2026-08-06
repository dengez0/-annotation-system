import json
import os


def _safe_project_names(main_folder, subfolder):
    main_folder = os.path.basename(main_folder or 'New_Project')
    subfolder = os.path.basename(subfolder or 'default')
    return main_folder or 'New_Project', subfolder or 'default'


def _destination_for_upload(target_dir, rel_path, fallback_name):
    parts = rel_path.replace('\\', '/').split('/')

    if len(parts) < 2:
        filename = os.path.basename(parts[0]) if parts else os.path.basename(fallback_name)
        return target_dir, filename

    inner_dirs = parts[1:-1]
    if inner_dirs:
        inner_path = os.path.join(*[os.path.basename(d) for d in inner_dirs])
        dest_dir = os.path.join(target_dir, inner_path)
    else:
        dest_dir = target_dir

    return dest_dir, os.path.basename(parts[-1])


def upload_files(data_dir, files, main_folder, subfolder, file_paths_raw='[]'):
    try:
        file_paths = json.loads(file_paths_raw)
    except Exception:
        file_paths = []

    main_folder, subfolder = _safe_project_names(main_folder, subfolder)
    target_dir = os.path.join(data_dir, main_folder, subfolder)
    os.makedirs(target_dir, exist_ok=True)

    count = 0
    for index, file in enumerate(files):
        if not file.filename:
            continue

        rel_path = file_paths[index] if index < len(file_paths) else ''
        dest_dir, filename = _destination_for_upload(target_dir, rel_path, file.filename)
        if not filename:
            continue

        os.makedirs(dest_dir, exist_ok=True)
        file.save(os.path.join(dest_dir, filename))
        count += 1

    return {'status': 'success', 'count': count}
