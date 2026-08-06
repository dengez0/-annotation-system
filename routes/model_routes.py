from flask import Blueprint, jsonify, request

from runtime import MODELS_DIR
from services.model_service import (
    delete_model as delete_model_file,
    list_models as list_model_files,
    save_model_file,
)

model_bp = Blueprint('models', __name__)


@model_bp.route('/api/models', methods=['GET'])
def list_models():
    return jsonify(list_model_files(MODELS_DIR))


@model_bp.route('/api/upload_model', methods=['POST'])
def upload_model():
    if 'model_file' not in request.files:
        return jsonify({'error': 'No file part'}), 400

    result, status = save_model_file(
        MODELS_DIR,
        request.files['model_file'],
        custom_name=request.form.get('custom_name'),
    )
    return jsonify(result), status


@model_bp.route('/api/delete_model/<model_name>', methods=['DELETE'])
def delete_model(model_name):
    result, status = delete_model_file(MODELS_DIR, model_name)
    return jsonify(result), status
