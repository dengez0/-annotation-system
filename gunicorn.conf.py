# Gunicorn configuration for SimpleLabel
# Usage: gunicorn -c gunicorn.conf.py app:app

import multiprocessing
import os

# === Server Socket ===
bind = "0.0.0.0:18083"
backlog = 2048

# === Worker Processes ===
# Recommended: (2 * CPU) + 1, or fixed count for small teams
workers = 4
worker_class = "sync"
threads = 2  # Threads per worker (to handle file I/O without blocking)
timeout = 600  # 10 minutes — large video uploads may take long
graceful_timeout = 30
keepalive = 5

# === Process Naming ===
proc_name = "simplelabel"

# === Logging ===
accesslog = os.path.join(os.path.dirname(os.path.abspath(__file__)), "logs", "access.log")
errorlog = os.path.join(os.path.dirname(os.path.abspath(__file__)), "logs", "error.log")
loglevel = "info"
access_log_format = '%(h)s %(l)s %(u)s %(t)s "%(r)s" %(s)s %(b)s "%(f)s" "%(a)s" %(D)s'

# === Security / Limits ===
limit_request_line = 4096
limit_request_fields = 100
limit_request_field_size = 8190

# === Restart ===
max_requests = 1000       # Restart workers after 1000 requests to prevent memory leaks
max_requests_jitter = 100 # Random jitter to avoid all workers restarting at once
preload_app = False       # False to allow model loading per-worker (avoids GPU sharing issues)

# === Development ===
reload = False  # Set to True for development auto-reload (do NOT use in production)
