FROM golang:1.24-alpine AS builder

WORKDIR /app

COPY go.mod go.sum ./

RUN go mod download

COPY . .

RUN go build -o main cmd/main.go 


FROM alpine:latest

RUN apk --no-cache add ca-certificates tzdata

WORKDIR /app

RUN adduser -D -s /bin/sh appuser

COPY --from=builder /app/main .

RUN chown appuser:appuser main

USER appuser

EXPOSE 8080

CMD ["./main"]

