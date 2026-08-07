from flask import Blueprint, jsonify, request

from runtime import task_manager
from services.work_logger import write_work_log

task_bp = Blueprint('tasks', __name__)


@task_bp.route('/api/task_status/<task_id>', methods=['GET'])
def get_task_status(task_id):
    task = task_manager.get_task(task_id)
    if not task:
        return jsonify({'error': 'Task not found'}), 404
    return jsonify(task)


@task_bp.route('/api/cancel_task/<task_id>', methods=['POST'])
def cancel_task(task_id):
    if not task_manager.cancel_task(task_id):
        return jsonify({'error': 'Task not found'}), 404
    write_work_log('AUTO_LABEL_CANCEL', request.remote_addr, target=task_id)
    return jsonify({'status': 'success'})
