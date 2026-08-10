#!/usr/bin/env bash
set -euo pipefail

compose_file="${1:-docker-compose.prod.yml}"

for command_name in docker free df; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "필수 명령을 찾을 수 없습니다: $command_name" >&2
        exit 1
    fi
done

echo "Docker Compose"
docker compose version

echo
echo "Host memory"
free -h

echo
echo "Host disk"
df -h /

echo
echo "Compose services"
docker compose -f "$compose_file" ps

echo
echo "Container resources"
docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.PIDs}}'
