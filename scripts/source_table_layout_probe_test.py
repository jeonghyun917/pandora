import unittest
import subprocess
import sys
from pathlib import Path
from source_table_layout_probe import owned_row_cells


class OwnedRowTests(unittest.TestCase):
    def setUp(self):
        self.headers = [(0, 0, 10, 10), (10, 0, 30, 10), (30, 0, 60, 10), (60, 0, 100, 10)]
        self.cells = [
            {"bbox": (0, 20, 10, 30), "literal": "7"},
            {"bbox": (10, 10, 30, 50), "literal": "시스템 개발"},
            {"bbox": (30, 20, 60, 40), "literal": "개발서비스"},
            {"bbox": (60, 20, 100, 30), "literal": "일반 시스템 개발"},
        ]

    def test_preserves_complete_merged_cell_literals_for_owned_row(self):
        result = owned_row_cells(self.headers, self.cells, 7)
        self.assertIsNotNone(result)
        self.assertEqual([c["literal"] for c in result], ["7", "시스템 개발", "개발서비스", "일반 시스템 개발"])

    def test_keeps_exclusion_in_the_same_cell(self):
        self.cells[3]["literal"] = "시설 구축 (단순 구매는 제외)"
        result = owned_row_cells(self.headers, self.cells, 7)
        self.assertIsNotNone(result)
        self.assertEqual(result[3]["literal"], "시설 구축 (단순 구매는 제외)")

    def test_rejects_cell_covering_only_part_of_numbered_row(self):
        self.cells[2]["bbox"] = (30, 21, 60, 40)
        self.assertIsNone(owned_row_cells(self.headers, self.cells, 7))

    def test_rejects_ambiguous_parent_cell(self):
        self.cells.append({"bbox": (10, 15, 30, 35), "literal": "다른 조건"})
        self.assertIsNone(owned_row_cells(self.headers, self.cells, 7))

    def test_rejects_duplicate_row_number(self):
        self.cells.append({"bbox": (0, 40, 10, 50), "literal": "7"})
        self.assertIsNone(owned_row_cells(self.headers, self.cells, 7))

    def test_rejects_missing_cell_text(self):
        self.cells[2]["literal"] = ""
        self.assertIsNone(owned_row_cells(self.headers, self.cells, 7))

    def test_rejects_reordered_columns(self):
        headers = [self.headers[0], self.headers[2], self.headers[1], self.headers[3]]
        self.assertIsNone(owned_row_cells(headers, self.cells, 7))

    def test_rejects_nonpositive_row_number(self):
        self.cells[0]["literal"] = "0"
        self.assertIsNone(owned_row_cells(self.headers, self.cells, 0))

    def test_rejects_one_cell_borrowed_across_two_columns(self):
        self.cells = self.cells[:2] + [{"bbox": (30, 20, 100, 40), "literal": "복합 항목"}]
        self.assertIsNone(owned_row_cells(self.headers, self.cells, 7))

    def test_rejects_nonfinite_header_geometry(self):
        self.headers[1] = (10, float("nan"), 30, 10)
        self.assertIsNone(owned_row_cells(self.headers, self.cells, 7))

    def test_cli_requires_explicit_source_and_hash(self):
        script = Path(__file__).with_name("source_table_layout_probe.py")
        result = subprocess.run([sys.executable, str(script)], capture_output=True)
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, b"")

    def test_cli_rejects_hash_mismatch_before_pdf_parsing(self):
        script = Path(__file__).with_name("source_table_layout_probe.py")
        result = subprocess.run([sys.executable, str(script), "--pdf", str(script),
                                 "--sha256", "0" * 64, "--page", "1", "--rows", "7"], capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"source hash mismatch", result.stderr)
        self.assertEqual(result.stdout, b"")


if __name__ == "__main__":
    unittest.main()
