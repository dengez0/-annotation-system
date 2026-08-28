# 更新部署记录（2026-08-26）

本文件记录当天实际使用的测试环境更新方式，以及下一次生产更新前必须执行的检查。

## 环境约定

| 用途 | 地址 / 容器 | 说明 |
| --- | --- | --- |
| 生产主站 | `http://192.168.1.226:18083` / `simplelabel` | Java 主站与生产模型检测同一镜像内运行。 |
| 生产模型检测 | `http://192.168.1.226:18086` | 对外模型检测端口；部署前必须确认其实际 Docker 映射。 |
| 测试主站 | `http://192.168.1.226:29090` / `simplelabel-web-test` | 独立 Java Web 容器。 |
| 测试模型检测 | `http://192.168.1.226:29091` / `simplelabel-model-test` | 独立模型检测容器。检测进行中不得停止、重启或替换。 |

测试机项目目录为 `~/simplelabel-test`，生产机项目目录为 `~/simplelabel`。

## 本次包含的代码改动

1. `backend-java/.../WorkLogReadService.java`
   - 同一项目和图片只统计最后一次 `START_ANNOTATION` / `SAVE_ANNOTATION` 的 `boxes`。
   - `saves` 仍是历史保存次数，`boxes` 不再把每一次保存累加。
2. `model-detection-python/main.py`、`model_detection.html`、`detector_adapter.py`
   - 批量图片检测改为逐文件原始请求 `/api/detect-image`；浏览器最多两路并发。
   - 服务端限制单图 25 MB，串行保护推理，返回坐标由浏览器绘制框。
   - 兼容原始 YOLOv5 checkpoint；生产模型检测页面从 18086 返回首页时应回到 18083。

## 传输补丁（SSH 不通时）

如果 Windows 到服务器的 `scp` 因 22 端口超时，可借用正在运行的模型检测服务上传一个 tar 包。该方式只用于小型代码补丁；上传文件会临时出现在测试 `runtime-test/models/`，部署确认后再删除该临时文件。

Windows PowerShell（在仓库根目录）：

```powershell
$archive = Join-Path $env:TEMP 'simplelabel-hotfix.tar.gz'
tar.exe -czf $archive backend-java/src/main/java/com/simplelabel/service/WorkLogReadService.java
tar.exe -tzf $archive
$hash = (Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant()
"$hash  simplelabel-hotfix.tar.gz"

curl.exe -f -X POST "http://192.168.1.226:29091/api/upload_model" `
  -F "file=@$archive;filename=simplelabel-hotfix.pt"
```

测试服务器上：

```bash
cd ~/simplelabel-test
cp runtime-test/models/simplelabel-hotfix.pt /tmp/simplelabel-hotfix.tar.gz
sha256sum /tmp/simplelabel-hotfix.tar.gz
tar -tzf /tmp/simplelabel-hotfix.tar.gz
tar -xzf /tmp/simplelabel-hotfix.tar.gz -C ~/simplelabel-test
```

先确认 `tar -tzf` 实际列出了 `.java` 文件；只有目录的压缩包是无效包，不能继续部署。

## 29090：只更新 Java 框数统计

远端没有 Maven 时，不要在 JAR 根目录执行 `jar uf`。Spring Boot 类必须更新到 `BOOT-INF/classes/`；把类写到根目录会导致 `NoClassDefFoundError`，例如找不到 `WorkLogService`。

推荐流程是：用与容器相同镜像的临时 helper 编译 patched JAR，再复制到 `simplelabel-web-test`。这个过程只会重启 29090，不会触碰 `simplelabel-model-test`（29091）。运行前将下列变量按实际路径替换：

```bash
cd ~/simplelabel-test
WORK=/tmp/simplelabel-worklog-build
mkdir -p "$WORK"

docker run --rm \
  -v "$PWD/backend-java/src/main/java/com/simplelabel/service/WorkLogReadService.java:/src/WorkLogReadService.java:ro" \
  -v "$WORK:/out" \
  --entrypoint sh simplelabel:test \
  -lc '
set -eu
JAR=/out/patched.jar
BUILD=/tmp/worklog-build
cp /opt/simplelabel/current/backend-java/target/simplelabel-java-1.0.0-SNAPSHOT.jar "$JAR"
mkdir -p "$BUILD/classes/BOOT-INF/classes"
cd "$BUILD"
jar xf "$JAR" BOOT-INF/classes BOOT-INF/lib
javac -cp "BOOT-INF/classes:BOOT-INF/lib/*" -d classes/BOOT-INF/classes /src/WorkLogReadService.java
javap -p classes/BOOT-INF/classes/com/simplelabel/service/WorkLogReadService.class | grep -q latestSaveEvents
cd classes
jar uf "$JAR" BOOT-INF/classes/com/simplelabel/service/WorkLogReadService*.class
'

docker cp "$WORK/patched.jar" simplelabel-web-test:/opt/simplelabel/current/backend-java/target/simplelabel-java-1.0.0-SNAPSHOT.jar
docker restart simplelabel-web-test
```

验证 JAR 中的类后再查看页面：

```bash
docker exec simplelabel-web-test sh -lc '
JAR=/opt/simplelabel/current/backend-java/target/simplelabel-java-1.0.0-SNAPSHOT.jar
D=/tmp/check-worklog
rm -rf "$D"; mkdir -p "$D"; cd "$D"
jar xf "$JAR" BOOT-INF/classes/com/simplelabel/service/WorkLogReadService.class
javap -p BOOT-INF/classes/com/simplelabel/service/WorkLogReadService.class | grep latestSaveEvents
'
```

`/internal/health` 在这个独立 Java 容器中可能显示 `worker:false` 并返回 503；只要 `http://192.168.1.226:29090/` 返回 200，即表示网页服务正常。这不应通过停止或修改 29091 来“修复”。

## 下一次生产全量更新（18083 + 18086）

本次生产发布必须是**完整镜像更新**，不能只热替换 Java JAR：模型检测 Python 代码也需要进入镜像。不要把 `runtime/` 加入发布包，也不要执行 `down -v`、删除 runtime 或 Docker 全局清理。

在维护窗口前，仅执行以下只读检查，确认生产 18086 的真实端口映射：

```bash
cd ~/simplelabel
docker inspect simplelabel --format 'image={{.Config.Image}} ports={{json .NetworkSettings.Ports}}'
docker exec simplelabel sh -lc '
echo "web=$SIMPLELABEL_PORT model=$SIMPLELABEL_MODEL_DETECTION_PORT"
ss -ltn | grep -E ":(18083|18086|8000)\\b" || true
'
grep -E '^SIMPLELABEL_(PORT|MODEL_DETECTION_PORT)=' deploy/docker/.env
```

原因：当前 Compose 默认端口写法是 `宿主机 ${SIMPLELABEL_MODEL_DETECTION_PORT} -> 容器 8000`，而 Python 进程也读取同名变量作为监听端口。生产既然对外使用 18086，必须以实际 `docker inspect` 结果为准后再制作/调整全量发布包，避免 Docker 转发端口与进程监听端口不一致。

生产全量更新会重启 `simplelabel`，因此会短暂影响 18083 和 18086；必须等用户工作结束、明确授权后才执行。宿主机挂载的 `runtime/data`、`models`、`logs`、`admin`、`processed`、`backups` 将原样保留。
