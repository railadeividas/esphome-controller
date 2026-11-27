#!/usr/bin/env python3
"""Builds the bundled Material Design Icons files from the npm packages @mdi/font and @mdi/svg.

Usage (each package extracted into its own directory):
    python3 -I tools/mdi/build_index.py <@mdi/font package dir> <@mdi/svg package dir>

Writes:
    app/src/main/res/font/mdi.ttf          the icon font
    app/src/main/assets/mdi/icons.tsv      name, codepoint, deprecated flag, aliases, tags
    app/src/main/assets/mdi/LICENSE        the upstream licence
    app/src/main/assets/mdi/VERSION        the package version
"""
import json
import pathlib
import shutil
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]


def main(font_dir: str, svg_dir: str) -> None:
    font, svg = pathlib.Path(font_dir), pathlib.Path(svg_dir)
    font_version = json.loads((font / "package.json").read_text())["version"]
    svg_version = json.loads((svg / "package.json").read_text())["version"]
    if font_version != svg_version:
        sys.exit(f"Version mismatch: @mdi/font {font_version}, @mdi/svg {svg_version}")

    assets = ROOT / "app/src/main/assets/mdi"
    assets.mkdir(parents=True, exist_ok=True)
    (ROOT / "app/src/main/res/font").mkdir(parents=True, exist_ok=True)
    shutil.copyfile(font / "fonts/materialdesignicons-webfont.ttf", ROOT / "app/src/main/res/font/mdi.ttf")
    shutil.copyfile(font / "LICENSE", assets / "LICENSE")
    (assets / "VERSION").write_text(f"@mdi/font and @mdi/svg {font_version}\n")

    lines = []
    for icon in sorted(json.loads((svg / "meta.json").read_text()), key=lambda i: i["name"]):
        clean = lambda s: s.replace("\t", " ").replace("\n", " ")
        lines.append("\t".join([
            icon["name"],
            icon["codepoint"],
            "1" if icon.get("deprecated") else "0",
            " ".join(clean(a) for a in icon.get("aliases", [])),
            "|".join(clean(t) for t in icon.get("tags", [])),
        ]))
    (assets / "icons.tsv").write_text("\n".join(lines) + "\n")
    print(f"MDI {font_version}: {len(lines)} icons")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
