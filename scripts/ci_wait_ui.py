#!/usr/bin/env python3
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

mode = sys.argv[1]
target_label = sys.argv[2]
target = " ".join(target_label.split()).casefold()

def norm(value):
    return " ".join((value or "").split()).casefold()

last_nodes = []
for attempt in range(32):
    subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/window.xml"], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    xml = subprocess.check_output(["adb", "shell", "cat", "/sdcard/window.xml"], text=True)
    root = ET.fromstring(xml)
    nodes = list(root.iter("node"))
    last_nodes = nodes
    node = next((item for item in nodes if target in {norm(item.attrib.get("text")), norm(item.attrib.get("content-desc"))}), None)
    if node is not None:
        if mode == "tap":
            bounds = list(map(int, re.findall(r"\d+", node.attrib.get("bounds", ""))))
            if len(bounds) != 4:
                raise SystemExit(f"Bad bounds for {target_label!r}: {node.attrib}")
            subprocess.run(["adb", "shell", "input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2)], check=True)
        print(f"Found UI target {target_label!r} on attempt {attempt + 1}")
        raise SystemExit(0)
    time.sleep(0.75)

visible = sorted({value for item in last_nodes for value in (item.attrib.get("text"), item.attrib.get("content-desc")) if value and value.strip()})
print(f"UI target {target_label!r} not found. Visible semantics: {visible}")
raise SystemExit(1)
