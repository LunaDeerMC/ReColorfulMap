# 真实服务器存档迁移验证

这组工具用于已经停服的隔离 Paper 测试服务器。`migration_safety.py` 会复制真实存档后运行
正式迁移 CLI，不修改原测试服务器目录，也不自行生成或代替 Minecraft 的世界 NBT。

需要 Python 3.10+、项目迁移脚本的 PyYAML 依赖、Linux 和 GCC。准备数据可以选择：

- 使用 `tests/e2e` 的真实网络客户端和 `RCME2E legacy-fixture`，由 Paper APIs 保存玩家、
  箱子、嵌套潜影盒、展示框、掉落地图及无关的原版地图。
- 在隔离服务器安装原始 `ColorfulMap-2.3.jar`，真实执行 `/tomap`、放到展示框，并用原版
  `/item replace` 将旧物品复制到末影箱和容器，再停服。不能同时安装两个地图插件。

```bash
python3 scripts/e2e/migration_safety.py \
  --fixture-root /tmp/recolorfulmap-e2e/server \
  --work-dir /tmp/recolorfulmap-save-safety
```

工作目录必须不存在。测试保留 `report.json`、每次 CLI 的 stdout/stderr/退出状态、原始快照和
各场景副本，方便核对。覆盖的实际子进程场景包括：预检查、缺图、目标冲突、损坏的真实区块、
不支持的压缩、其他进程持有世界锁、正式迁移及重复执行、原地转换及回滚、后续编辑拒绝回滚。

中断场景会临时编译 `rename_pause.c` 并仅给被测 CLI 注入 `LD_PRELOAD`：在真实玩家存档
原子替换成功之后暂停进程，再发送 `SIGKILL`。随后确认下一次迁移拒绝未完成事务，运行正式
`--rollback` 恢复，并比较原始文件的 SHA-256、权限及所有者。这个测试库不由生产迁移入口使用。

独立存档检查也可单独运行：

```bash
python3 scripts/e2e/storage_oracle.py \
  --before /path/to/stopped-before-snapshot \
  --after /path/to/stopped-migrated-server
```

`storage_oracle.py` 不引用生产脚本的 NBT 或 MCA 解析器。它独立读取整个真实世界，确认仅目标
地图物品新增 `re_colorful_map:image_path`；原始键顺序、列表位置、未知 primitive payload、
原生地图、维度 UUID、玩家和容器其余内容保持一致，并核对图片及元数据副本哈希。原地转换可用
`--target plugins/ColorfulMap`。测试夹具必须使用全局唯一的地图 ID，避免把被测身份解析逻辑
重新复制进独立检查器；跨世界同 ID 的身份边界由另外的单元测试覆盖。

独立检查必须在新版本服务器重启前运行，因为 Minecraft 正常启动和保存会更新时间、实体等
与迁移无关的数据。完成离线检查后，再使用 Paper 启动并由真实客户端验证渲染和地图画操作。
