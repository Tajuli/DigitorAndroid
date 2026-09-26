"""Check the *minified APK*, not source keep rules, for ML Kit reflection entry points.

Usage: python3 tools/validation/verify_mlkit_apk.py path/to/app-phone.apk
No third-party packages required. Reads DEX class/method/prototype tables directly.
The pre-fix PR #103 APK fails: all three registrars exist but lack constructors.
"""
import struct
import sys
import zipfile

REGISTRARS = (
    "com.google.mlkit.common.internal.CommonComponentRegistrar",
    "com.google.mlkit.vision.common.internal.VisionCommonRegistrar",
    "com.google.mlkit.vision.face.internal.FaceRegistrar",
)


def registrar_constructors(data):
    assert data[:4] == b"dex\x0a", "Not a DEX file"
    string_count, string_offset = struct.unpack_from("<II", data, 56)
    type_count, type_offset = struct.unpack_from("<II", data, 64)
    _, proto_offset = struct.unpack_from("<II", data, 72)
    _, method_offset = struct.unpack_from("<II", data, 88)
    class_count, class_offset = struct.unpack_from("<II", data, 96)

    def uleb(offset):
        value = shift = 0
        while True:
            byte = data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if not byte & 128:
                return value, offset
            shift += 7
            assert shift <= 35, "Invalid ULEB128"

    strings = []
    for index in range(string_count):
        offset = struct.unpack_from("<I", data, string_offset + index * 4)[0]
        _, offset = uleb(offset)
        strings.append(data[offset:data.index(0, offset)].decode("utf-8", "replace"))
    types = [strings[struct.unpack_from("<I", data, type_offset + i * 4)[0]]
             for i in range(type_count)]
    targets = {"L" + name.replace(".", "/") + ";": name for name in REGISTRARS}
    found = {}
    for index in range(class_count):
        class_id, flags, _, _, _, _, offset, _ = struct.unpack_from("<8I", data, class_offset + index * 32)
        name = targets.get(types[class_id])
        if name is None:
            continue
        found[name] = False
        if not offset or not flags & 1 or flags & (0x200 | 0x400):
            continue  # must be public and concrete
        counts = []
        for _ in range(4):
            count, offset = uleb(offset)
            counts.append(count)
        for _ in range(counts[0] + counts[1]):
            _, offset = uleb(offset)  # field index difference
            _, offset = uleb(offset)  # access flags
        method_index = 0
        for _ in range(counts[2]):  # constructors are direct methods
            delta, offset = uleb(offset)
            method_index += delta
            access, offset = uleb(offset)
            code_offset, offset = uleb(offset)
            _, proto_id, string_id = struct.unpack_from("<HHI", data, method_offset + method_index * 8)
            _, _, parameters = struct.unpack_from("<III", data, proto_offset + proto_id * 12)
            no_args = parameters == 0 or struct.unpack_from("<I", data, parameters)[0] == 0
            if strings[string_id] == "<init>" and access & 1 and no_args and code_offset:
                found[name] = True
    return found


def verify(path):
    found = {}
    with zipfile.ZipFile(path) as apk:
        manifest = apk.read("AndroidManifest.xml")
        for name in REGISTRARS:
            key = "com.google.firebase.components:" + name
            assert key.encode() in manifest or key.encode("utf-16le") in manifest, "Missing manifest registrar: " + name
        for name in apk.namelist():
            if name.endswith(".dex"):
                found.update(registrar_constructors(apk.read(name)))
    missing = [name for name in REGISTRARS if not found.get(name)]
    assert not missing, "Missing public no-arg ML Kit registrar constructors: " + ", ".join(missing)
    print("PASS: manifest and public no-arg constructors survive minification for all 3 ML Kit registrars")


if __name__ == "__main__":
    verify(sys.argv[1])
