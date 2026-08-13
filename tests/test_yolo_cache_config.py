import os
import subprocess
import sys


def test_yolo_backend_uses_configured_cache_outside_read_only_release(tmp_path):
    cache = tmp_path / "worker-cache"
    environment = os.environ.copy()
    environment["SIMPLELABEL_CACHE_DIR"] = str(cache)
    code = (
        "import services.yolo_backend as backend; "
        "print(backend.CACHE_DIR); "
        "print(backend.DEFAULT_YOLO_DEVICE)"
    )
    result = subprocess.run(
        [sys.executable, "-c", code],
        cwd=os.path.dirname(os.path.dirname(__file__)),
        env=environment,
        capture_output=True,
        text=True,
        check=True,
    )
    assert str(cache.resolve()) in result.stdout
    assert (cache / "ultralytics").is_dir()
    assert (cache / "matplotlib").is_dir()
    assert result.stdout.strip().endswith("cpu")
