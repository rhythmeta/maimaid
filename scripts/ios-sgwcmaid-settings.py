#!/usr/bin/env python3
"""Convert the MAIMAID_SGWCMAID JSON secret into Xcode build settings.

The Android build consumes this secret directly as a JSON environment
variable. The iOS build takes the same deployment payload but exposes it to
Xcode as individual build settings, which are substituted into Info.plist at
archive time.

Two output formats are supported:

* ``xcodebuild``: one ``NAME=value`` line per setting, suitable for passing
  directly to xcodebuild.
* ``xcconfig``: lines suitable for ``Config/Secrets.xcconfig``; URLs escape
  ``//`` as ``/$()/`` because xcconfig treats ``//`` as a comment.

The secret is read from the environment so values never appear on the command
line or in a workflow log.
"""

from __future__ import annotations

import argparse
import json
import os
import sys

MAPPING = {
    "chimeUrl": "SGWCMAID_CHIME_URL",
    "gameServer": "SGWCMAID_GAME_SERVER",
    "keychip": "SGWCMAID_KEYCHIP",
    "aesKey": "SGWCMAID_AES_KEY",
    "aesIv": "SGWCMAID_AES_IV",
    "apiSalt": "SGWCMAID_API_SALT",
    "chimeSecret": "SGWCMAID_CHIME_SECRET",
    "titleKey": "SGWCMAID_TITLE_KEY",
    "placeId": "SGWCMAID_PLACE_ID",
    "regionId": "SGWCMAID_REGION_ID",
}

REQUIRED = tuple(MAPPING)


def xcconfig_value(value: str) -> str:
    """Escape a value for use on the right-hand side of an xcconfig file."""
    return value.replace("//", "/$()/")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--format",
        choices=("xcodebuild", "xcconfig"),
        default="xcodebuild",
        help="Output format (default: xcodebuild).",
    )
    args = parser.parse_args()

    raw = os.environ.get("MAIMAID_SGWCMAID", "").strip()
    if not raw:
        return 0

    try:
        config = json.loads(raw)
    except json.JSONDecodeError as error:
        print(f"Invalid MAIMAID_SGWCMAID JSON: {error}", file=sys.stderr)
        return 1
    if not isinstance(config, dict):
        print("Invalid MAIMAID_SGWCMAID JSON: expected an object", file=sys.stderr)
        return 1

    missing = [key for key in REQUIRED if not config.get(key)]
    if missing:
        print(
            "MAIMAID_SGWCMAID is missing required keys: " + ", ".join(missing),
            file=sys.stderr,
        )
        return 1

    for key, setting in MAPPING.items():
        value = config.get(key)
        if value is None or value == "":
            continue
        text = str(value)
        if args.format == "xcconfig":
            print(f"{setting} = {xcconfig_value(text)}")
        else:
            print(f"{setting}={text}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
