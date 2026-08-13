import os

from flask import Blueprint, jsonify, request, send_file

from runtime import BASE_DIR, DATA_DIR, PROCESSED_DIR, task_manager
from services.dataset_processor import available_labels, process_dataset, validate_label_order
from services.annotation_store import create_empty_jsons as create_empty_json_files
from services.annotation_store import list_labels as list_project_labels
from services.file_service import (
    copy_files_to_paste,
    delete_files as delete_project_files,
    delete_project as delete_project_path,
    make_project_archive,
    move_files as move_project_files,
    move_files_to_completed,
    restore_files_from_completed,
    rename_project as rename_project_path,
    upload_files as save_uploaded_files,
)
from services.yolo_export import export_yolo_annotations, validate_yolo_label_order
from services.work_logger import write_work_log

file_bp = Blueprint('files', __name__)


@file_bp.route('/upload', methods=['POST'])
def upload_folder():
    try:
        if 'files[]' not in request.files:
            return jsonify({'error': 'No files part'}), 400

        files = request.files.getlist('files[]')
        main_folder = request.form.get('main_folder', 'New_Project')
        subfolder = request.form.get('subfolder', 'default')
        file_paths = request.form.get('file_paths', '[]')
        upload_manifest = request.form.get('upload_manifest', '')
        result = save_uploaded_files(
            DATA_DIR,
            files,
            main_folder,
            subfolder,
            file_paths,
            upload_manifest,
        )
        write_work_log(
            'UPLOAD',
            request.remote_addr,
            result['main_folder'],
            result['subfolder'],
            target='files',
            count=result['count'],
        )
        return jsonify(result)
    except ValueError as e:
        return jsonify({'error': str(e)}), 400
    except Exception as e:
        print(f"Upload error: {e}")
        return jsonify({'error': str(e)}), 500


@file_bp.route('/api/data_processing/labels/<main_folder>/<subfolder>')
def processing_labels(main_folder, subfolder):
    project_path = os.path.join(DATA_DIR, main_folder, subfolder)
    if not os.path.isdir(project_path):
        return jsonify({'error': 'Project not found'}), 404
    return jsonify({'labels': available_labels(project_path)})


@file_bp.route('/api/data_processing/process/<main_folder>/<subfolder>', methods=['POST'])
def process_uploaded_dataset(main_folder, subfolder):
    project_path = os.path.join(DATA_DIR, main_folder, subfolder)
    if not os.path.isdir(project_path):
        return jsonify({'error': 'Project not found'}), 404
    data = request.get_json(silent=True) or {}
    try:
        labels = data.get('labels', [])
        validate_label_order(labels, project_path)
    except ValueError as exc:
        return jsonify({'error': str(exc)}), 400
    task_id, _ = task_manager.create_task(type='data_processing', stage='queued', main_folder=main_folder, subfolder=subfolder)
    task_manager.submit(process_dataset, task_manager, task_id, DATA_DIR, PROCESSED_DIR, main_folder, subfolder, labels, request.remote_addr, write_work_log)
    return jsonify({'status': 'started', 'task_id': task_id})


@file_bp.route('/api/rename_project', methods=['POST'])
def rename_project():
    data = request.json
    main_folder = data.get('main_folder')
    old_name = data.get('old_name')
    new_name = data.get('new_name')
    level = data.get('level', 'main')

    if not old_name or not new_name:
        return jsonify({'error': 'Missing parameters'}), 400

    result, status = rename_project_path(
        DATA_DIR,
        old_name,
        new_name,
        level=level,
        main_folder=main_folder,
    )
    if status == 200:
        write_work_log(
            'RENAME_PROJECT',
            request.remote_addr,
            main_folder if level == 'sub' else old_name,
            old_name if level == 'sub' else None,
            target=f'{old_name} -> {new_name}',
        )
    return jsonify(result), status


@file_bp.route('/api/delete_project', methods=['POST'])
def delete_project():
    data = request.json
    main_folder = data.get('main_folder')
    subfolder = data.get('subfolder')
    level = data.get('level', 'main')

    if level == 'sub':
        if not main_folder or not subfolder:
            return jsonify({'error': 'Missing parameters'}), 400
    elif not main_folder:
        return jsonify({'error': 'Missing main folder name'}), 400

    result, status = delete_project_path(DATA_DIR, main_folder, subfolder=subfolder, level=level)
    if status == 200:
        write_work_log(
            'DELETE_PROJECT', request.remote_addr, main_folder, subfolder, target=level
        )
    return jsonify(result), status


@file_bp.route('/api/delete_files/<main_folder>/<subfolder>', methods=['POST'])
def delete_files(main_folder, subfolder):
    data = request.json
    filenames = data.get('filenames', [])

    if not filenames:
        return jsonify({'error': 'No files specified'}), 400

    result, status = delete_project_files(DATA_DIR, main_folder, subfolder, filenames)
    if status == 200:
        write_work_log(
            'DELETE_FILES',
            request.remote_addr,
            main_folder,
            subfolder,
            target='files',
            count=result.get('deleted', 0),
        )
    return jsonify(result), status


@file_bp.route('/api/move_files/<main_folder>/<subfolder>', methods=['POST'])
def move_files(main_folder, subfolder):
    data = request.json
    filenames = data.get('filenames', [])
    dest_folder = data.get('dest_folder', '')
    dest_main = data.get('dest_main_folder', '')
    dest_sub = data.get('dest_subfolder', '')

    if not filenames:
        return jsonify({'error': 'No files specified'}), 400

    result, status = move_project_files(
        DATA_DIR,
        main_folder,
        subfolder,
        filenames,
        dest_folder=dest_folder,
        dest_main=dest_main,
        dest_sub=dest_sub,
    )
    if status == 200:
        destination = '/'.join(part for part in (dest_main, dest_sub) if part) or dest_folder
        write_work_log(
            'MOVE_FILES',
            request.remote_addr,
            main_folder,
            subfolder,
            target='files',
            count=result.get('moved', 0),
            destination=destination,
        )
    return jsonify(result), status


@file_bp.route('/api/move_to_completed/<main_folder>/<subfolder>', methods=['POST'])
def move_to_completed(main_folder, subfolder):
    data = request.get_json(silent=True) or {}
    filenames = data.get('filenames', [])
    if not isinstance(filenames, list) or not filenames:
        return jsonify({'error': 'No files specified'}), 400

    result, status = move_files_to_completed(DATA_DIR, main_folder, subfolder, filenames)
    if status == 200:
        write_work_log(
            'MOVE_FILES',
            request.remote_addr,
            main_folder,
            subfolder,
            target='files',
            count=result.get('moved', 0),
            destination=result.get('destination'),
        )
    return jsonify(result), status


@file_bp.route('/api/restore_from_completed/<subfolder>', methods=['POST'])
def restore_from_completed(subfolder):
    data = request.get_json(silent=True) or {}
    filenames = data.get('filenames', [])
    if not isinstance(filenames, list) or not filenames:
        return jsonify({'error': 'No files specified'}), 400

    result, status = restore_files_from_completed(DATA_DIR, subfolder, filenames)
    if status == 200:
        write_work_log(
            'RESTORE_FILES',
            request.remote_addr,
            'moved image',
            subfolder,
            target='files',
            count=result.get('moved', 0),
            destination=result.get('destination'),
        )
    return jsonify(result), status


@file_bp.route('/api/copy_files/<main_folder>/<subfolder>', methods=['POST'])
def copy_files(main_folder, subfolder):
    data = request.json
    filenames = data.get('filenames', [])

    if not filenames:
        return jsonify({'error': 'No files specified'}), 400

    result, status = copy_files_to_paste(DATA_DIR, main_folder, subfolder, filenames)
    if status == 200:
        write_work_log(
            'COPY_FILES',
            request.remote_addr,
            main_folder,
            subfolder,
            target='files',
            count=result.get('copied', 0),
            destination='paste image',
        )
    return jsonify(result), status


@file_bp.route('/api/create_empty_jsons/<main_folder>/<subfolder>', methods=['POST'])
def create_empty_jsons(main_folder, subfolder):
    result = create_empty_json_files(DATA_DIR, main_folder, subfolder)
    if result is None:
        return jsonify({'error': 'Project not found'}), 404
    write_work_log(
        'CREATE_EMPTY_JSONS',
        request.remote_addr,
        main_folder,
        subfolder,
        target='images',
        count=result.get('created', 0),
    )
    return jsonify(result)


@file_bp.route('/api/export_yolo/<main_folder>/<subfolder>', methods=['POST'])
def export_yolo(main_folder, subfolder):
    project_path = os.path.join(DATA_DIR, main_folder, subfolder)
    if not os.path.exists(project_path):
        return jsonify({'error': 'Project not found'}), 404

    try:
        data = request.get_json(silent=True)
        if not isinstance(data, dict):
            raise ValueError('Invalid request body')
        selected_labels = data.get('labels')
        project_labels = list_project_labels(DATA_DIR, main_folder, subfolder)
        validate_yolo_label_order(selected_labels, project_labels)
        if os.path.basename(subfolder) != subfolder or '\\' in subfolder:
            raise ValueError('Invalid project folder name')
        output_dir = os.path.join(BASE_DIR, 'labels', f'{subfolder}_labels')
        result = export_yolo_annotations(project_path, selected_labels, output_dir)
        write_work_log(
            'EXPORT_YOLO',
            request.remote_addr,
            main_folder,
            subfolder,
            target=result['labels_dir'],
            count=result.get('exported', 0),
        )
        return jsonify(result)
    except ValueError as e:
        return jsonify({'error': str(e)}), 400
    except FileExistsError:
        return jsonify({'error': f'Export destination already exists: labels/{subfolder}_labels'}), 409
    except Exception as e:
        return jsonify({'error': str(e)}), 500


@file_bp.route('/api/export/<main_folder>/<subfolder>')
def export_project(main_folder, subfolder):
    project_path = os.path.join(DATA_DIR, main_folder, subfolder)
    if not os.path.exists(project_path):
        return jsonify({'error': 'Project not found'}), 404

    try:
        zip_path = make_project_archive(project_path, subfolder)
        write_work_log(
            'EXPORT_PROJECT', request.remote_addr, main_folder, subfolder, target='zip'
        )
        return send_file(zip_path, as_attachment=True, download_name=f"{subfolder}.zip")
    except Exception as e:
        return jsonify({'error': str(e)}), 500
