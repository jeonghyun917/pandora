"""Read-only PDF layout diagnostics. Results are not semantic proof or runtime grounds."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import sys

def owned_row_cells(headers, cells, number):
    if number <= 0 or len(headers) != 4 or any(box is None for box in headers):
        return None
    if any(not math.isfinite(value) for box in headers for value in box):
        return None
    if any(box[0] >= box[2] or box[1] >= box[3] for box in headers):
        return None
    if any(a[2] > b[0] for a, b in zip(headers, headers[1:])):
        return None
    centers = [(box[0] + box[2]) / 2 for box in headers]
    numbered = [cell for cell in cells
                if cell["bbox"][0] <= centers[0] <= cell["bbox"][2]
                and cell["literal"].strip() == str(number)]
    if len(numbered) != 1:
        return None
    row = numbered[0]["bbox"]
    result = []
    for column, center in zip(headers, centers):
        owned = [cell for cell in cells
                 if cell["bbox"][0] <= center <= cell["bbox"][2]
                 and cell["bbox"][0] >= column[0] and cell["bbox"][2] <= column[2]
                 and cell["bbox"][1] <= row[1] and cell["bbox"][3] >= row[3]]
        if len(owned) != 1 or not owned[0]["literal"].strip():
            return None
        result.append(owned[0])
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdf", required=True, type=Path)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--page", required=True, type=int, help="One-based physical PDF page")
    parser.add_argument("--rows", required=True, type=int, nargs="+")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-fA-F]{64}", args.sha256) or args.page <= 0 or any(n <= 0 for n in args.rows):
        parser.error("valid SHA-256 and positive page/row numbers required")
    with args.pdf.open("rb") as source:
        actual_hash = hashlib.file_digest(source, "sha256").hexdigest()
    if actual_hash != args.sha256.lower():
        parser.error("source hash mismatch")
    import pdfplumber
    with pdfplumber.open(args.pdf) as pdf:
        if args.page > len(pdf.pages):
            parser.error("page exceeds source page count")
        page = pdf.pages[args.page - 1]
        expected_header = ["No", "Level 1", "Level 2", "Level 3"]
        candidates = [t for t in page.find_tables() if t.extract()[0] == expected_header]
        if len(candidates) != 1:
            parser.error("source table header absent or ambiguous")
        initial = candidates[0]
        # Only extend observed outer header edges within this detected table's bounds.
        tables = page.crop(initial.bbox).find_tables({
            "explicit_vertical_lines": [initial.bbox[0], initial.bbox[2]]})
        if len(tables) != 1 or tables[0].extract()[0] != expected_header:
            parser.error("bounded source table absent or ambiguous")
        table = tables[0]
        cells = [{"bbox": cell, "literal": (page.crop(cell).extract_text() or "").strip()}
                 for cell in dict.fromkeys(table.cells)]
        rows = [{"number": number, "ownedCells": owned_row_cells(table.rows[0].cells, cells, number)}
                for number in args.rows]
        print(json.dumps({"sourceSha256": actual_hash, "physicalPage": args.page,
                          "tableBounds": initial.bbox, "rows": rows,
                          "diagnosticOnly": True, "semanticProof": False}, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(2)
