import os

CACHE_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), '.cache')
os.makedirs(os.path.join(CACHE_DIR, 'ultralytics'), exist_ok=True)
os.makedirs(os.path.join(CACHE_DIR, 'matplotlib'), exist_ok=True)
os.environ.setdefault('YOLO_CONFIG_DIR', os.path.join(CACHE_DIR, 'ultralytics'))
os.environ.setdefault('MPLCONFIGDIR', os.path.join(CACHE_DIR, 'matplotlib'))

from services.yolo_legacy_loader import (
    checkpoint_requires_legacy_models,
    find_local_yolov5_repo,
    isolated_import_context_for_yolov5,
)

DEFAULT_YOLO_DEVICE = os.environ.get('SIMPLELABEL_YOLO_DEVICE', 'cpu')

try:
    from ultralytics import YOLO
    try:
        from ultralytics import RTDETR
    except ImportError:
        RTDETR = None
    HAS_YOLO = True
except ImportError:
    YOLO = None
    RTDETR = None
    HAS_YOLO = False


def load_custom_names(models_dir, model_name):
    custom_names = None
    model_basename = os.path.splitext(model_name)[0]
    txt_path = os.path.join(models_dir, model_basename + '.txt')
    yaml_path = os.path.join(models_dir, model_basename + '.yaml')

    if os.path.exists(txt_path):
        try:
            with open(txt_path, 'r', encoding='utf-8') as f:
                custom_names = [line.strip() for line in f.readlines() if line.strip()]
        except Exception:
            pass
    elif os.path.exists(yaml_path):
        try:
            import yaml
            with open(yaml_path, 'r', encoding='utf-8') as f:
                data = yaml.safe_load(f)
                if isinstance(data, dict) and 'names' in data:
                    custom_names = data['names']
        except Exception:
            pass

    return custom_names


def _create_ultralytics_detector(model_path, model_name, device=DEFAULT_YOLO_DEVICE):
    if not HAS_YOLO:
        raise RuntimeError('ultralytics is not installed')

    if RTDETR and 'rtdetr' in model_name.lower():
        model = RTDETR(model_path)
        model_kind = 'rtdetr'
    else:
        model = YOLO(model_path, task='detect')
        model_kind = 'yolo'

    return {
        'backend': 'ultralytics',
        'model_kind': model_kind,
        'model': model,
        'model_names': getattr(model, 'names', None),
        'device': device,
    }


def _create_yolov5_hub_detector(base_dir, model_path, model_name, custom_repo=None, device=DEFAULT_YOLO_DEVICE):
    try:
        import torch
    except Exception as e:
        raise RuntimeError(f'torch is not available: {e}')

    local_repo = find_local_yolov5_repo(base_dir, custom_repo=custom_repo)
    if local_repo:
        print(f"Loading {model_name} using yolov5_hub(local): {local_repo}")
        with isolated_import_context_for_yolov5(base_dir, repo_path=local_repo):
            model = torch.hub.load(
                local_repo,
                'custom',
                path=model_path,
                source='local',
                force_reload=False,
                device=device,
            )
    else:
        print(f"Loading {model_name} using yolov5_hub(github)")
        with isolated_import_context_for_yolov5(base_dir):
            model = torch.hub.load(
                'ultralytics/yolov5',
                'custom',
                path=model_path,
                force_reload=False,
                trust_repo=True,
                device=device,
            )

    if hasattr(model, 'to'):
        try:
            model.to(device)
        except Exception:
            pass

    return {
        'backend': 'yolov5_hub',
        'model_kind': 'yolov5_custom',
        'model': model,
        'model_names': getattr(model, 'names', None),
        'device': device,
    }


def _ordered_backends(backend, model_path, model_name):
    backend = (backend or 'auto').lower()
    if backend == 'auto':
        if checkpoint_requires_legacy_models(model_path):
            print(f"Detected legacy checkpoint dependencies in {model_name}, prioritize yolov5_hub backend")
            return ['yolov5_hub', 'ultralytics']
        return ['ultralytics', 'yolov5_hub']

    if backend in ('ultralytics', 'yolov5_hub'):
        return [backend]

    raise ValueError(f'Unsupported backend: {backend}. Use auto/ultralytics/yolov5_hub')


def create_detector(base_dir, model_path, model_name, backend='auto', custom_repo=None):
    errors = []
    device = DEFAULT_YOLO_DEVICE
    for candidate in _ordered_backends(backend, model_path, model_name):
        try:
            if candidate == 'ultralytics':
                print(f"Loading {model_name} using ultralytics backend on {device}")
                return _create_ultralytics_detector(model_path, model_name, device=device)
            if candidate == 'yolov5_hub':
                return _create_yolov5_hub_detector(
                    base_dir,
                    model_path,
                    model_name,
                    custom_repo=custom_repo,
                    device=device,
                )
        except Exception as e:
            errors.append(f'{candidate}: {e}')

    raise RuntimeError('Failed to load model with all attempted backends. ' + ' | '.join(errors))


def run_detector(detector, img_path, conf):
    backend = detector['backend']
    model = detector['model']

    if backend == 'ultralytics':
        return model(img_path, conf=conf, verbose=False, device=detector.get('device', DEFAULT_YOLO_DEVICE))

    if backend == 'yolov5_hub':
        try:
            model.conf = conf
        except Exception:
            pass
        return model(img_path)

    raise RuntimeError(f'Unsupported detector backend at runtime: {backend}')
