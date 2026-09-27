# MCP-HELPER（mcpmod）

本目录整合了用户提供的 r4 修复源码，专用于 Minecraft 1.20.1 Forge。
`src/main/java` 中的同名类覆盖 `packages/common` 的反射实现；未覆盖的类和资源仍使用公共版本。
`client-overrides.gradle` 负责排除重复文件并执行 `reobfJar`，不要把这里的 `McBridge` 复制到公共模块。
构建使用上游固定的 Forge 47.4.10；原始 r4 工程使用 47.4.0。

在本目录以 Java 17 运行 `./gradlew build`（Windows 使用 `gradlew.bat build`）。
以下说明介绍整合的功能；生产环境下的游戏内行为仍需实际验证。

面向 AI 代理的 Minecraft 客户端桥接模组。它在游戏内起一个本地 HTTP 服务，
让外部程序（AI 代理、脚本、调试工具）能读取游戏状态、截图、模拟操作，
并在需要时**接管**鼠标与界面。

- 模组 ID：`mcpmod`
- 显示名：MCP-HELPER（保留原 ModDev MCP 的 `mcpmod` ID 和接口）
- 目标环境：**Minecraft 1.20.1 + Forge 47.4.x（客户端模组）**
- Java：17
- 协议：本地 HTTP/JSON 接口 + SSE 事件流（不是持久 WebSocket 长连接）
- 作者：langyo　许可证：MIT

> **本版（r4）要点**：单机按 `ESC` 默认不再冻结世界（`PauseScreen` 换成不暂停的等价菜单）；
> 控制模式默认改为 `shared`，**玩家可以自由转动视角**；新增 `open_to_lan`
> 可以一键把单机世界开到局域网，配合第二个客户端就能让 AI 拿到完全独立的视角。
> 细节见第六节。
>
> **本版（r5）要点**：**移除 HUD / 界面右上角的悬浮按钮**，改为按 **`F9`** 打开
> **「MCP 连接状态」界面**，在其中查看 HTTP 端口、SSE 客户端数、控制模式与**局域网（LAN）连接**，
> 并可一键开局域网 / 切换控制模式 / 打开调试页。同时做了连接速度优化：
> **去掉启动固定 5 秒等待**、SSE 广播改成异步（不再阻塞 `/api/cmd`）、调试日志默认关闭。
> 细节见第六节第 7 条。
>
> **本版（r6）要点**：AI（codex）在局域网世界里不再**和玩家共用一个身体**。
> 一旦世界发布到局域网，模组会在集成服务器上生成一个真正加入玩家列表的
> **独立身体 `MCP-HELPER`**：**创造模式 + OP（作弊命令）**，名字固定，
> 世界里可见。按 **`F9`** 打开状态界面的同时，会把它**传送到玩家所在的维度与位置**；
> 也可以随时调用 `helper_goto_player`。接口新增 `spawn_helper` / `helper_info` /
> `helper_goto_player` / `helper_move` / `helper_command` / `helper_remove`，
> `execute_command` 也支持 `as_helper=true` 以 MCP-HELPER 的身份执行命令。
> 调试页已完整汉化，并补上了这些新命令的下拉项。
>
> **本版（r7）要点**：**`9876` 不再是写死的端口**。端口可在
> `config/mcpmod.properties`（首次启动自动生成模板）、游戏内 `F9` 界面底部的输入框
> 或命令 `set_port` 里修改，写入配置文件并**热重启 HTTP 服务**，无需重启游戏；
> 读取优先级为 `-Dmcp.port` → `MC_MCP_PORT` → 配置文件 → 默认 `9876`。
> 新增 `get_port` / `set_port` 两个命令，调试页顶部也加了端口输入框。
>
> **本版（r8）要点**：按《优化.txt》把模组定位成「Codex 负责脑子，MCP-HELPER 负责身体」，
> 新增 **8 个高效接口**，把「一个方块一次调用」压成「一次调用完成一批」：
> `scan_area`、`build_structure`、`compare_structure`、`get_build_progress`、`cancel_build`、
> `save_structure`、`place_structure`、`list_structures`。
> 返回一律紧凑（相对坐标数组、只回差异、只回必要字段），不新增 `build_wall` / `scan_blocks`
> 这类细分工具；`build_structure` 支持一次提交上千方块并跨 tick 放置。

---

## 一、安装

1. 安装 **Minecraft 1.20.1** 与 **Forge 47.4.x**。
2. 把构建产物放进 `.minecraft/mods/`：

   ```
   build/libs/MCP-HELPER-1.20.1-forge-0.6.0-r1.jar
   ```

3. 启动游戏。模组初始化后**立即**在后台线程 `MCP-HTTP` 上启动服务（此前固定 5 秒等待已移除）。
4. 浏览器打开 `http://127.0.0.1:9876/debug` 可看到内置调试页（端口若改过就换成新端口）；或用下面的接口自测。
5. 游戏内按 **`F9`**（可在 选项 → 控制 改绑）打开 **「MCP 连接状态」界面**，
   查看端口、SSE 客户端、控制模式与局域网连接情况，并在底部**直接改端口**。

> 这是**纯客户端模组**。单人和联机都能用，但联机时命令权限仍受服务器约束。

---

## 二、端口与访问（`9876` 已改为可配置）

| 项目 | 说明 |
| --- | --- |
| 默认端口 | `9876` |
| 监听地址 | `0.0.0.0`（同一局域网的其它机器也能访问，见「安全提示」） |
| 配置文件 | `config/mcpmod.properties`，首次启动自动生成带注释的模板 |
| 读取优先级 | `-Dmcp.port=XXXX`（JVM 参数） → `MC_MCP_PORT`（环境变量） → 配置文件 → 默认 `9876` |
| 服务启动时机 | 模组初始化后立即，后台线程启动 |

### 改端口的三种方式

1. **游戏内 F9 界面（推荐）**：底部输入框填端口，点 **「应用并重启」**。
   会写入配置文件并**热重启 HTTP 服务**，当前游戏不必退出；旁边的「恢复默认」回到 `9876`。
2. **配置文件**：编辑 `.minecraft/config/mcpmod.properties`，把
   `#port=9876` 的注释去掉、改成想要的端口，重启游戏生效：
   ```properties
   port=12345
   ```
3. **MCP 命令**：`set_port`（`params.port`）与界面等价，同样会写文件并热重启；
   `get_port` 返回 `configured_port` / `active_port` / `source` / `config_file` 等信息。
   调试页顶部也提供了端口输入框。

### 占用与回退

- **没配置端口时**（模板保持注释状态）：从 `9876` 起向下依次尝试 `9875 → 9874 → ... → 9000`，找到空闲端口就绑定，行为与旧版一致。
- **显式配置了端口时**（文件里写了 `port=`，或用了 JVM 参数 / 环境变量）：只绑定该端口；已被占用则本次启动失败，
  日志为 `[MCP-MOD] HTTP server failed: Address already in use`，`F9` 界面会显示「HTTP 服务 未启动」。
  此时换一个端口再点「应用并重启」即可，不需要重启游戏。

可选 JVM 参数（会体现在 `/api/status` 里）：

```
-Dmcp.port=9876        # 服务端口（优先级最高，会盖过配置文件）
-Dmcp.mod.version=...  # 覆盖 /api/status 的 version 字段
-Dmcp.mod.loader=...   # 覆盖 loader 字段
-Dmcp.mod.forge.version=...
```

---

## 三、HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/status` | 服务状态：`ok`、`version`、`loader`、`pid`、`port`、`uptime`、`control_mode`、`mouse_mode`、`player_can_look`、`no_pause` 等 |
| GET | `/api/screenshot` | 截取当前画面，返回原图与带 100px 网格坐标的 PNG（base64 data URL） |
| POST | `/api/cmd` | 统一调用入口，body 形如 `{"cmd":"方法名","params":{...}}` |
| GET | `/api/events` | SSE 事件流，推送模组侧调用事件；最多 4 个客户端，服务端保留约 300 秒 |
| GET | `/api/calls` | 最近 50 条调用记录（JSON 数组） |
| GET | `/` 或 `/debug` | 内置调试页面 |

调用示例：

```bash
curl http://127.0.0.1:9876/api/status

curl -X POST http://127.0.0.1:9876/api/cmd \
  -H 'Content-Type: application/json' \
  -d '{"cmd":"get_player_info"}'

curl -X POST http://127.0.0.1:9876/api/cmd \
  -H 'Content-Type: application/json' \
  -d '{"cmd":"execute_command","params":{"command":"time set day"}}'
```

---

## 四、可用方法（`/api/cmd` 的 `cmd`）

**状态 / 观测类**

`ping`、`screenshot`、`screenshot_to_file`（`params.path`）、`get_player_info`、
`get_world_info`、`debug_fields`、`get_screen_buttons`、`enumerate_widgets`。

**输入 / 操作类**

`click`（`x`、`y`、`button`）、`press_key`（`key`、`hold_seconds`）、`type_text`（`text`、`press_enter`）、
`paste_text`、`scroll`、`scroll_at`、`direct_scroll`、`select_list_item`、`mouse_drag` / `drag`、
`hotkey`（逗号分隔按键）、`right_click`、`use_item`、`place_block`、`switch_tab`、
`click_button_id`、`click_button_index`、`call_screen_method`。

**视角 / 世界 / 界面**

`set_view_angle`（`yaw`、`pitch`）、`look_delta`（`delta_yaw`、`delta_pitch`）、
`execute_command`（`command`）、`pause_game`、`open_chat`、`close_screen`、`release_mouse`、
`set_gamemode`（`mode`）。

**环境 / 限制开关（不需要控制模式）**

`get_connection_info`、`open_to_lan`（`port`、`allow_cheats`）、`set_no_pause`（`enabled`）、
`set_mouse_sharing`（`enabled`）。

**端口配置（不需要控制模式）**

`get_port`（返回 `configured_port` / `active_port` / `source` / `config_file`）、
`set_port`（`params.port`，写入配置文件并热重启 HTTP 服务）。

**独立身体 MCP-HELPER（不需要控制模式）**

`spawn_helper`（在已开局域网的本地世界生成，创造 + OP，已存在则忽略）、
`helper_info`（名称 / 维度 / 坐标 / 游戏模式 / OP / 是否无敌）、
`helper_goto_player`（传送到玩家所在维度、所在位置）、
`helper_move`（相对 `dx`/`dy`/`dz`，或绝对 `x`/`y`/`z`）、
`helper_command`（以 MCP-HELPER 的 OP 身份执行命令，`params.command`）、
`helper_remove`（移除独立身体）。
`execute_command` 增加可选参数 `as_helper=true`，等价于以 MCP-HELPER 身份执行。

**高效建造接口（不需要控制模式，r8 新增）**

> 设计原则：**Codex 负责脑子，MCP-HELPER 负责身体。**
> 一个指令完成一批操作，返回尽量紧凑；细分功能一律做成参数，不新增独立工具。

- `scan_area`：扫描长方体区域，返回
  `{"origin":[x,y,z],"size":[sx,sy,sz],"count":N,"blocks":[[dx,dy,dz,"minecraft:stone"],...]}`。
  区域用 `pos1`+`pos2` 或 `origin`+`size` 指定（`[x,y,z]` 或 `x,y,z` 都行）；
  `include_air=false`（默认跳过空气）、`include_block_state=false`（默认只回方块 ID）、
  `include_block_entities=false`、`include_entities=false`；
  `limit`（默认 20000）与 `offset` 用于分页，被截断时返回 `truncated` / `next_offset`。
- `build_structure`：一次提交成百上千个方块，按服务器 tick 分批放置，不卡顿。
  - `origin` + `blocks`，例如
    `{"origin":[100,64,200],"blocks":[[0,0,0,"minecraft:stone"],[1,0,0,"minecraft:gold_block"]]}`；
  - 方块串支持完整方块状态与 NBT：`minecraft:oak_stairs[facing=north]`、`minecraft:chest{...}`；
  - 也支持纯文本：`"0,0,0,minecraft:stone;1,0,0,minecraft:gold_block"`；
  - `batch`（默认 4096 块/tick）只控制节奏，**分几批建造由 Codex 决定**；
  - 返回 `{"task_id":"b1","status":"running","total":N,"done":0,"failed":0}`。
- `get_build_progress`：只回 `task_id` / `status` / `total` / `done` / `failed`；
  `status` 取值 `running` / `done` / `done_with_failures` / `cancelled` / `idle`。
- `cancel_build`：取消当前（或指定 `task_id`）任务，返回同样的精简字段。
- `compare_structure`：只回差异
  `{"ok":bool,"missing":[[dx,dy,dz,"期望方块"]],"wrong":[[dx,dy,dz,"期望","实际"]],"extra":[[dx,dy,dz,"实际"]]}`；
  期望来源可以是已保存结构的 `id`，也可以是内联 `blocks`；
  `include_block_state=true` 时按完整方块状态比较（默认可只比方块 ID），
  `include_extra=false` 可关闭「体积内多余方块」检查。

**结构与预制建筑（不需要控制模式，r8 新增）**

只保留 3 个核心工具，结构本体与预制元数据分开存放：

- `save_structure`：把区域保存成 Minecraft 原生 Structure NBT。
  `pos1`/`pos2`（或 `origin`+`size`）、`name`（`ns:path`，缺省命名空间 `mcpmod`）、
  `prefab=false`、可选 `display_name` / `category` / `anchor`（默认 `bottom_center`）、
  `include_air=false`、`include_entities=false`；
  保存方块状态与方块实体 NBT，默认忽略空气、不保存实体，**不把方块数据回传给 AI**。
  返回极简：`{"id":"mcpmod:alchemy_furnace","size":"9x8x9","prefab":true,"status":"saved"}`。
  可选 `export_to=<目录>`，导出成可直接复制进 `src/main/resources` 的
  `data/<ns>/structures/*.nbt` 与 `data/<ns>/prefabs/*.json`。
- `place_structure`：把已保存结构放进世界。`id`、`origin`、`rotation=0|90|180|270`、
  `mirror=none|left_right|front_back`，可选 `anchor`（默认取保存时记录的锚点，
  即默认 `bottom_center`；传 `anchor=min_corner` 可把 `origin` 当作最小角）、
  `include_entities=true`。**不需要把方块坐标再发一遍。**
- `list_structures`：默认只回 `id` / `display_name` / `size` / `prefab`
  （以 `{"fields":[...],"structures":[[...]]}` 的紧凑元组返回），不返回完整 NBT 或方块列表。

运行时结构落在游戏目录：

```
<minecraft>/mcp_structures/structures/<命名空间>/<名称>.nbt   # 原生 Structure NBT
<minecraft>/mcp_structures/prefabs/<命名空间>/<名称>.json     # 预制建筑元数据
<minecraft>/mcp_structures/index.json                        # 运行期索引
```

`prefab=true` 时只注册到运行期 Prefab 列表（同时写一份到世界 `generated/` 目录，
`/place template <id>` 也能用），**不会在游戏运行时改动模组 JAR**；
确认成为正式资产后再用 `export_to` 导出，由开发阶段复制进 `src/main/resources`。

> 虚影预览、旋转、镜像、调整位置属于客户端功能，本版没有为它们新增
> `preview_prefab` / `rotate_prefab` / `mirror_prefab` 之类的 MCP 工具。

**控制模式**

`enter_control_mode`（可选 `mode=shared|detached`）、`exit_control_mode`、`overlay_click`（`x`、`y`）。

---

## 五、控制模式：为什么需要它

模组默认处于**只读状态**，防止误操作。以下方法不需要控制模式即可调用：

```
ping / screenshot / get_player_info / get_world_info / debug_fields /
get_screen_buttons / enumerate_widgets / overlay_click /
get_connection_info / open_to_lan / set_no_pause / set_mouse_sharing /
spawn_helper / helper_info / helper_goto_player / helper_move / helper_command / helper_remove /
scan_area / build_structure / compare_structure / get_build_progress / cancel_build /
save_structure / place_structure / list_structures /
enter_control_mode / exit_control_mode /
set_gamemode / release_mouse / pause_game / close_screen / open_chat
```

其余所有**输入类方法**（点击、按键、输入文字、拖动、视角、执行命令、截图存文件等）
必须先进入控制模式，否则返回：

```json
{"error":"not in control mode","hint":"Enter control mode via ESC > MCP Take Over"}
```

**进入方式：**

- 按 **`F9`** 打开「MCP 连接状态」界面，点 **「进入 MCP 控制」**；或
- 在游戏里按 `ESC` 打开暂停菜单，点击新增的 **「MCP 接管」** 按钮；或
- 直接调用 `enter_control_mode`；或
- 在游戏里按 **`F8`** 快速切换（见下）。

**退出方式：**

- 按 **`F9`** 打开「MCP 连接状态」界面，点 **「退出 MCP 控制」**；或
- 按 **`F8`**；或
- 调用 `exit_control_mode`；或
- 调用 `pause_game` 打开暂停菜单。

### 两种鼠标策略

`enter_control_mode` 现在有两种模式，用 `mode` 参数选择：

| 模式 | 行为 | 玩家能否用鼠标转视角 |
| --- | --- | --- |
| `shared`（**默认，推荐**） | 光标保持锁定，鼠标仍归玩家；AI 直接调用接口操作 | **能，完全自由** |
| `detached`（旧行为） | 每 tick 强制 `GLFW_CURSOR_NORMAL` 并清零鼠标增量 | 不能，只能靠 AI 的 `set_view_angle` |

```bash
# 默认就是 shared，不需要传参数
curl -X POST http://127.0.0.1:9876/api/cmd -H 'Content-Type: application/json' \
  -d '{"cmd":"enter_control_mode"}'

# 需要旧行为（AI 独占鼠标、绝对坐标拖动/滚动）时显式指定
curl -X POST http://127.0.0.1:9876/api/cmd -H 'Content-Type: application/json' \
  -d '{"cmd":"enter_control_mode","params":{"mode":"detached"}}'
```

`shared` 模式下界面点击不依赖真实光标：模组是直接把点击坐标派发给当前界面/控件的
（`McBridge.screenMouseClicked` / `pressWidget`），打开任何界面时原版本来就会自动释放光标，
所以 `click`、`click_button_id`、`overlay_click` 等照常可用；`set_view_angle` / `look_delta`
也是直接写玩家朝向，不经过鼠标。

需要 AI 用**绝对坐标**做原版级别的拖动/滚动时，可以先调用 `release_mouse` 显式释放光标，
或直接用 `detached` 模式；`set_mouse_sharing {"enabled":false}` 可以把本次游戏运行的默认策略切回 `detached`。

---

## 六、使用限制（重点）

### 1. 玩家按 ESC 暂停时，MCP 连接会断开吗？世界会冻结吗？

**连接不会断开；世界也（默认）不再冻结。**

- HTTP 服务跑在独立的 `MCP-HTTP` 线程上，**不受游戏 tick 和单机暂停影响**，暂停时照常接受请求。
- 默认开启了**「ESC 不暂停」**：玩家按 ESC 时，模组会用 Forge 的 `ScreenEvent.Opening`
  把原版 `PauseScreen` 换成一个行为完全相同的子类，只把 `isPauseScreen()` 覆写成 `false`。
  Minecraft 的暂停标志本来就是每帧由
  `hasSingleplayerServer() && screen.isPauseScreen() && !integratedServer.isPublished()`
  推导出来的，`isPauseScreen()` 一变，**客户端世界和集成本地服务器都会继续 tick**：
  实体照常移动、`execute_command` 立即结算，MCP 的操作不再"卡在暂停里"。
- 菜单本身、按钮、音效都还是原版的，只是不再冻结。想让世界暂停，暴露的开关是：

  ```bash
  # 恢复原版暂停行为
  curl -X POST http://127.0.0.1:9876/api/cmd -H 'Content-Type: application/json' \
    -d '{"cmd":"set_no_pause","params":{"enabled":false}}'
  ```

- 暂停时 `mc.screen != null`，HUD 上的悬浮按钮会隐藏，但接口本身照常可用；
  想操作暂停菜单本身，需要先进入控制模式（菜单里会多出一个 **「MCP Take Over」** 按钮）。
- 直接用 `/api/status` 或 `get_connection_info` 可以确认 `no_pause` / `game_paused` 当前状态。

> 注意：「ESC 不暂停」只在**单机、且世界还没开局域网**时介入。多人服务器和已开局域网的世界
> 本来就不会因为打开菜单而停摆，模组不会做任何替换。

### 2. 玩家移动时，MCP 连接会断开吗？

**不会断开。** 玩家移动只是游戏内状态变化，和 HTTP 服务没有关系，接口调用不会因此中断。

- 键盘移动（WASD）仍由游戏正常处理。
- 控制模式默认是 `shared`，**鼠标仍在玩家手里，转视角完全正常**；AI 需要动视角时用
  `set_view_angle` / `look_delta`，它直接写玩家朝向，不经过鼠标，双方互不干扰。
- 只有显式用 `detached` 模式（或多年前那种默认行为）时，鼠标才会被模组接管。
- 移动过程中调用会切换界面的方法（`open_chat`、`close_screen`、`pause_game`），画面状态会变化，属于预期行为。

### 3. 玩家在其他维度 / 切换维度时，MCP 连接会断开吗？

**不会断开。**

- 模组不缓存 `player` / `level`，每次请求都重新读取，因此切换维度后
  `get_player_info` 的 `dimension` 字段、`get_world_info` 会返回**新维度**的信息。
- 切换维度的瞬间（加载界面期间）`player` / `level` 可能短暂为 `null`，
  此时会返回 `{"name":null}` 或 `{"world_name":null}`，**稍后重试即可**，不是连接断开。
- 维度切换只影响世界数据内容，不影响 HTTP 服务存活。

### 4. 视角限制：现在默认已经解除

旧版本里"进入 MCP 控制模式后玩家无法转动视角"是**最明确的限制**，原因很清楚：

- 旧行为（现在叫 `detached`）为了保证 AI 能用绝对坐标点击，每 tick 强制
  `GLFW_CURSOR_NORMAL` + `mouseGrabbed = false`，并把累计鼠标位移清零；
  光标一旦释放，原版就不再累加鼠标增量，玩家自然转不了视角。

**现在的默认行为 `shared` 不再碰光标**：进入控制模式时模组什么都不做（没有界面时确保光标锁定，
有界面时原版自己会释放），玩家的鼠标、视角、灵敏度、`F5` 视角切换全部照常。AI 侧的能力没有缩水：

| AI 想要做的事 | `shared` 下是否可用 | 说明 |
| --- | --- | --- |
| `click` / `click_button_id` / `overlay_click` / `type_text` | ✅ | 直接派发到界面与控件，不依赖真实光标；打开界面时原版会释放光标 |
| `set_view_angle` / `look_delta` | ✅ | 直接写玩家朝向 |
| `right_click` / `use_item` / `place_block` / `hotkey` / `press_key` | ✅ | 走 `MouseHandler`/`KeyboardHandler` 注入 |
| 原版级别的绝对坐标拖动 / 精确定位滚动 | ⚠️ 需要先 `release_mouse` | 或改用 `enter_control_mode {"mode":"detached"}` |
| 退出控制模式 | ✅ | 调用 `exit_control_mode`，或让玩家按 `F8` / 在 `F9` 界面里点按钮 |
| 查看连接 / 局域网状态 | ✅ | 玩家按 `F9` 打开界面；接口侧用 `get_connection_info` 与 `/api/status` |

想恢复旧行为：`enter_control_mode {"mode":"detached"}`，或者一次性把默认策略换掉
`set_mouse_sharing {"enabled":false}`。

### 5. AI 的独立身体：MCP-HELPER（本版新增）

以前 AI 只能借用玩家本人的客户端，所以"和玩家共用一个身体"。本版在世界发布到局域网
的那一刻，会在集成服务器上生成一具**真正加入玩家列表的独立身体 `MCP-HELPER`**：

- 名字固定为 **`MCP-HELPER`**，在玩家列表和世界里都可见（其它客户端也能看到它）；
- **创造模式**，并且已经 **OP**，因此可以执行 `/give`、`/tp` 等作弊命令；
- 免疫伤害、无重力，不会被怪打死或掉虚空；它不参与网络心跳，不会因为"没有客户端"被踢。

**把它叫到身边：** 游戏内按 **`F9`** 打开「MCP 连接状态」界面时，会自动把它传送到
**玩家所在的维度与坐标**；界面上也有「传送 MCP-HELPER」按钮。接口侧等价于：

```bash
# 确保独立身体存在，并传送到玩家所在维度、所在位置
curl -X POST http://127.0.0.1:9876/api/cmd -H 'Content-Type: application/json' \
  -d '{"cmd":"helper_goto_player"}'

# 查看它的维度 / 坐标 / 游戏模式 / OP 状态
curl -X POST http://127.0.0.1:9876/api/cmd -H 'Content-Type: application/json' \
  -d '{"cmd":"helper_info"}'

# 以 MCP-HELPER 的 OP 身份执行命令（不影响玩家本人的权限判定）
curl -X POST http://127.0.0.1:9876/api/cmd -H 'Content-Type: application/json' \
  -d '{"cmd":"helper_command","params":{"command":"/give @s diamond 1"}}'
```

`helper_move` 可以让这具身体自己走（相对 `dx`/`dy`/`dz` 或绝对 `x`/`y`/`z`），
`helper_remove` 则移除它。

> **边界说明**：截图、界面点击、按键注入仍然来自玩家所在的客户端进程，因此这些"看和点"
> 的操作还是玩家视角；而**世界身份**——移动、传送、执行命令——可以完全走 `MCP-HELPER`，
> 不再借用玩家的角色。如果你需要**连相机都完全独立**的第二视角，仍然可以按下面第 6 条
> 的做法另开一个客户端。

<details>
<summary>旧方案（可选）：开局域网 + 第二个客户端，获得完全独立的相机</summary>

1. 在原客户端调用一次开局域网（世界必须已经加载好）：

   ```bash
   curl -X POST http://127.0.0.1:9876/api/cmd -H 'Content-Type: application/json' \
     -d '{"cmd":"open_to_lan","params":{"port":"25565","allow_cheats":"true"}}'
   ```

   > 等价于游戏内 ESC → **对局域网开放**。返回里带 `port` 和 `hint`。

2. 用**第二个 Minecraft 客户端**（同样装本模组）连到 `127.0.0.1:25565`（跨机器就用主机局域网 IP）。
   第二个客户端要换一个 HTTP 端口，避免和第一个撞车：

   ```
   -Dmcp.port=9877
   ```

3. 之后 `9877` 上的那一份 MCP 就是"AI 玩家"：它有自己的 `player`、自己的相机，
   `set_view_angle` 只影响它自己；你的客户端视角完全自由。

</details>

`get_connection_info` 会返回 `lan_published` / `lan_port`，方便确认是否已经开好。

### 6. 其它已知限制与注意事项

- **退出控制模式后有短暂抑制**：约 200ms 内输入会被忽略，属正常保护。
- **「ESC 不暂停」的一个边角表现**：不暂停菜单是通过把 `isPauseScreen()` 改成 `false` 实现的，
  而原版 `LocalPlayer.handleNetherPortalClient()` 也会读这个值。所以**人正站在下界传送门里**
  打开 ESC 菜单时，菜单会被原版逻辑自动关掉（人已经进维度了，不影响游戏）。站着不动按 ESC 没有这个问题。
- **`F9` 是注册过的键位**：出现在 选项 → 控制 → 「MCP-HELPER」分类里，可以改绑。
  **`F8` 仍是硬编码快捷键**，不进重绑定列表；如果和其它模组冲突，请改用接口调用或 `F9` 界面里的按钮。
- **`open_to_lan` 的端口不自动回退**：默认 `25565` 被占用时返回
  `{"ok":false,"error":"publishServer failed..."}`，换一个 `port` 再调即可。
  `open_to_lan` 只在单机本地世界有效，在多人服务器上会返回错误。
- **开局域网会短暂暴露服务**：世界会在局域网内可见（原版也会打一条聊天提示）。
  不想要了就正常退出世界/关游戏。
- **SSE 客户上限**：`/api/events` 最多 4 个并发客户端，超出返回 `503`；单条 SSE 连接服务端保留约 300 秒后关闭，客户端需自行重连。
- **端口占用与回退**：未配置端口时默认从 `9876` 向下扫描到 `9000`；在配置文件 / JVM 参数 / 环境变量里
  显式指定了端口则只绑该端口，被占用会让本次启动失败——请在 `F9` 界面里换一个端口并点「应用并重启」，
  或用 `set_port`。
- **安全提示**：服务监听 `0.0.0.0`，同一局域网内任何人都能访问这些接口。请勿在不可信网络下开启，
  需要时用防火墙限制访问，或只在本机使用。
- **命令权限**：`execute_command` 是模拟玩家发送命令，权限取决于是否单人作弊 / 服务器 OP，模组不会提权。
- **平台支持**：当前构建只针对 **1.20.1 + Forge**。代码大量使用反射并保留多版本兼容路径，
  但未经其它版本实机验证，不保证可用。
- **版本号显示**：启动时会自动把模组版本与加载器写入 `/api/status` 的 `version` / `loader`，
  不再需要手动传 `-Dmcp.mod.version=`（仍可手动覆盖）。

### 7. 连接速度优化（r5）

针对「Codex 连接本模组速度很慢」做了以下改动：

1. **去掉启动固定 5 秒等待**：HTTP 服务在模组初始化后立即 bind 端口，MCP 客户端能更早连上。
2. **SSE 广播改异步**：`/api/events` 的事件广播改到独立的 `MCP-SSE` 线程、有界队列；
   慢速 SSE 客户端不再拖住 `/api/cmd`、`/api/screenshot` 的应答。并新增 15 秒一次的心跳注释，
   避免连接被中间层判定为卡死。
3. **调试日志默认关闭**：原先每个输入动作都会追加写 `mcp_debug.log` 并 flush。
   现在默认关闭，需要排查时用 `-Dmcp.debug=true` 或环境变量 `MC_MCP_DEBUG=1` 打开。
4. **HTTP 线程池 8 → 16**，并缓存 PID，减少每条请求的重复开销。
5. **首次 tick 预热反射缓存**，让第一条命令不必承担类扫描开销。
6. `/api/status` 新增 `sse_clients`、`requests`、`last_activity_ms`、`http_url`、`lan_addresses` 字段，
   状态界面与调试页直接读取。

---

## 七、兼容性说明（给排查问题的人）

关键成员（`Minecraft.getInstance()`、`player`、`level`、连接与命令发送等）
已改为**编译期直接引用**，构建时由 reobf 自动改写为 SRG 名，因此在
Forge 生产环境（SRG 映射）和开发环境（official 映射）下都能正确解析；
原先的反射路径保留为兜底。这修复了「服务能起、`ping` 正常，但玩家信息为 `null`、
`execute_command` 返回 `no player`」一类问题。

排查日志：默认**关闭**。需要时用 `-Dmcp.debug=true` 或 `MC_MCP_DEBUG=1` 打开，
反射调试信息会写到用户目录下的 `mcp_debug.log`。

---

## 八、目录速查

```
src/main/java/xyz/langyo/minecraft/mcp/mod/      模组入口、Forge 事件、暂停菜单按钮、F9 连接状态界面
src/main/java/xyz/langyo/minecraft/mcp/common/    HTTP 服务、消息分发、反射桥接、输入注入、截图
src/main/resources/mcp-debug/                     内置调试页
src/main/resources/assets/mcpmod/                 图标与多语言文本
```
