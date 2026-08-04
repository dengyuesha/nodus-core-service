# 设备侧部署说明

## 主机边界

- 设备主机：`192.168.1.177`
- 现有设备端目录：`/home/aidlux/nodus-one`
- 现有语音服务目录：`/opt/aiwei-ainas`
- Nodus Core 推荐目录：`/opt/nodus-core-service`
- 公网 tools-service 不部署本服务，也不保存 Nodus 私有业务数据

SSH 账号密码、数据库密码和 API Key 不写入仓库。

## 部署前检查

在设备上确认：

```bash
uname -m
java -version
psql --version
ss -lntp | grep -E ':8094|:5432'
```

本服务需要 Java 17 和 PostgreSQL。若设备上没有 PostgreSQL，应先确定由系统包或容器提供，再创建独立数据库和最小权限账号。

当前设备使用独立容器 `nodus-core-postgres` 提供 PostgreSQL 16。由于设备内核不支持 Docker 的 loopback filtering iptables 规则，容器使用 host 网络，并通过 PostgreSQL `listen_addresses=127.0.0.1` 保证数据库只监听设备回环地址。

## 构建与安装

开发机执行：

```powershell
$env:JAVA_HOME='D:\java\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn clean package
```

将 `target/nodus-core-service-0.1.0-SNAPSHOT.jar` 上传为 `/opt/nodus-core-service/nodus-core-service.jar`，将 `deploy/nodus-core.env.example` 复制为 `nodus-core.env` 并填写真实密钥。

设备上执行：

```bash
sudo chown -R aidlux:aidlux /opt/nodus-core-service
chmod 600 /opt/nodus-core-service/nodus-core.env
sudo cp /opt/nodus-core-service/nodus-core.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now nodus-core
curl -fsS http://127.0.0.1:8094/actuator/health
```

首次启动时 Flyway 自动建立基础表。部署前需要备份数据库；升级时只新增 Flyway 迁移，不修改已经发布的迁移文件。
