import re, os, sys

"""v0.5.7: sync ONLY the version fields in docs — never rewrite sections.

Bug history: an earlier greedy regex swallowed the README quick-start
section. This script now:
  1. patches only exact, line-scoped patterns,
  2. verifies REQUIRED sections still exist afterwards,
  3. exits non-zero if any required section is missing.
"""
os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

ver = ""
for line in open("gradle.properties", encoding="utf-8"):
    m = re.match(r"version=(.+)", line.strip())
    if m:
        ver = m.group(1).strip()
if not ver:
    print("FATAL: version not found in gradle.properties")
    sys.exit(1)
print("version:", ver)

REQUIRED = {
    "README.md": ["## 快速开始", "按 K", "Fabric API", "1.21.1", "Java **21**",
                  "worldforge-", "## 核心机制", "## 已知限制"],
    "docs/BETA-GUIDE.md": ["安装", "5 分钟", "能力清单", "环境要求", "常见问题",
                           "API Key", "worldforge-" + ver + ".jar"],
}

def patch(path, subs):
    s = open(path, encoding="utf-8").read()
    orig = s
    for pat, rep in subs:
        s = re.sub(pat, rep, s)
    missing = [r for r in REQUIRED.get(path, []) if r not in s]
    if missing:
        print("FATAL: patching %s would remove required sections: %s" % (path, missing))
        print("       aborting WITHOUT writing the file.")
        sys.exit(2)
    if s != orig:
        open(path, "w", encoding="utf-8").write(s)
        print("patched", path)
    else:
        print("no change", path)

# line-scoped, non-greedy replacements ONLY (never cross a newline).
# The status suffix (已发布 / Public Beta / …) is PRESERVED — the script only
# swaps the version number, so wording edits are never clobbered.
patch("README.md", [
    (r"(?m)^- 当前版本：\*\*v([\d.]+)([^*]*)\*\*.*$", r"- 当前版本：**v" + ver + r"\2**"),
    (r"worldforge-0\.\d+\.\d+\.jar", f"worldforge-{ver}.jar"),
])
patch("docs/BETA-GUIDE.md", [
    (r"(?m)^# WorldForge 公测指南（v[\d.]+）$", f"# WorldForge 公测指南（v{ver}）"),
    (r"worldforge-0\.\d+\.\d+\.jar", f"worldforge-{ver}.jar"),
])
print("docs sync OK")
