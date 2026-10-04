# 冷数据重压缩与分层压缩组件

Pair-wise GSB 标注任务仓库（第 17 批 / 271）。

| 项目 | 内容 |
|------|------|
| 任务类型 | Feature 迭代 |
| 任务难度 | 困难 |
| 语言/框架 | Java, Maven, JUnit 5 |
| 环境可复现等级 | 无外部依赖 |
| 构建方式 | Maven（含 mvnw wrapper，无需本机安装 Maven） |

> 本仓库是**初始环境快照**：只有工程骨架，不含任何实现代码。
> 分支说明：`main` 为初始环境；`A`、`B` 为两次独立执行各自的工作分支，均从 `main` 的同一个提交拉出。

## 运行方式

```bash
./mvnw -q verify
```

## 任务提示词

以下为本题完整的 User Prompt 原文，两次执行必须使用完全相同的文本。

我们的历史数据用统一压缩策略，冷数据本可以用更高压缩率，但重压缩又不能影响在线读写。请从零实现一个冷数据重压缩组件。仓库目前只有一个空的 Maven 工程（pom.xml 只声明 JUnit 5 与 AssertJ）。要求：1) 支持按数据年龄与访问频率把数据分为热、冷两级，各级使用不同压缩策略（例如热用快速压缩、冷用高压缩率）；2) 支持后台重压缩：把已转冷的数据用高压缩率重新编码，重压缩期间该数据仍可被读取（读旧副本或新副本均可，但必须完整）；3) 支持重压缩的原子替换：新编码写完后一次性切换，切换前后数据内容完全一致；4) 支持重压缩中断恢复：中断后不得留下无法读取的半成品，重启后能判断继续或丢弃；5) 正确性：重压缩前后解密解压结果必须逐条一致，需用测试证明；6) 支持统计：重压缩条数、压缩率变化、耗时与中断恢复次数；7) 给出不同数据量下重压缩带来的空间节省与耗时数据；8) 测试覆盖分层判定、后台重压缩、原子切换、中断恢复、内容一致性验证与统计；`mvn -q verify` 一条命令跑通。

## 提交要求

1. 在本仓库中完成提示词要求的全部内容。
2. `./mvnw -q verify` 必须通过。
3. 完成后在所属分支（A 或 B）上提交，产物快照的父提交必须是初始环境快照。

---

# 实现说明（分支 B）

## 组件结构

`com.example.gsb.recompress` 包，零外部依赖（仅 JDK + JUnit 5/AssertJ 测试）：

| 类 | 职责 |
|---|---|
| `TierPolicy` | 按数据年龄（`coldAge`）与访问频率（`coldMaxAccesses`）判定 HOT/COLD |
| `Codecs` / `DeflaterCodec` | 热层 `deflate-fast`（BEST_SPEED），冷层 `deflate-high`（BEST_COMPRESSION） |
| `Encryptor` | AES-256/GCM 加密，落盘数据为 `encrypt(compress(plaintext))` |
| `RecordFile` | 记录文件格式：magic + codecId + CRC32 + payload，读时校验完整性 |
| `Journal` | 重压缩写前日志，携带暂存文件 CRC，供恢复时判断继续/丢弃 |
| `ColdDataStore` | 主入口：`put`/`get`/`recompressCold(Async)`/`stats`，打开时自动恢复 |
| `RecompressionStats` | 重压缩条数、压缩率变化、空间节省、耗时、中断恢复次数 |
| `RecompressionHook` | 各持久化边界的回调，测试用它注入模拟崩溃 |

## 分层与后台重压缩

- 写入时按 `TierPolicy` 选 codec：热数据快速压缩，冷数据高压缩率压缩。
- `get` 会累计访问次数；老数据若仍被频繁访问则保持热层，不会被重压缩。
- `recompressColdAsync()` 在后台线程扫描所有转冷记录并逐条重编码；读路径只读已提交文件，重压缩期间读到的要么是完整的旧副本、要么是完整的新副本。

## 原子替换与中断恢复

每条记录的重压缩按以下顺序执行：

1. 新编码完整写入 `staging/<id>.new` 并 fsync；
2. 写入 `journal/<id>.jrn`（含暂存文件 CRC32）并 fsync；
3. `Files.move(..., ATOMIC_MOVE)` 一次性替换已提交文件；
4. 删除 journal。

重启打开 store 时回放 journal：

- 暂存文件存在且 CRC 与 journal 匹配 → 完成替换（**继续**）；
- 暂存文件缺失/损坏，或只有孤儿暂存文件（journal 未落盘）→ 删除暂存（**丢弃**），旧副本继续服务。

两种恢复都计入统计（`recoveriesCompleted` / `recoveriesDiscarded`）。任何时刻都不会留下无法读取的半成品：已提交文件只在存在完整替代文件后才被原子替换。

## 运行

```bash
./mvnw -q verify
```

## 测试覆盖（39 个用例）

- 分层判定：`TierPolicyTest`（年龄/频率边界值）
- 编解码与加密：`CodecTest`、`RecordFileTest`、`EncryptorTest`（往返、损坏拒绝、密文不含明文）
- 后台重压缩：`BackgroundRecompressionTest`（4 线程并发读 + 后台重压缩，无撕裂读）
- 原子切换：`ColdDataStoreTest.atomicSwapKeepsOldCopyReadableUntilTheSingleSwitch`
- 中断恢复：`CrashRecoveryTest`（journal 后崩溃→继续、journal 前崩溃→丢弃、暂存损坏→丢弃、丢弃后可重新重压缩）
- 内容一致性：`ColdDataStoreTest.contentIsIdenticalForEveryRecordAfterRecompression`（300 条逐条比对解密解压结果）
- 统计：`ColdDataStoreTest.statsReportCountsRatiosSpaceSavedAndElapsed` 等

## 基准数据（要求 7）

`RecompressionBenchmarkTest` 实测（日志型数据，约 16 KiB/条，fsync 关闭以测量纯重编码开销；JDK 17 / Temurin，容器环境）：

| records | plaintext (MB) | hot bytes (MB) | cold bytes (MB) | saved (MB) | saved % | ratio hot -> cold | time (ms) | records/s |
|---|---|---|---|---|---|---|---|---|
| 1,000 | 15.77 | 2.67 | 2.31 | 0.36 | 13.4% | 0.1692 -> 0.1466 | 173 | 5,780 |
| 5,000 | 78.48 | 13.24 | 11.47 | 1.77 | 13.4% | 0.1688 -> 0.1462 | 849 | 5,889 |
| 10,000 | 156.88 | 26.46 | 22.92 | 3.54 | 13.4% | 0.1687 -> 0.1461 | 1,618 | 6,180 |

结论：在已压缩约 84% 的热数据上，冷层高压缩率再省约 13% 空间；吞吐约 6k 条/秒（约 95 MB/s 明文），耗时随数据量线性增长。开启 fsync 时每条记录增加两次落盘同步开销，实际部署可按批次权衡。
