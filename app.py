import logging
import os
import socket
import sys
import time

from flask import Flask, request

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CACHE_DIR = os.path.join(BASE_DIR, '.cache')
os.makedirs(os.path.join(CACHE_DIR, 'ultralytics'), exist_ok=True)
os.makedirs(os.path.join(CACHE_DIR, 'matplotlib'), exist_ok=True)
os.environ.setdefault('YOLO_CONFIG_DIR', os.path.join(CACHE_DIR, 'ultralytics'))
os.environ.setdefault('MPLCONFIGDIR', os.path.join(CACHE_DIR, 'matplotlib'))

from routes import register_blueprints
from runtime import ensure_runtime_dirs

app = Flask(__name__)
# app.config['MAX_CONTENT_LENGTH'] = 16 * 1024 * 1024 * 1024

LOG_DIR = os.path.join(BASE_DIR, 'logs')


def configure_logging():
    os.makedirs(LOG_DIR, exist_ok=True)

    app_logger = logging.getLogger('simplelabel')
    app_logger.setLevel(logging.INFO)
    app_logger.propagate = False

    if not app_logger.handlers:
        formatter = logging.Formatter(
            '[%(asctime)s] %(levelname)s %(message)s',
            datefmt='%Y-%m-%d %H:%M:%S',
        )

        console_handler = logging.StreamHandler(sys.stdout)
        console_handler.setLevel(logging.INFO)
        console_handler.setFormatter(
            logging.Formatter('[%(asctime)s] %(message)s', datefmt='%Y-%m-%d %H:%M:%S')
        )

        file_handler = logging.FileHandler(os.path.join(LOG_DIR, 'server.log'), encoding='utf-8')
        file_handler.setLevel(logging.INFO)
        file_handler.setFormatter(formatter)

        app_logger.addHandler(console_handler)
        app_logger.addHandler(file_handler)

    logging.getLogger('ultralytics').setLevel(logging.WARNING)
    return app_logger


app_logger = configure_logging()


@app.before_request
def log_request_start():
    request._start_time = time.time()


@app.after_request
def log_request(response):
    duration = (time.time() - getattr(request, '_start_time', time.time())) * 1000
    app_logger.info(
        '%s %s %s %d %.0fms',
        request.method,
        request.path,
        request.remote_addr,
        response.status_code,
        duration,
    )
    return response


ensure_runtime_dirs()
register_blueprints(app)


if __name__ == '__main__':
    logging.basicConfig(
        stream=sys.stdout,
        level=logging.INFO,
        format='[%(asctime)s] %(message)s',
        datefmt='%Y-%m-%d %H:%M:%S',
    )
    try:
        local_ip = socket.gethostbyname(socket.gethostname())
    except Exception:
        local_ip = '0.0.0.0'
    port = 18083
    print(f"\n{'=' * 50}")
    print("  SimpleLabel Server starting...")
    print(f"  Local:   http://127.0.0.1:{port}")
    print(f"  Network: http://{local_ip}:{port}")
    print("  Logs:    logs/server.log")
    print("  Press Ctrl+C to stop")
    print(f"{'=' * 50}\n")
    app.run(host='0.0.0.0', port=port, debug=False)
