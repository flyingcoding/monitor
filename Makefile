.PHONY: init up up-client up-vm down down-vm dev-server dev-web build logs logs-vm clean validate-client-token migrate-influx-to-vm

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

# v2.0-beta：启动 VictoriaMetrics 单节点（opt-in profile，不影响 `make up` 默认拓扑）。
# 需要在 .env 中设置 MONITOR_TSDB_PROVIDER=victoria-metrics 让 server 写入路径切换到 VM。
up-vm:
	docker compose --profile vm up -d victoria-metrics
	@echo "VictoriaMetrics 已启动 (http://localhost:8428/)；请把 .env 的 MONITOR_TSDB_PROVIDER 改为 victoria-metrics 并重启 monitor-server"

# v2.0-beta：一次性运行 vmctl 把 InfluxDB 2.7 历史数据迁移到 VictoriaMetrics。
# 前置：(1) make up-vm 已成功 (2) .env 已配 INFLUX_V1_USERNAME / INFLUX_V1_PASSWORD（v1 兼容凭证，
# 见 docs/v2.0-beta-vm.md "vmctl 迁移：InfluxDB v2 → v1 兼容 workaround" 章节）。
migrate-influx-to-vm:
	@if [ ! -f .env ]; then \
		echo "未找到 .env，请先执行 make init"; \
		exit 1; \
	fi
	docker compose --profile migration run --rm vmctl-migrate

down:
	docker compose down

# v2.0-beta：单独停止 VictoriaMetrics 容器，不影响其它服务。
down-vm:
	docker compose --profile vm stop victoria-metrics
	docker compose --profile vm rm -f victoria-metrics

dev-server:
	cd monitor-server && mvn spring-boot:run -Pdev

dev-web:
	cd monitor-web && pnpm install && pnpm run dev

build:
	cd monitor-server && mvn clean package -Pprod -DskipTests
	cd monitor-web && pnpm run build

logs:
	docker compose logs -f

# v2.0-beta：仅查看 VictoriaMetrics 容器日志。
logs-vm:
	docker compose --profile vm logs -f victoria-metrics

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
