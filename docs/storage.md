# 地图画存储与离线迁移

PNG 图片保存在插件数据目录中；地图物品的 PDC 只保存对应 PNG 的相对路径。
新格式没有 `maps.yml` / `maps.yaml`，也不把图片字节存入物品或区块 PDC。
默认数据目录由 `getDataFolder()` 决定，为 `plugins/ReColorfulMap`，注意大小写。

## 文件与物品格式

```text
plugins/ReColorfulMap/
├── maps/
│   └── <地图画 UUID>/
│       ├── raw.png
│       ├── thumb.png
│       ├── 0_0.png
│       ├── 1_0.png
│       ├── 0_1.png
│       ├── 1_1.png
│       └── meta.txt       # 旧版留下的可选日志
└── deliveries/
    └── <地图画 UUID>.properties  # 新生成地图的交付/收费记录
```

| 文件 | 含义 |
| --- | --- |
| `raw.png` | 下载的原始图片，尚未应用生成时指定的缩放比例 |
| `thumb.png` | 整幅地图画物品使用的 128 × 128 缩略图 |
| `<x>_<y>.png` | 128 × 128 图片分块；左上角为 `0_0`，向右增加 x、向下增加 y |
| `meta.txt` | 旧版操作日志，不参与身份识别、显示或取回 |

物品使用字符串类型的 PDC 键 `re_colorful_map:image_path`：

```text
整幅物品：maps/<UUID>/thumb.png
展示框分块：maps/<UUID>/<x>_<y>.png
```

路径相对于插件数据目录，只接受规范 UUID 和上述文件名。绝对路径、路径穿越及符号链接不受支持。
Minecraft 原生地图 ID 继续负责客户端显示；插件根据物品的路径识别图片，不依赖持有者当前所在世界。
在 Paper 26.2 存档中，此 PDC 位于物品的
`components["minecraft:custom_data"].PublicBukkitValues` 下。

图片下载、生成、渲染读取、墙面资料准备和删除在后台进行。
渲染使用最多 128 张图片的共享缓存及有界待绘制结果队列；玩家上线、打开容器及地图实体加载时按需恢复渲染器。
存储新图片先写临时目录，再原子发布完整目录，防止半幅图片被使用。
新生成地图的交付记录用于断线领取及收费恢复，不是地图 ID 索引，旧地图显示不依赖该记录。
生成队列、收费规则和恢复命令见 [运行说明](runtime.md)。

## 从 ColorfulMap 2.3 迁移

**本次格式调整需要停服迁移物品存档，仅复制 maps 目录已不够。**
旧版物品没有新路径 PDC；脚本使用旧 `maps/maps.yml` 将图片路径写入现有地图物品。
它会处理玩家背包、末影箱、展示框、掉落物、容器及其嵌套物品，并将 PNG 复制到目标目录。
旧插件目录保留，目标不再保留旧索引。整幅物品及已放置分块均可继续使用。

1. 停止服务器，备份世界、玩家数据及 `plugins/ColorfulMap`。
2. 将本仓库的整个 `scripts` 目录放到服务器目录下。需要 Linux/macOS（或 WSL）、Python 3.10+ 和 Python venv；包装脚本首次运行会在用户缓存目录建立独立环境并安装固定版本 PyYAML。
3. 在服务器目录执行预检。默认只报告结果，不改动存档或插件数据：

   ```bash
   bash scripts/migrate-colorfulmap.sh --server-root .
   ```

4. 执行迁移：

   ```bash
   bash scripts/migrate-colorfulmap.sh --server-root . --apply
   ```

5. 移除旧 ColorfulMap JAR，安装 ReColorfulMap，再启动服务器。Paper 26.2 需要 Java 25。
   保留原世界及原生地图数据，不要删除原生地图文件。

默认源目录为 `plugins/ColorfulMap`，目标为 `plugins/ReColorfulMap`。
若使用此前基于索引的 ReColorfulMap 版本，可原地迁移：

```bash
bash scripts/migrate-colorfulmap.sh --server-root . \
  --source plugins/ReColorfulMap --target plugins/ReColorfulMap --apply
```

如果已经将旧 `maps` 复制到新目录，也可按上述原地迁移方式处理。
源和目标相同时，旧索引会先备份，再删除。
插件启动若发现 `maps.yml` 或 `maps.yaml`，会提示先执行离线迁移，避免带着未转换的物品继续运行。

脚本自动发现服务器目录中的世界；外部世界或世界名与目录名不一致时，用 `--world` 明确指定，可以重复传入：

```bash
bash scripts/migrate-colorfulmap.sh --server-root /srv/minecraft \
  --world resource=/mnt/worlds/resource --apply
```

应提供全部世界，包含暂时未启用的世界。脚本使用世界 `session.lock` 拒绝迁移正在运行的存档。
它同时读取 Bukkit 世界 UUID 和原生地图数据，处理跨世界移动的地图；遇到无法确定归属的重号地图会中止，不猜测图片。
旧存档从 `uid.dat` 读取世界 UUID；Paper 26.2 从
`world/dimensions/<命名空间>/<维度>/data/paper/metadata.dat` 读取。
主世界、地狱和末地自动识别。通过其他插件创建的自定义维度，若存档无法确定其 Bukkit 世界名，需明确指定：

```bash
bash scripts/migrate-colorfulmap.sh --server-root . \
  --dimension ResourceWorld=world/dimensions/custom/resource --apply
```

`--dimension` 左侧必须是旧 `maps.yml` 使用的 Bukkit 世界名，右侧是对应维度目录；可以重复传入。
使用标准维度键、但由插件赋予非默认 Bukkit 世界名的维度，也可通过此参数明确指定。

## 备份、回滚与范围

全部文件先预检并写入临时区；检查通过后，才备份被修改文件并逐个原子替换。
事务记录保存在服务器目录 `.recolorfulmap-migrations/<事务目录>/`，执行结果会输出具体路径。
迁移中出错会尝试自动回滚；进程被强制终止后，可保持关服并执行：

```bash
bash scripts/migrate-colorfulmap.sh --server-root . \
  --rollback .recolorfulmap-migrations/<事务目录>
```

回滚会核对备份和当前文件的哈希；服务器启动后若已修改存档，会拒绝覆盖这些新数据。
完成后再次运行会验证迁移记录，未改变的数据不会重复迁移。
源目录、事务备份以及完整服务器备份都应保留到验收结束。

迁移器支持旧 `tag` 和现代 `components` 物品格式，并按 26.1/26.2 的
`data/minecraft/maps/<ID>.dat`、`players/data` 等路径扫描。
它递归处理各维度的实体区块、方块实体区块和物品容器。
NBT 中不相关的字段保留原始字节，包括 Java modified UTF-8 字符串；未修改的区块保留压缩内容。
MCA 支持 gzip、zlib、无压缩及外置 `.mcc`；遇到 LZ4 或旧 Paper oversized 格式会在提交前中止。
其他插件数据库中的虚拟背包不属于世界存档，需由相应插件导出或另行处理。
本仓库早先未投入使用的“PDC 保存图片字节”格式不在迁移范围内。

## 放置、取回与自动删除

整幅地图放置到展示框阵列后，各分块引用同一个 UUID 目录。
点击任意分块取回完整阵列，仍得到整幅地图物品；放置、整幅取回、拾取、堆叠和区块卸载都不删除图片。
阵列缺块或混入其他图片时不会清空相邻物品；未加载区域或 Folia 线程无法安全访问的区域保留原状并提示重试。

从 5.0.1 开始，新生成画作和 `/mapmanage copy` 副本分别使用独立 UUID 目录，目录带
`.independent-artwork` 标记。该标记只声明本插件通过支持路径创建的数据所有权，不会发现其他插件私自复制的物品。
复制整图时复制已有 PNG；复制单块时用该块创建新的 1×1 画作，其他已损坏分块不会阻止幸存分块复制。

掉落的独立整图因伤害、自然消失或掉出世界而真正销毁时，插件异步删除该 UUID 的目录。
独立画作的一个分块销毁时，只删除该分块文件，其余分块仍可显示和复制；最后一个分块消失后再清理目录。
Paper/Folia 的明确实体移除原因用于判断；缺少该事件的旧服务端保留图片并在启动时提示。
潜影盒、收纳袋自然消失或掉出世界时，按上述规则递归处理内部地图；容器受到伤害可能释放物品，
因此不立即递归删除，后续内部地图真正销毁时才处理。

旧 ColorfulMap 2.3 迁移的共享图片，以及没有独立标记的旧 ReColorfulMap 图片，均保留原关联，
不因其中某个物品销毁而自动删除。迁移脚本不会新增独立标记，也不强拆旧堆叠或墙面。
不要手工给共享目录加标记。管理员应结合世界、玩家和其他插件的数据审核磁盘清理，不能凭当前在线物品数判断无引用。

仅支持 `/mapmanage copy` 复制；工作台和制图台的图片复制配方会被取消且不消费材料。
创造模式复制、复制含图容器、其他插件直接 `clone/setItem` 不在支持范围，可能绕过独立复制并重新引入共享路径。
普通创造背包移动不被拦截；创造界面删除、`/clear` 等没有可靠销毁事件的操作也不自动删图。
这些边界与复制失败处理见[复制与数据生命周期](copy-and-lifecycle.md)。

`/cleancolorfulmap` 清理写入或删除失败遗留的私有临时目录，不扫描删除尚存的地图画目录。
备份时应同时保存 `plugins/ReColorfulMap/maps`、`plugins/ReColorfulMap/deliveries`、世界和玩家数据。

## 验证

自动化测试覆盖 PDC 路径读写、PNG 存储、缓存失效、销毁原因筛选、任意分块整幅取回和重新放置，
以及迁移中的玩家/容器/展示框物品、26.2 路径、NBT 保真、坏文件拒绝、备份和回滚。
另已在隔离 Paper 26.2 和 Folia 26.2 中使用真实协议客户端验证，并运行原始 ColorfulMap 2.3 JAR
生成旧数据完成升级链路。独立存档检查器确认迁移只修改目标字段；真实进程强杀后的回滚也已验证。
具体环境、覆盖范围、修复和制品哈希见 [端到端验证记录](e2e-validation.md)。
后续生成、收费、缓存与墙面优化的回归见 [运行优化验证记录](optimization-validation.md)。

建议在服务器副本中迁移后检查背包、箱子和墙面地图，执行整幅取回与重放，再重启检查显示。
使用专门的新生成地图验证独立副本的烧毁/自然消失只影响自身，单块销毁保留其余分块，旧共享目录继续保留。
运行时图片删除与 Minecraft 世界保存不是同一个事务；突然崩溃后的旧保存点可能仍含有已删除图片的物品，
因此应将世界、玩家与图片目录作为一组备份和恢复。

5.0.1 的复制、旧共享保护与 26.1.2 回归见[复制与数据生命周期](copy-and-lifecycle.md)。上述 26.2 历史测试不替代该版本的新验证。
