.PHONY: infra-up infra-down infra-logs infra-clean build test

infra-up:
	docker compose up -d

infra-down:
	docker compose down

infra-clean:
	docker compose down -v

infra-logs:
	docker compose logs -f

build:
	./mvnw clean package -DskipTests

test:
	./mvnw test