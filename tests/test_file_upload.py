import base64
import io
import json
import os
import tempfile
import unittest

from services.file_upload import decode_upload_manifest, upload_files


class MemoryUpload:
    def __init__(self, filename, content=b'image'):
        self.filename = filename
        self.content = content

    def save(self, destination):
        with open(destination, 'wb') as output:
            output.write(self.content)


def encode_manifest(value):
    payload = json.dumps(value, ensure_ascii=False).encode('utf-8')
    return base64.b64encode(payload).decode('ascii')


class UploadFilesTest(unittest.TestCase):
    def test_utf8_manifest_preserves_chinese_names(self):
        manifest = {
            'main_folder': '疲劳驾驶',
            'subfolder': '夜间 样本',
            'paths': ['原始目录/内层/中文 图片.jpg'],
        }
        with tempfile.TemporaryDirectory() as data_dir:
            result = upload_files(
                data_dir,
                [MemoryUpload('ignored-header-name.jpg')],
                'ignored',
                'ignored',
                manifest_raw=encode_manifest(manifest),
            )
            destination = os.path.join(
                data_dir, '疲劳驾驶', '夜间 样本', '内层', '中文 图片.jpg'
            )
            self.assertEqual(
                result,
                {
                    'status': 'success',
                    'count': 1,
                    'main_folder': '疲劳驾驶',
                    'subfolder': '夜间 样本',
                },
            )
            self.assertTrue(os.path.isfile(destination))

    def test_manifest_is_strict_utf8(self):
        invalid = base64.b64encode(b'\xff').decode('ascii')
        with self.assertRaisesRegex(ValueError, 'UTF-8 upload manifest'):
            decode_upload_manifest(invalid)

    def test_rejects_path_traversal(self):
        manifest = {
            'main_folder': 'project',
            'subfolder': 'dataset',
            'paths': ['root/../outside.jpg'],
        }
        with tempfile.TemporaryDirectory() as data_dir:
            with self.assertRaisesRegex(ValueError, 'Invalid'):
                upload_files(
                    data_dir,
                    [MemoryUpload('outside.jpg')],
                    'ignored',
                    'ignored',
                    manifest_raw=encode_manifest(manifest),
                )

    def test_rejects_manifest_file_count_mismatch(self):
        manifest = {'main_folder': 'p', 'subfolder': 's', 'paths': []}
        with tempfile.TemporaryDirectory() as data_dir:
            with self.assertRaisesRegex(ValueError, 'does not match'):
                upload_files(
                    data_dir,
                    [MemoryUpload('one.jpg')],
                    'ignored',
                    'ignored',
                    manifest_raw=encode_manifest(manifest),
                )


if __name__ == '__main__':
    unittest.main()
