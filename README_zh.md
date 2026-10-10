简体中文 | [English](README.md)

<div align="center">

# ReColorfulMap

<img src="https://ssl.lunadeer.cn:14437/i/2024/02/21/65d5e0d10b7d5.png" alt="" width="70%">

[![源码仓库](https://img.shields.io/badge/SourceCode-GitHub-blue?logo=github)](https://github.com/ColdeZhang/ReColorfulMap)
[![统计数据](https://img.shields.io/badge/bStats-Statistics-eacd76?logo=google-analytics)](https://bstats.org/plugin/bukkit/ColorfulMap/21443)
[![最新构建](https://img.shields.io/github/v/release/ColdeZhang/ReColorfulMap?label=LatestBuild&logo=github&color=0aa344)](https://github.com/ColdeZhang/ReColorfulMap/releases/latest)

[![Hangar](https://img.shields.io/badge/To-Hangar-004ee9)](https://hangar.papermc.io/zhangyuheng/ReColorfulMap)
[![Modrinth](https://img.shields.io/badge/To-Modrinth-1bd96a)](https://modrinth.com/project/recolorfulmap)

</div>

## 简介

ReColorfulMap 可以将图片自动转换为地图画阵列，用以方便地悬挂公告、装饰画、地图等。

该插件是 ColorfulMap 的重制版，使用与 ColorfulMap 2.3 相同的文件布局，将 PNG 图片保存在
`plugins/ReColorfulMap/maps` 中。启动时读取地图 ID 索引，图片按需加载，图片数据不会写入世界或物品 PDC。

不论是 ColorfulMap 还是 ReColorfulMap 它们的实现方式均源自于 [ImageFrame](https://github.com/LOOHP/ImageFrame)
和 [ImageMaps](https://github.com/SydMontague/ImageMaps). 你可以认为这是简化版的 ImageFrame 或者 ImageMaps 的高版本重置。

## 功能

- 自动将图片转换为地图画阵列;
- 图片缩放;
- 自动放置到展示框阵列;
- 支持消耗金钱生成地图画 (需要 Vault 依赖);
- 一键取回地图画 **(主要新增功能)**;

## 支持版本

- 1.20.1+ (Paper、Folia)

## 使用方法

1. 首先需要将你准备的图片上传到 [图床](https://img.lunadeer.cn/)（这是我的自建图床，仅供测试使用）这样插件才能通过网络获取到图片内容。上传完成后复制得到的图片地址。

2. 在游戏中使用指令：`/tomap <图片地址>` 获得地图：

   ![](images/01.png)

   由于 Minecraft 中的一张地图只能容纳最大 128 x 128 像素的图片，所以 ReColorfulMap 会自动将你的图片自动分片。
   无需担心分片后的图片顺序等问题，你实际上只会拿到一张缩略图，ReColorfulMap 会告诉你需要多少个展示框来放置完整的图片，
   并在你放置的时候自动放置图片分片。

   例如上面这张图片， ReColorfulMap 会告诉你需要 8 x 13 个展示框来放置这张图片。

3. 准备好展示框后，将地图放置在展示框阵列的 **左下角**，插件会自动放置其他地图画：

   ![](images/02.png)

4. 如果图片过大或者过小，你可以在指令后添加一个缩放参数，例如 `/tomap <url-you-just-got> 0.3` 表示渲染为原图的 30% 大小。
   如果你希望填满所有展示框边缘而不留下任何白边，你需要确保你的图片分辨率是 **128** 的倍数。

   ![](images/03.png)

## 管理员手册

### 指令与权限节点

> 以下指令中的尖括号 `<>` 表示必选参数，方括号 `[]` 表示可选参数。

| Function | Command                         | Permission Node     | Default |
|----------|---------------------------------|---------------------|---------|
| 生成地图画    | `/tomap <image-url> [scalling]` | recolorfulmap.tomap | true    |
| 重载配置     | `/reloadColorfulMap`            | recolorfulmap.admin | op      |
| 清理临时文件  | `/cleanColorfulMap`             | recolorfulmap.admin | op      |

`/cleanColorfulMap` 仅清理插件写入失败遗留的临时文件，保留地图画 PNG 和地图 ID 索引，
包括背包、箱子和未加载区块中的地图画所引用的数据。

### 从 ColorfulMap 2.3 升级

1. 停服，备份世界存档和 `plugins/ColorfulMap/maps`。
2. 移除 ColorfulMap 2.3 插件 JAR，安装 ReColorfulMap。
3. 将整个 `plugins/ColorfulMap/maps` 复制为 `plugins/ReColorfulMap/maps`，
   **包括 `maps.yml` 和所有 UUID 子目录**。路径以插件实际数据目录为准，默认是 `plugins/ReColorfulMap`。
4. 保留原世界存档及地图 ID 数据，启动服务器即可，无需执行转换命令。

旧版已悬挂的地图和未放置的整幅地图画都可以继续使用，完整的旧版墙面阵列也支持整幅取回。
放置、取回时会保留图片文件和已有地图 ID 索引，避免其他地图副本失效。
备份时请同时保存 `maps` 目录、世界存档和玩家数据。

本仓库此前的 PDC 图片存储方案已废弃，不提供迁移兼容。
文件格式、兼容范围及验证说明见 [地图画存储说明](docs/storage.md)。

### 配置文件参考

```yaml
# 严禁修改此字段
version: 2
# 最大展示框阵列大小
# 32 x 18 表示图片不能大于 4096 x 2304 像素
# 不要将此值设置过大，可能会导致卡顿
max-frame-x: 32
max-frame-y: 18
# 语言文件，查看 plugins/FurnitureCore/languages 文件夹中的其他语言文件
language: en_us
# 经济系统 (需要 Vault 和经济插件)
# 如果为 true，玩家需要为每个地图付费
# 此功能可有效防止玩家滥用插件
economy:
   enable: false
   cost-per-map: 100.0
# 图床地址白名单，你可以指定玩家只能使用白名单中的图床地址
# 借助图床的过滤功能，可以有效防止玩家使用不当图片
# 留空表示不启用白名单
address-white-list:
   - ''
# 调试模式，如果报告错误请打开此选项
debug: true
```

## 获取帮助

Mail: [zhangyuheng@lunadeer.cn](mailto:zhangyuheng@lunadeer.cn)

GitHub: [Issues](https://github.com/ColdeZhang/ReColorfulMap/issues)

QQ群：309428300

## 统计数据

![bstats](https://bstats.org/signatures/bukkit/ColorfulMap.svg)

