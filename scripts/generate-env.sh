#!/bin/bash
set -euo pipefail

ENV_FILE=".env"

# 生成用于配置项的随机安全字符串。
generate_password() {
    openssl rand -base64 24 | tr -d '/+=' | cut -c1-24
}

# 兼容不同平台的 sed -i 行为并执行占位符替换。
replace_placeholder() {
    local placeholder="$1"
    local replacement="$2"
    if [[ "$(uname)" == "Darwin" ]]; then
        sed -i '' "s|${placeholder}|${replacement}|g" "${ENV_FILE}"
    else
        sed -i "s|${placeholder}|${replacement}|g" "${ENV_FILE}"
    fi
}

# 为 .env 模板中的占位符填充随机值。
fill_placeholders() {
    replace_placeholder "your_root_password" "$(generate_password)"
    replace_placeholder "your_mysql_password" "$(generate_password)"
    replace_placeholder "your_redis_password" "$(generate_password)"
    replace_placeholder "your_rabbitmq_password" "$(generate_password)"
    replace_placeholder "your_influx_password" "$(generate_password)"
    replace_placeholder "your_jwt_secret_key" "$(generate_password)"
}

if [[ ! -f "${ENV_FILE}" ]]; then
    echo "未找到 ${ENV_FILE}，请先执行: cp .env.example .env"
    exit 1
fi

fill_placeholders
echo "已为 ${ENV_FILE} 自动生成随机密码。"
