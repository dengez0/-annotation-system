import os

from flask import Blueprint, jsonify, request

from runtime import BASE_DIR, DATA_DIR, MODELS_DIR, task_manager
from services.auto_label_llm import HAS_OPENAI, background_auto_label_llm
from services.auto_label_sam3 import background_auto_label_sam3
from services.auto_label_yolo import background_auto_label

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
    return jsonify({'status': 'started', 'task_id': task_id, 'backend': backend})


@auto_label_bp.route('/api/auto_label_llm/<main_folder>/<subfolder>', methods=['POST'])
def auto_label_llm(main_folder, subfolder):
    if not HAS_OPENAI:
        return jsonify({'error': 'OpenAI library not installed. pip install openai'}), 500

    data = request.json
    api_key = data.get('api_key')
    base_url = data.get('base_url')
    model = data.get('model')
    prompt = data.get('prompt')
    sample_project = data.get('sample_project')
    sample_enabled = data.get('sample_enabled', True)

    if not api_key or not base_url or not model:
        return jsonify({'error': 'Missing API configuration'}), 400

    project_path = os.path.join(DATA_DIR, main_folder, subfolder)
    if not os.path.exists(project_path):
        return jsonify({'error': 'Project not found'}), 404

    task_id, _ = task_manager.create_task()
    combined_path = main_folder + '/' + subfolder
    task_manager.submit(
        background_auto_label_llm,
        task_manager,
        task_id,
        DATA_DIR,
        combined_path,
        api_key,
        base_url,
        model,
        prompt,
        sample_project,
        sample_enabled,
    )
    return jsonify({'status': 'started', 'task_id': task_id})


@auto_label_bp.route('/api/auto_label_sam3/<main_folder>/<subfolder>', methods=['POST'])
def auto_label_sam3(main_folder, subfolder):
    data = request.json
    sample_project_name = data.get('sample_project_name')
    model_name = data.get('model_name')
    conf = float(data.get('conf', 0.25))

    if not sample_project_name:
        return jsonify({'error': 'Missing sample_project_name'}), 400

    if not model_name:
        return jsonify({'error': 'Missing model_name'}), 400

    task_id, _ = task_manager.create_task()
    combined_path = main_folder + '/' + subfolder
    task_manager.submit(
        background_auto_label_sam3,
        task_manager,
        task_id,
        DATA_DIR,
        MODELS_DIR,
        combined_path,
        sample_project_name,
        model_name,
        conf,
    )
    return jsonify({'status': 'started', 'task_id': task_id})
