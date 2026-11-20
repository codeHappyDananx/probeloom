#!/usr/bin/env python3
"""Reassemble the exact split source packages in a caller-selected directory."""
import hashlib
import json
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[3]
manifest = json.loads((root / "licenses/native/dependencies.json").read_text(encoding="utf-8"))
destination = Path(sys.argv[1]).resolve()
destination.mkdir(parents=True, exist_ok=True)
for component in manifest["components"]:
    for source in component["sources"]:
        if "parts" not in source:
            continue
        output = destination / Path(source["path"]).name
        whole = hashlib.sha256()
        with output.open("wb") as target:
            for part in source["parts"]:
                data = (root / part["path"]).read_bytes()
                if hashlib.sha256(data).hexdigest() != part["sha256"]:
                    raise RuntimeError("Source part SHA-256 mismatch: " + part["path"])
                target.write(data)
                whole.update(data)
        if whole.hexdigest() != source["sha256"]:
            raise RuntimeError("Reassembled source SHA-256 mismatch")
        print(output)
