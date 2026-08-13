import json
import os
import tempfile
import unittest

from services.annotation_store import LABEL_COLORS_FILENAME, get_label_colors, save_label_color


class LabelColorsTest(unittest.TestCase):
    def test_saved_color_is_project_scoped_and_persistent(self):
        with tempfile.TemporaryDirectory() as data_dir:
            project = os.path.join(data_dir, 'main', 'project')
            os.makedirs(project)

            colors = save_label_color(data_dir, 'main', 'project', 'open_eye', '#ff8844')

            self.assertEqual(colors, {'open_eye': '#FF8844'})
            self.assertEqual(get_label_colors(data_dir, 'main', 'project'), colors)
            with open(os.path.join(project, LABEL_COLORS_FILENAME), encoding='utf-8') as f:
                self.assertEqual(json.load(f), colors)

    def test_rejects_colors_outside_the_palette(self):
        with tempfile.TemporaryDirectory() as data_dir:
            os.makedirs(os.path.join(data_dir, 'main', 'project'))
            with self.assertRaisesRegex(ValueError, 'Unsupported'):
                save_label_color(data_dir, 'main', 'project', 'open_eye', '#123456')


if __name__ == '__main__':
    unittest.main()
