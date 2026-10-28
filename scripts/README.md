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

Paper 26.1/26.2 的世界 UUID 会从各维度的 `data/paper/metadata.dat` 读取；旧版 `uid.dat`
同样支持。默认主世界、下界和末地名称自动匹配。插件创建的自定义维度可能使用任意 Bukkit
名称，而 Paper 没有将该名称保存在维度元数据中；遇到这种数据，按旧 `maps.yml` 中的名称指定：

```bash
bash scripts/migrate-colorfulmap.sh --server-root /srv/minecraft \
  --dimension 'My Custom World=world/dimensions/example/custom_world' --apply
```

`--dimension` 只补充名字和维度目录的对应，不会将维度当作独立世界创建或单独锁定。
使用标准维度键但自定义 Bukkit 名称的世界，也应通过该参数指定实际名称。

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
包括 Minecraft 26.1/26.2 的 `players/data` 与 `data/minecraft/maps/<地图ID>.dat`。
扫描玩家背包、末影箱、展示框和掉落物、区块容器，并递归处理潜影盒和物品组件中的嵌套容器。
不会迁移插件数据库中的虚拟背包、商店或邮件附件；这些需要相应插件先将物品导出到原版存档。

支持 MCA 的 GZIP、ZLIB、未压缩和外置 MCC 区块。发现 LZ4Block、旧 Paper oversized
存储、无法识别的地图所属世界、缺失图片或冲突文件会在提交前停止。
所有区块均校验扇区边界、重叠和压缩流完整性；合法的最后一个扇区不必有填充字节。
已解压区块若不含 `filled_map`，无需构建 NBT 对象；含该标识的区块及独立 DAT
仍完整解析，损坏时停止。旧式与新式地图物品、嵌套容器都使用该 ASCII 标识。
因此本工具不充当无地图区块的完整 NBT 健康检查，也不会修补源存档的尾部填充。
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

实际 Paper 存档的独立字节检查和 CLI 进程中断测试见 [e2e/README.md](e2e/README.md)。
