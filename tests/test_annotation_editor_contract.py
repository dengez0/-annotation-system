import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
EDITOR = ROOT / "static" / "js" / "editor.js"


class AnnotationEditorContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.editor = EDITOR.read_text(encoding="utf-8")
        cls.save_current = cls.editor.split("async function saveCurrent()", 1)[1].split(
            "async function createEmptyJsons()", 1
        )[0]

    def test_save_response_marks_the_saved_filename_not_the_current_selection(self):
        self.assertIn("const savedIndex = images.findIndex(item => item.name === filename);", self.save_current)
        self.assertIn("images[savedIndex].processed = true;", self.save_current)
        self.assertIn("'file-item-' + savedIndex", self.save_current)
        self.assertNotIn("'file-item-' + currentImageIndex", self.save_current)


if __name__ == "__main__":
    unittest.main()
