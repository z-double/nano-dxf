# 发布到 Maven 中央仓库指南

## 前置准备

### 1. 注册 Sonatype 账户
1. 访问 [Sonatype JIRA](https://issues.sonatype.org/) 注册账户
2. 创建新项目工单（New Project）
   - 选择 "Community Support - Open Source Project Repository Hosting (OSSRH)"
   - 填写项目信息，申请 Group ID（如 `com.nanodxf`）
   - 提供项目 GitHub 地址
3. 等待审核通过（通常 1-2 个工作日）

### 2. 安装 GPG 密钥

#### Windows 系统：
```bash
# 下载并安装 Gpg4win: https://gpg4win.org/

# 生成密钥
gpg --gen-key

# 查看密钥列表
gpg --list-keys

# 上传公钥到密钥服务器
gpg --keyserver keyserver.ubuntu.com --send-keys <YOUR_KEY_ID>
```

#### Linux/Mac 系统：
```bash
# 安装 GPG
sudo apt-get install gnupg  # Ubuntu/Debian
brew install gpg            # macOS

# 生成密钥
gpg --gen-key

# 查看密钥列表
gpg --list-keys

# 上传公钥到密钥服务器
gpg --keyserver keyserver.ubuntu.com --send-keys <YOUR_KEY_ID>
```

### 3. 配置 Maven settings.xml

1. 复制 `settings-template.xml` 到你的 Maven 配置目录：
   - Windows: `%USERPROFILE%\.m2\settings.xml`
   - Linux/Mac: `~/.m2/settings.xml`

2. 修改以下配置：
   ```xml
   <username>你的Sonatype用户名</username>
   <password>你的Sonatype密码</password>
   <gpg.passphrase>你的GPG密钥密码</gpg.passphrase>
   ```

### 4. 修改 pom.xml

需要修改以下占位符为实际值：
- `https://github.com/your-username/nano-dxf` → 你的 GitHub 项目地址
- `Your Name` → 你的姓名
- `your-email@example.com` → 你的邮箱
- `https://github.com/your-username` → 你的 GitHub 主页

## 发布步骤

### 1. 确保代码已提交且版本正确
```bash
git add .
git commit -m "prepare for release v1.2.0"
```

### 2. 执行发布命令

#### 发布 Release 版本：
```bash
mvn clean deploy -P ossrh
```

#### 跳过测试快速发布：
```bash
mvn clean deploy -P ossrh -DskipTests
```

### 3. 登录 Sonatype 检查

1. 访问 [Sonatype Nexus](https://s01.oss.sonatype.org/)
2. 登录你的账户
3. 点击左侧 "Staging Repositories"
4. 找到你的仓库，检查内容是否正确
5. 如果配置了 `autoReleaseAfterClose=true`，会自动发布
6. 否则需要手动点击 "Close" → "Release"

### 4. 等待同步到 Maven 中央仓库

- 首次发布可能需要 2-4 小时同步
- 后续发布通常 10-30 分钟
- 可以在 [Maven Central](https://search.maven.org/) 搜索验证

## 常见问题

### Q1: GPG 签名失败
```bash
# 确保 GPG agent 正在运行
gpgconf --launch gpg-agent

# 或在命令行指定密码
mvn clean deploy -P ossrh -Dgpg.passphrase=你的密码
```

### Q2: 401 Unauthorized
- 检查 settings.xml 中的用户名密码是否正确
- 确认 Sonatype 工单已审核通过

### Q3: 403 Forbidden
- 确认 Group ID 与申请的匹配
- 检查版本号是否已存在

### Q4: Javadoc 错误
- 当前配置已禁用 doclint（`<doclint>none</doclint>`）
- 如需修复，完善 JavaDoc 注释

## 快照版本发布

如需发布 SNAPSHOT 版本（版本号带 `-SNAPSHOT`）：
```bash
mvn clean deploy -P ossrh
```

快照版本会发布到 Sonatype 的 snapshots 仓库，不会同步到 Maven Central。

## 更新版本

每次发布新版本前：
1. 修改 pom.xml 中的 `<version>` 为新版本号
2. 提交代码
3. 执行发布命令

## 参考资源

- [Sonatype OSSRH 官方文档](https://central.sonatype.org/publish/publish-guide/)
- [Maven GPG Plugin](https://maven.apache.org/plugins/maven-gpg-plugin/)
- [Maven Javadoc Plugin](https://maven.apache.org/plugins/maven-javadoc-plugin/)
