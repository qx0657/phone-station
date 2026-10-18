"""Write a diagnostic directory after redacting secrets. Does not read the phone."""

from __future__ import annotations

from pathlib import Path

from redact import redact


def write_bundle(dest: Path, parts: dict[str, str]) -> None:
    for name in parts:
        if not _safe_name(name):
            raise ValueError("name")
    dest.mkdir(mode=0o700, parents=True, exist_ok=True)
    if any(dest.iterdir()):
        raise ValueError("bundle directory is not empty")
    for name, text in parts.items():
        path = dest / name
        path.write_text(redact(text), encoding="utf-8")
        path.chmod(0o600)


def gateway_excerpt(path: Path, limit: int = 65536) -> str:
    data = path.read_bytes()
    if len(data) > limit:
        data = data[-limit:]
    return data.decode("utf-8", "replace")


def _safe_name(name: str) -> bool:
    return (
        name.endswith(".txt")
        and "/" not in name
        and "\\" not in name
        and not name.startswith(".")
        and name == Path(name).name
    )
