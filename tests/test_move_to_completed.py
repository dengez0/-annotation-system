import json
import os
import tempfile
import unittest

from services.file_service import move_files_to_completed, restore_files_from_completed


class MoveToCompletedTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.source = os.path.join(self.temp_dir.name, 'annotation flies', 'worker-a')
        os.makedirs(self.source)

    def tearDown(self):
        self.temp_dir.cleanup()

    def _create_pair(self, name):
        with open(os.path.join(self.source, name), 'wb') as image_file:
            image_file.write(b'image')
        with open(os.path.join(self.source, os.path.splitext(name)[0] + '.json'), 'w', encoding='utf-8') as json_file:
            json.dump({'shapes': []}, json_file)

    def test_moves_image_and_annotation_to_matching_subfolder(self):
        self._create_pair('sample.jpg')

        result, status = move_files_to_completed(
            self.temp_dir.name, 'annotation flies', 'worker-a', ['sample.jpg']
        )

        target = os.path.join(self.temp_dir.name, 'moved image', 'worker-a')
        self.assertEqual(status, 200)
        self.assertEqual(result['moved'], 1)
        self.assertFalse(os.path.exists(os.path.join(self.source, 'sample.jpg')))
        self.assertTrue(os.path.isfile(os.path.join(target, 'sample.jpg')))
        self.assertTrue(os.path.isfile(os.path.join(target, 'sample.json')))

    def test_skips_pair_when_destination_has_matching_annotation(self):
        self._create_pair('sample.jpg')
        target = os.path.join(self.temp_dir.name, 'moved image', 'worker-a')
        os.makedirs(target)
        with open(os.path.join(target, 'sample.json'), 'w', encoding='utf-8') as json_file:
            json.dump({'shapes': []}, json_file)

        result, status = move_files_to_completed(
            self.temp_dir.name, 'annotation flies', 'worker-a', ['sample.jpg']
        )

        self.assertEqual(status, 200)
        self.assertEqual(result['moved'], 0)
        self.assertEqual(result['skipped'][0]['name'], 'sample.jpg')
        self.assertTrue(os.path.isfile(os.path.join(self.source, 'sample.jpg')))

    def test_rejects_non_annotation_source(self):
        result, status = move_files_to_completed(
            self.temp_dir.name, 'other source', 'worker-a', ['sample.jpg']
        )

        self.assertEqual(status, 403)
        self.assertIn('only available', result['error'])

    def test_restores_image_and_annotation_to_matching_subfolder(self):
        self._create_pair('sample.jpg')
        move_files_to_completed(self.temp_dir.name, 'annotation flies', 'worker-a', ['sample.jpg'])

        result, status = restore_files_from_completed(self.temp_dir.name, 'worker-a', ['sample.jpg'])

        moved_folder = os.path.join(self.temp_dir.name, 'moved image', 'worker-a')
        self.assertEqual(status, 200)
        self.assertEqual(result['destination'], 'annotation flies/worker-a')
        self.assertEqual(result['moved'], 1)
        self.assertTrue(os.path.isfile(os.path.join(self.source, 'sample.jpg')))
        self.assertTrue(os.path.isfile(os.path.join(self.source, 'sample.json')))
        self.assertFalse(os.path.exists(os.path.join(moved_folder, 'sample.jpg')))
        self.assertFalse(os.path.exists(os.path.join(moved_folder, 'sample.json')))

    def test_restore_skips_when_annotation_destination_exists(self):
        self._create_pair('sample.jpg')
        move_files_to_completed(self.temp_dir.name, 'annotation flies', 'worker-a', ['sample.jpg'])
        with open(os.path.join(self.source, 'sample.json'), 'w', encoding='utf-8') as json_file:
            json.dump({'shapes': [{'label': 'existing'}]}, json_file)

        result, status = restore_files_from_completed(self.temp_dir.name, 'worker-a', ['sample.jpg'])

        moved_folder = os.path.join(self.temp_dir.name, 'moved image', 'worker-a')
        self.assertEqual(status, 200)
        self.assertEqual(result['moved'], 0)
        self.assertEqual(result['skipped'][0]['name'], 'sample.jpg')
        self.assertTrue(os.path.isfile(os.path.join(moved_folder, 'sample.jpg')))
        self.assertTrue(os.path.isfile(os.path.join(moved_folder, 'sample.json')))


if __name__ == '__main__':
    unittest.main()
