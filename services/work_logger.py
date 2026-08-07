import logging
import os
from logging.handlers import RotatingFileHandler

try:
    from concurrent_log_handler import ConcurrentRotatingFileHandler
except ImportError:  # Falls back for development before requirements are installed.
    ConcurrentRotatingFileHandler = None


BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOG_DIR = os.path.join(BASE_DIR, 'logs')
WORK_LOG_PATH = os.path.join(LOG_DIR, 'ip_work.log')
MAX_LOG_BYTES = 10 * 1024 * 1024
BACKUP_COUNT = 30


def _safe_field(value, fallback='-'):
    if value is None:
        return fallback
    text = str(value).replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
    text = text.replace('|', '/').strip()
    return text[:500] or fallback


def _make_handler(path, max_bytes=MAX_LOG_BYTES, backup_count=BACKUP_COUNT):
    if ConcurrentRotatingFileHandler is not None:
        return ConcurrentRotatingFileHandler(
            path,
            mode='a',
            maxBytes=max_bytes,
            backupCount=backup_count,
            encoding='utf-8',
            delay=True,
        )
    return RotatingFileHandler(
        path,
        mode='a',
        maxBytes=max_bytes,
        backupCount=backup_count,
        encoding='utf-8',
        delay=True,
    )


def configure_work_logger():
    os.makedirs(LOG_DIR, exist_ok=True)
    logger = logging.getLogger('simplelabel.ip_work')
    logger.setLevel(logging.INFO)
    logger.propagate = False

    if not logger.handlers:
        handler = _make_handler(WORK_LOG_PATH)
        handler.setLevel(logging.INFO)
        handler.setFormatter(
            logging.Formatter('%(asctime)s | %(message)s', datefmt='%Y-%m-%d %H:%M:%S')
        )
        logger.addHandler(handler)

    return logger


work_logger = configure_work_logger()


def write_work_log(
    action,
    client_ip,
    main_folder=None,
    subfolder=None,
    target=None,
    count=None,
    boxes=None,
    destination=None,
):
    """Write one compact successful work event without request bodies or file contents."""
    try:
        project_parts = [part for part in (main_folder, subfolder) if part]
        project = '/'.join(project_parts) if project_parts else '-'
        fields = [
            _safe_field(client_ip),
            _safe_field(action),
            _safe_field(project),
            _safe_field(target),
        ]
        if count is not None:
            fields.append(f'count={_safe_field(count)}')
        if boxes is not None:
            fields.append(f'boxes={_safe_field(boxes)}')
        if destination:
            fields.append(f'destination={_safe_field(destination)}')
        fields.append('success')
        work_logger.info(' | '.join(fields))
    except Exception:
        # Work statistics are best-effort and must never break annotation operations.
        logging.getLogger('simplelabel').exception('Failed to write IP work log')
