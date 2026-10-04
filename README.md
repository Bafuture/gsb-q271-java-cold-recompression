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

## 实现说明（分支 A）

### 架构

```
com.example.gsb.recompress
├── TieringPolicy          按数据年龄 + 滑动窗口内访问次数判定热/冷
├── CompressionProfile     热=DEFLATE level 1（快），冷=DEFLATE level 9（高压缩率）
├── BlobCodec              加密压缩格式：magic|tier|iv + AES-256-GCM(deflate(data))，头部作 GCM AAD
├── CryptoService          AES-256-GCM，每条记录随机 IV
├── BlobStore/FileBlobStore 文件存储：stage 写入 + 提交标记 + 原子 rename 切换 + 启动恢复
├── RecompressionJournal   重压缩 WAL（STARTED/STAGED/DONE），用于崩溃后判断继续或丢弃
├── MetaStore              每条记录的层级/时间戳/访问窗口元数据（原子写）
├── RecompressionManager   总装：读写路径、后台重压缩线程、恢复、统计
├── RecompressionStats     条数、压缩率变化、耗时、中断恢复次数（继续/丢弃分列）
└── ColdRecompressionBenchmark  不同数据量下的空间节省与耗时基准
```

### 需求对照

1. **分层**：`TieringPolicy`（年龄 ≥ coldAge 且窗口内访问 ≤ 阈值 → 冷），热用 L1、冷用 L9。
2. **后台重压缩**：`startBackgroundRecompression(interval, batchLimit)` 周期扫描冷候选；
   重压缩期间旧 blob 原样保留，读请求始终读到完整的旧副本或新副本
   （`ReadableDuringRecompressionTest` 在切换点阻塞验证）。
3. **原子替换**：新编码先写 `staging/<id>.stage` 并 fsync，写入提交标记后一次
   原子 rename 覆盖正式 blob；切换后立刻解码逐字节比对源数据。
4. **中断恢复**：崩溃后重启，`FileBlobStore.recover()` 按提交标记决定完成切换或
   丢弃半成品，`RecompressionManager` 再按 WAL 最后一条记录核对（STAGED 比对
   当前 CRC 决定继续/丢弃；STARTED 且内容已变则验证新副本完整性后完成）。
   任何崩溃点都不会留下无法读取的半成品（`CrashRecoveryTest` 覆盖 5 种场景，
   含连续 3 次崩溃）。
5. **正确性**：`ContentConsistencyTest` 对 300 条混合负载（可压缩/随机/空/日志型）
   逐条断言重压缩前后解密解压结果完全一致，并在进程重启后再次验证。
6. **统计**：`RecompressionStats` 提供重压缩条数、压缩前/后字节数、压缩率变化、
   总耗时/平均耗时、中断恢复次数（完成/丢弃分列）。
7. **基准数据**：`ColdRecompressionBenchmarkTest` 运行基准并把结果写入
   `target/benchmark-results.md`；`ColdRecompressionBenchmark` 也可作为 main 类单独运行。
8. **测试**：11 个测试类共 40 个用例，覆盖分层判定、后台重压缩、原子切换、
   中断恢复、内容一致性与统计。

### 基准结果（本机实测，日志型数据 ~75% 重复文本 + ~25% 随机字符，1.5 KB/条）

| records | plaintext bytes | hot (L1) on disk | cold (L9) on disk | space saved | hot write ms | recompress ms | recompress us/record |
|---------|-----------------|------------------|-------------------|-------------|--------------|---------------|----------------------|
| 100 | 150,000 | 36,229 | 33,371 | 7.9% | 29 | 43 | 430.0 |
| 1,000 | 1,500,000 | 363,139 | 334,644 | 7.8% | 99 | 227 | 227.0 |
| 5,000 | 7,500,000 | 1,815,099 | 1,673,056 | 7.8% | 262 | 514 | 102.8 |

说明：磁盘占用含 33 字节/条的加密头（magic+tier+IV+GCM tag）；L1→L9 的增量收益
取决于数据冗余度，随机数据无收益，重复度高的日志/文本收益更大。重压缩吞吐随
记录数上升（摊薄固定开销），5000 条约 103 µs/条。
