import os
import sys
import threading

from flask import Flask, jsonify, request


ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT_DIR not in sys.path:
    sys.path.insert(0, ROOT_DIR)

from services.yolo_backend import create_detector, load_custom_names, run_detector
from services.yolo_result_parser import parse_shapes_from_results, parse_detections_from_results


DATA_DIR = os.path.abspath(os.environ.get('SIMPLELABEL_DATA_DIR', os.path.join(ROOT_DIR, 'data')))
MODELS_DIR = os.path.abspath(os.environ.get('SIMPLELABEL_MODELS_DIR', os.path.join(ROOT_DIR, 'models')))
WORKER_TOKEN = os.environ.get('SIMPLELABEL_YOLO_WORKER_TOKEN', 'local-simplelabel-worker')
WORKER_PORT = int(os.environ.get('SIMPLELABEL_YOLO_WORKER_PORT', '18085'))

app = Flask(__name__)
_cache_lock = threading.Lock()
_detectors = {}
_detector_locks = {}


def _inside(root, path):
    try:
        return os.path.commonpath([root, path]) == root
    except ValueError:
        return False


def _require_token():
    return request.headers.get('X-SimpleLabel-Worker-Token', '') == WORKER_TOKEN


def _safe_model(model_name):
    if not isinstance(model_name, str) or not model_name or os.path.basename(model_name) != model_name:
        raise ValueError('Invalid model name')
    path = os.path.abspath(os.path.join(MODELS_DIR, model_name))
    if not _inside(MODELS_DIR, path) or not os.path.isfile(path):
        raise FileNotFoundError('Model not found')
    return path


def _safe_image(image_path):
    if not isinstance(image_path, str) or not image_path:
        raise ValueError('Invalid image path')
    path = os.path.abspath(image_path)
    if not _inside(DATA_DIR, path) or not os.path.isfile(path):
        raise FileNotFoundError('Image not found')
    return path


def _detector(model_name, model_path, backend, custom_repo):
    custom_repo_path = None
    if custom_repo:
        custom_repo_path = os.path.abspath(custom_repo)
        if not _inside(ROOT_DIR, custom_repo_path):
            raise ValueError('Custom repository must be inside the application root')
    key = (model_path, backend or 'auto', custom_repo_path or '')
    with _cache_lock:
        detector = _detectors.get(key)
        if detector is None:
            detector = create_detector(
                ROOT_DIR, model_path, model_name, backend=backend or 'auto', custom_repo=custom_repo_path
            )
            _detectors[key] = detector
            _detector_locks[key] = threading.Lock()
        return key, detector, _detector_locks[key]


@app.get('/internal/health')
def health():
    return jsonify({
        'status': 'ok',
        'service': 'simplelabel-yolo-worker',
        'device': os.environ.get('SIMPLELABEL_YOLO_DEVICE', 'cpu'),
    })


@app.post('/internal/yolo/infer')
def infer():
    if not _require_token():
        return jsonify({'error': 'Invalid worker token'}), 403
    payload = request.get_json(silent=True) or {}
    try:
        model_name = payload.get('model_name')
        model_path = _safe_model(model_name)
        image_path = _safe_image(payload.get('image_path'))
        confidence = float(payload.get('confidence', 0.25))
        if confidence < 0 or confidence > 1:
            raise ValueError('Confidence must be between 0 and 1')
        key, detector, detector_lock = _detector(
            model_name, model_path, payload.get('backend', 'auto'), payload.get('custom_repo')
        )
        with detector_lock:
            results = run_detector(detector, image_path, confidence)
            custom_names = load_custom_names(MODELS_DIR, model_name)
            shapes, height, width = parse_shapes_from_results(
                detector, results, custom_names=custom_names
            )
        return jsonify({
            'backend': detector['backend'],
            'width': width,
            'height': height,
            'shapes': shapes,
        })
    except FileNotFoundError as exc:
        return jsonify({'error': str(exc)}), 404
    except ValueError as exc:
        return jsonify({'error': str(exc)}), 400
    except Exception as exc:
        return jsonify({'error': str(exc)}), 500


@app.post('/internal/yolo/detect')
def detect():
    if not _require_token():
        return jsonify({'error': 'Invalid worker token'}), 403
    payload = request.get_json(silent=True) or {}
    try:
        model_name = payload.get('model_name')
        model_path = _safe_model(model_name)
        image_path = _safe_image(payload.get('image_path'))
        confidence = float(payload.get('confidence', 0.25))
        iou = float(payload.get('iou', 0.45))
        if not 0 <= confidence <= 1 or not 0 <= iou <= 1:
            raise ValueError('Confidence and IoU must be between 0 and 1')
        key, detector, detector_lock = _detector(model_name, model_path, payload.get('backend', 'auto'), payload.get('custom_repo'))
        with detector_lock:
            if detector['backend'] == 'ultralytics':
                results = detector['model'](image_path, conf=confidence, iou=iou, verbose=False,
                                              device=detector.get('device', 'cpu'))
            else:
                try:
                    detector['model'].conf = confidence
                    detector['model'].iou = iou
                except Exception:
                    pass
                results = detector['model'](image_path)
            detections, width, height = parse_detections_from_results(
                detector, results, custom_names=load_custom_names(MODELS_DIR, model_name))
        return jsonify({'backend': detector['backend'], 'width': width, 'height': height,
                        'detections': detections})
    except FileNotFoundError as exc:
        return jsonify({'error': str(exc)}), 404
    except ValueError as exc:
        return jsonify({'error': str(exc)}), 400
    except Exception as exc:
        return jsonify({'error': str(exc)}), 500


@app.post('/internal/yolo/inspect-onnx')
def inspect_onnx():
    if not _require_token():
        return jsonify({'error': 'Invalid worker token'}), 403
    try:
        model_name = request.get_json(silent=True).get('model_name')
        model_path = _safe_model(model_name)
        if not model_name.lower().endswith('.onnx'):
            raise ValueError('Only ONNX models can be inspected')
        import onnx
        onnx.checker.check_model(onnx.load(model_path))
        return jsonify({'status': 'ok'})
    except FileNotFoundError as exc:
        return jsonify({'error': str(exc)}), 404
    except Exception as exc:
        return jsonify({'error': 'Invalid ONNX model: ' + str(exc)}), 400


if __name__ == '__main__':
    from waitress import serve
    serve(app, host='127.0.0.1', port=WORKER_PORT, threads=3, channel_timeout=600)
