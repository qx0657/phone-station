"""Pinned ZXing core. The jar stays in ignored build output."""
import hashlib
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
ARTIFACT = (
    'com/google/zxing/core/3.5.3',
    'core-3.5.3.jar',
    '8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82',
)


def jars():
    directory = ROOT / 'build/third_party/zxing'
    directory.mkdir(parents=True, exist_ok=True)
    location, name, digest = ARTIFACT
    target = directory / name
    data = target.read_bytes() if target.exists() else urllib.request.urlopen(
        f'https://repo.maven.apache.org/maven2/{location}/{name}', timeout=30).read()
    if hashlib.sha256(data).hexdigest() != digest:
        raise ValueError(f'{name} 摘要不匹配')
    if not target.exists():
        target.write_bytes(data)
    return [target]


if __name__ == '__main__':
    for path in jars():
        print(path)
