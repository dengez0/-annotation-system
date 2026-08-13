import os
import tempfile
import unittest

from services.annotation_store import list_main_folders, list_subfolders


class AnnotationStoreCountTest(unittest.TestCase):
    def test_counts_only_images_with_matching_json(self):
        with tempfile.TemporaryDirectory() as root:
            project = os.path.join(root, 'project-a', 'batch-1')
            os.makedirs(project)
            for name in ('annotated.jpg', 'pending.png', 'orphan.json', 'annotated.json'):
                open(os.path.join(project, name), 'w', encoding='utf-8').close()

            subfolder = list_subfolders(root, 'project-a')[0]
            self.assertEqual(2, subfolder['count'])
            self.assertEqual(1, subfolder['annotated_count'])

            main = list_main_folders(root)[0]
            self.assertEqual(2, main['total_images'])
            self.assertEqual(1, main['total_annotated'])
