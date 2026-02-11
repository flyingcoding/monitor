#!/bin/bash
set -e

# 运维监控客户端一键安装脚本
# 用法: ./install-client.sh --server=http://your-server:8001 --token=YOUR_TOKEN

INSTALL_DIR="/opt/monitor-client"
SERVICE_NAME="monitor-client"
JAR_NAME="monitor-client.jar"
JAVA_MIN_VERSION="17"

# 解析参数
SERVER_URL=""
TOKEN=""
JAR_URL=""

for arg in "$@"; do
    case $arg in
        --server=*)
            SERVER_URL="${arg#*=}"
            ;;
        --token=*)
            TOKEN="${arg#*=}"
            ;;
        --jar-url=*)
            JAR_URL="${arg#*=}"
            ;;
        --help)
            echo "用法: $0 --server=URL --token=TOKEN [--jar-url=JAR_DOWNLOAD_URL]"
            echo ""
            echo "参数:"
            echo "  --server=URL     服务端地址 (例如 http://192.168.1.100:8001)"
            echo "  --token=TOKEN    注册令牌 (从服务端管理页面获取)"
            echo "  --jar-url=URL    JAR 包下载地址 (可选，默认从服务端下载)"
            exit 0
            ;;
    esac
done

if [ -z "$SERVER_URL" ] || [ -z "$TOKEN" ]; then
    echo "错误: 必须提供 --server 和 --token 参数"
    echo "用法: $0 --server=URL --token=TOKEN"
    exit 1
fi

# 检测操作系统
detect_os() {
    if [ "$(uname)" = "Darwin" ]; then
        echo "macos"
    elif [ -f /etc/os-release ]; then
        echo "linux"
    else
        echo "unknown"
    fi
}

# 检查 Java 版本
check_java() {
    if ! command -v java &> /dev/null; then
        echo "错误: 未找到 Java，请安装 Java $JAVA_MIN_VERSION 或更高版本"
        echo "  Ubuntu/Debian: sudo apt install openjdk-17-jre-headless"
        echo "  CentOS/RHEL:   sudo yum install java-17-openjdk-headless"
        echo "  macOS:         brew install openjdk@17"
        exit 1
    fi

    JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VERSION" -lt "$JAVA_MIN_VERSION" ]; then
        echo "错误: Java 版本过低 (当前: $JAVA_VERSION, 需要: >= $JAVA_MIN_VERSION)"
        exit 1
    fi
    echo "Java 版本检查通过: $JAVA_VERSION"
}

# 安装
install() {
    echo "=== 运维监控客户端安装 ==="
    check_java

    # 创建安装目录
    sudo mkdir -p "$INSTALL_DIR"

    # 下载 JAR
    if [ -n "$JAR_URL" ]; then
        echo "正在从 $JAR_URL 下载客户端..."
        sudo curl -fSL -o "$INSTALL_DIR/$JAR_NAME" "$JAR_URL"
    elif [ -f "$JAR_NAME" ]; then
        echo "使用本地 JAR 包..."
        sudo cp "$JAR_NAME" "$INSTALL_DIR/$JAR_NAME"
    elif [ -f "monitor-client/target/$JAR_NAME" ]; then
        echo "使用构建目录中的 JAR 包..."
        sudo cp "monitor-client/target/$JAR_NAME" "$INSTALL_DIR/$JAR_NAME"
    else
        echo "错误: 未找到 JAR 包，请通过 --jar-url 指定下载地址或将 $JAR_NAME 放在当前目录"
        exit 1
    fi

    OS=$(detect_os)
    case $OS in
        linux)
            install_systemd
            ;;
        macos)
            install_launchd
            ;;
        *)
            echo "不支持的操作系统，请手动启动:"
            echo "  MONITOR_SERVER=$SERVER_URL MONITOR_TOKEN=$TOKEN java -jar $INSTALL_DIR/$JAR_NAME"
            ;;
    esac

    echo "=== 安装完成 ==="
}

# Linux systemd 服务
install_systemd() {
    echo "注册 systemd 服务..."
    sudo tee /etc/systemd/system/${SERVICE_NAME}.service > /dev/null <<EOF
[Unit]
Description=Monitor Client
After=network.target

[Service]
Type=simple
User=root
Environment=MONITOR_SERVER=${SERVER_URL}
Environment=MONITOR_TOKEN=${TOKEN}
ExecStart=$(which java) -jar ${INSTALL_DIR}/${JAR_NAME}
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

    sudo systemctl daemon-reload
    sudo systemctl enable ${SERVICE_NAME}
    sudo systemctl start ${SERVICE_NAME}
    echo "服务已启动，使用以下命令管理:"
    echo "  sudo systemctl status ${SERVICE_NAME}"
    echo "  sudo systemctl stop ${SERVICE_NAME}"
    echo "  sudo journalctl -u ${SERVICE_NAME} -f"
}

# macOS launchd 服务
install_launchd() {
    echo "注册 launchd 服务..."
    PLIST_PATH="$HOME/Library/LaunchAgents/com.monitor.client.plist"
    cat > "$PLIST_PATH" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.monitor.client</string>
    <key>ProgramArguments</key>
    <array>
        <string>$(which java)</string>
        <string>-jar</string>
        <string>${INSTALL_DIR}/${JAR_NAME}</string>
    </array>
    <key>EnvironmentVariables</key>
    <dict>
        <key>MONITOR_SERVER</key>
        <string>${SERVER_URL}</string>
        <key>MONITOR_TOKEN</key>
        <string>${TOKEN}</string>
    </dict>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>/tmp/monitor-client.log</string>
    <key>StandardErrorPath</key>
    <string>/tmp/monitor-client.err</string>
</dict>
</plist>
EOF

    launchctl load "$PLIST_PATH"
    echo "服务已启动，使用以下命令管理:"
    echo "  launchctl list | grep monitor"
    echo "  launchctl unload $PLIST_PATH"
    echo "  tail -f /tmp/monitor-client.log"
}

install
