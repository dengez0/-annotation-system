# SimpleLabel Ubuntu 24.04 隔离部署说明

部署完成后，Web、YOLO Worker、数据、模型和日志全部位于远程服务器。用户电脑只负责访问网页；即使上传代码的电脑关机，远程标注仍可继续。

## 安全边界

- 不修改、停止或重启任何已有服务。
- 不修改防火墙、SSH、CUDA、驱动或系统级 Java/Python。
- 不执行全局 `pip`；Python 依赖只安装到 `/opt/simplelabel/runtime/.venv`。
- 预览服务只监听 `127.0.0.1:28083/28085`，通过 SSH 隧道访问。
- 未取得管理员提供的关键业务健康检查命令前，不启动任何 SimpleLabel 服务。
- 端口被占用、可用内存不足 12 GiB 或磁盘不足 15 GiB 时，中止部署。

## 构建和交付

在隔离的 Ubuntu 24.04/Python 3.12 构建环境中执行：

```bash
bash deploy/ubuntu/build_wheelhouse.sh
bash deploy/ubuntu/build_release.sh
```

发布脚本要求 Git 工作区干净，执行最新 Java 测试，打包固定 commit、JAR、离线 wheelhouse及两层 SHA-256 清单。不要把本地虚拟环境、工具链、缓存、真实数据、日志或模型放入发布包。

## 远程预览（管理员手工执行）

1. 记录现有关键业务健康状态、监听端口、资源状态和 `systemctl --failed`。
2. 执行 `bash deploy/ubuntu/preflight_readonly.sh` 进行只读预检。
3. 将发布包解压到 `/opt/simplelabel/releases/<release-id>`，然后执行：

   ```bash
   bash deploy/ubuntu/verify_release.sh /opt/simplelabel/releases/<release-id>
   ```

4. 仅为本项目创建无登录用户 `simplelabel`、`/opt/simplelabel/runtime` 和 `/srv/simplelabel-preview/{data,models,logs,admin,processed}`。管理员目录权限设为 `0700`；这些是管理员明确批准后的手工动作，项目脚本不会自动执行。
5. 令 `/opt/simplelabel/current` 指向已验证版本。将 `simplelabel-preview.env.example` 复制到 `/etc/simplelabel/simplelabel-preview.env`，替换随机 Worker Token，并通过服务器私有配置或离线工具初始化至少一个管理员令牌哈希。
6. 以 `simplelabel` 用户执行离线安装：

   ```bash
   SIMPLELABEL_RUNTIME_DIR=/opt/simplelabel/runtime bash /opt/simplelabel/current/deploy/ubuntu/setup_python.sh --apply
   ```

7. 管理员审核后，才把两个 preview service 模板复制为 systemd 单元并启动。不要安装或启用正式 service 模板。
8. 客户端建立 SSH 隧道：`ssh -L 28083:127.0.0.1:28083 <server>`，然后打开 `http://127.0.0.1:28083`。

SSH 隧道只负责当前用户访问；服务自身一直在远程运行，不要求最初上传代码的电脑保持开机。任意获授权电脑均可建立自己的隧道。

## 验收门禁

- `curl http://127.0.0.1:28083/internal/health` 返回 HTTP 200。
- `ss -ltn` 显示 preview 端口只绑定 `127.0.0.1`。
- 即使通过回环地址访问，管理员功能也必须先在 `/admin/activate` 输入有效设备令牌。
- 用脱敏样本验证标注保存、恢复、移动、日志、CPU推理和数据处理。
- 启动后立即、15分钟、60分钟重复管理员提供的既有业务健康检查。
- 任何关键业务变化、资源越限、新失败单元或越界写入，都只停止并禁用 SimpleLabel preview 服务。

## 正式上线和数据迁移

- 正式数据只复制到 `/srv/simplelabel/`，不移动、覆盖或删除源数据；复制后核对文件数、字节数和哈希。
- 原数据至少保留7天，删除需要独立授权。
- 正式 Web 默认端口18083，只有端口空闲且管理员批准后才启用。
- 不要更改环境文件中的部署模式、监听地址和固定端口；运行前校验会拒绝预览/正式配置混用。
- 正式验收后，标注人员直接使用远程服务器的获批地址访问，不通过上传者电脑，也不要求任何个人电脑维持SSH隧道。SSH隧道仅用于上线前预览。
- Worker始终只监听远程服务器回环地址。
- 首次正式运行仍使用CPU。启用GPU需要管理员另行批准GPU编号并设置 `CUDA_VISIBLE_DEVICES`；不得修改驱动或CUDA。
- 防火墙如不允许访问，由管理员走独立变更流程；本项目不自动修改防火墙。

## 回滚

只停止或禁用 SimpleLabel 单元，并把 `/opt/simplelabel/current` 恢复到上一发布版本。不得删除数据或日志，不得操作其他服务。回滚后重新执行既有关键业务健康检查。
