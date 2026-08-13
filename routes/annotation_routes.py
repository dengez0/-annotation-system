import os

from flask import Blueprint, jsonify, render_template, request, send_from_directory

from runtime import DATA_DIR
from services.annotation_store import (
    list_images as list_project_images,
    get_label_colors as get_project_label_colors,
    list_labels as list_project_labels,
    list_main_folders,
    list_projects as list_project_names,
    list_subfolders as list_project_subfolders,
    save_annotation as save_annotation_file,
    save_label_color as save_project_label_color,
)
from services.work_logger import write_work_log


annotation_bp = Blueprint('annotation', __name__)


@annotation_bp.route('/')
def home():
    return render_template('home.html')


@annotation_bp.route('/annotation')
def index():
    return render_template('index.html', main_folders=list_main_folders(DATA_DIR), data_processing=False)


@annotation_bp.route('/data-processing')
def data_processing():
    return render_template('index.html', main_folders=list_main_folders(DATA_DIR), data_processing=True)


@annotation_bp.route('/model-detection')
def model_detection():
    return render_template('model_detection.html')


@annotation_bp.route('/api/projects')
def list_projects():
    return jsonify(list_project_names(DATA_DIR))


@annotation_bp.route('/api/subfolders/<main_folder>')
def list_subfolders(main_folder):
    return jsonify(list_project_subfolders(DATA_DIR, main_folder))


@annotation_bp.route('/annotate/<main_folder>/<subfolder>')
def annotate(main_folder, subfolder):
    project_path = os.path.join(DATA_DIR, main_folder, subfolder)
    if not os.path.exists(project_path):
        return "Project not found", 404
    return render_template('annotate.html', main_folder=main_folder, subfolder=subfolder)


@annotation_bp.route('/api/images/<main_folder>/<subfolder>')
def get_images(main_folder, subfolder):
    return jsonify(list_project_images(DATA_DIR, main_folder, subfolder))


@annotation_bp.route('/api/labels/<main_folder>/<subfolder>')
def get_labels(main_folder, subfolder):
    return jsonify(list_project_labels(DATA_DIR, main_folder, subfolder))


@annotation_bp.route('/api/label-colors/<main_folder>/<subfolder>')
def get_label_colors(main_folder, subfolder):
    return jsonify(get_project_label_colors(DATA_DIR, main_folder, subfolder))


@annotation_bp.route('/api/label-colors/<main_folder>/<subfolder>', methods=['PUT'])
def update_label_color(main_folder, subfolder):
    data = request.get_json(silent=True) or {}
    try:
        colors = save_project_label_color(
            DATA_DIR,
            main_folder,
            subfolder,
            data.get('label'),
            data.get('color'),
        )
    except ValueError as exc:
        return jsonify({'error': str(exc)}), 400
    return jsonify({'status': 'success', 'colors': colors})


@annotation_bp.route('/data/<main_folder>/<subfolder>/<path:filename>')
def serve_file(main_folder, subfolder, filename):
    return send_from_directory(os.path.join(DATA_DIR, main_folder, subfolder), filename)


@annotation_bp.route('/api/save/<main_folder>/<subfolder>', methods=['POST'])
def save_annotation(main_folder, subfolder):
    data = request.json
    filename = data.get('filename')
    json_data = data.get('json')

    if not filename or not json_data:
        return jsonify({'error': 'Invalid data'}), 400

    save_annotation_file(DATA_DIR, main_folder, subfolder, filename, json_data)
    shapes = json_data.get('shapes', []) if isinstance(json_data, dict) else []
    write_work_log(
        'SAVE_ANNOTATION',
        request.remote_addr,
        main_folder,
        subfolder,
        target=filename,
        boxes=len(shapes) if isinstance(shapes, list) else 0,
    )
    return jsonify({'status': 'success'})
