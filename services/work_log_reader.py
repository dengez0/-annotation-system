import glob
import os
import threading
from collections import defaultdict
from datetime import datetime, timedelta

from services.work_logger import WORK_LOG_PATH


_CACHE_LOCK = threading.Lock()
_CACHE_SIGNATURE = None
_CACHE_EVENTS = []
_TIME_RANGES = {'today', 'yesterday', '7d', 'date', 'all'}


def _log_paths():
    paths = [path for path in glob.glob(WORK_LOG_PATH + '*') if os.path.isfile(path)]
    return sorted(paths)


def _signature(paths):
    signature = []
    for path in paths:
        try:
            stat = os.stat(path)
        except OSError:
            continue
        signature.append((path, stat.st_mtime_ns, stat.st_size))
    return tuple(signature)


def _parse_line(line):
    parts = [part.strip() for part in line.strip().split(' | ')]
    if len(parts) < 6:
        return None
    try:
        timestamp = datetime.strptime(parts[0], '%Y-%m-%d %H:%M:%S')
    except ValueError:
        return None

    event = {
        'timestamp': timestamp,
        'ip': parts[1],
        'action': parts[2],
        'project': parts[3],
        'target': parts[4],
        'count': 0,
        'boxes': 0,
        'destination': None,
    }
    for field in parts[5:-1]:
        key, separator, value = field.partition('=')
        if not separator:
            continue
        if key in {'count', 'boxes'}:
            try:
                event[key] = int(value)
            except ValueError:
                pass
        elif key == 'destination':
            event['destination'] = value
    return event


def _load_events():
    global _CACHE_EVENTS, _CACHE_SIGNATURE
    paths = _log_paths()
    signature = _signature(paths)
    with _CACHE_LOCK:
        if signature == _CACHE_SIGNATURE:
            return list(_CACHE_EVENTS)

        events = []
        for path in paths:
            try:
                with open(path, 'r', encoding='utf-8') as log_file:
                    for line in log_file:
                        event = _parse_line(line)
                        if event:
                            events.append(event)
            except OSError:
                continue
        events.sort(key=lambda event: event['timestamp'])
        _CACHE_SIGNATURE = signature
        _CACHE_EVENTS = events
        return list(events)


def _time_bounds(range_name, now, date_value=None):
    if range_name not in _TIME_RANGES:
        raise ValueError('Invalid time range')

    today_start = now.replace(hour=0, minute=0, second=0, microsecond=0)
    if range_name == 'today':
        return today_start, None
    if range_name == 'yesterday':
        return today_start - timedelta(days=1), today_start
    if range_name == '7d':
        return today_start - timedelta(days=6), None
    if range_name == 'date':
        if not date_value:
            raise ValueError('Date is required')
        try:
            selected_date = datetime.strptime(date_value, '%Y-%m-%d').date()
        except ValueError as exc:
            raise ValueError('Invalid date') from exc

        earliest_date = (today_start - timedelta(days=6)).date()
        if selected_date < earliest_date or selected_date > today_start.date():
            raise ValueError('Date must be within the last 7 days')

        start = datetime.combine(selected_date, datetime.min.time())
        return start, start + timedelta(days=1)
    return None, None


def filter_events(range_name='today', ip=None, project=None, action=None, date_value=None, now=None):
    now = now or datetime.now()
    start, end = _time_bounds(range_name, now, date_value)
    events = _load_events()
    return [
        event for event in events
        if (start is None or event['timestamp'] >= start)
        and (end is None or event['timestamp'] < end)
        and (not ip or event['ip'] == ip)
        and (not project or event['project'] == project)
        and (not action or event['action'] == action)
    ]


def filter_options(range_name='today', date_value=None):
    events = filter_events(range_name=range_name, date_value=date_value)
    return {
        'ips': sorted({event['ip'] for event in events}),
        'projects': sorted({event['project'] for event in events if event['project'] != '-'}),
        'actions': sorted({event['action'] for event in events}),
    }


def _event_payload(event):
    return {
        'timestamp': event['timestamp'].strftime('%Y-%m-%d %H:%M:%S'),
        'ip': event['ip'],
        'action': event['action'],
        'project': event['project'],
        'target': event['target'],
        'count': event['count'],
        'boxes': event['boxes'],
        'destination': event['destination'],
    }


def _image_key(event):
    """Return a project-scoped image identity for annotation-save events."""
    if event['action'] != 'SAVE_ANNOTATION' or not event['target'] or event['target'] == '-':
        return None
    return event['project'], event['target']


def _moved_image_count(event):
    """Use only the confirmed successful image count recorded for move actions."""
    if event['action'] != 'MOVE_FILES':
        return 0
    return max(event['count'], 0)


def build_overview(range_name='today', ip=None, project=None, action=None, date_value=None):
    events = filter_events(
        range_name, ip=ip, project=project, action=action, date_value=date_value
    )
    by_ip = defaultdict(lambda: {
        'annotated_images': set(), 'moved_images': 0,
        'saves': 0, 'boxes': 0, 'last_activity': None,
    })
    annotated_images = set()
    moved_images = 0
    for event in events:
        summary = by_ip[event['ip']]
        image_key = _image_key(event)
        if image_key:
            annotated_images.add(image_key)
            summary['annotated_images'].add(image_key)
            summary['saves'] += 1
            summary['boxes'] += event['boxes']
        moved = _moved_image_count(event)
        if moved:
            moved_images += moved
            summary['moved_images'] += moved
        if summary['last_activity'] is None or event['timestamp'] > summary['last_activity']:
            summary['last_activity'] = event['timestamp']

    ranking = [
        {
            'ip': worker_ip,
            'images': len(data['annotated_images']) + data['moved_images'],
            'annotated_images': len(data['annotated_images']),
            'moved_images': data['moved_images'],
            'saves': data['saves'],
            'boxes': data['boxes'],
            'last_activity': data['last_activity'].strftime('%Y-%m-%d %H:%M:%S'),
        }
        for worker_ip, data in by_ip.items()
    ]
    ranking.sort(
        key=lambda item: (item['images'], item['boxes'], item['saves'], item['last_activity']),
        reverse=True,
    )
    return {
        'summary': {
            'active_ips': len(by_ip),
            'images': len(annotated_images) + moved_images,
            'annotated_images': len(annotated_images),
            'moved_images': moved_images,
            'saves': sum(item['saves'] for item in ranking),
            'boxes': sum(item['boxes'] for item in ranking),
        },
        'ranking': ranking,
        'filters': filter_options(range_name, date_value=date_value),
    }


def build_ip_detail(ip, range_name='today', project=None, action=None, date_value=None, limit=200):
    events = filter_events(
        range_name, ip=ip, project=project, action=action, date_value=date_value
    )
    projects = defaultdict(lambda: {
        'annotated_images': set(), 'moved_images': 0,
        'saves': 0, 'boxes': 0, 'actions': 0,
    })
    for event in events:
        summary = projects[event['project']]
        summary['actions'] += 1
        image_key = _image_key(event)
        if image_key:
            summary['annotated_images'].add(image_key)
            summary['saves'] += 1
            summary['boxes'] += event['boxes']
        summary['moved_images'] += _moved_image_count(event)

    project_summary = [
        {
            'project': name,
            'images': len(summary['annotated_images']) + summary['moved_images'],
            'annotated_images': len(summary['annotated_images']),
            'moved_images': summary['moved_images'],
            'saves': summary['saves'],
            'boxes': summary['boxes'],
            'actions': summary['actions'],
        }
        for name, summary in projects.items()
    ]
    project_summary.sort(
        key=lambda item: (item['images'], item['boxes'], item['saves'], item['actions']),
        reverse=True,
    )
    newest_first = sorted(events, key=lambda event: event['timestamp'], reverse=True)
    return {
        'ip': ip,
        'event_count': len(newest_first),
        'events': [_event_payload(event) for event in newest_first[:limit]],
        'projects': project_summary,
    }
