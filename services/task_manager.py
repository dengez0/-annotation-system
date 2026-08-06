import threading
import uuid
from concurrent.futures import ThreadPoolExecutor


class TaskManager:
    def __init__(self, max_workers=3):
        self.tasks = {}
        self.lock = threading.Lock()
        self.executor = ThreadPoolExecutor(max_workers=max_workers)

    def create_task(self, **extra):
        task_id = str(uuid.uuid4())
        task_data = {
            'status': 'running',
            'progress': 0,
            'total': 0,
            'processed_count': 0,
            'cancel': False,
            'error': None,
        }
        task_data.update(extra)
        with self.lock:
            self.tasks[task_id] = task_data
        return task_id, task_data

    def get_task(self, task_id):
        with self.lock:
            task = self.tasks.get(task_id)
            return dict(task) if task else None

    def cancel_task(self, task_id):
        with self.lock:
            task = self.tasks.get(task_id)
            if not task:
                return False
            task['cancel'] = True
            task['status'] = 'cancelled'
            return True

    def submit(self, fn, *args, **kwargs):
        return self.executor.submit(fn, *args, **kwargs)

    def update(self, task_id, **changes):
        with self.lock:
            task = self.tasks.get(task_id)
            if task:
                task.update(changes)
            return task
