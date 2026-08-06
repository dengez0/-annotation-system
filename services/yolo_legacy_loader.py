import os
import sys
from contextlib import contextmanager


def find_local_yolov5_repo(base_dir, custom_repo=None):
    candidates = []
    env_repo = os.environ.get('SIMPLELABEL_YOLOV5_DIR')
    if env_repo:
        candidates.append(env_repo)
    if custom_repo:
        candidates.append(custom_repo)

    candidates.extend([
        os.path.join(base_dir, 'yolov5'),
        os.path.join(os.path.dirname(base_dir), 'yolov5'),
        os.path.join(os.path.dirname(os.path.dirname(base_dir)), 'yolov5'),
    ])

    for repo in candidates:
        if not repo:
            continue
        detect_py = os.path.join(repo, 'detect.py')
        model_yolo_py = os.path.join(repo, 'models', 'yolo.py')
        if os.path.exists(detect_py) and os.path.exists(model_yolo_py):
            return repo

    return None


def checkpoint_requires_legacy_models(model_path):
    markers = [b'models.yolo', b'models.common']
    try:
        with open(model_path, 'rb') as f:
            while True:
                chunk = f.read(1024 * 1024)
                if not chunk:
                    break
                if any(marker in chunk for marker in markers):
                    return True
    except Exception:
        pass
    return False


@contextmanager
def isolated_import_context_for_yolov5(base_dir, repo_path=None):
    original_sys_path = list(sys.path)
    saved_modules = {}

    try:
        filtered = []
        base_dir_norm = os.path.normcase(os.path.normpath(base_dir))
        for path in sys.path:
            if not path:
                continue
            try:
                path_norm = os.path.normcase(os.path.normpath(path))
            except Exception:
                path_norm = path
            if path_norm != base_dir_norm:
                filtered.append(path)

        if repo_path:
            filtered.insert(0, repo_path)

        sys.path[:] = filtered

        for key in list(sys.modules.keys()):
            if key == 'models' or key.startswith('models.'):
                saved_modules[key] = sys.modules[key]
                del sys.modules[key]

        yield
    finally:
        sys.path[:] = original_sys_path
        for key in list(sys.modules.keys()):
            if key == 'models' or key.startswith('models.'):
                del sys.modules[key]
        sys.modules.update(saved_modules)
