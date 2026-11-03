"""Read-only native PDF/HWPX diagnosis, never a fidelity/activation receipt.

No OCR, network, DB, Qdrant, model, or source writes. Run with a bounded process
timeout. Input identities are checked against caller-supplied SHA256 values.
"""
import hashlib
import json
import re
import sys
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path
import pdfplumber


def normalized(text):
    return re.sub(r"\s+", "", text or "")


def checked(path, expected):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 8 * 1024 * 1024:
        raise ValueError("bounded regular file required")
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise ValueError("exact hash required")
    if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise ValueError("artifact identity changed")
    return path


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    pdf_path = checked(sys.argv[1], sys.argv[2])
    hwpx_path = checked(sys.argv[3], sys.argv[4])
    native_sections = []
    with zipfile.ZipFile(hwpx_path) as archive:
        if len(archive.infolist()) > 512 or sum(i.file_size for i in archive.infolist()) > 16 * 1024 * 1024:
            raise ValueError("archive expansion bound")
        for name in sorted(archive.namelist()):
            if re.fullmatch(r"Contents/section\d+\.xml", name):
                xml = archive.read(name)
                if b"<!DOCTYPE" in xml or b"<!ENTITY" in xml:
                    raise ValueError("external XML declarations rejected")
                root = ET.fromstring(xml)
                native_sections.append("".join(e.text or "" for e in root.iter() if e.tag.endswith("}t")))
    native = normalized("".join(native_sections))
    pages, rows, pdf_text = [], [], []
    with pdfplumber.open(pdf_path) as pdf:
        if not 1 <= len(pdf.pages) <= 100:
            raise ValueError("diagnostic page bound")
        for index, page in enumerate(pdf.pages, 1):
            if len(page.chars) > 50000 or len(page.edges) > 50000:
                raise ValueError("diagnostic native object bound")
            text = page.extract_text() or ""
            pdf_text.append(text)
            tables = page.find_tables()
            pages.append(dict(page=index, chars=len(page.chars), images=len(page.images),
                              curves=len(page.curves), rects=len(page.rects), tables=len(tables)))
            for table in tables:
                rows.append(dict(page=index, bbox=table.bbox,
                                 rows=[[normalized(cell) if cell is not None else None for cell in row]
                                       for row in table.extract()]))
    extracted = normalized("".join(pdf_text))
    # Counters expose missing/extra characters; equality is not semantic/layout proof.
    from collections import Counter
    missing = Counter(native) - Counter(extracted)
    extra = Counter(extracted) - Counter(native)
    mappings = []
    if len(sys.argv) > 5:
        manifest = Path(sys.argv[5])
        if manifest.stat().st_size > 4 * 1024 * 1024:
            raise ValueError("native manifest bound")
        evidence = [json.loads(line) for line in manifest.read_text(encoding="utf-8").splitlines()
                    if line.startswith("{")]
        proofs = [p for p in evidence if p.get("artifactHash") == sys.argv[4]]
        if len(proofs) != 1:
            raise ValueError("exact native artifact proof required")
        for table in proofs[0]["rangeEvidence"]:
            for track in table["tracks"]:
                conditions = [normalized(band["condition"]["text"]) for band in track["bands"]]
                scores = [normalized(band["score"]["text"]) for band in track["bands"]]
                candidates = []
                for table_index, pdf_table in enumerate(rows):
                    for row_index, row in enumerate(pdf_table["rows"][:-1]):
                        if row[1:] == conditions and pdf_table["rows"][-1][1:] == scores:
                            candidates.append(dict(table=table_index, row=row_index, page=pdf_table["page"]))
                mappings.append(dict(sourceTable=table["tablePath"], sourceRow=track["row"],
                                     unit=track["unit"], candidates=candidates))
    print(json.dumps(dict(pdfHash=sys.argv[2], hwpxHash=sys.argv[4], pages=pages,
                          nativeChars=len(native), pdfChars=len(extracted),
                          orderedTextEqual=native == extracted,
                          missingChars=dict(missing), extraChars=dict(extra),
                          tables=rows, rowMappings=mappings,
                          renderedEquivalence=False, legalRoleCertified=False,
                          fidelityReady=False), ensure_ascii=False))


if __name__ == "__main__":
    main()
