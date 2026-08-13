# SimpleLabel 后端 Java 化实施说明

## 当前结果

主业务后端已迁移为 Java 21 + Spring Boot，接口路径、网页入口、`data/`、`models/`、`logs/ip_work.log` 和 LabelMe JSON 格式保持兼容。Python 仅保留本机 YOLO 推理 Worker；LLM 和 SAM3 的页面按钮、前端调用和后端路由均已移除。

默认端口分工：

- 当前 Python 正式服务：`18083`（切换前保持不动）
- Java 并行预览：`18084`
- Python YOLO Worker：`127.0.0.1:18085`，不对局域网开放
- 正式切换后的 Java 服务：`18083`

## 构建与验证

1. 双击 `build_java.bat`。它会先运行全部 Java 回归测试，再生成：
   `backend-java/target/simplelabel-java-1.0.0-SNAPSHOT.jar`。
2. 双击 `start_java_preview.bat`。
3. 浏览器打开 `http://127.0.0.1:18084`；局域网验证时用本机 IP 加 `:18084`。
4. 验证项目列表、打开图片、保存标注、移动/恢复图片、工作日志、模型列表和 YOLO 自动标注。

预览服务和当前 `18083` 正式服务可以同时运行，但两者会读取同一套数据。验证界面和只读功能没有影响；不要在两个服务里同时修改同一张图片的标注 JSON，以免后保存的一方覆盖先保存结果。

## 正式切换

1. 选择无人标注的时间窗口，确认没有进行中的自动标注任务。
2. 正常停止旧的 `start_server.bat` 窗口。
3. 双击 `start_java_server.bat`。脚本若发现 `18083` 仍被占用会直接退出，不会自动杀死旧服务。
4. 验证 `http://127.0.0.1:18083` 和局域网访问。

## 回滚

停止 `start_java_server.bat` 窗口，然后重新运行原来的 `start_server.bat`。Java 后端复用原数据与日志格式，因此不需要转换或回写数据。旧 Python 服务中 LLM/SAM3 路由也已停用，回滚后仍只提供 YOLO 自动标注。

## 代码边界

- `backend-java/`：网页、REST API、文件操作、日志统计、任务状态、模型管理。
- `yolo-worker/worker.py`：只负责加载 YOLO 模型和对单张图片推理。
- `static/`：Java 与原系统共用的前端资源。
- `templates/`：旧 Flask 模板；`backend-java/src/main/resources/templates/` 是对应的 Thymeleaf 模板。

历史日志中的 `AUTO_LABEL_LLM_START` 和 `AUTO_LABEL_SAM3_START` 显示名称仍被保留，仅用于正确展示旧记录，不代表功能入口仍存在。
