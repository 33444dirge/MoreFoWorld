# jiujiu 重生修复源码

基线为 HSGamer/MoreFoWorld 的 `1.16.0` 标签，提交
`b0a9186db0638727bed98ae8e8b4dae63d7046e9`，服务端版本保持 `26.2`。
完整上游源码保留，修改直接落在 `RespawnListener.java`，没有引入反编译目录或原插件二进制依赖。

对齐参考：`MoreFoWorld-1.16.0-jiujiu-respawnfix1.jar`。
SHA-256：`437a7c1cca274f06be8bed14a07f80ef214ed1f4124d99b96ab6be07b30c1031`。
与本地原版 `MoreFoWorld-1.16.0-all.jar` 比较 ZIP 条目内容，仅重生监听器、
其新增的 `Pending` / `Target` 类和插件版本号有变化。

## 行为

- 死亡时记录重生目标，收到服务端重生完成事件后，在玩家实体调度器延迟一 tick 执行。
- 床和重生锚优先；在重生完成后重新检查有效位置，避免失效床阻止世界重生回退。
- 相同请求只调度一次；再次死亡、退出、实体退休和插件停用会清理旧请求与任务。
- 等待期间只保存世界 UUID 和坐标，检查无效坐标、目标世界卸载和传送失败。

此版本与参考 JAR 一样，需要提供
`me.earthme.luminol.api.entity.player.PostPlayerRespawnEvent`
及 `Player.getRespawnLocation(boolean)` 的 Luminol/Shiroha 服务端。
不能直接视作原生 Folia 通用版本。未执行真实服务端启动或多人在线测试。

## 构建

需要 JDK 25、Folia 26.2 开发包，以及对应的 Luminol/Shiroha API。
上游默认的 `26.2.local-SNAPSHOT` 开发包和 Canvas 快照仓库目前无法正常下载，
可通过参数使用已发布的开发包和本地 Canvas API；这些 API 都不会打包进插件。
Canvas API 用于上游已有的异步世界卸载方法。

```powershell
.\gradlew.bat clean build `
  "-PfoliaDevBundleVersion=26.2.build.7-beta" `
  "-PcanvasApiJar=D:/deps/canvas-api.jar" `
  "-PrespawnApiJar=D:/deps/shiroha-api-26.2-SNAPSHOT.jar" `
  "-PbuildOutputDir=D:/build/MoreFoWorld"
```

验证使用的 Canvas API 来自 `canvas-build.866.jar` 中内嵌的
`canvas-api-26.1.2.build.866-stable.jar`；重生 API 为 Shiroha 26.2。
Windows 中文路径下测试工作进程存在参数文件编码问题，可按上例使用纯英文输出路径。
产物为输出目录下 `libs/MoreFoWorld-1.16.0-jiujiu-respawnfix1-all.jar`。

## 验证结果

完整源码 `clean build` 通过，14 项重生回归测试通过。
将编译产物和参考 JAR 的三个重生类转换为 ASM 指令文本，忽略调试信息和栈帧后，
`RespawnListener`、`RespawnListener$Pending`、`RespawnListener$Target` 全部一致，
包括事件注解、调度逻辑、错误处理与日志文本。整个 JAR 并非逐字节相同。
