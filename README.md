# 智能工作台 Smart Workbench（Minecraft 1.12.2 版）

[![Build & Release](https://github.com/BarryAlen777/Smart-workbench/actions/workflows/build.yml/badge.svg)](https://github.com/BarryAlen777/Smart-workbench/actions/workflows/build.yml)

一款高度自动化、可高度自定义的 Minecraft 合成辅助模组。新增「智能工作台」方块：自动扫描并联动周围存储容器，无需手动取放材料。内置可视化合成 UI、多级链式合成、无人值守自动合成、输入输出容器分离绑定，并兼容 JEI / HEI 配方转移与多态合成（Polymorph），彻底解放双手。

> 本分支是 **1.12.2 移植版**：基于 Forge 1.12.2（14.23.5.2847+），兼容 Cleanroom 加载器。
> 上游 1.20.1 版本见仓库其他分支。

## 环境要求

| 项目 | 要求 |
| ---- | ---- |
| Minecraft | 1.12.2 |
| Forge | 14.23.5.2847 或更高 |
| Cleanroom | 兼容（推荐 Cleanroom 0.6+） |
| 前置模组 | 无 |

## 安装

把 `smart_workbench-1.12.2-<版本>.jar` 放进 `.minecraft/mods` 目录，启动游戏即可。推荐同时安装 JEI / HEI（如 HadEnoughItems）以获得配方查询与「+」一键取料体验（可选）。

## ✨ 核心功能

1. **容器自动联动 · 可视化合成 UI**
   自动侦测并接入范围内所有容器，打开 GUI 即可预览所有可合成物品。点击物品即自动从绑定容器提取材料填充合成网格，无需手动翻箱倒柜。
2. **丰富的快捷合成操作**
   - 左键点列表项：自动取料填充合成格
   - Shift + 左键：直接从存储合成一次，产物进背包
   - Ctrl + 左键（可改键）：批量合成，直到材料用完
   - Alt + 左键：对选中项一键自动合成
3. **链式递归合成（自动补齐中间材料）**
   目标材料不足时，自动规划缺失中间物品的合成路径；确认后逐级合成全部半成品，从原材料到成品一步到位。
4. **无人值守自动合成**
   Alt + 左键设定目标后持续自动合成，产物优先送入绑定的输出容器，适合挂机量产。
5. **扳手绑定 · 输入输出分离**
   自带「智能扳手」：
   - 潜行 + 右键空中：切换「取料容器 / 输出容器」绑定模式
   - 右键容器 → 右键工作台：完成绑定（重复点同一容器 = 取消绑定）
   - 潜行 + 右键工作台：清空该工作台全部绑定
6. **全维度可配置**（`config/smart_workbench.cfg`）
   - 容器扫描范围：默认 5×5×5，最大 50×50×50
   - 取料 / 输出容器绑定上限：1–50 可调
   - 单次自动合成上限：默认 64，最大 12318
   - 链式合成深度：自动补齐中间材料的最大层数，默认 5 层（1–16 可调）
   - 可合成列表容量、方块黑名单等
7. **配方模组联动**
   - JEI / HEI（HadEnoughItems）：在工作台界面内点击配方的「+」即可从存储取料；原料不足时按钮变红并提示缺几种材料
   - Polymorph Legacy（多态合成）：同产物多配方时按你的选择渲染产物
8. **安全的合成管道**
   所有取料合成都先模拟规划、再真实执行，失败自动全额退还材料；服务器端限频防连点，权限校验在服务端完成。

## ⌨️ 按键绑定

| 操作 | 默认按键 | 说明 |
| ---- | ---- | ---- |
| 刷新列表 | R | 等同界面「刷新」按钮 |
| 批量合成 | 左 Ctrl + 左键 | 点列表项或产物格批量合成 |
| 全部展开 / 折叠 | 空格 + 左键 | 作用于右侧列表区域 |
| 自动合成 | Alt + 左键 | 选中项一键自动合成 |

以上按键均可在「选项 → 控制」中修改（自动合成为固定 Alt + 左键）。

## 🛠 从源码构建

1.12.2 的 ForgeGradle 2.3 **必须使用 JDK 8** 构建：

```bash
# 设置 JAVA_HOME 指向 JDK 8 后执行
gradle clean build
```

- Gradle 版本：7.6.4
- ForgeGradle：com.anatawa12.forge:ForgeGradle:2.3-1.0.8（社区维护版，自带已停运 mcpbot 映射镜像）
- 映射：MCP stable_39

构建产物在 `build/libs/`，其中不含 `-sources` 的 jar 即为可安装模组。

## 🤖 自动构建与发布

仓库配置了 GitHub Actions（`.github/workflows/build.yml`）：

- 推送到 `main` / `1.12.2` 分支或提交 PR：自动构建并上传构建产物（Artifacts）
- 推送 **`v*` 标签**：自动以标签版本号构建，并创建 GitHub Release 附带模组 jar（正式版发布）

发布正式版只需：

```bash
git tag v1.0.0
git push origin v1.0.0
```

CI 会自动构建出 `smart_workbench-1.12.2-1.0.0.jar` 并发布到 Release 页面。

## 📦 与 1.20.1 上游版本的差异

- 精妙背包（Sophisticated Backpacks）为 1.16+ 模组，1.12.2 无对应版本，相关兼容未移植
- EMI / REI 为高版本模组，1.12.2 由 JEI / HEI 提供同等体验
- 多态合成使用 1.12.2 的 Polymorph Legacy

## 📄 开源协议

MIT，详见 [LICENSE](LICENSE)。
