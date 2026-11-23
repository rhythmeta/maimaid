#!/usr/bin/env python3
"""Build an expanded YOLO/Ultralytics dataset for maimai song-select screens.

The source NDJSON contains only title boxes.  For square 640x640 captures, the
remaining regions are emitted as reviewable template annotations anchored to
the existing title box.  The script deliberately leaves non-square captures
title-only because their camera perspective and rotation vary too much for a
single template.  External UTAGE samples use explicit, hand-authored boxes and
are recorded in sources.json.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


CLASS_NAMES = [
    "achievement",
    "ap",
    "app",
    "difficulty",
    "dx",
    "dxscore",
    "fc",
    "fcp",
    "fdx",
    "fdxp",
    "fs",
    "fsp",
    "kanji",
    "lv",
    "maxdxscore",
    "std",
    "sync",
    "title",
]
CLASS_INDEX = {name: index for index, name in enumerate(CLASS_NAMES)}


@dataclass(frozen=True)
class Box:
    label: str
    cx: float
    cy: float
    width: float
    height: float
    source: str

    def clipped(self) -> "Box | None":
        left = max(0.0, self.cx - self.width / 2)
        top = max(0.0, self.cy - self.height / 2)
        right = min(1.0, self.cx + self.width / 2)
        bottom = min(1.0, self.cy + self.height / 2)
        width = right - left
        height = bottom - top
        if width <= 0.001 or height <= 0.001:
            return None
        return Box(self.label, (left + right) / 2, (top + bottom) / 2, width, height, self.source)

    def yolo(self) -> str:
        return f"{CLASS_INDEX[self.label]} {self.cx:.6f} {self.cy:.6f} {self.width:.6f} {self.height:.6f}"


def download(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": "maimaidetector-dataset-builder/1.0"})
    with urllib.request.urlopen(request, timeout=60) as response:
        destination.write_bytes(response.read())


def read_source(path: Path) -> tuple[dict, list[dict]]:
    records = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not records or records[0].get("type") != "dataset":
        raise ValueError(f"{path} is not an Ultralytics NDJSON dataset export")
    return records[0], records[1:]


def box_from_values(label: str, values: Iterable[float], source: str) -> Box:
    cx, cy, width, height = values
    return Box(label, float(cx), float(cy), float(width), float(height), source)


def template_boxes(title: Box) -> list[Box]:
    """Return conservative square-screen candidates around the selected card.

    The title strip is the only source truth in v1.1.  These boxes intentionally
    carry an ``auto_template`` source in the manifest so they can be corrected
    in an annotation tool before a production training run.
    """

    cx, cy = title.cx, title.cy
    card_width = min(0.42, max(0.28, title.width * 1.12))
    row_height = max(0.028, min(0.052, title.height * 1.35))
    return [
        Box("difficulty", cx - card_width * 0.10, cy - 0.075, card_width * 0.62, row_height, "auto_template"),
        Box("lv", cx + card_width * 0.31, cy - 0.075, card_width * 0.20, row_height * 1.15, "auto_template"),
        Box("achievement", cx, cy + 0.075, card_width * 0.68, row_height * 1.15, "auto_template"),
        Box("dxscore", cx - card_width * 0.17, cy + 0.116, card_width * 0.30, row_height, "auto_template"),
        Box("maxdxscore", cx + card_width * 0.10, cy + 0.116, card_width * 0.28, row_height, "auto_template"),
        Box("fc", cx + card_width * 0.34, cy + 0.112, card_width * 0.12, row_height * 1.35, "auto_template"),
        Box("fs", cx + card_width * 0.46, cy + 0.112, card_width * 0.12, row_height * 1.35, "auto_template"),
        Box("std", cx - card_width * 0.36, cy - 0.075, card_width * 0.15, row_height, "auto_template"),
    ]


def add_box(boxes: list[Box], label: str, values: Iterable[float], source: str) -> None:
    clipped = box_from_values(label, values, source).clipped()
    if clipped:
        boxes.append(clipped)


def create_utage_sample(root: Path) -> tuple[str, dict, list[Box]]:
    """Download and crop a clear public UTAGE selection-card sample."""

    source_url = "https://i.ytimg.com/vi/5NjgWLWbHYc/maxresdefault.jpg"
    source_page = "https://www.youtube.com/watch?v=5NjgWLWbHYc"
    raw = root / "_sources" / "youtube-5NjgWLWbHYc.jpg"
    if not raw.exists():
        download(source_url, raw)

    from PIL import Image

    output_name = "utage-youtube-5NjgWLWbHYc.jpg"
    output = root / "images" / "train" / output_name
    output.parent.mkdir(parents=True, exist_ok=True)
    if not output.exists():
        Image.open(raw).crop((580, 40, 1180, 640)).save(output, quality=95)

    # Coordinates are relative to the 600x600 crop and were checked visually.
    boxes = [
        Box("title", 0.50, 0.685, 0.25, 0.045, "manual_utage"),
        Box("difficulty", 0.42, 0.535, 0.30, 0.065, "manual_utage"),
        Box("kanji", 0.405, 0.685, 0.045, 0.040, "manual_utage"),
        Box("lv", 0.665, 0.535, 0.13, 0.075, "manual_utage"),
        Box("achievement", 0.415, 0.735, 0.27, 0.038, "manual_utage"),
        Box("dxscore", 0.415, 0.785, 0.18, 0.040, "manual_utage"),
        Box("maxdxscore", 0.585, 0.785, 0.16, 0.040, "manual_utage"),
        Box("fc", 0.695, 0.785, 0.070, 0.075, "manual_utage"),
    ]
    source = {
        "id": output_name,
        "kind": "external_utage",
        "sourceUrl": source_page,
        "assetUrl": source_url,
        "videoId": "5NjgWLWbHYc",
        "licenseStatus": "unknown_public_web_asset",
        "licenseNote": "Public YouTube thumbnail; retain source attribution and verify redistribution rights before publishing the dataset.",
        "crop": [580, 40, 1180, 640],
    }
    return output_name, source, boxes


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=Path("/Users/shiko/Downloads/maimaidetector-v11.ndjson"))
    parser.add_argument("--output", type=Path, default=Path("datasets/maimaidetector-v12-expanded"))
    args = parser.parse_args()

    dataset_meta, records = read_source(args.source)
    root = args.output.resolve()
    if root.exists():
        shutil.rmtree(root)
    for split in ("train", "val", "test"):
        (root / "images" / split).mkdir(parents=True, exist_ok=True)
        (root / "labels" / split).mkdir(parents=True, exist_ok=True)
    source_root = root / "_sources"
    source_root.mkdir(parents=True, exist_ok=True)

    output_records: list[dict] = []
    annotation_sources: dict[str, dict] = {}
    counts = {name: 0 for name in CLASS_NAMES}
    template_count = 0
    review_required = 0

    for index, record in enumerate(records):
        split = record.get("split") or "train"
        filename = f"{index:03d}-{Path(record['file']).name}"
        image_path = root / "images" / split / filename
        download(record["url"], image_path)

        boxes: list[Box] = []
        for values in record.get("annotations", {}).get("boxes", []):
            if len(values) != 5 or int(values[0]) != 0:
                continue
            box = box_from_values("title", values[1:], "source_ndjson").clipped()
            if box:
                boxes.append(box)

        if record["width"] == 640 and record["height"] == 640 and boxes:
            boxes.extend(template_boxes(boxes[0]))
            template_count += 1
        else:
            review_required += 1

        label_path = root / "labels" / split / f"{Path(filename).stem}.txt"
        label_path.write_text("\n".join(box.yolo() for box in boxes) + "\n", encoding="utf-8")
        for box in boxes:
            counts[box.label] += 1
        annotation_sources[filename] = {
            "annotationSources": sorted({box.source for box in boxes}),
            "reviewRequired": record["width"] != 640 or record["height"] != 640,
            "originalFile": record["file"],
            "originalUrl": record["url"],
        }
        output_records.append({
            "type": "image",
            "file": filename,
            "url": record["url"],
            "width": record["width"],
            "height": record["height"],
            "split": split,
            "annotations": {"boxes": [[CLASS_INDEX[b.label], b.cx, b.cy, b.width, b.height] for b in boxes]},
            "annotationSources": sorted({box.source for box in boxes}),
        })

    utage_name, utage_source, utage_boxes = create_utage_sample(root)
    utage_split = "train"
    utage_label = root / "labels" / utage_split / f"{Path(utage_name).stem}.txt"
    utage_label.write_text("\n".join(box.yolo() for box in utage_boxes) + "\n", encoding="utf-8")
    for box in utage_boxes:
        counts[box.label] += 1
    annotation_sources[utage_name] = {
        "annotationSources": ["manual_utage"],
        "reviewRequired": True,
        "sourceId": utage_name,
    }
    output_records.append({
        "type": "image",
        "file": utage_name,
        "url": utage_source["assetUrl"],
        "width": 600,
        "height": 600,
        "split": utage_split,
        "annotations": {"boxes": [[CLASS_INDEX[b.label], b.cx, b.cy, b.width, b.height] for b in utage_boxes]},
        "annotationSources": ["manual_utage"],
    })

    manifest = {"dataset": dataset_meta, "images": output_records}
    (root / "maimaidetector-v12-expanded.ndjson").write_text(
        json.dumps({"type": "dataset", "task": "detect", "name": "maimaidetector v1.2 expanded", "class_names": {str(i): n for i, n in enumerate(CLASS_NAMES)}}, ensure_ascii=False) + "\n"
        + "\n".join(json.dumps(item, ensure_ascii=False) for item in output_records) + "\n",
        encoding="utf-8",
    )
    (root / "sources.json").write_text(json.dumps({"images": annotation_sources, "utage": utage_source}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (root / "data.yaml").write_text(
        "path: .\ntrain: images/train\nval: images/val\ntest: images/test\n"
        f"nc: {len(CLASS_NAMES)}\nnames: {json.dumps(CLASS_NAMES, ensure_ascii=False)}\n",
        encoding="utf-8",
    )
    (root / "README.md").write_text(
        "# maimaidetector v1.2 expanded\n\n"
        "This dataset extends the v1.1 title-only export with song-select regions. "
        "The original title boxes are retained as `source_ndjson`; square 640x640 images use geometry-based `auto_template` candidates and require visual review before production training. "
        "Non-square captures remain title-only because their camera perspective varies.\n\n"
        "UTAGE is represented without a new class: `difficulty`/`kanji`/`title` allow the application to infer UTAGE. "
        "One public YouTube thumbnail crop is included as a manually boxed UTAGE sample. Its redistribution status is unknown; see `sources.json`.\n\n"
        "Classes:\n\n" + "\n".join(f"{i}. `{name}`" for i, name in enumerate(CLASS_NAMES)) + "\n\n"
        f"Generated template images: {template_count}; title-only review images: {review_required}; total images: {len(output_records)}.\n",
        encoding="utf-8",
    )

    archive = root.with_suffix(".zip")
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as handle:
        for path in root.rglob("*"):
            if path.is_file() and "_sources" not in path.parts:
                handle.write(path, path.relative_to(root))
    print(json.dumps({"output": str(root), "archive": str(archive), "counts": counts, "images": len(output_records)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
