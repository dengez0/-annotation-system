import json
import os
import tempfile
import unittest

from services.yolo_export import export_yolo_annotations, validate_yolo_label_order


class YoloExportOrderTest(unittest.TestCase):
    def test_export_uses_submitted_label_order_for_classes_and_ids(self):
        with tempfile.TemporaryDirectory() as root:
            project = os.path.join(root, 'data', 'main', 'project')
            output_dir = os.path.join(root, 'labels', 'project_labels')
            os.makedirs(project)
            with open(os.path.join(project, 'frame.jpg'), 'wb') as image:
                image.write(b'not-read-when-json-has-dimensions')
            with open(os.path.join(project, 'frame.json'), 'w', encoding='utf-8') as annotation:
                json.dump({
                    'imageWidth': 100,
                    'imageHeight': 100,
                    'shapes': [
                        {'label': 'alpha', 'points': [[10, 10], [30, 30]]},
                        {'label': '中文 标签', 'points': [[40, 40], [80, 80]]},
                    ],
                }, annotation, ensure_ascii=False)

            result = export_yolo_annotations(project, ['中文 标签', 'alpha'], output_dir)

            self.assertEqual(result['class_count'], 2)
            self.assertEqual(result['labels_dir'], 'labels/project_labels')
            self.assertFalse(os.path.exists(os.path.join(project, 'labels')))
            with open(os.path.join(output_dir, 'classes.txt'), encoding='utf-8') as classes:
                self.assertEqual(classes.read(), '中文 标签\nalpha\n')
            with open(os.path.join(output_dir, 'frame.txt'), encoding='utf-8') as labels:
                lines = labels.read().splitlines()
            self.assertTrue(lines[0].startswith('1 '))
            self.assertTrue(lines[1].startswith('0 '))

    def test_existing_root_destination_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as root:
            project = os.path.join(root, 'data', 'main', 'project')
            output_dir = os.path.join(root, 'labels', 'project_labels')
            os.makedirs(project)
            os.makedirs(output_dir)
            with open(os.path.join(output_dir, 'existing.txt'), 'w', encoding='utf-8') as existing:
                existing.write('keep me')

            with self.assertRaises(FileExistsError):
                export_yolo_annotations(project, ['alpha'], output_dir)

            with open(os.path.join(output_dir, 'existing.txt'), encoding='utf-8') as existing:
                self.assertEqual(existing.read(), 'keep me')

    def test_requires_each_project_label_exactly_once(self):
        validate_yolo_label_order(['beta', 'alpha'], ['alpha', 'beta'])

        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            validate_yolo_label_order(['alpha', 'alpha'], ['alpha', 'beta'])
        with self.assertRaisesRegex(ValueError, 'incomplete or out of date'):
            validate_yolo_label_order(['alpha'], ['alpha', 'beta'])
        with self.assertRaisesRegex(ValueError, 'incomplete or out of date'):
            validate_yolo_label_order(['alpha', 'unknown'], ['alpha', 'beta'])
        with self.assertRaisesRegex(ValueError, 'array of strings'):
            validate_yolo_label_order(['alpha', 2], ['alpha', 'beta'])


if __name__ == '__main__':
    unittest.main()
