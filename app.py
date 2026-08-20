import logging
import os
from pathlib import Path
import socket
import subprocess
import sys
import time


def _java_build_is_stale(root: Path) -> bool:
    """Return True when the executable JAR is missing or older than its inputs."""
    jar_path = root / 'backend-java' / 'target' / 'simplelabel-java-1.0.0-SNAPSHOT.jar'
    if not jar_path.is_file():
        return True

    jar_mtime = jar_path.stat().st_mtime
    build_inputs = [root / 'backend-java' / 'pom.xml']
    build_inputs.extend(
        path for path in (root / 'backend-java' / 'src').rglob('*') if path.is_file()
    )
    return any(path.stat().st_mtime > jar_mtime for path in build_inputs)


def _run_current_java_server() -> int:
    """Keep ``python app.py`` working as an entry point for the current Java app."""
    root = Path(__file__).resolve().parent
    if os.name != 'nt':
        print(
            'The current SimpleLabel server is the Java application. '
            'On Linux, start it with the deployment service or java -jar.',
            file=sys.stderr,
        )
        return 2

    if _java_build_is_stale(root):
        print('Java sources are newer than the executable JAR; rebuilding first...')
        build_result = subprocess.run(
            ['cmd.exe', '/d', '/c', str(root / 'build_java.bat')],
            cwd=root,
            check=False,
        )
        if build_result.returncode != 0:
            return build_result.returncode

    print('Starting the current Java version (compatibility command: python app.py)...')
    try:
        return subprocess.call(
            [
                'powershell.exe',
                '-NoProfile',
                '-ExecutionPolicy',
                'Bypass',
                '-File',
                str(root / 'scripts' / 'run_java_backend.ps1'),
                '-Mode',
                'Production',
            ],
            cwd=root,
        )
    except KeyboardInterrupt:
        return 130


# ``python app.py`` used to start the retired Flask server. Keep that command as
# a compatibility entry point, but send it to the current Java implementation.
# The environment switch remains available for deliberate legacy debugging.
if __name__ == '__main__' and os.environ.get('SIMPLELABEL_LEGACY_FLASK') != '1':
    raise SystemExit(_run_current_java_server())

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
