import argparse
import json
import os
from pathlib import Path


def collect_labels(data):
    shapes = data.get("shapes", [])
    labels = set()
    for shape in shapes:
        if isinstance(shape, dict):
            label = shape.get("label")
            if isinstance(label, str):
                labels.add(label)
    return labels


def find_image_paths(base_path, image_exts):
    image_paths = []
    for ext in image_exts:
        candidate = base_path.with_suffix(ext)
        if candidate.exists():
            image_paths.append(candidate)
    return image_paths


def process_directory(target_dir, keep_labels, image_exts, dry_run=False):
    json_files = sorted(target_dir.glob("*.json"))
    scanned = 0
    kept = 0
    deleted_json = 0
    deleted_images = 0
    errors = 0

    for json_file in json_files:
        scanned += 1
        try:
            with open(json_file, "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception as e:
            print(f"读取失败: {json_file.name} -> {e}")
            errors += 1
            continue

        labels_in_file = collect_labels(data)
        has_keep_label = bool(labels_in_file & keep_labels)
        image_paths = find_image_paths(json_file.with_suffix(""), image_exts)

        if has_keep_label:
            kept += 1
            continue

        try:
            if not dry_run:
                json_file.unlink(missing_ok=True)
            deleted_json += 1
        except Exception as e:
            print(f"删除失败: {json_file.name} -> {e}")
            errors += 1
            continue

        for image_path in image_paths:
            try:
                if not dry_run:
                    image_path.unlink(missing_ok=True)
                deleted_images += 1
            except Exception as e:
                print(f"删除失败: {image_path.name} -> {e}")
                errors += 1

    print(f"扫描JSON文件: {scanned}")
    print(f"保留JSON文件: {kept}")
    print(f"删除JSON文件: {deleted_json}")
    print(f"删除图片文件: {deleted_images}")
    print(f"错误数量: {errors}")


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dir", required=True, help="目标目录")
    parser.add_argument(
        "--labels",
        required=True,
        nargs="+",
        help="要保留的标签，可输入一个或多个，例如 --labels mask person",
    )
    parser.add_argument(
        "--image-exts",
        nargs="+",
        default=[".jpg", ".jpeg", ".png", ".bmp", ".webp", ".JPG", ".PNG"],
        help="同名图片扩展名列表",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="只统计不删除",
    )
    return parser.parse_args()


def main():
    args = parse_args()
    target_dir = Path(args.dir)
    if not target_dir.exists() or not target_dir.is_dir():
        print(f"目录不存在: {target_dir}")
        return

    keep_labels = {label.strip() for label in args.labels if label.strip()}
    if not keep_labels:
        print("标签不能为空")
        return

    image_exts = []
    for ext in args.image_exts:
        ext = ext.strip()
        if not ext:
            continue
        if not ext.startswith("."):
            ext = "." + ext
        image_exts.append(ext)

    process_directory(target_dir, keep_labels, image_exts, dry_run=args.dry_run)


if __name__ == "__main__":
    main()
