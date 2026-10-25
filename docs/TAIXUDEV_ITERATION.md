# TaiXuDev 迭代说明（feature/taixudev-iteration-20261022）

本分支在**不改动 Agent Loop 与审批检查点**的前提下，加入四个可独立开关的实验模块，
并修复 TaiXuDev 预览包的安装体验问题。所有模块默认关闭时，行为与上游完全一致。

## 1. 模块清单

| 模块 | 实现位置 | 默认 | 说明 |
| --- | --- | --- | --- |
| 1 出站零宽混淆 | `core/network/.../TextObfuscator.kt`、`assets/obfuscation_lexicon.txt` | 关 | trie 最长匹配，命中词条的字符间交替插入 `U+200B`/`U+200C`；**只作用于出站**，入站 SSE 不做逆变换。`filesDir/obfuscation_lexicon.txt` 存在时覆盖 assets（热更新）。 |
| 2 拒答级联 | `harness/.../RefusalCascade.kt`（含 `CascadeTracker`） | 开（上限 S3） | 检测 SSE 前 240 字符的拒答模式（中/英/日/韩），按 S0→S1→S2→S3→S4 逐级改写重试；同一轮只升一级，且 `toolCalls == 0` 才升级；拒答轮不提交 tool_calls、不落库；审批检查点不变。 |
| 3 文件访问守卫 | `app/src/main/cpp/guard/*`、`app/.../core/guard/GuardHook.kt`、`jniLibs/arm64-v8a/libguard_hook.so` | 关 | **ByteHook（PLT/GOT hook）** hook libc `open`/`openat`/`fopen`，命中 `assets/guard/guard_manifest.txt` 规则且私有目录存在安全副本时重定向；`bytehook_hook_all` 只作用于自身进程。 |
| 4 成功策略回放 | `core/database/.../SuccessfulStrategy*`、`harness/.../StrategyReplay.kt` | 开 | 拒答转成功时写 `successful_strategy`；组装 system prompt 时取 top-10 few-shot 拼到尾部，受上下文压缩预算约束（超预算先裁最旧）。 |

### 级联级别语义

- **S0**：原 prompt 不改写（基线）。
- **S1**：要求直接回答，不要以道歉/能力声明/政策套话开头。
- **S2**：声明为本地良性工程任务，一句话复述目标后给具体步骤。
- **S3**：上一轮视为未发送，请求拆成最小可执行子任务。
- **S4**：上限档（`maxLevel=4` 时启用），退化为中性技术重述，禁止再输出拒答语。

## 2. 设置入口

设置 →「特色功能」→「迭代增强」：

- 四个模块的开关与状态（词库条数、级联上限、so 是否随包、策略条数）。
- 出站混淆：预览 + **导入词库（热更新）**弹窗，粘贴词表写入 `filesDir`，下一轮请求立即生效。
- 拒答级联：S0–S4 上限调节与命中/改写次数。
- 文件守卫：如实显示 `libguard_hook.so` 是否随包；未随包时开关置灰。
- 策略回放：条数查看与清空。

## 3. 本轮修复

1. **安装后提示“要更新版本”**：dev 包在 `AppUpdateManager` 内抑制官方 Release 比较（两处调用方一并覆盖）。
2. **签名漂移**：`app/signing/taixu-dev.p12` 固定 keystore，CI 每次构建签名一致；`versionCode` 加 1000 偏移（1041），避免与旧预览包互相覆盖。
3. **缺模块入口**：新增「迭代增强」统一子页（四模块入口 + 上述交互）。

## 4. 验证证据

- 单测：`TextObfuscatorTest` 6/6、`RefusalCascadeTest` 9/9、`StrategyReplayTest` 6/6（含“开关关闭后不写不注入”与“设置页热更新对 runner 持有的 tracker 立即生效”两个新用例）。
- 架构：`./gradlew :architectureCheck` 通过（38 模块）。
- 原生：`libguard_hook.so` 由 NDK 交叉编译，`DT_NEEDED` = `libbytehook.so`/`liblog.so`/`libandroid.so`，导出 `JNI_OnLoad` 与 `Java_top_wkbin_taixu_core_guard_GuardHook_nativeInit`。
- 构建：GitHub Actions `Build TaiXuDev APK` 通过，产物 `TaiXuDev-arm64-debug.apk`。

## 5. 已知限制

- 仅 arm64-v8a（与 TaiXuDev 预览包一致）；`libbytehook.so` 同为 arm64。
- ByteHook 的 PLT hook 装上后无法安全卸载：关掉开关后本进程仍生效，重启应用才彻底失效（设置页有提示）。
- `BotAvatarImageTest` 在 aarch64 宿主上因 Robolectric 无 native runtime 失败，属平台限制，与本次改动无关。
- 词库内置只有 3 条示例词条，需用户导入真实词表。

## 6. 第三方许可

- ByteHook（`com.bytedance:bytehook:1.1.2`，Apache-2.0）：预编译 `libbytehook.so` 随包分发，源码与许可见 https://github.com/bytedance/bhook 。
