import os

from flask import Blueprint, jsonify, request

from runtime import BASE_DIR, DATA_DIR, MODELS_DIR, task_manager
from services.auto_label_yolo import background_auto_label
from services.work_logger import write_work_log

auto_label_bp = Blueprint('auto_label', __name__)


@auto_label_bp.route('/api/auto_label/<main_folder>/<subfolder>', methods=['POST'])
def auto_label(main_folder, subfolder):
    data = request.json
    model_name = data.get('model_name')
    conf = float(data.get('conf', 0.25))
    backend = data.get('backend', 'auto')
    custom_repo = data.get('custom_repo')

    if not model_name:
        return jsonify({'error': 'Model name required'}), 400

    model_path = os.path.join(MODELS_DIR, model_name)
    if not os.path.exists(model_path):
        return jsonify({'error': 'Model not found'}), 404

    project_path = os.path.join(DATA_DIR, main_folder, subfolder)
    if not os.path.exists(project_path):
        return jsonify({'error': 'Project path not found'}), 404

    task_id, _ = task_manager.create_task(backend=backend)
    combined_path = main_folder + '/' + subfolder
    task_manager.submit(
        background_auto_label,
        task_manager,
        task_id,
        BASE_DIR,
        DATA_DIR,
        MODELS_DIR,
        combined_path,
        model_name,
        conf,
        backend,
        custom_repo,
    )
    write_work_log(
        'AUTO_LABEL_START',
        request.remote_addr,
        main_folder,
        subfolder,
        target=f'{model_name} ({backend})',
    )
    return jsonify({'status': 'started', 'task_id': task_id, 'backend': backend})
