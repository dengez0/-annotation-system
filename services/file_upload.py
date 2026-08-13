import base64
import binascii
import json
import os
import tempfile
import unicodedata


def _normalise_name(value):
    if not isinstance(value, str):
        raise ValueError('Upload names must be strings')
    return unicodedata.normalize('NFC', value)


def _safe_component(value, fallback=None):
    value = _normalise_name(value)
    if not value and fallback is not None:
        value = fallback
    if not value or value in ('.', '..') or '/' in value or '\\' in value or '\x00' in value:
        raise ValueError(f'Invalid upload path component: {value!r}')
    return value


def decode_upload_manifest(raw):
    if not raw:
        return None
    try:
        payload = base64.b64decode(raw, validate=True).decode('utf-8')
        manifest = json.loads(payload)
    except (binascii.Error, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError('Invalid UTF-8 upload manifest') from exc

    if not isinstance(manifest, dict):
        raise ValueError('Invalid upload manifest')
    paths = manifest.get('paths')
    if not isinstance(paths, list) or not all(isinstance(path, str) for path in paths):
        raise ValueError('Invalid upload paths')
    return {
        'main_folder': _normalise_name(manifest.get('main_folder', '')),
        'subfolder': _normalise_name(manifest.get('subfolder', '')),
        'paths': [_normalise_name(path) for path in paths],
    }


def _safe_project_names(main_folder, subfolder):
    return (
        _safe_component(main_folder or '', 'New_Project'),
        _safe_component(subfolder or '', 'default'),
    )


def _destination_for_upload(target_dir, rel_path, fallback_name):
    rel_path = _normalise_name(rel_path or fallback_name)
    parts = rel_path.replace('\\', '/').split('/')
    if not parts or any(not part or part in ('.', '..') or '\x00' in part for part in parts):
        raise ValueError('Invalid relative upload path')

    if len(parts) < 2:
        filename = _safe_component(parts[0])
        return target_dir, filename

    # webkitRelativePath starts with the selected folder. The target subfolder
    # already represents that root, so only recreate directories below it.
    inner_dirs = [_safe_component(part) for part in parts[1:-1]]
    if inner_dirs:
        inner_path = os.path.join(*inner_dirs)
        dest_dir = os.path.join(target_dir, inner_path)
    else:
        dest_dir = target_dir

    return dest_dir, _safe_component(parts[-1])


def _atomic_save(file, destination):
    fd, temporary_path = tempfile.mkstemp(prefix='.upload-', dir=os.path.dirname(destination))
    os.close(fd)
    try:
        file.save(temporary_path)
        os.replace(temporary_path, destination)
    finally:
        if os.path.exists(temporary_path):
            os.remove(temporary_path)


def upload_files(
    data_dir,
    files,
    main_folder,
    subfolder,
    file_paths_raw='[]',
    manifest_raw='',
):
    manifest = decode_upload_manifest(manifest_raw)
    if manifest is not None:
        main_folder = manifest['main_folder']
        subfolder = manifest['subfolder']
        file_paths = manifest['paths']
        if len(file_paths) != len(files):
            raise ValueError('Upload manifest does not match the file batch')
    else:
        try:
            file_paths = json.loads(file_paths_raw)
            if not isinstance(file_paths, list):
                file_paths = []
        except (TypeError, json.JSONDecodeError):
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
        _atomic_save(file, os.path.join(dest_dir, filename))
        count += 1

    return {
        'status': 'success',
        'count': count,
        'main_folder': main_folder,
        'subfolder': subfolder,
    }
