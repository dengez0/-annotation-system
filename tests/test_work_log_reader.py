import os
import tempfile
import unittest
from datetime import datetime
from unittest.mock import patch

from services import work_log_reader


class WorkLogReaderTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.log_path = os.path.join(self.temp_dir.name, 'ip_work.log')
        self.path_patch = patch.object(work_log_reader, 'WORK_LOG_PATH', self.log_path)
        self.path_patch.start()
        work_log_reader._CACHE_SIGNATURE = None
        work_log_reader._CACHE_EVENTS = []

        with open(self.log_path, 'w', encoding='utf-8') as log_file:
            log_file.write('2026-08-10 09:00:00 | 192.168.77.10 | SAVE_ANNOTATION | project/a | one.jpg | boxes=3 | success\n')
            log_file.write('2026-08-10 09:30:00 | 192.168.77.10 | SAVE_ANNOTATION | project/a | one.jpg | boxes=4 | success\n')
            log_file.write('2026-08-10 10:00:00 | 192.168.77.10 | SAVE_ANNOTATION | project/a | two.jpg | boxes=2 | success\n')
            log_file.write('2026-08-10 11:00:00 | 192.168.77.20 | UPLOAD | project/b | files | count=4 | success\n')
            log_file.write('not a log record\n')
        with open(self.log_path + '.1', 'w', encoding='utf-8') as log_file:
            log_file.write('2026-08-09 09:00:00 | 192.168.77.20 | SAVE_ANNOTATION | project/b | old.jpg | boxes=7 | success\n')

    def tearDown(self):
        self.path_patch.stop()
        work_log_reader._CACHE_SIGNATURE = None
        work_log_reader._CACHE_EVENTS = []
        self.temp_dir.cleanup()

    def test_overview_combines_current_and_rotated_logs(self):
        overview = work_log_reader.build_overview(range_name='all')

        self.assertEqual(overview['summary'], {
            'active_ips': 2, 'images': 3, 'annotated_images': 3,
            'moved_images': 0, 'saves': 4, 'boxes': 16,
        })
        self.assertEqual(overview['ranking'][0]['ip'], '192.168.77.10')
        self.assertEqual(overview['ranking'][0]['images'], 2)
        self.assertEqual(overview['filters']['projects'], ['project/a', 'project/b'])

    def test_ip_detail_honors_filters(self):
        detail = work_log_reader.build_ip_detail(
            '192.168.77.10', range_name='all', project='project/a',
        )

        self.assertEqual(detail['event_count'], 3)
        self.assertEqual(detail['projects'][0]['images'], 2)
        self.assertEqual(detail['projects'][0]['boxes'], 9)
        self.assertEqual(detail['events'][0]['target'], 'two.jpg')

    def test_overview_adds_confirmed_move_counts_to_distinct_annotation_images(self):
        with open(self.log_path, 'a', encoding='utf-8') as log_file:
            log_file.write('2026-08-10 11:30:00 | 192.168.77.10 | MOVE_FILES | project/a | files | count=3 | success\n')
            log_file.write('2026-08-10 11:40:00 | 192.168.77.10 | RESTORE_FILES | project/a | files | count=2 | success\n')

        overview = work_log_reader.build_overview(range_name='all')
        worker = next(item for item in overview['ranking'] if item['ip'] == '192.168.77.10')
        detail = work_log_reader.build_ip_detail('192.168.77.10', range_name='all', project='project/a')

        self.assertEqual(overview['summary']['annotated_images'], 3)
        self.assertEqual(overview['summary']['moved_images'], 3)
        self.assertEqual(overview['summary']['images'], 6)
        self.assertEqual(worker['annotated_images'], 2)
        self.assertEqual(worker['moved_images'], 3)
        self.assertEqual(worker['images'], 5)
        self.assertEqual(detail['projects'][0]['images'], 5)
        self.assertEqual(detail['projects'][0]['moved_images'], 3)

    def test_calendar_ranges_use_full_natural_days(self):
        now = datetime(2026, 8, 10, 12, 0, 0)

        today_events = work_log_reader.filter_events(range_name='today', now=now)
        yesterday_events = work_log_reader.filter_events(range_name='yesterday', now=now)
        seven_day_events = work_log_reader.filter_events(range_name='7d', now=now)
        selected_day_events = work_log_reader.filter_events(
            range_name='date', date_value='2026-08-09', now=now,
        )

        self.assertEqual(len(today_events), 4)
        self.assertEqual(len(yesterday_events), 1)
        self.assertEqual(len(seven_day_events), 5)
        self.assertEqual(len(selected_day_events), 1)
        self.assertEqual(selected_day_events[0]['target'], 'old.jpg')

    def test_calendar_date_must_be_within_last_seven_days(self):
        now = datetime(2026, 8, 10, 12, 0, 0)

        with self.assertRaisesRegex(ValueError, 'within the last 7 days'):
            work_log_reader.filter_events(range_name='date', date_value='2026-08-03', now=now)
        with self.assertRaisesRegex(ValueError, 'Invalid date'):
            work_log_reader.filter_events(range_name='date', date_value='2026-08-10T00:00', now=now)


if __name__ == '__main__':
    unittest.main()
