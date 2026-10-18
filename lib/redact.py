"""Strip secrets from diagnostic text. The result is safe to attach to a report."""

from __future__ import annotations

import re

_BEARER = re.compile(r"(?i)(authorization:\s*bearer\s+)\S+")
_FIELD = re.compile(
    r'(?i)("(?:token|pin|enroll|enrollCode|password|secret|authorization|spki)"\s*:\s*")[^"]*(")'
)
_HEX64 = re.compile(r"\b[0-9a-fA-F]{64}\b")
_PLACEHOLDER = "[redacted]"


def redact(text: str) -> str:
    text = _BEARER.sub(r"\1" + _PLACEHOLDER, text)
    text = _FIELD.sub(r"\1" + _PLACEHOLDER + r"\2", text)
    return _HEX64.sub(_PLACEHOLDER, text)
