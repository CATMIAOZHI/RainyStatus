# AGENTS.md

任务开始前必须先读取 taste.md（协作偏好），任务全程遵守其中约定，包括：修改后派发独立 subagent 审计（无阻断问题 + 无影响用户体验问题），修复后用同一个 subagent 复审。

未经用户允许禁止提交推送

派发子代理任务（独立审计/探索/通用 subagent）时，prompt 中涉及工作区文件的所有路径必须使用完整绝对路径（以工作区根目录为前缀）。子代理没有工作区附着上下文，相对路径会导致其找不到文件或无法执行。

## 本仓库结构约定

| 目录 | 内容 |
|------|------|
| `app/` | Android 客户端（Kotlin + Jetpack Compose），负责采集并上报心跳 |
| `cloud/` | Cloudflare Worker（状态网页 + API），部署到 `status.WaterRainCat.com` |
| `.github/workflows/` | CI（单测 / lint / 构建）与 Release（打 tag 触发） |

## 接口契约

云端接口契约是 App 与 Worker 的唯一耦合点，任何一侧改动都必须同步另一侧与本文件说明。详见 `cloud/README.md` 与 `app/src/main/java/com/rainy/status/data/` 下的 DTO 定义。