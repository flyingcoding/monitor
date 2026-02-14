.PHONY: init up up-client down dev-server dev-web build logs clean validate-client-token

init:
	@test -f .env || (cp .env.example .env && bash scripts/generate-env.sh)
	@echo "环境初始化完成，请检查 .env 文件"

up:
	docker compose up -d --build

# 校验客户端启动所需的 MONITOR_TOKEN 配置，避免容器进入交互输入并启动失败。
validate-client-token:
	@if [ ! -f .env ]; then \
		echo "未找到 .env，请先执行 make init"; \
		exit 1; \
	fi
	@MONITOR_TOKEN_VALUE=$$(grep -E '^MONITOR_TOKEN=' .env | tail -n 1 | cut -d '=' -f2- || true); \
	MONITOR_TOKEN_VALUE=$$(printf '%s' "$$MONITOR_TOKEN_VALUE" | tr -d '[:space:]'); \
	if [ -z "$$MONITOR_TOKEN_VALUE" ]; then \
		echo "错误: MONITOR_TOKEN 为空，请先在 .env 中设置客户端注册令牌后再执行 make up-client"; \
		exit 1; \
	fi

up-client: validate-client-token
	cd monitor-client && mvn -DskipTests package
	docker compose --profile client up -d --build monitor-client

down:
	docker compose down

dev-server:
	cd monitor-server && mvn spring-boot:run -Pdev

dev-web:
	cd monitor-web && pnpm install && pnpm run dev

build:
	cd monitor-server && mvn clean package -Pprod -DskipTests
	cd monitor-web && pnpm run build

logs:
	docker compose logs -f

clean:
	docker compose down -v
	cd monitor-server && mvn clean
	@if command -v trash >/dev/null 2>&1; then \
		if [ -e monitor-web/dist ]; then \
			trash monitor-web/dist; \
		else \
			echo "monitor-web/dist 不存在，跳过删除。"; \
		fi; \
	else \
		echo "未检测到 trash 命令，跳过 monitor-web/dist 删除。"; \
	fi
