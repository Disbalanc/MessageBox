package com.messagebox;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ChatWebSocketServer extends WebSocketServer {

    // Связь: соединение ↔ имя пользователя
    private final Map<WebSocket, String> connToUser = new ConcurrentHashMap<>();
    private final Map<String, WebSocket> userToConn = new ConcurrentHashMap<>();

    // История последних сообщений
    private final List<Message> history =
            Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_HISTORY = 200;

    public ChatWebSocketServer(int port) {
        super(new InetSocketAddress(port));
    }

    // ─────────── События WebSocket ───────────

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        log("🔌 Новое соединение: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String raw) {
        try {
            Message msg = Message.fromJson(raw);

            switch (msg.getType()) {
                case CONNECT        -> handleConnect(conn, msg);
                case CHAT_MESSAGE   -> handleChat(conn, msg);
                case PRIVATE_MESSAGE-> handlePrivate(conn, msg);
                case TYPING         -> handleTyping(conn, msg);
                default -> {}
            }
        } catch (Exception e) {
            log("⚠ Ошибка парсинга: " + e.getMessage());
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String username = connToUser.remove(conn);
        if (username != null) {
            userToConn.remove(username.toLowerCase());

            Message leaveMsg = new Message(
                    MessageType.SERVER_MESSAGE, "SYSTEM",
                    username + " покинул чат 🔴");
            broadcast(leaveMsg);
            addHistory(leaveMsg);
            broadcastUserList();

            log("❌ " + username + " отключился (онлайн: " + userToConn.size() + ")");
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        log("⚠ Ошибка: " + ex.getMessage());
    }

    @Override
    public void onStart() {
        log("WebSocket-сервер запущен на порту " + getPort());
    }

    // ─────────── Обработчики сообщений ───────────

    private void handleConnect(WebSocket conn, Message msg) {
        String username = msg.getSender().trim();

        // Валидация
        if (username.isEmpty() || username.length() > 20
                || username.equalsIgnoreCase("SYSTEM")) {
            send(conn, new Message(MessageType.CONNECT_REJECT, "SYSTEM",
                    "Недопустимое имя (1–20 символов)"));
            return;
        }

        // Проверка уникальности
        if (userToConn.containsKey(username.toLowerCase())) {
            send(conn, new Message(MessageType.CONNECT_REJECT, "SYSTEM",
                    "Имя «" + username + "» уже занято"));
            return;
        }

        // Регистрация
        connToUser.put(conn, username);
        userToConn.put(username.toLowerCase(), conn);

        // Подтверждение
        send(conn, new Message(MessageType.CONNECT_ACK, "SYSTEM", "OK"));

        // Отправляем историю
        synchronized (history) {
            for (Message h : history) {
                send(conn, h);
            }
        }

        // Уведомление всем
        Message joinMsg = new Message(MessageType.SERVER_MESSAGE, "SYSTEM",
                username + " присоединился к чату 🟢");
        broadcast(joinMsg);
        addHistory(joinMsg);

        broadcastUserList();
        log("✅ " + username + " подключился (онлайн: " + userToConn.size() + ")");
    }

    private void handleChat(WebSocket conn, Message msg) {
        String username = connToUser.get(conn);
        if (username == null) return;

        msg.setSender(username); // безопасность — подменяем имя
        msg.setTimestamp(System.currentTimeMillis());

        // ═══════════════════════════════════════════
        // Здесь в будущем: вызов нейросети-модератора
        // boolean safe = moderationAI.check(msg.getContent());
        // if (!safe) { отправить предупреждение; return; }
        // ═══════════════════════════════════════════

        broadcast(msg);
        addHistory(msg);
        log("[" + username + "]: " + msg.getContent());
    }

    private void handlePrivate(WebSocket conn, Message msg) {
        String sender = connToUser.get(conn);
        if (sender == null || msg.getRecipient() == null) return;

        msg.setSender(sender);
        msg.setTimestamp(System.currentTimeMillis());

        // Получателю
        WebSocket recipientConn = userToConn.get(msg.getRecipient().toLowerCase());
        if (recipientConn != null) {
            send(recipientConn, msg);
        }

        // Эхо отправителю
        if (!sender.equalsIgnoreCase(msg.getRecipient())) {
            send(conn, msg);
        }

        log("[ЛС " + sender + " → " + msg.getRecipient() + "]: " + msg.getContent());
    }

    private void handleTyping(WebSocket conn, Message msg) {
        String username = connToUser.get(conn);
        if (username == null) return;

        msg.setSender(username);
        for (Map.Entry<WebSocket, String> entry : connToUser.entrySet()) {
            if (entry.getKey() != conn) {
                send(entry.getKey(), msg);
            }
        }
    }

    // ─────────── Утилиты ───────────

    private void broadcast(Message msg) {
        String json = msg.toJson();
        for (WebSocket conn : connToUser.keySet()) {
            try { conn.send(json); } catch (Exception ignored) {}
        }
    }

    private void send(WebSocket conn, Message msg) {
        try { conn.send(msg.toJson()); } catch (Exception ignored) {}
    }

    private void broadcastUserList() {
        StringBuilder sb = new StringBuilder();
        for (String user : connToUser.values()) {
            if (!sb.isEmpty()) sb.append(",");
            sb.append(user);
        }
        Message msg = new Message(MessageType.USER_LIST, "SYSTEM", sb.toString());
        broadcast(msg);
    }

    private void addHistory(Message msg) {
        history.add(msg);
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
    }

    private void log(String text) {
        String time = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.println("[" + time + "] " + text);
    }
}