"""Pinned Java control transport dependencies; cache only in ignored build output."""
import hashlib
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
ARTIFACTS = (
    ('org/java-websocket/Java-WebSocket/1.6.0', 'Java-WebSocket-1.6.0.jar', 'eae29213e4f16515639c28957200f011b3967fffcada1962cf0255d24919c22f'),
    ('org/slf4j/slf4j-api/2.0.13', 'slf4j-api-2.0.13.jar', 'e7c2a48e8515ba1f49fa637d57b4e2f590b3f5bd97407ac699c3aa5efb1204a9'),
    ('org/slf4j/slf4j-nop/2.0.13', 'slf4j-nop-2.0.13.jar', '8962a107b4a8bdf80b6c17e470cd3614ad3329643833ff5cf0c60c7dce9deaac'),
)

def jars():
    directory = ROOT / 'build/third_party/websocket'
    directory.mkdir(parents=True, exist_ok=True)
    paths = []
    for location, name, digest in ARTIFACTS:
        target = directory / name
        data = target.read_bytes() if target.exists() else urllib.request.urlopen(
            f'https://repo.maven.apache.org/maven2/{location}/{name}', timeout=30).read()
        if hashlib.sha256(data).hexdigest() != digest:
            raise ValueError(f'{name} 摘要不匹配')
        if not target.exists():
            target.write_bytes(data)
        paths.append(target)
    return paths

if __name__ == '__main__':
    for path in jars():
        print(path)
