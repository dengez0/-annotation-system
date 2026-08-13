from flask import Blueprint, jsonify, render_template, request

from services.admin_access import is_admin_ip
from services.work_log_reader import build_ip_detail, build_overview


work_log_bp = Blueprint('work_logs', __name__)


@work_log_bp.before_request
def require_startup_ip():
    if is_admin_ip(request.remote_addr):
        return None
    if request.path.startswith('/api/'):
        return jsonify({'error': 'Invalid access'}), 403
    return render_template('invalid_access.html'), 403


def _query_filters():
    return {
        'range_name': request.args.get('range', 'today'),
        'date_value': request.args.get('date') or None,
        'ip': request.args.get('ip') or None,
        'project': request.args.get('project') or None,
        'action': request.args.get('action') or None,
    }


@work_log_bp.route('/work-logs')
def work_logs():
    return render_template('work_logs.html')


@work_log_bp.route('/api/work-logs/overview')
def work_log_overview():
    try:
        return jsonify(build_overview(**_query_filters()))
    except ValueError as exc:
        return jsonify({'error': str(exc)}), 400


@work_log_bp.route('/api/work-logs/ip/<worker_ip>')
def work_log_ip_detail(worker_ip):
    filters = _query_filters()
    filters.pop('ip')
    try:
        return jsonify(build_ip_detail(worker_ip, **filters))
    except ValueError as exc:
        return jsonify({'error': str(exc)}), 400
