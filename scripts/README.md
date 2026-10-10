# ColorfulMap 离线迁移

在服务器完全关闭后运行，推荐先在完整存档副本中检查。要求 Linux/macOS
（也可使用服务器已停止的 WSL 环境）、Python 3.10+、`python3-venv`。
入口会在用户缓存目录创建独立虚拟环境并安装固定版本的 PyYAML；服务器目录不会安装 Python 依赖。

```bash
# 预检查，不修改现有图片或存档
bash scripts/migrate-colorfulmap.sh --server-root /srv/minecraft

# 一条命令正式迁移；无需提前复制 maps
bash scripts/migrate-colorfulmap.sh --server-root /srv/minecraft --apply
```

默认从 `plugins/ColorfulMap` 读取，写入 `plugins/ReColorfulMap`。整个旧目录保留。
目标获得 UUID 图片目录，旧索引只在迁移过程中读取，不写入新插件目录。
使用原生地图数据确定所属世界，再为世界存档中的地图物品添加
`re_colorful_map:image_path` 字符串，例如 `maps/<UUID>/0_0.png`。

如果正在迁移上一版 ReColorfulMap，可以原地转换：

```bash
bash scripts/migrate-colorfulmap.sh --server-root /srv/minecraft \
  --source plugins/ReColorfulMap --target plugins/ReColorfulMap --apply
```

原地转换会备份并移除旧 `maps.yml`。支持 `--world 世界名=/实际路径` 指定额外世界；
文件夹名与 Bukkit 世界名不一致时也应使用此参数。可重复传入多个 `--world`。

备份、哈希和事务状态保存在服务器根目录的 `.recolorfulmap-migrations/<事务ID>`。
遇到普通提交错误自动回滚；进程或主机异常退出后，保持停服，使用输出中的事务目录恢复：

```bash
bash scripts/migrate-colorfulmap.sh --server-root /srv/minecraft \
  --rollback .recolorfulmap-migrations/<事务ID>
```

回滚前会验证当前文件与备份，已经运行过服务器或手动修改了文件时会拒绝覆盖。
连续重复执行已完成的迁移会校验所有相关存档和图片后报告 `already_migrated`；
存档、世界集合或图片已改变时明确停止，避免误认为恢复的旧存档已经迁移。
保留事务目录和完整服务器备份，不要将一个旧玩家文件单独混入新存档后再次启动。

支持旧 `tag.PublicBukkitValues` 和新 `components.minecraft:custom_data.PublicBukkitValues`，
包括 Minecraft 26.2 的 `players/data` 与 `data/minecraft/maps/<地图ID>.dat`。
扫描玩家背包、末影箱、展示框和掉落物、区块容器，并递归处理潜影盒和物品组件中的嵌套容器。
不会迁移插件数据库中的虚拟背包、商店或邮件附件；这些需要相应插件先将物品导出到原版存档。

支持 MCA 的 GZIP、ZLIB、未压缩和外置 MCC 区块。发现 LZ4Block、旧 Paper oversized
存储、损坏 NBT、无法识别的地图所属世界、缺失图片或冲突文件会在提交前停止。
源图片路径只允许标准小写 UUID 与 `thumb.png` / `x_y.png`，不会接受绝对路径或路径穿越。
服务器账户应具有读取源文件、修改目标/世界存档以及保留原文件所有者的权限。

输出中的 `migrated` 是添加 PDC 的物品堆数量，`already` 是已有 PDC 的物品堆数量；
`unused_index_entries` 表示没有在已扫描存档物品中找到对应引用的旧索引项数量，
不代表这些图片可以安全删除，因此迁移会保留源目录中所有标准 UUID 图片目录。

运行脚本测试（仅使用临时生成的存档，不访问真实服务器）：

```bash
python3 -m pip install -r scripts/requirements.txt
python3 -m unittest discover -s scripts/tests -v
```
