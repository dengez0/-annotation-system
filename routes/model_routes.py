from flask import Blueprint, jsonify, request

from runtime import MODELS_DIR
from services.model_service import (
    delete_model as delete_model_file,
    list_models as list_model_files,
    save_model_file,
)
from services.work_logger import write_work_log

model_bp = Blueprint('models', __name__)


@model_bp.route('/api/models', methods=['GET'])
def list_models():
    return jsonify(list_model_files(MODELS_DIR))


@model_bp.route('/api/upload_model', methods=['POST'])
def upload_model():
    if 'model_file' not in request.files:
        return jsonify({'error': 'No file part'}), 400

    uploaded_file = request.files['model_file']
    custom_name = request.form.get('custom_name')
    result, status = save_model_file(
        MODELS_DIR,
        uploaded_file,
        custom_name=custom_name,
    )
    if status == 200:
        write_work_log(
            'UPLOAD_MODEL',
            request.remote_addr,
            target=custom_name or uploaded_file.filename,
            count=1,
        )
    return jsonify(result), status


@model_bp.route('/api/delete_model/<model_name>', methods=['DELETE'])
def delete_model(model_name):
    result, status = delete_model_file(MODELS_DIR, model_name)
    if status == 200:
        write_work_log('DELETE_MODEL', request.remote_addr, target=model_name, count=1)
    return jsonify(result), status
