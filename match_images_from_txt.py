#!/usr/bin/env python3
"""Copy images whose filenames are listed in a TXT file.

Example:
    python match_images_from_txt.py names.txt ./images ./matched --recursive
"""

from __future__ import annotations

import argparse
import re
import shutil
import sys
from pathlib import Path


IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".bmp", ".webp", ".tif", ".tiff"}
IMAGE_NAME_RE = re.compile(
    r"(?i)(?<!\S)(?:[\"'])?(.+?\.(?:jpe?g|png|bmp|webp|tiff?))(?:[\"'])?(?=\s|,|;|$)"
)


def read_names(txt_path: Path) -> tuple[list[str], list[str]]:
    """Read image names from a UTF-8 or Chinese-local-encoding TXT file.

    A line may be just ``image.jpg`` or may contain an image name followed by
    other fields, for example ``image.jpg 0 1`` or ``\"folder/image.jpg\",tag``.
    """
    raw = txt_path.read_bytes()
    for encoding in ("utf-8-sig", "utf-8", "gb18030"):
        try:
            text = raw.decode(encoding)
            break
        except UnicodeDecodeError:
            continue
    else:  # Practically unreachable because gb18030 accepts most byte sequences.
        raise ValueError(f"Cannot decode TXT file: {txt_path}")

    names: list[str] = []
    skipped: list[str] = []
    for line_number, raw_line in enumerate(text.splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue

        match = IMAGE_NAME_RE.search(line)
        if match is None:
            skipped.append(f"line {line_number}: {raw_line}")
            continue

        # Keep only the basename, so either "a.jpg" or "subdir/a.jpg" works.
        names.append(Path(match.group(1).replace("\\", "/")).name)
    return names, skipped


def image_index(source: Path, recursive: bool) -> dict[str, Path]:
    files = source.rglob("*") if recursive else source.glob("*")
    index: dict[str, Path] = {}
    for path in files:
        if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS:
            key = path.name.casefold()
            index.setdefault(key, path)
    return index


def main() -> int:
    parser = argparse.ArgumentParser(description="Match image names from a TXT file and copy matching images.")
    parser.add_argument("txt", type=Path, help="TXT file: one image filename per line")
    parser.add_argument("source", type=Path, help="Directory containing source images")
    parser.add_argument("output", type=Path, help="Directory to receive matched images")
    parser.add_argument("--recursive", action="store_true", help="Search source subdirectories")
    parser.add_argument("--move", action="store_true", help="Move files instead of copying them")
    args = parser.parse_args()

    if not args.txt.is_file():
        parser.error(f"TXT file does not exist: {args.txt}")
    if not args.source.is_dir():
        parser.error(f"Source directory does not exist: {args.source}")

    names, skipped = read_names(args.txt)
    index = image_index(args.source, args.recursive)
    args.output.mkdir(parents=True, exist_ok=True)

    matched, missing = [], []
    for name in names:
        source = index.get(name.casefold())
        if source is None:
            missing.append(name)
            continue
        destination = args.output / source.name
        if args.move:
            shutil.move(str(source), str(destination))
        else:
            shutil.copy2(source, destination)
        matched.append(name)

    (args.output / "matched.txt").write_text("\n".join(matched) + ("\n" if matched else ""), encoding="utf-8")
    (args.output / "missing.txt").write_text("\n".join(missing) + ("\n" if missing else ""), encoding="utf-8")
    (args.output / "skipped_lines.txt").write_text(
        "\n".join(skipped) + ("\n" if skipped else ""), encoding="utf-8"
    )
    print(
        f"TXT image names: {len(names)}; source images: {len(index)}; "
        f"matched: {len(matched)}; missing: {len(missing)}; skipped lines: {len(skipped)}; "
        f"output: {args.output.resolve()}"
    )
    if not names:
        print("No image filenames were found in the TXT. Check skipped_lines.txt.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
