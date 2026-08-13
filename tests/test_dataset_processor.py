import json
import os
import tempfile
import unittest

from PIL import Image

from services.dataset_processor import available_labels, validate_label_order


class DatasetProcessorTest(unittest.TestCase):
    def test_mask_is_excluded_from_required_yolo_order(self):
        with tempfile.TemporaryDirectory() as root:
            Image.new('RGB', (8, 8)).save(os.path.join(root, 'sample.jpg'))
            with open(os.path.join(root, 'sample.json'), 'w', encoding='utf-8') as handle:
                json.dump({'shapes': [{'label': 'mask'}, {'label': 'car'}]}, handle)
            self.assertEqual(['car'], available_labels(root))
            validate_label_order(['car'], root)
            with self.assertRaises(ValueError):
                validate_label_order(['mask'], root)
