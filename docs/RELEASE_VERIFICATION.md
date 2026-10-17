# Release 校验规程与 v0.1.1 核查记录

本文件是 **#6「Release 与源码一致性」** 的可复现交付物：任何人都能用下面的
步骤从版本标签独立重建 JAR，并逐字节比对 SHA-256。

## 校验规程（对任意 tag 执行）

```bash
# 1. 干净检出该 tag（--no-checkout 避免复用工作树缓存）
git clone --no-checkout <本仓库> wf-verify && cd wf-verify
git checkout -q v0.1.1

# 2. 独立构建（需 JDK 21；loom 会自建 MC 缓存）
./gradlew build

# 3. 计算校验值（Windows/Linux 通用）
sha256sum build/libs/worldforge-0.1.1.jar build/libs/worldforge-0.1.1-sources.jar

# 4. 与下表“发布产物 SHA-256”逐字节比对，应完全一致
```

构建已配置为**字节可复现**（`build.gradle`：`preserveFileTimestamps=false`、
`reproducibleFileOrder=true`，配合 `.gitattributes` 强制 `eol=lf`），
因此不同机器 / 不同时间的干净构建应得到相同哈希。

## v0.1.1 发布产物 SHA-256

发布代码 commit：`053be25`；tag `v0.1.1` = `5c01ab6`（其后仅文档变更，JAR 逐字节不变）。

| 产物 | SHA-256 |
|---|---|
| worldforge-0.1.1.jar | `29c380922f383ce8283f871b8fdfc675650942d5c70b3bb23c06efc7278fdfef` |
| worldforge-0.1.1-sources.jar | `f494714e97d7aaaf3aa4fb903dc6bbce9c5cfc2b697d745f0ff8fc68db659c3b` |

## 本次核查结果（真实执行，非声称）

- **独立干净克隆 @ tag v0.1.1**（fresh clone、`./gradlew clean build`）：上表两个
  SHA 与主工作区构建输出**逐字节一致**（`sha256sum` 相同），证明 tag 源码 == Release JAR。
- **同一干净克隆直接跑真实服务端 GameTest**：`All 15 required tests passed`（0 失败）；
  其中 R1/R1b/R2/R3 四项在 v0.1.0 源码上运行得到 FAILED（问题复现，见 CHANGELOG）。
- **GitHub Release 下载回验**：Release 附件下载回本地重算 SHA，与上表一致。

## v0.1.0 曾存在的不一致（本迭代的发现，如实记录，不回改历史）

核查 v0.1.0 时发现其 GitHub Release 附件 JAR **并非**由 `v0.1.0` tag 指向的源码构建：

- tag `v0.1.0` 指向 commit `cb9cadb`；
- 但附件 JAR 是在之后的 HEAD（`604edde`，该提交删除了 `ClientDefinitionCache`）
  构建并上传的 → **tag ≠ 产物**。
- 另外当时构建**不可复现**：同一棵树两次构建的 JAR 因 (a) 检出时 `core.autocrlf`
  把内嵌 `LICENSE`/资源 JSON 转成 CRLF、(b) `sources.jar` 内嵌构建时间戳，
  导致字节级差异（内容等价但哈希不同），无法做可靠校验。

v0.1.1 起已通过 `.gitattributes`（`eol=lf`）+ Gradle 可复现归档两项改进根治，
并用本文件的可复现规程固化。v0.1.0 的历史差异保留记录、不追溯修改（不改写已发布
历史是基本原则）。
