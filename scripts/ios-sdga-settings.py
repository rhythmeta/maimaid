#!/usr/bin/env python3
"""Convert the MAIMAID_SDGA JSON secret into Xcode build settings.

The Android build consumes this secret directly as a JSON environment
variable. The iOS build takes the same payload but exposes it to Xcode as
individual build settings, which are substituted into Info.plist at archive
time.

Two output formats are supported:

* ``xcodebuild``: one ``NAME=value`` line per setting.
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
    "titleServerUrl": "SDGA_TITLE_SERVER",
    "aesKey": "SDGA_AES_KEY",
    "aesIv": "SDGA_AES_IV",
    "obfuscateParam": "SDGA_OBFUSCATE_PARAM",
    "commonKey": "SDGA_COMMON_KEY",
    "aimeHost": "SDGA_AIME_HOST",
    "aimePort": "SDGA_AIME_PORT",
    "gameVersion": "SDGA_GAME_VERSION",
    "gameId": "SDGA_GAME_ID",
    "keychip": "SDGA_KEYCHIP",
    "placeId": "SDGA_PLACE_ID",
    "regionId": "SDGA_REGION_ID",
}

REQUIRED = (
    "titleServerUrl",
    "aesKey",
    "aesIv",
    "obfuscateParam",
    "commonKey",
    "aimeHost",
)


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

    raw = os.environ.get("MAIMAID_SDGA", "").strip()
    if not raw:
        return 0

    try:
        config = json.loads(raw)
    except json.JSONDecodeError as error:
        print(f"Invalid MAIMAID_SDGA JSON: {error}", file=sys.stderr)
        return 1
    if not isinstance(config, dict):
        print("Invalid MAIMAID_SDGA JSON: expected an object", file=sys.stderr)
        return 1

    missing = [key for key in REQUIRED if not config.get(key)]
    if missing:
        print("MAIMAID_SDGA is missing required keys: " + ", ".join(missing), file=sys.stderr)
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
