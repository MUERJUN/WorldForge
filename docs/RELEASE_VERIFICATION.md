# Release 校验规程与 v0.1.1 核查记录

本文件是 **#6「Release 与源码一致性」** 的可复现交付物：任何人都能用下面的
步骤从版本标签独立重建 JAR，并逐字节比对 SHA-256。

## 校验规程（对任意 tag 执行）

```bash
# 1. 干净检出该 tag（--no-checkout 避免复用工作树缓存）
git clone --no-checkout <本仓库> wf-verify && cd wf-verify
git checkout -q vX.Y.Z          # 例如 v0.2.0

# 2. 独立构建（需 JDK 21；loom 会自建 MC 缓存）
./gradlew build

# 3. 计算校验值（Windows/Linux 通用；文件名随版本变化）
sha256sum build/libs/worldforge-X.Y.Z.jar build/libs/worldforge-X.Y.Z-sources.jar

# 4. 与下表“发布产物 SHA-256”逐字节比对，应完全一致
```

构建已配置为**字节可复现**（`build.gradle`：`preserveFileTimestamps=false`、
`reproducibleFileOrder=true`，配合 `.gitattributes` 强制 `eol=lf`），
因此不同机器 / 不同时间的干净构建应得到相同哈希。

## v0.3.0 发布产物 SHA-256

| 产物 | SHA-256 |
|---|---|
| worldforge-0.3.0.jar | `b81637e29364cc8e4f6ca7ba546f86248b32e87ef9ec134f5f74d99d50d44f3b` |
| worldforge-0.3.0-sources.jar | `462fec9bd55c28a07860cd33b37b85d42c961899a5c93ae0c306817a5bd7b82c` |

发布 commit：见 tag v0.3.0（tag 指向含本记录的最终提交；构建哈希在 tag 定稿后于独立克隆复核）。

---

## v0.2.0 发布产物 SHA-256

| 产物 | SHA-256 |
|---|---|
| worldforge-0.2.0.jar | `5ec1ebc5c899e546ccdfa4dcffaa5c4ffa21ceb25103844f068e7df52f3dd885` |
| worldforge-0.2.0-sources.jar | `c69e79f16fe750574555f2e55ea14010544068eaf3c6cf249572b063ac6d8132` |

发布 commit：`09dbe78`（tag `v0.2.0`）。独立核查见文末执行记录。

---

## v0.1.2 发布产物 SHA-256

（v0.1.2 代码 commit `cdd1091` 之后仅行尾 renormalize，工作区与 tag 独立构建字节一致，见下）

| 产物 | SHA-256 |
|---|---|
| worldforge-0.1.2.jar | `e601ff49ba64a360cd7253e705670c0da1e4941c88675cbe4d23da4358dfc477` |
| worldforge-0.1.2-sources.jar | `4c96f6650bcd3152b7d69be1ae325d73834b455d6025c28f14aa9a7ac381430b` |

独立核查（2026-10-17）：全新克隆检出 v0.1.2 -> `clean build` -> 上表哈希与工作区构建一致；
该克隆直接 `runGameTest` -> All 19 required tests passed。

---

## v0.1.1 发布产物 SHA-256

最后一个改变 JAR 内容的代码 commit 为 `053be25`（其后的提交仅文档，构建字节
不变，已实测）。当前 tag 指向的提交用 `git rev-list -n1 v0.1.1` 查询；对 tag
本身做上表独立克隆构建核查（2026-10-17 实测，三方一致：tag 独立构建 ==
工作区构建 == Release 附件下载回验）。

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
