#!/usr/bin/env python3
"""Builds the GitHub release body from the newest ENGINEERING_LOG.md entry,
so every release says what actually changed in it."""
import re
import sys


def build(log_text: str) -> str:
    m = re.search(r"^## (Cycle \d+ — .+?) \(v[\d.]+, [\d-]+\)\n(.*?)(?=^## |\Z)",
                  log_text, re.M | re.S)
    header = "Download **MockingBird.apk** below on your Android phone and open it to install (updates in place, nothing is lost). Large download (about 600 MB)."
    if not m:
        return header
    title, body = m.group(1), m.group(2)
    s = re.search(r"\*\*Shipped:\*\*\s*(.*?)(?=\n\s*\n\*\*|\Z)", body, re.S)
    shipped = re.sub(r"\s+", " ", s.group(1)).strip() if s else ""
    if len(shipped) > 900:
        shipped = shipped[:900].rsplit(" ", 1)[0] + "…"
    out = [header, "", f"### What's new — {title}", shipped, "",
           "_Speak, Clones and Sounds tabs · 54 voices · voice cloning · 460+ medieval sounds · fully offline._"]
    return "\n".join(out).strip() + "\n"


if __name__ == "__main__":
    log = open(sys.argv[1] if len(sys.argv) > 1 else "ENGINEERING_LOG.md").read()
    print(build(log))
