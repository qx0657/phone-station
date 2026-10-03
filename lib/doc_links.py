"""Validate repository Markdown references without network access."""
from pathlib import Path
import re
import unicodedata
from urllib.parse import unquote


def prose(text):
    text = re.sub(r"\A---\n.*?\n---\n", "", text, flags=re.S)
    return re.sub(r"^```[^\n]*\n.*?^```[^\n]*$", "", text, flags=re.M | re.S)


def anchors(text):
    found = set(re.findall(r'<a\s+(?:id|name)=[\"\']([^\"\']+)', text))
    counts = {}
    for heading in re.findall(r"^#{1,6}\s+(.+?)(?:\s+#+)?$", prose(text), re.M):
        heading = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", heading)
        heading = re.sub(r"<[^>]+>|[`*~]", "", heading).strip().lower()
        slug = "".join(c for c in heading if c in " _-" or unicodedata.category(c)[0] in "LN").replace(" ", "-")
        number = counts.get(slug, 0)
        counts[slug] = number + 1
        found.add(slug + ("-" + str(number) if number else ""))
    return found


def check(root):
    files = sorted(set(root.glob("*.md")) | set((root / "docs").rglob("*.md")) |
                   set((root / ".agents").rglob("SKILL.md")) | set((root / "server").rglob("*.md")))
    errors = []
    for file in files:
        for raw in re.findall(r"!?\[[^\]]*\]\(([^)]+)\)", prose(file.read_text())):
            raw = raw.split(' "', 1)[0].strip("<>")
            if re.match(r"[a-zA-Z][\w+.-]*:", raw):
                continue
            name, separator, anchor = unquote(raw).partition("#")
            target = (file.parent / name).resolve() if name else file
            if not target.exists():
                errors.append(f"{file.relative_to(root)}: missing {raw}")
            elif separator and anchor and target.suffix == ".md" and anchor not in anchors(target.read_text()):
                errors.append(f"{file.relative_to(root)}: missing anchor {raw}")
    index = (root / "docs/README.md").read_text()
    for file in (root / "docs").glob("*.md"):
        if file.name != "README.md" and file.name not in index:
            errors.append(f"docs index missing {file.name}")
    if errors:
        raise ValueError("Markdown references:\n" + "\n".join(errors))
    return len(files)


if __name__ == "__main__":
    print(f"Markdown references verified: {check(Path(__file__).resolve().parent.parent)} files")
