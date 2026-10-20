import re, os
os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ver = ""
for line in open("gradle.properties", encoding="utf-8"):
    m = re.match(r"version=(.+)", line.strip())
    if m: ver = m.group(1).strip()
print("version:", ver)
def patch(path, subs):
    s = open(path, encoding="utf-8").read()
    orig = s
    for pat, rep in subs:
        s = re.sub(pat, rep, s)
    if s != orig:
        open(path, "w", encoding="utf-8").write(s)
        print("patched", path)
    else:
        print("no change", path)
patch("README.md", [
    (r"当前版本：\*\*v[\d.]+ 已发布\*\*（[^*]*）", f"当前版本：**v{ver} 已发布**"),
    (r"worldforge-0\.\d+\.\d+\.jar", f"worldforge-{ver}.jar"),
])
patch("docs/BETA-GUIDE.md", [
    (r"公测指南（v[\d.]+）", f"公测指南（v{ver}）"),
    (r"worldforge-0\.\d+\.\d+\.jar", f"worldforge-{ver}.jar"),
])
