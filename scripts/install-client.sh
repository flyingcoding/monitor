#!/bin/bash
set -euo pipefail

# 运维监控客户端一键安装脚本
# 用法示例:
#   安装:   ./install-client.sh --server=http://your-server:8001 --token=YOUR_TOKEN
#   更新:   ./install-client.sh --update [--jar-url=JAR_DOWNLOAD_URL]
#   卸载:   ./install-client.sh --uninstall

INSTALL_DIR="/opt/monitor-client"
SERVICE_NAME="monitor-client"
JAR_NAME="monitor-client.jar"
JAVA_MIN_VERSION="17"
LAUNCHD_LABEL="com.monitor.client"
PLIST_PATH="$HOME/Library/LaunchAgents/${LAUNCHD_LABEL}.plist"

SERVER_URL=""
TOKEN=""
JAR_URL=""
OPERATION="install"
TRASH_CMD=""

# 打印脚本帮助信息。
print_help() {
    echo "用法:"
    echo "  安装: $0 --server=URL --token=TOKEN [--jar-url=JAR_DOWNLOAD_URL]"
    echo "  更新: $0 --update [--jar-url=JAR_DOWNLOAD_URL]"
    echo "  卸载: $0 --uninstall"
    echo ""
    echo "参数:"
    echo "  --server=URL      服务端地址 (例如 http://192.168.1.100:8001)"
    echo "  --token=TOKEN     注册令牌 (从服务端管理页面获取)"
    echo "  --jar-url=URL     JAR 下载地址 (可选，默认使用本地JAR)"
    echo "  --update          更新客户端JAR并重启服务"
    echo "  --uninstall       卸载客户端服务和安装目录（删除使用trash）"
    echo "  --help            查看帮助"
}

# 按需使用 sudo 执行高权限命令。
run_privileged() {
    if command -v sudo >/dev/null 2>&1 && [ "$(id -u)" -ne 0 ]; then
        sudo "$@"
    else
        "$@"
    fi
}

# 获取 trash 的绝对路径，避免 sudo secure_path 导致找不到命令。
resolve_trash_command() {
    command -v trash 2>/dev/null || true
}

# 检测当前操作系统。
detect_os() {
    if [ "$(uname)" = "Darwin" ]; then
        echo "macos"
    elif [ -f /etc/os-release ]; then
        echo "linux"
    else
        echo "unknown"
    fi
}

# 返回对应系统的 Java 一键安装命令。
java_install_command() {
    local os="$1"
    if [ "$os" = "macos" ]; then
        echo "brew install openjdk@17"
        return
    fi

    if command -v apt-get >/dev/null 2>&1; then
        echo "apt-get update && apt-get install -y openjdk-17-jre-headless"
    elif command -v dnf >/dev/null 2>&1; then
        echo "dnf install -y java-17-openjdk-headless"
    elif command -v yum >/dev/null 2>&1; then
        echo "yum install -y java-17-openjdk-headless"
    else
        echo ""
    fi
}

# 在未安装 Java 时提示并可执行一键安装命令。
prompt_install_java() {
    local os="$1"
    local install_cmd
    install_cmd="$(java_install_command "$os")"
    if [ -z "$install_cmd" ]; then
        echo "错误: 未找到可用的 Java 自动安装命令，请手动安装 Java ${JAVA_MIN_VERSION}+"
        return 1
    fi

    echo "未检测到 Java。可执行一键安装命令:"
    echo "  ${install_cmd}"
    read -r -p "是否现在自动安装 Java? [y/N]: " answer
    if [[ "$answer" =~ ^[Yy]$ ]]; then
        # Homebrew 不支持 root 执行，macOS 直接用当前用户安装。
        if [ "$os" = "macos" ]; then
            bash -lc "$install_cmd"
        else
            run_privileged bash -lc "$install_cmd"
        fi
    else
        echo "已取消自动安装，请先安装 Java ${JAVA_MIN_VERSION}+ 后重试。"
        return 1
    fi
    return 0
}

# 解析 Java 主版本号。
parse_java_major() {
    local version_raw="$1"
    if [[ "$version_raw" == 1.* ]]; then
        echo "$version_raw" | cut -d'.' -f2
    else
        echo "$version_raw" | cut -d'.' -f1
    fi
}

# 读取 Java 版本字符串；当命令不可用或输出异常时返回非零状态。
read_java_version() {
    local java_output
    java_output="$(java -version 2>&1 || true)"
    local java_raw
    java_raw="$(printf '%s\n' "$java_output" | head -1 | cut -d'"' -f2)"
    if [ -z "$java_raw" ]; then
        return 1
    fi
    echo "$java_raw"
}

# 检查 Java 是否满足最低版本要求，缺失时可交互安装。
check_java() {
    local os="$1"
    if ! command -v java >/dev/null 2>&1; then
        if ! prompt_install_java "$os"; then
            exit 1
        fi
    fi

    local java_raw
    if ! java_raw="$(read_java_version)"; then
        if ! prompt_install_java "$os"; then
            exit 1
        fi
        if ! java_raw="$(read_java_version)"; then
            echo "错误: Java 安装后仍无法识别版本，请检查 java 命令是否可用。"
            exit 1
        fi
    fi
    local java_major
    java_major=$(parse_java_major "$java_raw")

    if ! [[ "$java_major" =~ ^[0-9]+$ ]]; then
        echo "错误: 无法解析 Java 版本信息: $java_raw"
        exit 1
    fi

    if [ "$java_major" -lt "$JAVA_MIN_VERSION" ]; then
        echo "错误: Java 版本过低 (当前: $java_major, 需要: >= $JAVA_MIN_VERSION)"
        exit 1
    fi
    echo "Java 版本检查通过: $java_major"
}

# 统一检查 trash 命令可用性。
ensure_trash_available() {
    TRASH_CMD="$(resolve_trash_command)"
    if [ -z "$TRASH_CMD" ]; then
        echo "错误: 未检测到 trash 命令，无法执行删除操作。"
        echo "请先安装后重试，例如: brew install trash"
        exit 1
    fi
    if ! [ -x "$TRASH_CMD" ]; then
        echo "错误: trash 命令不可执行: $TRASH_CMD"
        exit 1
    fi
    if command -v sudo >/dev/null 2>&1 && [ "$(id -u)" -ne 0 ]; then
        if ! run_privileged test -x "$TRASH_CMD" >/dev/null 2>&1; then
            echo "错误: sudo 环境无法执行 trash 命令: $TRASH_CMD"
            echo "请将 trash 安装到 sudo 可访问路径后重试。"
            exit 1
        fi
    fi
}

# 将目标文件或目录放入回收站，而不是直接删除。
move_to_trash() {
    local target="$1"
    local privileged="${2:-false}"
    local trash_cmd="${TRASH_CMD:-$(resolve_trash_command)}"
    if [ ! -e "$target" ] && [ ! -L "$target" ]; then
        return
    fi
    if [ -z "$trash_cmd" ]; then
        echo "错误: 未检测到 trash 命令，无法删除: $target"
        exit 1
    fi
    if [ "$privileged" = "true" ]; then
        run_privileged "$trash_cmd" "$target"
    else
        "$trash_cmd" "$target"
    fi
}

# 下载或复制客户端 JAR 到指定路径。
obtain_jar() {
    local target_path="$1"
    local local_build_jar=""
    if ls monitor-client/target/*.jar >/dev/null 2>&1; then
        local_build_jar="$(ls monitor-client/target/*.jar | grep -v '\\.original$' | head -1 || true)"
    fi

    if [ -n "$JAR_URL" ]; then
        echo "正在从 $JAR_URL 下载客户端..."
        run_privileged curl -fSL -o "$target_path" "$JAR_URL"
    elif [ -f "$JAR_NAME" ]; then
        echo "使用当前目录下的 JAR 包..."
        run_privileged cp "$JAR_NAME" "$target_path"
    elif [ -f "monitor-client/target/$JAR_NAME" ]; then
        echo "使用 monitor-client/target 目录中的 JAR 包..."
        run_privileged cp "monitor-client/target/$JAR_NAME" "$target_path"
    elif [ -n "$local_build_jar" ]; then
        echo "使用 monitor-client/target 下检测到的 JAR 包: $local_build_jar"
        run_privileged cp "$local_build_jar" "$target_path"
    else
        echo "错误: 未找到 JAR 包，请通过 --jar-url 指定下载地址或准备本地 $JAR_NAME"
        exit 1
    fi
}

# 在 Linux 上创建并启动 systemd 服务。
install_systemd_service() {
    echo "注册 systemd 服务..."
    run_privileged tee "/etc/systemd/system/${SERVICE_NAME}.service" > /dev/null <<EOF_SERVICE
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
EOF_SERVICE

    run_privileged systemctl daemon-reload
    run_privileged systemctl enable "$SERVICE_NAME"
    run_privileged systemctl restart "$SERVICE_NAME"

    echo "服务已启动，使用以下命令管理:"
    echo "  sudo systemctl status ${SERVICE_NAME}"
    echo "  sudo systemctl stop ${SERVICE_NAME}"
    echo "  sudo journalctl -u ${SERVICE_NAME} -f"
}

# 在 macOS 上创建并启动 launchd 服务。
install_launchd_service() {
    echo "注册 launchd 服务..."
    mkdir -p "$(dirname "$PLIST_PATH")"
    cat > "$PLIST_PATH" <<EOF_PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>${LAUNCHD_LABEL}</string>
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
EOF_PLIST

    launchctl unload "$PLIST_PATH" >/dev/null 2>&1 || true
    launchctl load "$PLIST_PATH"

    echo "服务已启动，使用以下命令管理:"
    echo "  launchctl list | grep monitor"
    echo "  launchctl unload $PLIST_PATH"
    echo "  tail -f /tmp/monitor-client.log"
}

# 安装客户端并注册系统服务。
install_client() {
    local os="$1"
    if [ -z "$SERVER_URL" ] || [ -z "$TOKEN" ]; then
        echo "错误: 安装模式必须提供 --server 和 --token 参数"
        exit 1
    fi

    echo "=== 运维监控客户端安装 ==="
    check_java "$os"

    run_privileged mkdir -p "$INSTALL_DIR"
    obtain_jar "${INSTALL_DIR}/${JAR_NAME}"

    case "$os" in
        linux)
            install_systemd_service
            ;;
        macos)
            install_launchd_service
            ;;
        *)
            echo "不支持的操作系统，请手动启动:"
            echo "  MONITOR_SERVER=$SERVER_URL MONITOR_TOKEN=$TOKEN java -jar $INSTALL_DIR/$JAR_NAME"
            ;;
    esac

    echo "=== 安装完成 ==="
}

# 更新客户端 JAR 并重启已安装服务。
update_client() {
    local os="$1"
    echo "=== 运维监控客户端更新 ==="
    check_java "$os"

    if [ ! -d "$INSTALL_DIR" ]; then
        echo "错误: 未检测到安装目录 $INSTALL_DIR，请先执行安装。"
        exit 1
    fi

    obtain_jar "${INSTALL_DIR}/${JAR_NAME}"

    case "$os" in
        linux)
            run_privileged systemctl restart "$SERVICE_NAME"
            ;;
        macos)
            if [ ! -f "$PLIST_PATH" ]; then
                echo "错误: 未检测到 launchd 配置 $PLIST_PATH，请先执行安装。"
                exit 1
            fi
            launchctl unload "$PLIST_PATH" >/dev/null 2>&1 || true
            launchctl load "$PLIST_PATH"
            ;;
        *)
            echo "已更新 JAR，请手动重启服务。"
            ;;
    esac

    echo "=== 更新完成 ==="
}

# 卸载客户端服务并将文件移动到回收站。
uninstall_client() {
    local os="$1"
    echo "=== 运维监控客户端卸载 ==="
    ensure_trash_available

    case "$os" in
        linux)
            run_privileged systemctl stop "$SERVICE_NAME" >/dev/null 2>&1 || true
            run_privileged systemctl disable "$SERVICE_NAME" >/dev/null 2>&1 || true
            move_to_trash "/etc/systemd/system/${SERVICE_NAME}.service" true
            run_privileged systemctl daemon-reload
            ;;
        macos)
            launchctl unload "$PLIST_PATH" >/dev/null 2>&1 || true
            move_to_trash "$PLIST_PATH"
            ;;
        *)
            echo "未知系统，跳过服务卸载步骤。"
            ;;
    esac

    move_to_trash "$INSTALL_DIR" true
    echo "=== 卸载完成 ==="
}

# 解析命令行参数并确定运行模式。
parse_args() {
    for arg in "$@"; do
        case "$arg" in
            --server=*)
                SERVER_URL="${arg#*=}"
                ;;
            --token=*)
                TOKEN="${arg#*=}"
                ;;
            --jar-url=*)
                JAR_URL="${arg#*=}"
                ;;
            --update)
                if [ "$OPERATION" = "uninstall" ]; then
                    echo "错误: --update 与 --uninstall 不能同时使用"
                    exit 1
                fi
                OPERATION="update"
                ;;
            --uninstall)
                if [ "$OPERATION" = "update" ]; then
                    echo "错误: --update 与 --uninstall 不能同时使用"
                    exit 1
                fi
                OPERATION="uninstall"
                ;;
            --help)
                print_help
                exit 0
                ;;
            *)
                echo "错误: 未知参数 $arg"
                print_help
                exit 1
                ;;
        esac
    done
}

parse_args "$@"
OS="$(detect_os)"

case "$OPERATION" in
    install)
        install_client "$OS"
        ;;
    update)
        update_client "$OS"
        ;;
    uninstall)
        uninstall_client "$OS"
        ;;
    *)
        echo "错误: 不支持的操作模式 $OPERATION"
        exit 1
        ;;
esac
