# KINTH 2D Annotation System

基于 Web 的轻量级 2D 图像标注系统，支持手工标注与三种 AI 自动标注能力（YOLO、LLM、SAM3），适用于目标检测数据集的快速构建。

## 主要功能

### 手工标注
- **矩形框绘制**：拖拽绘制，编辑模式支持拖拽移动/缩放标注框
- **多标签管理**：右侧面板展示所有标签，支持自动复用上次标签
- **文件浏览**：左侧文件列表，支持搜索过滤、多选批量操作
- **视图控制**：滚轮缩放、右键平移、双击自适应、Ctrl+0 还原

### AI 自动标注

| 模式 | 适用场景 | 说明 |
|------|---------|------|
| **Auto Label (YOLO)** | 大量数据批量标注 | 支持 ultralytics YOLO/RTDETR 及 YOLOv5 自定义权重，多后端自动回退 |
| **Auto Label (LLM)** | 通用场景补充 | 对接 OpenAI 兼容接口（硅基流动等），支持 Few-shot 样本引导 |
| **Auto Label (SAM3)** | 新数据初筛 | 基于标注样本的 Box Prompt + Text Prompt 混合推理，适用于大物体 |

### 数据管理
- **上传**：支持嵌套文件夹上传，自动保留目录结构
- **项目组织**：主文件夹 → 子文件夹 两级结构
- **批量操作**：多选后复制/移动/删除，支持跨项目移动
- **复制到 Paste**：选中图片一键复制到 `paste image` 文件夹
- **导出**：子项目 ZIP 打包下载、YOLO 格式 TXT 导出（含 classes.txt）
- **重命名**：支持主文件夹和子文件夹重命名

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Flask |
| 生产服务器 | Waitress (Windows) / Gunicorn (Linux) |
| 前端 | HTML5 Canvas + 原生 JavaScript（无框架） |
| AI 推理 | ultralytics (YOLO/RTDETR/SAM3)、OpenAI SDK（LLM） |
| 图像处理 | OpenCV (cv2)、Pillow |
| 数据格式 | LabelMe JSON (v5.2.1) |

## 快速开始

### 环境要求

- Python 3.8+
- pip

### 安装

```bash
pip install -r requirements.txt
```

`requirements.txt` 内容：

```
Flask
ultralytics
tenacity
openai
Pillow
gunicorn
waitress
```

### 启动服务

**开发模式（单人使用）：**

```bash
python app.py
```

**生产模式（多人并发）：**

Windows:
```bash
双击 start_server.bat
# 或手动: python -m waitress --host=0.0.0.0 --port=18083 --threads=8 app:app
```

Linux/Mac:
```bash
bash start_gunicorn.sh
# 或手动: gunicorn -c gunicorn.conf.py app:app
```

启动后访问：**http://localhost:18083**

### 局域网访问

1. 查看本机 IP（Windows: `ipconfig`，Linux/Mac: `ip a`）
2. 其他设备访问：`http://<你的IP>:18083`
3. 确保防火墙允许 18083 端口

## 项目结构

```
json初步框定/
├── app.py                    # Flask 主应用（含所有 API 路由和后台任务）
├── default_prompt.py         # LLM 自动标注的默认 System Prompt
├── gunicorn.conf.py          # Gunicorn 生产配置（端口/worker/日志）
├── start_gunicorn.sh         # Linux 生产启动脚本
├── start_server.bat          # Windows 生产启动脚本
├── requirements.txt          # Python 依赖
│
├── data/                     # 数据存储目录
│   ├── annotation image/     # 标注项目（示例）
│   ├── moved image/          # 移动操作目标目录
│   ├── paste image/          # 粘贴操作目标目录
│   └── test image/           # 测试图片
│
├── models/                   # AI 模型文件（.pt / .onnx / .engine）
│
├── static/
│   ├── css/style.css         # 样式表（暗色主题）
│   └── js/editor.js          # 标注编辑器核心逻辑（~2600 行）
│
├── templates/
│   ├── index.html            # 首页（项目列表 + 上传入口）
│   └── annotate.html         # 标注页（画布 + 工具栏 + 面板）
│
├── logs/                     # 服务器日志
│   ├── server.log            # 应用日志
│   ├── access.log            # HTTP 访问日志（Gunicorn）
│   └── error.log             # 错误日志（Gunicorn）
│
└── 工具脚本/
    ├── convert_climb.py      # Climb 数据格式转换
    ├── convert_specific.py   # 特定数据格式转换
    ├── xml_to_json.py        # XML 标注 → LabelMe JSON
    ├── format_jsons.py       # JSON 格式化/修复
    ├── keep_files_by_labels.py # 按标签筛选文件
    └── remove_mask.py        # 移除 mask 标注
```

## 数据存储结构

```
data/
└── <主文件夹>/
    └── <子文件夹>/
        ├── image001.jpg        # 原始图片
        ├── image001.json       # 标注数据（LabelMe 格式）
        ├── image002.jpg
        ├── image002.json
        └── labels/             # YOLO 导出目录（可选）
            ├── image001.txt
            ├── image002.txt
            └── classes.txt
```

### 标注 JSON 格式 (LabelMe)

```json
{
  "version": "5.2.1",
  "flags": {},
  "shapes": [
    {
      "label": "helmet",
      "points": [[100, 200], [300, 200], [300, 400], [100, 400]],
      "group_id": null,
      "description": "",
      "difficult": false,
      "shape_type": "rectangle",
      "flags": {},
      "attributes": {}
    }
  ],
  "imagePath": "image001.jpg",
  "imageData": null,
  "imageHeight": 1080,
  "imageWidth": 1920
}
```

## 操作指南

### 快捷键

| 快捷键 | 功能 |
|--------|------|
| `R` | 切换绘制模式 / 编辑模式 |
| `A` | 上一张图片 |
| `D` | 下一张图片 |
| `Delete` / `Backspace` | 删除选中的标注框 |
| `Esc` | 取消当前正在绘制的框 |
| `Ctrl` + `=` | 放大 |
| `Ctrl` + `-` | 缩小 |
| `Ctrl` + `0` | 自适应窗口 |
| `Ctrl` + `F` | 搜索文件 |

### 鼠标操作

| 操作 | 功能 |
|------|------|
| 左键拖拽（绘制模式） | 绘制矩形标注框 |
| 左键点击（编辑模式） | 选中标注框 |
| 左键拖拽标注框（编辑模式） | 移动标注框 |
| 左键拖拽控制点（编辑模式） | 缩放标注框 |
| 右键/中键拖拽 | 平移画布 |
| 滚轮 | 缩放 |
| 双击空白处 | 自适应还原 |

### 批量操作

1. 在左侧文件列表中**勾选**多张图片
2. 工具栏按钮亮起，可执行：
   - **📋 Paste**：复制到 `paste image` 文件夹
   - **📁 Move**：移动到其他项目（支持新建目标子文件夹）
   - **🗑 Delete**：删除选中文件及其 JSON

## 生产部署配置

### Gunicorn 配置 (`gunicorn.conf.py`)

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `bind` | `0.0.0.0:18083` | 监听地址和端口 |
| `workers` | 4 | Worker 进程数 |
| `threads` | 2 | 每个 Worker 线程数 |
| `timeout` | 300s | 请求超时（自动标注任务耗时长） |
| `max_requests` | 1000 | 处理 N 请求后重启 Worker（防内存泄漏） |
| `reload` | false | 生产环境务必关闭 |

### 并发限制

- 最大同时后台任务数：`MAX_BG_TASKS = 3`（在 `app.py` 中配置）
- Windows (Waitress)：`--threads=8`，约等于并发用户数 × 2

## API 接口一览

### 页面路由

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 首页，项目列表 |
| GET | `/annotate/<main_folder>/<subfolder>` | 标注页面 |

### 数据 API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/projects` | 获取所有主文件夹列表 |
| GET | `/api/subfolders/<main_folder>` | 获取主文件夹下的子文件夹 |
| GET | `/api/images/<main_folder>/<subfolder>` | 获取子文件夹下图片列表及标注状态 |
| GET | `/api/labels/<main_folder>/<subfolder>` | 获取子文件夹下所有已用标签 |
| POST | `/api/save/<main_folder>/<subfolder>` | 保存标注 JSON |
| GET | `/data/<main_folder>/<subfolder>/<filename>` | 获取文件（图片/JSON） |

### 文件操作

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/upload` | 上传文件（支持嵌套目录） |
| POST | `/api/rename_project` | 重命名项目/子文件夹 |
| POST | `/api/delete_project` | 删除项目/子文件夹 |
| POST | `/api/delete_files/<main_folder>/<subfolder>` | 批量删除文件 |
| POST | `/api/move_files/<main_folder>/<subfolder>` | 批量移动文件 |
| POST | `/api/copy_files/<main_folder>/<subfolder>` | 批量复制到 paste image |
| POST | `/api/create_empty_jsons/<main_folder>/<subfolder>` | 为无标注图片创建空 JSON |

### 导出

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/export/<main_folder>/<subfolder>` | 导出子文件夹为 ZIP |
| POST | `/api/export_yolo/<main_folder>/<subfolder>` | 导出为 YOLO 格式 TXT |

### 模型管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/models` | 获取可用模型列表 |
| POST | `/api/upload_model` | 上传模型文件 |
| DELETE | `/api/delete_model/<model_name>` | 删除模型 |

### 自动标注

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auto_label/<main_folder>/<subfolder>` | 启动 YOLO 自动标注 |
| POST | `/api/auto_label_llm/<main_folder>/<subfolder>` | 启动 LLM 自动标注 |
| POST | `/api/auto_label_sam3/<main_folder>/<subfolder>` | 启动 SAM3 自动标注 |
| GET | `/api/task_status/<task_id>` | 查询任务进度 |
| POST | `/api/cancel_task/<task_id>` | 取消任务 |

## AI 自动标注详解

### 1. Auto Label (YOLO)

使用 YOLO 模型对图片进行批量推理，自动生成标注 JSON。

**后端支持：**
- **ultralytics**：原生 YOLOv8/v11/RTDETR 模型（优先）
- **yolov5_hub**：YOLOv5 自定义权重（检测到旧版 pickle 依赖时自动启用）

**模型放置：** 将 `.pt` / `.onnx` 文件放入 `models/` 目录

**标签名映射：** 可在模型同目录放置 `.txt` 或 `.yaml` 文件定义类别名：
- `xxx.txt`：每行一个类别名
- `xxx.yaml`：`names:` 字段定义类别

**适用场景：** 数据量大、标签种类固定的批量标注。对小物体（closeeyes、fire）识别可能不佳。

---

### 2. Auto Label (LLM)

调用大语言视觉模型进行标注，支持 Few-shot 样本引导。

**前置条件：** 需要 API Key（推荐硅基流动 siliconflow.cn）

**配置参数：**
- API Key
- Base URL（默认 `https://api.siliconflow.cn/v1`）
- 模型名称（如 `Qwen/Qwen2.5-VL-7B-Instruct`）
- 自定义 Prompt（支持修改系统指令）
- 样本源（可选，启用 Few-shot 引导）

**样本准备：** 创建 `data/samples/` 文件夹，放入少量已标注的图片+JSON 作为示例。

**标注流程：**
1. 图片缩放至模型输入尺寸
2. 构造 System Prompt + Few-shot Messages + User Message
3. 调用 OpenAI 兼容 API
4. 解析返回 JSON，坐标还原至原图尺寸
5. 标签映射与过滤（仅保留预定义标签列表中的标签）

**适用场景：** 通用场景补充，标注精度不如专用模型。

---

### 3. Auto Label (SAM3)

基于 Segment Anything Model 3 的语义预测，使用已标注样本引导新数据标注。

**前置条件：** 安装 ultralytics（含 SAM3 模块），模型文件放入 `models/`

**推理策略：**
1. **Box Prompt（优先）**：读取样本中同名图片的标注框，缩放后作为 prompt box
2. **Text Prompt（回退）**：Box prompt 无结果时，使用标签名作文本提示
3. **后处理**：面积过滤 + IoU 检查 + 同类 NMS

**适用场景：**
- 新数据初筛，快速过一遍
- 适用于大物体：helmet、extinguisher、mobilephone、walkie、fire
- **不适用**于小物体或有歧义标签：closeeyes、yawn、uniform/unwear_uniform、deckopen/close

---

### 三种模式对比

| 维度 | YOLO | LLM | SAM3 |
|------|------|-----|------|
| 精度 | ⭐⭐⭐ 高（需训练） | ⭐⭐ 中 | ⭐⭐⭐ 较高 |
| 速度 | ⭐⭐⭐ 快 | ⭐ 慢（API 调用） | ⭐⭐ 中 |
| 小物体 | 一般 | 差 | 差 |
| 成本 | 免费（本地推理） | API 费用 | 免费（本地推理） |
| 前提条件 | 需训练/微调模型 | 需 API Key | 需少量标注样本 |

**推荐工作流：**
1. **SAM3** 对新数据过一遍 → 人工检查修正
2. 用修正后的数据**训练/微调 YOLO** 模型
3. **YOLO** 对大批量数据自动标注
4. **LLM** 处理 YOLO 覆盖不全的边缘情况

## 工具脚本说明

| 脚本 | 用途 |
|------|------|
| `xml_to_json.py` | 将 Pascal VOC XML 标注转为 LabelMe JSON |
| `convert_climb.py` | 转换特定爬架数据集格式 |
| `convert_specific.py` | 转换特定数据集格式 |
| `format_jsons.py` | 批量修复/格式化 JSON 文件 |
| `keep_files_by_labels.py` | 按标签名筛选并保留图片和 JSON |
| `remove_mask.py` | 移除标注中的 mask 字段 |

## 默认预定义标签

```
without_helmet   helmet      smoking       walkie
cup              mobilephone mask          closeeyes
yawn             unwear_uniform  uniform   fire
deckopen         deckclose  life          unwear_life
extinguisher
```

## License

Internal use.
