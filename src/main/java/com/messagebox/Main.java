package com.messagebox;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;

public class Main {

    private static final int HTTP_PORT = 8080;
    private static final int WS_PORT   = 8887;

    public static void main(String[] args) throws Exception {

        System.out.println("╔════════════════════════════════════╗");
        System.out.println("║        MessageBox Server           ║");
        System.out.println("╠════════════════════════════════════╣");
        System.out.println("║  HTTP:      http://localhost:" + HTTP_PORT + "  ║");
        System.out.println("║  WebSocket: ws://localhost:" + WS_PORT + "    ║");
        System.out.println("╚════════════════════════════════════╝");
        System.out.println();

        // 1. Запускаем WebSocket-сервер
        ChatWebSocketServer wsServer = new ChatWebSocketServer(WS_PORT);
        wsServer.start();
        System.out.println("[OK] WebSocket-сервер запущен");

        // 2. Запускаем HTTP-сервер (раздаёт index.html)
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);

        httpServer.createContext("/", exchange -> {
            // Читаем index.html из ресурсов
            try (InputStream is = Main.class.getResourceAsStream("/index.html")) {
                if (is == null) {
                    String err = "index.html not found";
                    exchange.sendResponseHeaders(404, err.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(err.getBytes());
                    }
                    return;
                }

                byte[] html = is.readAllBytes();
                exchange.getResponseHeaders().set("Content-Type",
                        "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, html.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(html);
                }
            }
        });

        httpServer.setExecutor(null);
        httpServer.start();
        System.out.println("[OK] HTTP-сервер запущен");
        System.out.println();
        System.out.println(">>> Откройте в браузере: http://localhost:" + HTTP_PORT);
        System.out.println(">>> Для второго клиента — откройте ещё одну вкладку");
        System.out.println();
    }
}