# Fork 说明（本仓库相对上游的改动）

上游：<https://github.com/beppe2880/opencode-mobile>（本仓库为 fork）。
本文件记录本 fork 自己的改动，升级上游时逐条保留即可。

## 一键升级上游 + 云端编译

Actions 里有两个只属于 fork 的工作流：

| 工作流 | 用途 |
|---|---|
| **Sync and build APK**（`sync-and-build.yml`） | 一键：把上游 `main` 合并进当前分支并编译 release APK。手动触发；每周一也会自动检查一次，上游没变化就跳过编译。 |
| **Build APK**（`build-apk.yml`） | 只编译当前分支的 release APK，不合并。 |

两个都只出 **release（R8 混淆 + 资源压缩）** 一种包，不再构建 debug 包。

用法：Actions → 选工作流 → Run workflow，完事后在本 run 的 Artifacts 里下载
`apk-*.zip`（里面有 APK 和 `SHA256SUMS`）。

要点：

- 首次用时，在仓库 Secrets 里放上游 release 用的四个值，APK 才会用正式签名（可覆盖安装
  已装的正式版）：`OCM_KEYSTORE_BASE64`、`OCM_KEYSTORE_PASSWORD`、`OCM_KEY_ALIAS`、
  `OCM_KEY_PASSWORD`。
- 没放密钥也能出包：用仓库里的 `android/debug.keystore` 签名。它能覆盖安装同样用调试密钥
  签过的包，但装不到正式签名的版本。
- 默认的 `GITHUB_TOKEN` 不能推送改动到 `.github/workflows/` 的文件。若想让合并结果自动
  写回分支，加一个 Secret `UPSTREAM_SYNC_TOKEN`（有 `repo` + `workflow` 权限的 PAT）。
  不加也能用：APK 照常从合并后的代码编译，只是分支上不会留下那笔合并提交。
- 合并有冲突时工作流直接失败、不编译。本地解决后推送，再用 **Build APK**。

本地同步（可选，和上面等价）：

```bash
git remote add upstream https://github.com/beppe2880/opencode-mobile.git  # 只需一次
git fetch upstream && git merge upstream/main
git push origin main
```

## 本 fork 的代码改动

### 工具耗时显示（`feat(chat)`）

工具行末尾显示该次调用的耗时，样式与「已思考 9s」那行一致（`bodySmall`），颜色跟随结果：
成功绿色、失败红色。设置 → 聊天 → 对话记录 里有开关 **显示工具耗时**（默认开）。

- `core/domain`：`Part.Tool` 新增 `ranAt`（服务端 `time.ran`，真正开始执行的时刻）和派生
  属性 `durationMs`（`ran`→`completed`，老服务端没有 `ran` 时退回 `created`）；
  `ServerEvent.ToolCalled` 带上事件时间 `at`。
- `core/network`：消息与事件映射填充 `ranAt` / `at`。
- `feature/chat`：`ChatState.showToolDurations`，`ToolRows.kt` 的 `ToolDuration`。
- `feature/settings`：`AppSettings.showToolDurations`、持久化键 `show_tool_durations`、
  设置项 `SettingOption.ToolDurations`（含 11 种语言的文案）。
- 测试：`TranscriptReducerTest`（按 `ran` 计时）、`MessageMappingTest`（`ran` 与回落）、
  `SettingsViewModelTest` / `PreferencesRepositoriesTest`（开关持久化）。

### 其它

- `.github/workflows/sync-and-build.yml`、`.github/workflows/build-apk.yml`：见上。
- `FORK.md`：本文件。
