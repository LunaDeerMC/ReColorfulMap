# 真实服务端端到端测试

这些工具仅用于新建的隔离测试服务器。测试插件会给固定测试玩家授权，并创建、清空测试背包和场地，不能安装到正式服务器。控制工具要求目录带有自己的标记，服务端只监听 `127.0.0.1`。

本次使用 Paper 26.2 build 133、Folia 26.2 build 7、Java 25 和实际 Minecraft 26.2 协议客户端。游戏中的生成、右键放置、攻击取回、丢弃均由真实网络包触发；辅助插件负责准备环境、读取状态和设置保护/掉落取消条件，不直接调用 ReColorfulMap 的业务方法。

需要 Linux x86_64、Python 3.12+、Node.js 22+、Bash、ripgrep；迁移故障注入另需 GCC 和 `scripts/requirements.txt`。服务端和 Java 下载固定版本并校验 SHA-256；客户端依赖与 26.2 协议数据也固定版本。首次运行需要联网下载依赖。

## Paper 操作与重启

先构建生产插件（构建工具使用 Java 17），再准备临时服务器：

```bash
./gradlew build shadowJar
python3 tests/e2e/paper_server.py prepare \
  --plugin build/libs/ReColorfulMap-4.0-rc.1.jar
bash tests/e2e/client/setup.sh
python3 tests/e2e/paper_server.py start
```

等待 `/tmp/recolorfulmap-e2e/server/logs/latest.log` 出现 `Done`，然后停服编译测试插件。首次启动用于让 Paper 解压自己的 API 和依赖：

```bash
python3 tests/e2e/paper_server.py stop
bash tests/e2e/build-harness.sh \
  /tmp/recolorfulmap-e2e/jdk \
  /tmp/recolorfulmap-e2e/server/libraries/io/papermc/paper/paper-api/26.2.build.133-stable/paper-api-26.2.build.133-stable.jar \
  /tmp/recolorfulmap-e2e/server
python3 tests/e2e/paper_server.py start
```

等待启动完成后执行：

```bash
node tests/e2e/run-scenarios.js
python3 tests/e2e/paper_server.py stop
python3 tests/e2e/paper_server.py start
# 等待启动完成。
node tests/e2e/run-after-restart.js
```

测试报告和原始日志保留在 `/tmp/recolorfulmap-e2e`。客户端会累计地图的完整像素包和增量像素包，使用最终像素校验重启恢复；区块卸载以实际实体卸载事件为准，不把一次卸载请求当作已完成。

## 旧版升级和存档安全

完整说明见 [迁移验证工具](../../scripts/e2e/README.md)。在另一台隔离 Paper 26.2 服务器中安装用户提供的原始 ColorfulMap 2.3 JAR，通过真实 `/tomap` 和放置操作产生旧数据；不能同时加载新旧地图插件。

也可由辅助插件的 `/rcme2e legacy-fixture` 创建包含中文、表情、额外 PDC、原版地图和嵌套容器的扩展测试数据。保存并停服后执行：

```bash
python3 scripts/e2e/migration_safety.py \
  --fixture-root /tmp/recolorfulmap-e2e/server \
  --work-dir /tmp/recolorfulmap-save-safety
```

工具只在新副本上执行缺图、冲突、坏区块、锁冲突、重复执行、回滚及强杀中断测试。独立检查器不复用生产 NBT/MCA 解析器，逐项检查原始 NBT 字节、结构、列表位置和图片哈希。

离线校验通过后，对隔离服务器执行正式迁移命令，再替换 JAR 并重启，用真实客户端确认旧墙面和容器物品仍能显示、整幅取回及重新放置。Minecraft 自身启动保存会改动时间、实体等字段，因此字节级检查在这次重启之前完成。

## Folia

参见 [独立 Folia 冒烟测试](folia/README.md)。这组测试使用实体/区域调度器，验证生成、像素显示、拾取、重启恢复和虚空销毁；没有重复 Paper 的全部墙面、保护和迁移矩阵。
