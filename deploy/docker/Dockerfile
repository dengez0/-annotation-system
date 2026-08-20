FROM nvidia/cuda:11.8.0-cudnn8-devel-ubuntu22.04

ARG DEBIAN_FRONTEND=noninteractive
ARG JDK_URL=https://aka.ms/download-jdk/microsoft-jdk-21-linux-x64.tar.gz

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates curl python3 python3-pip python3-venv tar \
    && rm -rf /var/lib/apt/lists/*

RUN curl --fail --location --retry 3 --output /tmp/jdk.tar.gz "${JDK_URL}" \
    && curl --fail --location --retry 3 --output /tmp/jdk.sha256 "${JDK_URL}.sha256sum.txt" \
    && expected="$(awk 'NR == 1 {print $1}' /tmp/jdk.sha256)" \
    && test -n "${expected}" \
    && printf '%s  %s\n' "${expected}" /tmp/jdk.tar.gz | sha256sum --check --strict - \
    && mkdir -p /opt/java \
    && tar -xzf /tmp/jdk.tar.gz -C /opt/java --strip-components=1 \
    && rm -f /tmp/jdk.tar.gz /tmp/jdk.sha256 \
    && /opt/java/bin/java -version

COPY deploy/ubuntu/requirements-yolo-worker.txt /tmp/requirements-yolo-worker.txt
RUN python3 -m pip install --no-cache-dir --upgrade pip \
    && python3 -m pip install --no-cache-dir \
        --extra-index-url https://download.pytorch.org/whl/cpu \
        --requirement /tmp/requirements-yolo-worker.txt \
    && python3 -m pip uninstall --yes opencv-python opencv-contrib-python \
    && python3 -m pip install --no-cache-dir --force-reinstall --no-deps \
        opencv-python-headless==4.11.0.86 \
    && python3 -c "import cv2, flask, PIL, torch, torchvision, ultralytics, waitress; print('Worker imports: OK'); print('PyTorch:', torch.__version__, 'CUDA:', torch.cuda.is_available())" \
    && rm -f /tmp/requirements-yolo-worker.txt

ENV JAVA_HOME=/opt/java \
    PATH=/opt/java/bin:${PATH} \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    YOLO_CONFIG_DIR=/tmp/simplelabel-cache/ultralytics \
    MPLCONFIGDIR=/tmp/simplelabel-cache/matplotlib \
    TORCH_HOME=/tmp/simplelabel-cache/torch \
    XDG_CACHE_HOME=/tmp/simplelabel-cache

WORKDIR /opt/simplelabel/current

COPY backend-java/target/simplelabel-java-1.0.0-SNAPSHOT.jar backend-java/target/simplelabel-java-1.0.0-SNAPSHOT.jar
COPY static static
COPY services/__init__.py services/yolo_backend.py services/yolo_legacy_loader.py services/yolo_result_parser.py services/
COPY yolo-worker yolo-worker
COPY yolov5 yolov5
COPY deploy/ubuntu/validate_runtime_env.sh deploy/ubuntu/wait_for_worker.sh deploy/ubuntu/
COPY deploy/docker/entrypoint.sh /usr/local/bin/simplelabel-entrypoint

RUN chmod 0755 \
        /usr/local/bin/simplelabel-entrypoint \
        deploy/ubuntu/validate_runtime_env.sh \
        deploy/ubuntu/wait_for_worker.sh

EXPOSE 18083

ENTRYPOINT ["/usr/local/bin/simplelabel-entrypoint"]
