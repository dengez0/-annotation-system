# SimpleLabel 2D 图像标注系统

SimpleLabel 是一个面向目标检测数据集生产的 Web 标注系统。系统以 **Java 21 + Spring Boot** 提供页面、标注、文件管理、数据处理和权限控制，以 **Python YOLO Worker** 执行自动标注，并提供独立的 **PT/ONNX 模型检测服务**；前端使用 HTML5 Canvas 和原生 JavaScript，无需额外前端构建步骤。

当前版本支持从“未标注”到“已完成”的完整任务流、LabelMe JSON 标注、YOLO 自动标注、YOLO 数据集导出、批量文件操作、数据清洗、工作日志和基于设备令牌的管理员权限。

> 项目包含旧 Flask 服务作为迁移和回滚参考。日常运行与新部署应优先使用 `backend-java/` 中的 Spring Boot 后端。

## 当前开发与部署状态

- 生产主站实际地址为 `http://192.168.1.226:18083`，生产模型检测实际地址为 `http://192.168.1.226:18086`。
- 隔离测试主站为 `http://192.168.1.226:29090`，测试模型检测为 `http://192.168.1.226:29091`，数据位于独立的 `runtime-test/`。
- 工作日志框数已改为“同一图片只统计最后一次保存的框数”；保存次数仍保留全部历史次数。
- 模型检测多图上传已改为逐图原始字节请求，前端最多两路并发，避免大批量 Base64 JSON 导致 `fail to fetch`。
- 返回首页逻辑已在本地改为优先读取 Java 传入的 `home_port`，并为 29091→29090、18086→18083 提供兜底；运行中的 29091 尚需更新页面文件后验证。
- 当前工作区仍有未提交改动，且尚未生成同时包含 Java 与模型检测修改的最新生产全量包。部署或接手工作前请先阅读 [`PROJECT_HANDOVER.md`](PROJECT_HANDOVER.md)。
- 生产 18086 的现有 Docker 映射与容器内监听端口必须在重建前现场确认，不能只按 `.env.example` 的默认 `8000` 推断。

## 目录

- [当前开发与部署状态](#当前开发与部署状态)
- [核心能力](#核心能力)
- [系统架构](#系统架构)
- [运行端口](#运行端口)
- [环境要求](#环境要求)
- [Windows 快速开始](#windows-快速开始)
- [通用手动启动](#通用手动启动)
- [Docker 部署](#docker-部署)
- [使用说明](#使用说明)
- [数据与目录约定](#数据与目录约定)
- [配置项](#配置项)
- [管理员权限](#管理员权限)
- [API 概览](#api-概览)
- [测试与构建](#测试与构建)
- [故障排查](#故障排查)
- [安全与数据保护](#安全与数据保护)

## 核心能力

### 标注编辑

- 在 Canvas 上绘制、选择、移动、缩放和删除矩形框。
- 自动保存为 LabelMe 兼容 JSON，图片与 JSON 同名存放。
- 支持标签复用、项目标签统计和自定义标签颜色。
- 支持图片搜索、多选、缩放、平移和上一张/下一张导航。
- 支持为未标注图片批量创建空 JSON。

### 四阶段任务流

任务按同名子目录在以下状态间流转：

```text
未标注 → 标注中 → 待检查 → 已完成
```

- 标注人员可以领取任务、提交检查。
- 检查人员可以退回任务或确认完成。
- 提交前会统计图片数和已标注数；是否允许不完整提交由操作参数控制。
- 文件移动和状态变化写入工作日志。

### YOLO 自动标注

- Java 后端创建和跟踪异步任务。
- Python Worker 加载本地 YOLO/RT-DETR 模型并逐图推理。
- 支持任务进度查询和取消。
- 模型文件由 `models/` 或运行时模型目录提供，不提交到 Git。
- 当前 Java 主流程仅保留 YOLO 自动标注；旧日志中的 LLM/SAM3 操作名不代表仍有对应入口。

### 独立模型检测

- 支持上传和选择 PT/ONNX 模型，对图片和视频执行检测。
- 多图模式使用 `POST /api/detect-image` 逐图上传原始文件，不再将整批图片编码为 Base64 JSON。
- 单图默认限制为 25 MB，浏览器最多同时发送两个检测请求，服务端通过推理锁保护模型推理。
- 接口返回检测坐标、类别和置信度，检测框由浏览器在原图上绘制。
- 通过兼容适配器同时支持现代 Ultralytics 模型和原始 YOLOv5 checkpoint。

### 文件和数据集管理

- 上传带目录结构的图片和标注文件。
- 项目/子项目重命名、删除、复制和移动。
- 标注项目 ZIP 下载。
- 按选定类别生成 YOLO 标签和 `classes.txt`。
- 数据处理页面支持图片修复、JSON 标签处理、指定区域涂黑和结果管理。
- 源数据及处理结果的下载、删除操作受管理员权限保护。

### 管理与审计

- 使用设备令牌激活管理员浏览器，不依赖来源 IP 自动授权。
- 管理员可查看工作日志、管理设备令牌以及执行敏感下载/删除操作。
- 令牌仅以 SHA-256 摘要持久化，浏览器通过 HttpOnly、SameSite=Strict Cookie 保持登录。
- 工作日志记录时间、客户端 IP、动作、项目、对象和数量，不记录图片内容、完整标注坐标或明文令牌。

## 系统架构

```text
浏览器
  ├─ :18083（生产）/ :29090（测试）→ Spring Boot 主站
  │    ├─ Thymeleaf 页面、标注、文件、日志、权限和数据处理 API
  │    └─ HTTP 调用 127.0.0.1:18085 → Python YOLO Worker
  │
  └─ :18086（生产）/ :29091（测试）→ Python 模型检测服务
       └─ PT / ONNX / YOLOv5 兼容推理

持久化目录
  ├─ 生产：runtime/{data,models,logs,admin,processed,backups}
  └─ 测试：runtime-test/{data,models,logs,admin,processed,backups}
```

主要技术栈：

| 层级 | 技术 |
| --- | --- |
| 主后端 | Java 21、Spring Boot 3.4、Spring MVC、Thymeleaf |
| 推理服务 | Python、FastAPI/Flask、Ultralytics、ONNX Runtime、YOLOv5 |
| 前端 | HTML5 Canvas、原生 JavaScript、CSS |
| 图像与归档 | metadata-extractor、TwelveMonkeys ImageIO、Apache Commons Compress |
| 数据格式 | LabelMe JSON、YOLO TXT |
| 部署 | Windows 脚本、systemd 示例、Docker Compose |

## 运行端口

| 端口 | 用途 | 默认监听范围 |
| --- | --- | --- |
| `18083` | Java 正式服务 | `0.0.0.0` |
| `18084` | Windows 本地 Java 并行预览服务 | `0.0.0.0` |
| `18085` | YOLO Worker | 本机回环地址 |
| `18086` | 生产 PT/ONNX 模型检测页面（现场实际端口） | `0.0.0.0`，重建前核对 Docker 映射 |
| `29090` | Ubuntu 隔离测试 Java 主站 | `192.168.1.226` |
| `29091` | Ubuntu 隔离测试模型检测页面 | `192.168.1.226` |
| `8000` | 仓库默认的模型检测容器监听/示例端口 | 按部署配置，不等同于现场生产外部端口 |

局域网用户通过 18083 使用生产主站，通过 18086 使用生产模型检测。测试环境仅使用 29090/29091，并必须挂载 `runtime-test/`；YOLO Worker 18085 不应直接暴露到局域网或公网。

## 环境要求

本地运行需要：

- Java 21
- Maven 3.9+
- Python 3.10+ 和 pip
- Windows 10/11，或常见 Linux 发行版
- 足够的磁盘空间存放原图、标注、处理结果和模型

Docker 部署还需要 Docker Engine 和 Docker Compose v2。

GPU 不是必需条件。默认部署可以在 CPU 上推理；如需 CUDA，请自行确认主机驱动、容器运行时、镜像和模型兼容性后再启用。

## Windows 快速开始

仓库中的 Windows 脚本约定本地工具链位于：

```text
.tools/jdk/<JDK 目录>/
.tools/maven/<Maven 目录>/
```

`.tools/` 仅用于本机，不会提交到 Git。如果已经全局安装 Java 和 Maven，也可以使用下一节的通用命令。

### 1. 安装 Python 依赖

建议使用虚拟环境：

```powershell
py -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
pip install -r requirements.txt
```

### 2. 构建 Java 后端

双击 `build_java.bat`，或执行：

```powershell
.\build_java.bat
```

脚本会运行 Java 测试，并生成：

```text
backend-java/target/simplelabel-java-1.0.0-SNAPSHOT.jar
```

### 3. 启动预览服务

```powershell
.\start_java_preview.bat
```

打开 `http://127.0.0.1:18084`。预览模式用于在不占用正式端口 `18083` 的情况下验证 Java 版本。

### 4. 启动正式服务

确认旧服务已经正常停止，再执行：

```powershell
.\start_java_server.bat
```

打开 `http://127.0.0.1:18083`。启动脚本发现目标端口被占用时会直接报错，不会终止已有进程。

> 预览和正式服务如果指向同一份 `data/`，不要同时编辑同一张图片，否则后保存的一方会覆盖先保存的 JSON。

## 通用手动启动

### 1. 构建后端

```bash
mvn -f backend-java/pom.xml clean package
```

### 2. 创建运行目录

```bash
mkdir -p data models logs admin processed backups
```

### 3. 启动 YOLO Worker

先安装 Python 依赖：

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

然后设置 Worker 配置并启动：

```bash
export SIMPLELABEL_DATA_DIR="$PWD/data"
export SIMPLELABEL_MODELS_DIR="$PWD/models"
export SIMPLELABEL_YOLO_WORKER_PORT=18085
export SIMPLELABEL_YOLO_WORKER_TOKEN='请替换为随机长令牌'
export SIMPLELABEL_YOLO_DEVICE=cpu
python yolo-worker/worker.py
```

### 4. 启动 Java 服务

在另一个终端使用同一个 Worker 令牌：

```bash
export SIMPLELABEL_ROOT="$PWD"
export SIMPLELABEL_PORT=18083
export SIMPLELABEL_DATA_DIR="$PWD/data"
export SIMPLELABEL_MODELS_DIR="$PWD/models"
export SIMPLELABEL_LOGS_DIR="$PWD/logs"
export SIMPLELABEL_ADMIN_DIR="$PWD/admin"
export SIMPLELABEL_PROCESSED_DIR="$PWD/processed"
export SIMPLELABEL_BACKUPS_DIR="$PWD/backups"
export SIMPLELABEL_STATIC_DIR="$PWD/static"
export SIMPLELABEL_YOLO_WORKER_URL='http://127.0.0.1:18085'
export SIMPLELABEL_YOLO_WORKER_TOKEN='与 Worker 相同的随机长令牌'
java -jar backend-java/target/simplelabel-java-1.0.0-SNAPSHOT.jar
```

健康检查：

```bash
curl --fail http://127.0.0.1:18083/internal/health
curl --fail http://127.0.0.1:18085/internal/health
```

## Docker 部署

Docker 配置位于 `deploy/docker/`，会将运行数据绑定到仓库根目录的 `runtime/`，从而使容器重建不影响标注数据。

生产与测试必须使用不同的运行目录：生产挂载 `runtime/`，测试挂载 `runtime-test/`。生产实际使用 18083/18086；隔离测试使用 29090/29091。当前服务器曾采用 `simplelabel-web-test` 与 `simplelabel-model-test` 两个独立测试容器，执行仓库的单容器测试栈脚本前应先用 `docker ps -a` 核对现场拓扑。

### 首次部署

```bash
cp deploy/docker/.env.example deploy/docker/.env
bash deploy/docker/deploy.sh
python3 deploy/docker/configure_admin_token.py runtime/admin/admin_tokens.json admin-pc-1
docker compose --env-file deploy/docker/.env -f deploy/docker/compose.yml up -d
bash deploy/docker/verify.sh
```

`configure_admin_token.py` 会无回显读取令牌并只保存摘要。不要把明文令牌放入命令行、Shell 历史、仓库或发布包。

### 日常运维

```bash
# 查看状态
docker compose --env-file deploy/docker/.env -f deploy/docker/compose.yml ps

# 查看最近日志
docker compose --env-file deploy/docker/.env -f deploy/docker/compose.yml logs --tail=200

# 重启
docker compose --env-file deploy/docker/.env -f deploy/docker/compose.yml restart

# 停止服务但保留数据
docker compose --env-file deploy/docker/.env -f deploy/docker/compose.yml down
```

不要对该项目使用 `docker compose down -v`。更完整的生产部署、隔离测试栈和令牌恢复说明见 [`deploy/docker/README.md`](deploy/docker/README.md)。Ubuntu 原生部署参考 [`deploy/ubuntu/README.md`](deploy/ubuntu/README.md)。

当前生产发布还需同时包含 Java 框数统计和 Python 模型检测修改，已有的 `simplelabel-worklog-final-boxes-production.tar.gz` 只是 Java 热修复包，不能作为本次完整生产包。生产发布与回滚前置检查见 [`PROJECT_HANDOVER.md`](PROJECT_HANDOVER.md)。

## 使用说明

### 标注快捷键

| 快捷键 | 功能 |
| --- | --- |
| `R` | 切换绘制/编辑模式 |
| `A` | 上一张图片 |
| `D` | 下一张图片 |
| `Delete` / `Backspace` | 删除选中标注框 |
| `Esc` | 取消当前绘制 |
| `Ctrl` + `=` | 放大 |
| `Ctrl` + `-` | 缩小 |
| `Ctrl` + `0` | 图片自适应窗口 |
| `Ctrl` + `F` | 搜索文件 |

### 鼠标操作

| 操作 | 功能 |
| --- | --- |
| 绘制模式下左键拖拽 | 创建矩形框 |
| 编辑模式下左键单击 | 选择矩形框 |
| 拖拽框体 | 移动标注 |
| 拖拽控制点 | 调整标注大小 |
| 右键或中键拖拽 | 平移画布 |
| 滚轮 | 缩放 |
| 双击空白处 | 恢复自适应视图 |

### 推荐生产流程

1. 将待处理图片上传到“未标注”任务。
2. 领取任务进入“标注中”。
3. 选择人工标注，或先运行 YOLO 自动标注再逐图修正。
4. 提交到“待检查”。
5. 检查人员退回修改或确认进入“已完成”。
6. 管理员按需导出项目 ZIP、YOLO 标签或运行数据处理任务。

## 数据与目录约定

### 代码目录

```text
.
├─ backend-java/               # Spring Boot 主后端、Thymeleaf 模板和测试
├─ yolo-worker/                # Python YOLO 推理服务
├─ static/                     # Java 与旧 Flask 共用的 CSS/JavaScript
├─ templates/                  # 旧 Flask 模板（迁移/回滚保留）
├─ routes/、services/           # 旧 Flask 路由与服务（迁移/回滚保留）
├─ deploy/
│  ├─ docker/                  # Docker Compose、镜像、验证与令牌工具
│  └─ ubuntu/                  # Ubuntu 原生部署和 systemd 示例
├─ scripts/                    # 本地运行和发布脚本
├─ tests/                      # Python 回归测试
├─ app.py                      # 旧 Flask 入口
└─ requirements.txt            # Python Worker/旧服务依赖
```

### 运行时目录

```text
data/
├─ 未标注/<任务名>/
├─ 标注中/<任务名>/
├─ 待检查/<任务名>/
└─ 已完成/<任务名>/

models/                        # .pt / .onnx / .engine 等模型
logs/                          # 应用和工作日志
admin/                         # 管理员令牌摘要注册表
processed/                     # 数据处理结果
backups/                       # 可选的处理前备份
```

Docker 生产环境使用对应的 `runtime/data`、`runtime/models`、`runtime/logs`、`runtime/admin`、`runtime/processed` 和 `runtime/backups`；隔离测试环境使用同结构的 `runtime-test/`。任何代码发布均不得覆盖、删除或用另一环境的数据替换这些目录。

图片和标注文件同名存放，例如：

```text
data/标注中/安全帽任务/
├─ image001.jpg
├─ image001.json
├─ image002.jpg
└─ image002.json
```

### LabelMe JSON 示例

```json
{
  "version": "5.2.1",
  "flags": {},
  "shapes": [
    {
      "label": "helmet",
      "points": [[100, 200], [300, 400]],
      "group_id": null,
      "shape_type": "rectangle",
      "flags": {}
    }
  ],
  "imagePath": "image001.jpg",
  "imageData": null,
  "imageHeight": 1080,
  "imageWidth": 1920
}
```

## 配置项

Spring Boot 默认配置位于 `backend-java/src/main/resources/application.yml`。生产环境建议通过环境变量覆盖，不直接修改代码或提交真实密钥。

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SIMPLELABEL_BIND_ADDRESS` | `0.0.0.0` | Java 监听地址 |
| `SIMPLELABEL_PORT` | `18084` | Java 服务端口；生产配置通常设为 `18083` |
| `SIMPLELABEL_ROOT` | `.` | 项目根目录 |
| `SIMPLELABEL_DATA_DIR` | `data` | 标注数据目录 |
| `SIMPLELABEL_MODELS_DIR` | `models` | 模型目录 |
| `SIMPLELABEL_LOGS_DIR` | `logs` | 日志目录 |
| `SIMPLELABEL_ADMIN_DIR` | `admin` | 管理员注册表目录 |
| `SIMPLELABEL_PROCESSED_DIR` | `processed` | 数据处理结果目录 |
| `SIMPLELABEL_BACKUPS_DIR` | `backups` | 备份目录 |
| `SIMPLELABEL_STATIC_DIR` | `static` | 静态资源目录 |
| `SIMPLELABEL_YOLO_WORKER_URL` | `http://127.0.0.1:18085` | Worker 地址 |
| `SIMPLELABEL_YOLO_WORKER_TOKEN` | 本地开发值 | Java 与 Worker 共享令牌；生产必须替换 |
| `SIMPLELABEL_YOLO_WORKER_PORT` | `18085` | Worker 端口 |
| `SIMPLELABEL_YOLO_DEVICE` | `cpu`（部署示例） | 推理设备，如 `cpu`、`0` |
| `SIMPLELABEL_MODEL_DETECTION_PORT` | `8000` | 模型检测监听端口，同时被 Java 用作模型检测跳转端口；生产外部 18086 部署前必须核对实际映射 |
| `SIMPLELABEL_MODEL_DETECTION_MAX_IMAGE_BYTES` | `26214400` | 独立模型检测单张图片请求体上限，默认 25 MB |
| `SIMPLELABEL_ADMIN_TOKEN_HASHES` | 空 | 仅首次启动导入的管理员令牌摘要 |
| `SIMPLELABEL_ADMIN_COOKIE_DAYS` | `365` | 管理员 Cookie 有效天数 |
| `SIMPLELABEL_ADMIN_COOKIE_SECURE` | `false` | 启用 HTTPS 后应设为 `true` |
| `SIMPLELABEL_TIME_ZONE` | `Asia/Shanghai` | 日志和健康检查时区 |

## 管理员权限

### 激活设备

1. 管理员为每台受信设备生成不同令牌。
2. 在该设备浏览器打开 `/admin/activate`。
3. 输入设备名和令牌完成激活。
4. 激活后通过 `/admin/tokens` 管理设备。

服务端只保存令牌摘要；新令牌的明文只显示一次。吊销设备后，其下一次受保护请求会失效。当前设备和最后一个有效设备不能直接删除，以避免意外锁死管理入口。

### 受保护操作

包括但不限于：

- 查看详细工作日志。
- 管理管理员设备令牌。
- 下载或删除源数据。
- 下载或删除数据处理结果。
- 执行项目及 YOLO 导出等敏感操作。

直接使用 HTTP 时，令牌 Cookie 可能被同一网络中的攻击者窃取。正式环境应在反向代理后启用 HTTPS，并设置 `SIMPLELABEL_ADMIN_COOKIE_SECURE=true`。

## API 概览

以下列出主要接口，具体请求体和响应结构以控制器及前端调用为准。

### 页面

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/` | 工作流首页 |
| `GET` | `/annotation` | 标注入口 |
| `GET` | `/annotate/{main}/{sub}` | 指定任务标注页 |
| `GET` | `/data-processing` | 数据处理页 |
| `GET` | `/model-detection` | 模型检测页 |
| `GET` | `/work-logs` | 工作日志页 |
| `GET` | `/admin/activate` | 管理员设备激活页 |
| `GET` | `/admin/tokens` | 管理员设备管理页 |

### 标注与工作流

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/projects` | 获取项目列表 |
| `GET` | `/api/subfolders/{main}` | 获取任务列表 |
| `GET` | `/api/images/{main}/{sub}` | 获取图片及标注状态 |
| `GET` | `/api/labels/{main}/{sub}` | 获取已使用标签 |
| `GET/PUT` | `/api/label-colors/{main}/{sub}` | 读取/保存标签颜色 |
| `POST` | `/api/save/{main}/{sub}` | 保存 LabelMe JSON |
| `GET` | `/api/annotations/{main}/{sub}/{filename}` | 读取标注 |
| `POST` | `/api/workflow/tasks/{task}/transition` | 转换任务状态 |

### 文件、导出与模型

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/annotation-upload` | 上传标注项目 |
| `POST` | `/api/rename_project` | 重命名项目 |
| `POST` | `/api/delete_project` | 删除项目 |
| `POST` | `/api/delete_files/{main}/{sub}` | 批量删除文件 |
| `POST` | `/api/copy_files/{main}/{sub}` | 批量复制文件 |
| `POST` | `/api/create_empty_jsons/{main}/{sub}` | 创建空标注 JSON |
| `GET` | `/api/export/{main}/{sub}` | 下载项目 ZIP |
| `POST` | `/api/export_yolo/{main}/{sub}` | 导出 YOLO 标签 |
| `GET/POST` | `/api/models`、`/api/upload_model` | 列出/上传模型 |
| `DELETE` | `/api/delete_model/{modelName}` | 删除模型 |

独立模型检测服务还提供：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/detect-image?model_name=...&conf=...&iou=...` | 请求体为单张图片原始字节，返回检测坐标、类别、置信度和图片尺寸 |
| `POST` | `/api/batch_detect` | 旧批量 Base64 接口，仅保留兼容；当前网页不再调用 |

### 自动标注与运维

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/auto_label/{main}/{sub}` | 创建 YOLO 自动标注任务 |
| `GET` | `/api/task_status/{taskId}` | 查询任务进度 |
| `POST` | `/api/cancel_task/{taskId}` | 取消任务 |
| `GET` | `/api/work-logs/overview` | 工作日志概览 |
| `GET` | `/api/work-logs/ip/{workerIp}` | 指定 IP 的工作日志 |
| `GET` | `/internal/health` | Java 健康检查 |

## 测试与构建

### Java

```bash
mvn -f backend-java/pom.xml clean verify
```

Windows 本地工具链也可以直接执行：

```powershell
.\build_java.bat
```

### Python

```bash
python -m unittest tests.test_model_detection_streaming_contract tests.test_model_detection_compat
python -m pytest tests
```

前两项覆盖模型检测的流式上传契约、两路前端并发、返回首页端口规则和 YOLOv5 兼容适配。交接时当前 Windows 会话中的 `python.exe` 无法访问，因此这些新增测试尚需在可用 Python 环境中重新执行并记录结果。

### Docker 配置检查

```bash
docker compose --env-file deploy/docker/.env -f deploy/docker/compose.yml config
bash deploy/docker/verify.sh
```

每次修改文件路径、权限、上传、归档或数据处理逻辑后，除自动化测试外，还应使用无生产数据的测试任务验证上传、保存、状态流转、导出和删除边界。

## 故障排查

### 页面无法访问

1. 检查 `18083` 或 `18084` 是否监听。
2. 本机执行 `/internal/health` 健康检查。
3. 检查系统防火墙和上游网络策略。
4. Docker 环境查看 `docker compose ... ps` 和 `logs --tail=200`。

### 自动标注失败

1. 确认 `18085` Worker 健康检查通过。
2. 确认 Java 和 Worker 使用相同的 `SIMPLELABEL_YOLO_WORKER_TOKEN`。
3. 检查模型是否位于配置的模型目录，且容器/进程有读取权限。
4. 检查所选模型格式是否受当前 Ultralytics/ONNX Runtime 支持。
5. GPU 模式失败时先用 `SIMPLELABEL_YOLO_DEVICE=cpu` 验证基础链路。

### 上传或导出失败

- 检查运行目录的所有者、读写权限和剩余空间。
- 检查项目名、子目录名和文件名是否包含不受支持的路径片段。
- 大文件上传时检查反向代理的请求体限制和超时设置。
- Docker 部署确认 `runtime/` 绑定目录存在且 UID/GID 与 `.env` 一致。

### 多图模型检测出现 `fail to fetch`

1. 在浏览器网络面板确认新页面调用的是逐图 `/api/detect-image`，不是旧 `/api/batch_detect`。
2. 检查 18086/29091 容器日志是否发生重启、内存不足、请求体过大或模型加载失败。
3. 确认单图没有超过 `SIMPLELABEL_MODEL_DETECTION_MAX_IMAGE_BYTES`。
4. 如果源码已更新但浏览器仍显示旧行为，检查容器内 `model_detection.html`，并强制刷新；新服务会对首页返回 `Cache-Control: no-store`。

### 模型检测返回首页端口错误

Java 主站跳转到模型检测时会通过 `home_port` 查询参数显式传入首页端口；检测页面还保留端口映射兜底：29091 返回 29090，18086 返回 18083。若仍跳错，依次检查：

1. 浏览器地址中是否包含 `?home_port=29090` 或 `?home_port=18083`。
2. 运行容器中的 `model_detection.html` 是否包含 `returnToHome`。
3. 页面响应是否含 `Cache-Control: no-store`，以及浏览器是否仍使用旧缓存。

### 管理员无法登录

- 确认设备名与注册表中的名称一致。
- 令牌明文无法从摘要恢复；遗失时应停机并使用离线工具重新配置。
- HTTPS 环境如果启用了 Secure Cookie，请确保用户确实通过 HTTPS 访问。
- 不要通过修改客户端 IP 的方式绕过令牌认证。

## 安全与数据保护

- `data/`、`models/`、`logs/`、`admin/`、`processed/`、`backups/`、`runtime/`、`.env` 和本地工具链均不应提交到 Git。
- 不要提交模型权重、真实图片、标注数据、管理员令牌、Cookie、私钥或生产日志。
- 发布包应只包含代码、部署定义和校验文件。
- 删除或覆盖生产数据前先确认备份可恢复，并避免在标注进行中切换服务。
- Worker 令牌和管理员令牌必须使用不同的高强度随机值。
- 如需通过公网访问，应增加 HTTPS、访问控制、可信反向代理和定期备份，不要直接暴露应用或 Worker。

## 迁移与回滚

Java 后端复用既有数据目录、LabelMe JSON 和工作日志格式。旧 Flask 服务可用于应急回滚，但不要让两个后端同时写同一份标注数据。详细迁移说明见 [`JAVA_MIGRATION.md`](JAVA_MIGRATION.md)。

当前环境、未提交改动、发布物状态、生产 18086 风险和接任顺序统一记录在 [`PROJECT_HANDOVER.md`](PROJECT_HANDOVER.md)；2026-08-26 的实际补丁传输与 JAR 热更新过程见 [`deploy/DEPLOYMENT_RUNBOOK_2026-08-26.md`](deploy/DEPLOYMENT_RUNBOOK_2026-08-26.md)。

## License

当前仓库未声明开源许可证，默认仅供项目内部使用。若计划公开分发或接受外部贡献，请先补充明确的许可证和贡献指南。
