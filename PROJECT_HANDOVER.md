# SimpleLabel 项目工作交接文档

> 交接日期：2026-08-27  
> 本地仓库：`C:\Users\32427\Desktop\-annotation-system`  
> 服务器：`192.168.1.226`（Ubuntu，账号 `kinth`）  
> 本文不包含管理员明文 Key、Token、密码或业务数据。

## 1. 交接摘要

> 2026-08-28 运行状态更新：生产主机根分区曾达到 100%（`df -h /` 可用空间为 0），会导致标注保存、修改标签和移动文件返回 `No space left on device` / HTTP 500。恢复可用磁盘空间并复核保存功能前，不得将生产环境视为可验证或可发布状态。该问题是主机 Docker 历史数据占用，不是 `runtime/` 目录或标注逻辑本身；清理前必须逐项确认目标，禁止无差别删除 volume 或 `runtime/`。

> 已修复但尚待部署验证：编辑器保存请求完成后改用保存时捕获的文件名定位列表项，避免用户切图后把另一张未标图片错误显示为绿色。该问题在大任务中更易出现；服务端 JSON 统计和导出结果仍是事实来源。

SimpleLabel 是一套用于二维目标检测数据集生产的 Web 标注系统。主站由 Java 21 + Spring Boot 提供，模型推理由 Python YOLO Worker 和独立模型检测模块提供，生产与测试均通过 Docker 运行。

当前需要接任者重点关注的状态：

1. 测试主站 `29090` 已运行，工作日志“标注框数只取每张图片最后一次保存结果”的修复已经通过人工验证。
2. 测试模型检测 `29091` 已独立运行，已加入逐图流式上传、并发限制和 YOLOv5 兼容等修改。
3. 生产主站为 `18083`，生产模型检测为 `18086`。本次生产更新要求同时发布 Java 框数统计和 Python 模型检测修改。
4. **当前尚未生成包含上述两部分修改的最新生产全量包，也尚未部署生产。**
5. 生产模型检测 `18086` 的 Docker 对外映射与容器内监听端口需要先现场核实，不能直接使用仓库默认 Compose 重建。
6. 本地工作区存在多项未提交修改；接任者应先保护当前工作区，不要执行 `git reset --hard`、`git checkout -- .` 或覆盖式同步。

## 2. 系统架构与目录

### 2.1 主要模块

| 模块 | 位置 | 职责 |
| --- | --- | --- |
| Java 主站 | `backend-java/` | 页面、标注、文件管理、工作流、工作日志、管理员权限与 API。 |
| 模型检测 | `model-detection-python/` | PT/ONNX 模型管理、图片/视频检测和模型检测网页。 |
| YOLO Worker | `yolo-worker/` | Java 自动标注任务使用的内部推理服务。 |
| 兼容服务 | `services/` | YOLO 后端选择、旧模型加载和推理结果适配。 |
| YOLOv5 源码 | `yolov5/` | 旧 YOLOv5 checkpoint 的兼容加载。 |
| Docker 部署 | `deploy/docker/` | 镜像、Compose、环境模板、部署和验证脚本。 |
| Ubuntu 辅助脚本 | `deploy/ubuntu/` | 环境校验、Worker 等待和依赖。 |
| 发布物 | `dist/` | 本地构建的 tar.gz、JAR 和 SHA-256 校验文件。 |

### 2.2 本地与服务器目录

| 用途 | 路径 |
| --- | --- |
| Windows 本地仓库 | `C:\Users\32427\Desktop\-annotation-system` |
| Ubuntu 生产项目 | `/home/kinth/simplelabel`（命令中常写 `~/simplelabel`） |
| Ubuntu 测试项目 | `/home/kinth/simplelabel-test`（命令中常写 `~/simplelabel-test`） |
| 生产持久化数据 | `~/simplelabel/runtime/` |
| 测试持久化数据 | `~/simplelabel-test/runtime-test/` |
| Java 可执行包 | `backend-java/target/simplelabel-java-1.0.0-SNAPSHOT.jar` |

### 2.3 持久化数据边界

生产和测试分别挂载以下目录到容器内 `/srv/simplelabel/`：

- `data/`：图片、标注 JSON 和工作流目录。
- `models/`：模型文件。
- `logs/`：工作日志，主要文件为 `ip_work.log`。
- `admin/`：管理员设备令牌注册表。
- `processed/`：数据处理结果。
- `backups/`：备份。

这些目录属于业务数据，不是代码发布物。生产部署时必须保留 `runtime/` 原状，禁止执行：

```text
docker compose down -v
docker system prune
docker volume prune
rm -rf runtime
```

不要将 `runtime-test/` 复制到 `runtime/`，也不要用测试管理员注册表覆盖生产注册表。

## 3. 环境清单与实际拓扑

### 3.1 对外地址

| 环境 | 服务 | 地址 | 当前约定容器 |
| --- | --- | --- | --- |
| 生产 | Java 主站 | `http://192.168.1.226:18083` | `simplelabel` |
| 生产 | 模型检测 | `http://192.168.1.226:18086` | 预计与 `simplelabel` 同镜像/容器，必须现场确认 |
| 测试 | Java 主站 | `http://192.168.1.226:29090` | `simplelabel-web-test` |
| 测试 | 模型检测 | `http://192.168.1.226:29091` | `simplelabel-model-test` |

### 3.2 测试环境的实际运行方式

本次排障期间，29090 与 29091 采用两个独立容器运行：

- `simplelabel-web-test`：只提供 Java Web，映射 29090。
- `simplelabel-model-test`：只提供 Python 模型检测，映射 29091。

这与仓库中 `compose.test.yml` 当前定义的单容器 `simplelabel-test` 不完全相同。因此接任者在执行任何测试环境命令前，先读取真实状态：

```bash
docker ps -a --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}' \
  | grep -E 'NAMES|simplelabel'
docker inspect simplelabel-web-test --format '{{json .HostConfig.PortBindings}}' 2>/dev/null || true
docker inspect simplelabel-model-test --format '{{json .HostConfig.PortBindings}}' 2>/dev/null || true
```

不要在模型检测进行中停止、重启或替换 `simplelabel-model-test`。只修改 29090 时，只能操作 `simplelabel-web-test`。

### 3.3 29090 健康检查说明

独立 Java 容器没有启动其预期的 YOLO Worker，因此：

```bash
curl -s http://192.168.1.226:29090/internal/health
```

可能返回 HTTP 503，并在 JSON 中显示：

```json
"worker": false
```

这不代表网页没有启动。网页可用性应另外验证：

```bash
curl -I http://192.168.1.226:29090/
```

返回 `HTTP/1.1 200` 表示 29090 页面正常。不要通过停止或修改 29091 来消除独立 Java 容器的 `worker:false`。

## 4. 本地最新代码进度

### 4.1 工作日志框数统计

涉及核心文件：

- `backend-java/src/main/java/com/simplelabel/service/WorkLogReadService.java`
- `backend-java/src/main/resources/templates/work_logs.html`
- `backend-java/src/test/java/com/simplelabel/service/WorkLogReadServiceTest.java`

旧逻辑对每一次 `SAVE_ANNOTATION` 的 `boxes` 求和，导致用户连续保存同一张图片时重复累计。新逻辑为：

- 图片唯一键为“项目 + 目标图片文件名”。
- 同一图片只取最新一次 `START_ANNOTATION` 或 `SAVE_ANNOTATION` 的框数。
- 框数归属给最后一次保存该图片的 IP。
- `saves` 仍统计全部历史保存次数。
- 页面提示文字已改为“最终框数”。

真实日志中，同一张图片曾连续写入 `boxes=2、3、4、...、6`；旧版全部相加，新版只取最后一条。运行中类可用以下方法确认：

```bash
docker exec simplelabel-web-test sh -lc '
JAR=/opt/simplelabel/current/backend-java/target/simplelabel-java-1.0.0-SNAPSHOT.jar
D=/tmp/check-worklog
rm -rf "$D"; mkdir -p "$D"; cd "$D"
jar xf "$JAR" BOOT-INF/classes/com/simplelabel/service/WorkLogReadService.class
javap -p BOOT-INF/classes/com/simplelabel/service/WorkLogReadService.class | grep latestSaveEvents
'
```

有 `latestSaveEvents` 输出才是新逻辑。

### 4.2 模型检测模块

涉及核心文件：

- `model-detection-python/main.py`
- `model-detection-python/model_detection.html`
- `model-detection-python/detector_adapter.py`
- `tests/test_model_detection_compat.py`
- `tests/test_model_detection_streaming_contract.py`

修改目标是解决上传一组图片时浏览器出现 `fail to fetch`。原前端将多张图片转 Base64 后作为一个大 JSON 请求发送，响应又返回多张 Base64 图片，容易触发请求过大、内存峰值和连接关闭。

新实现：

- 新增 `POST /api/detect-image`，请求体直接传单张图片原始字节。
- 支持 JPEG、PNG、WebP、BMP、TIFF。
- 默认单图上限 25 MB，可由 `SIMPLELABEL_MODEL_DETECTION_MAX_IMAGE_BYTES` 调整。
- 前端最多同时运行两个图片检测请求。
- 服务端使用推理锁，避免并发模型推理造成资源冲突。
- API 返回目标坐标、类别、置信度和原图尺寸，不返回标注后 Base64 图片。
- 浏览器用原图 Object URL 和返回坐标绘制检测框。
- 新增 `detector_adapter.py`，兼容现代 Ultralytics 和原始 YOLOv5 checkpoint。
- Java 跳转到模型检测时显式传入 `home_port`；检测页还提供 29091→29090、18086→18083 的兜底映射，并对首页返回 `Cache-Control: no-store` 防止旧页面缓存。运行中的 29091 尚需更新页面文件后验证。
- 原 `/api/batch_detect` 后端仍保留作兼容，但新网页不再调用它。

### 4.3 测试环境与发布脚本

本地还修改/新增了测试隔离和发布脚本：`.dockerignore`、`.gitignore`、`compose.test.yml`、`deploy.sh`、`prepare_test_image.sh`、`test_stack.sh`、`verify_test.sh`、`validate_runtime_env.sh` 和 `build_docker_release.ps1`。

这些脚本描述的是计划中的单容器 `simplelabel-test` 拓扑，而当前服务器测试环境曾采用 `simplelabel-web-test` + `simplelabel-model-test` 两容器临时拓扑。切换前必须先确认现场容器，不能直接运行 `test_stack.sh up`。

### 4.4 辅助脚本

`match_images_from_txt.py` 用于读取 TXT 中的图片名称，并从指定目录匹配、复制或移动同名图片。它不属于生产服务运行依赖。

## 5. 本地工作区状态

截至交接时，以下改动尚未提交。

### 5.1 已修改文件

```text
.dockerignore
.gitignore
backend-java/src/main/java/com/simplelabel/service/WorkLogReadService.java
backend-java/src/main/resources/templates/work_logs.html
backend-java/src/test/java/com/simplelabel/service/WorkLogReadServiceTest.java
deploy/docker/README.md
deploy/docker/compose.test.yml
deploy/docker/compose.yml
deploy/docker/deploy.sh
deploy/ubuntu/validate_runtime_env.sh
model-detection-python/main.py
model-detection-python/model_detection.html
scripts/build_docker_release.ps1
```

### 5.2 新增但未跟踪文件

```text
deploy/DEPLOYMENT_RUNBOOK_2026-08-26.md
deploy/docker/.env.test.example
deploy/docker/prepare_test_image.sh
deploy/docker/test_stack.sh
deploy/docker/verify_test.sh
deploy/hotfixes/apply_worklog_final_boxes_production.sh
match_images_from_txt.py
model-detection-python/detector_adapter.py
tests/test_model_detection_compat.py
tests/test_model_detection_streaming_contract.py
```

本交接文档 `PROJECT_HANDOVER.md` 也是新增文件。接任后应先执行：

```powershell
git status --short
git diff --stat
```

确认上述改动仍存在，再进行提交、打包或部署。

## 6. 构建、发布物与传输

### 6.1 Java 构建

项目要求 Java 21 和 Maven 3.9+。Windows 本地工具位于 `.tools/`，标准入口为：

```powershell
.\build_java.bat
```

成功后应生成：

```text
backend-java/target/simplelabel-java-1.0.0-SNAPSHOT.jar
```

本次会话中，本地 Maven 测试曾因 Windows `backend-java/target` 文件锁/访问拒绝而未能完整跑完；不能把它记录为“自动测试全部通过”。应先关闭占用 JAR/target 的 Java 进程或编辑器，再重新执行完整构建。

### 6.2 Docker 全量发布包

标准脚本：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build_docker_release.ps1
```

脚本会：

1. 调用 `build_java.bat`。
2. 收集 JAR、Docker 配置、模型检测、YOLO Worker、兼容服务和 YOLOv5 源码。
3. 生成 `dist/simplelabel-docker-<时间>.tar.gz`。
4. 生成对应 `.sha256`。

当前脚本会在包内创建空的 `runtime/` 和 `runtime-test/` 目录骨架，但不会打包本地业务文件。制作下一份生产包后仍要检查压缩包，确认没有任何 runtime 文件：

```powershell
$archive = Get-ChildItem .\dist\simplelabel-docker-*.tar.gz |
  Sort-Object LastWriteTime -Descending | Select-Object -First 1
tar.exe -tzf $archive.FullName | Select-String '/runtime(-test)?/.+'
Get-FileHash $archive.FullName -Algorithm SHA256
```

除目录项外不应出现 runtime 内容。

### 6.3 当前 `dist/` 状态

- `simplelabel-docker-20260824-120058.tar.gz` 是最近的历史全量包，但早于 8 月 26 日的框数统计和模型检测修改。
- `simplelabel-worklog-final-boxes-production.tar.gz` 仅包含工作日志热修复，未包含模型检测修改。
- **以上两个包都不是本次要求的最新生产全量包。**

新全量包必须在 Java 与 Python 修改均确认后重新生成，并记录文件名和 SHA-256 到本节。

### 6.4 SSH/SCP 不通时的临时传输方法

服务器监听 22 和 2222，但 Windows 到 `192.168.1.226` 的 SCP 曾超时。当天使用过模型检测上传接口传输小型 tar 补丁。

Windows PowerShell：

```powershell
$archive = Join-Path $env:TEMP 'simplelabel-hotfix.tar.gz'
tar.exe -czf $archive backend-java/src/main/java/com/simplelabel/service/WorkLogReadService.java
tar.exe -tzf $archive
$hash = (Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant()
"$hash  simplelabel-hotfix.tar.gz"

curl.exe -f -X POST "http://192.168.1.226:29091/api/upload_model" `
  -F "file=@$archive;filename=simplelabel-hotfix.pt"
```

Ubuntu 测试目录：

```bash
cd ~/simplelabel-test
cp runtime-test/models/simplelabel-hotfix.pt /tmp/simplelabel-hotfix.tar.gz
sha256sum /tmp/simplelabel-hotfix.tar.gz
tar -tzf /tmp/simplelabel-hotfix.tar.gz
tar -xzf /tmp/simplelabel-hotfix.tar.gz -C ~/simplelabel-test
```

注意事项：

- 这是 SSH 不通时的小型补丁兜底方式，不是正式大包发布首选。
- 上传文件会临时出现在 `runtime-test/models/`，可能被模型页面列出。
- 解压前必须确认 tar 列出真实文件；曾出现过压缩包只有目录、没有 `.java` 文件的问题。
- 部署确认后删除该临时伪 `.pt` 文件，但不要删除真实模型。
- 正在进行模型检测时不要通过此方式传输或清理文件。

## 7. 测试环境更新方法

### 7.1 只更新 29090 的 Java 框数统计

远端没有 Maven 时，采用与当前镜像相同的临时 helper 容器编译 patched JAR。关键要求是 Spring Boot 类必须写入 `BOOT-INF/classes/`。

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
javac -cp "BOOT-INF/classes:BOOT-INF/lib/*" \
  -d classes/BOOT-INF/classes /src/WorkLogReadService.java
javap -p classes/BOOT-INF/classes/com/simplelabel/service/WorkLogReadService.class \
  | grep -q latestSaveEvents
cd classes
jar uf "$JAR" BOOT-INF/classes/com/simplelabel/service/WorkLogReadService*.class
'

docker cp "$WORK/patched.jar" \
  simplelabel-web-test:/opt/simplelabel/current/backend-java/target/simplelabel-java-1.0.0-SNAPSHOT.jar
docker restart simplelabel-web-test
```

不要使用下面这种错误方式：

```text
jar uf app.jar com/simplelabel/service/WorkLogReadService*.class
```

它会把类放到 JAR 根目录。当天曾因此造成 `NoClassDefFoundError: WorkLogService`，容器重启失败。正确路径必须是：

```text
BOOT-INF/classes/com/simplelabel/service/WorkLogReadService*.class
```

### 7.2 管理员 Key

测试管理员注册表：

```text
~/simplelabel-test/runtime-test/admin/admin_tokens.json
```

签发命令：

```bash
cd ~/simplelabel-test
python3 deploy/docker/configure_admin_token.py \
  runtime-test/admin/admin_tokens.json \
  test-admin-<新设备名>
```

如果提示 `Device already exists`，脚本不会替换该设备的 Token。需要使用新的设备名，或在维护窗口内按管理员令牌管理流程显式轮换。修改测试注册表后只重启 29090 对应 Web 容器，不要重启 29091。

### 7.3 测试验收

```bash
curl -I http://192.168.1.226:29090/
curl -f -o /dev/null http://192.168.1.226:29091/
docker logs --tail=100 simplelabel-web-test
docker logs --tail=100 simplelabel-model-test
```

功能验收：

1. 在 29090 对同一图片连续保存不同框数。
2. 工作日志保存次数应累计，框数应只取每张图片最后一次保存值。
3. 在 29091 上传多张图片，确认不出现 `fail to fetch`。
4. 观察请求是否走 `/api/detect-image`，并确认最多两路前端并发。
5. 点击 29091“返回首页”，应进入 29090。

## 8. 生产环境发布方法

### 8.1 本次发布范围

生产更新必须同时包含：

- Java 主站：工作日志最终框数统计及页面提示。
- Python 模型检测：`main.py`、`model_detection.html`、`detector_adapter.py` 及所需兼容服务。

仅应用 `simplelabel-worklog-final-boxes-production.tar.gz` 不满足本次生产要求。下一次必须构建完整镜像发布包。

### 8.2 发布前只读检查

生产对外模型检测端口实际为 18086，但仓库 `compose.yml` 当前写法为：

```yaml
"0.0.0.0:${SIMPLELABEL_MODEL_DETECTION_PORT:-8000}:8000"
```

同时 Python 进程也读取 `SIMPLELABEL_MODEL_DETECTION_PORT` 作为容器内监听端口，Java 主站又读取它作为跳转端口。如果生产 `.env` 直接设置为 18086，可能出现“Docker 转发到容器 8000，但进程监听 18086”的不一致。

在制作最终部署命令前，必须在生产机执行：

```bash
cd ~/simplelabel
docker inspect simplelabel --format \
  'image={{.Config.Image}} ports={{json .NetworkSettings.Ports}}'

docker inspect simplelabel --format '{{json .Config.Env}}' \
  | python3 -m json.tool \
  | grep -E 'SIMPLELABEL_(PORT|MODEL_DETECTION_PORT)'

docker exec simplelabel sh -lc '
echo "web=$SIMPLELABEL_PORT model=$SIMPLELABEL_MODEL_DETECTION_PORT"
ss -ltn | grep -E ":(18083|18086|8000)\b" || true
'

grep -E '^SIMPLELABEL_(PORT|MODEL_DETECTION_PORT)=' deploy/docker/.env
```

确认以下三项后才能决定最终 Compose 端口写法：

1. 浏览器访问端口确实为 18086。
2. 当前容器内模型检测进程监听端口。
3. Java `/model-detection` 当前跳转目标。

### 8.3 生产发布原则

- 等所有标注和模型检测任务结束后再进入维护窗口。
- 发布前记录当前容器 ID、镜像 ID、端口映射和健康状态。
- 为当前生产镜像创建明确的回滚标签。
- 校验新 tar.gz 的 SHA-256 和文件列表。
- 只构建/替换应用镜像与容器；继续挂载原 `runtime/`。
- 禁止 `down -v`、删除 runtime、复制测试 runtime 或全局 prune。
- 更新会同时短暂影响 18083 和 18086。
- 发布完成后立即验证主站、模型检测、管理员登录、工作日志和少量图片检测。

在 18086 映射尚未确认前，本文不提供可直接执行的生产重建命令，以避免把当前工作中的生产服务启动到错误端口。

## 9. 验证、回滚与故障排查

### 9.1 生产验收清单

- `http://192.168.1.226:18083/` 返回 200。
- `http://192.168.1.226:18086/` 返回 200。
- 从 18083 点击模型检测跳到 18086。
- 从 18086 点击返回首页跳回 18083。
- 管理员设备 Key 可以激活，且生产 `runtime/admin` 未变化。
- 原生产图片、标注、日志、模型和处理结果均存在。
- 同一图片多次保存后，框数只取最后一次结果。
- 多图检测不再出现 `fail to fetch`。
- 容器没有持续重启，日志没有 ClassNotFound、端口占用或模型加载错误。

### 9.2 回滚原则

回滚只替换应用镜像/容器，不恢复或覆盖 runtime 数据：

1. 停止新容器。
2. 使用发布前记录的旧镜像标签和原端口/环境配置重新创建容器。
3. 挂载同一套 `~/simplelabel/runtime/*`。
4. 验证 18083、18086 和健康状态。
5. 保留失败容器日志和新镜像，完成原因分析前不要全局清理。

### 9.3 常见问题

| 现象 | 首要检查 |
| --- | --- |
| `curl: connection refused` | `docker ps -a`、容器日志、端口映射和进程监听端口。 |
| 容器不断 Restarting | `docker logs --tail=200 <容器>`，不要反复 restart 掩盖首个错误。 |
| `NoClassDefFoundError` | JAR 类是否错误写到根目录；应在 `BOOT-INF/classes/`。 |
| 健康接口 503、网页 200 | 检查 JSON 中是否仅 `worker:false`。 |
| 管理员 Key 无效 | 注册表路径是否属于正确环境；签发脚本是否提示设备已存在。 |
| 多图 `fail to fetch` | 浏览器是否仍调用旧 `/api/batch_detect`；请求是否过大或服务被重启。 |
| 返回首页端口错误 | 检查 `model_detection.html` 的端口判断和 Java 跳转配置。 |

## 10. 验证状态、遗留任务与接任清单

### 10.1 已完成/已人工确认

- 测试 29090 页面可访问。
- 运行中 Java JAR 已确认包含 `latestSaveEvents`。
- 工作日志框数问题的根因已通过真实日志确认。
- 29090 和 29091 已按独立容器运行，不依赖生产 18083。
- 返回首页逻辑已在本地调整为显式 `home_port` 加端口兜底；运行中的 29091 尚待更新与验证。

### 10.2 尚未完成

- Java 完整 Maven 回归测试尚需在解除 Windows target 文件占用后重新执行。
- Python 合约测试在当前 Windows 会话中因 `python.exe` 无法访问而未运行完成。
- 尚未确认生产 18086 的容器内监听和 Docker 映射。
- 尚未构建包含 Java + 模型检测修改的最新生产全量包。
- 尚未执行本次生产更新。
- 当前工作区修改尚未整理成正式 Git 提交。

### 10.3 接任第一天建议顺序

1. 阅读本文和 `deploy/DEPLOYMENT_RUNBOOK_2026-08-26.md`。
2. 在本地执行 `git status --short`，确认未提交改动完整。
3. 在服务器执行只读 `docker ps -a` 和 `docker inspect`，记录真实生产/测试拓扑。
4. 不干扰当前任务的情况下，确认 18083、18086、29090、29091 页面状态。
5. 重新运行 Java 和 Python 测试，记录真实结果。
6. 解决/确认生产 18086 端口变量设计。
7. 生成新的完整生产发布包和 SHA-256，并检查不含 runtime 数据。
8. 等待明确维护窗口与部署授权，再更新生产。

## 11. 相关文档

- `README.md`：项目总体说明。
- `JAVA_MIGRATION.md`：Java 主站迁移背景。
- `deploy/docker/README.md`：Docker 部署说明。
- `deploy/DEPLOYMENT_RUNBOOK_2026-08-26.md`：当天详细部署记录。
- `PROJECT_HANDOVER.md`：当前交接事实的主入口；后续应持续更新。
