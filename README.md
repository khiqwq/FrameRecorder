# Frame Recorder（帧率记录器）

Minecraft 1.21.11 / Fabric 客户端模组：进入世界后按秒记录帧时间、帧率限制器状态、GC 停顿、内存与位置，并在卡顿帧上抓取渲染线程调用栈，用来定位"是哪个模组让游戏卡了一下"。

纯客户端，不改变任何游戏行为，无配置项，进入单人世界或服务器自动开始，断开时自动结束。

## 安装

- Minecraft 1.21.11
- Fabric Loader 0.18 及以上
- Fabric API
- Java 21

把 `framerecorder-<版本>.jar` 放进 `mods` 文件夹即可。下载见 [Releases](https://github.com/khiqwq/FrameRecorder/releases)。

## 输出

每次进入世界会在游戏目录的 `framerecorder/` 下生成三个文件（文件名带开始时间）：

### `frames-*.csv` — 每秒一行

| 列 | 含义 |
| --- | --- |
| `time` | 本地时间 |
| `fps` | 这一秒的帧率 |
| `avg_ms` / `p99_ms` / `max_ms` | 平均、99 分位、最长帧时间（含帧率限制器的等待） |
| `work_max_ms` | 扣除帧率限制器等待后的最长实际工作时间 |
| `fps_limit` | 当前帧率上限（`0` 表示不限帧，原版此时不调用限制器） |
| `limiter_ms_per_frame` | 平均每帧在帧率限制器中等待的时间 |
| `stalls_50ms` / `stalls_100ms` | 实际工作时间 ≥ 50 / 100 ms 的帧数 |
| `gc_pauses` / `gc_pause_ms` / `gc_max_ms` | GC 停顿次数、总时长、最长一次（G1 并发周期与 ZGC Cycles 不计入） |
| `heap_used_mb` / `heap_committed_mb` / `heap_max_mb` | 堆内存 |
| `entities` | 客户端实体数 |
| `server` / `dimension` / `x` / `y` / `z` | 服务器地址（单人为 `singleplayer`）、维度与坐标 |

### `stalls-*.log` — 卡顿明细

实际工作时间 ≥ 50 ms 的帧逐条记录：时间、耗时、同时发生的 GC 停顿，以及看门狗线程在该帧进行到 50 ms 时抓取的渲染线程调用栈（≥ 250 ms 时再抓一次）。每条带一个归类签名：

```
top=<栈顶方法> | game=<最靠近栈顶的非 JDK 方法> | mod=<最靠近栈顶的模组方法>
```

### `summary-*.txt` — 断开时生成

总帧数、平均帧率、p50 / p99 / p99.9 帧时间与 1% low，卡顿与 GC 合计，以及按总耗时排序的卡顿来源。

## 说明

- 帧率限制器的等待（例如最大帧率设置、失焦或挂机降帧）不计为卡顿，可通过 `fps_limit` 与 `limiter_ms_per_frame` 区分"被限帧"和"真的卡"。
- 原版类在生产环境中显示为 intermediary 名称（如 `net.minecraft.class_310.method_1523`），其他模组显示原名。
- 卡顿采样使用 `Thread.getStackTrace`，只在卡顿帧上触发，正常帧没有额外开销。

## 构建

使用 Java 21：

```
gradlew build
```

输出 `build/libs/framerecorder-<版本>.jar`。

## English

Client-side Fabric mod for Minecraft 1.21.11. While in a world it writes a per-second CSV of frame times, the active frame-rate limit and time spent in the limiter, GC pauses, heap, entity count and position; frames whose work time (limiter wait excluded) reaches 50 ms get a render-thread stack sample in `stalls-*.log`, and a ranked summary of stall sources is written on disconnect. Output goes to `<game dir>/framerecorder/`. No configuration; requires Fabric API. MIT licensed.
