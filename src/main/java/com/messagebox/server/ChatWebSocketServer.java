package com.messagebox.server;

import com.google.gson.Gson;
import com.messagebox.model.*;
import com.messagebox.util.HashUtil;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ChatWebSocketServer extends WebSocketServer {

    private final DataStore store;
    private final Gson gson = new Gson();

    // Онлайн-пользователи
    private final Map<WebSocket, String> connToUser = new ConcurrentHashMap<>();
    private final Map<String, WebSocket> userToConn = new ConcurrentHashMap<>();

    // В какой комнате находится каждый юзер
    private final Map<String, String> userCurrentRoom = new ConcurrentHashMap<>();

    public ChatWebSocketServer(int port, DataStore store) {
        super(new InetSocketAddress(port));
        this.store = store;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        log("🔌 Новое соединение: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String raw) {
        try {
            Message msg = Message.fromJson(raw);
            switch (msg.getType()) {
                case REGISTER       -> handleRegister(conn, msg);
                case LOGIN          -> handleLogin(conn, msg);
                case CHAT_MESSAGE   -> handleChat(conn, msg);
                case PRIVATE_MESSAGE-> handleDM(conn, msg);
                case EDIT_MESSAGE   -> handleEdit(conn, msg);
                case DELETE_MESSAGE -> handleDelete(conn, msg);
                case REPLY_MESSAGE  -> handleReply(conn, msg);
                case PIN_MESSAGE    -> handlePin(conn, msg);
                case CREATE_ROOM    -> handleCreateRoom(conn, msg);
                case JOIN_ROOM      -> handleJoinRoom(conn, msg);
                case SWITCH_ROOM    -> handleSwitchRoom(conn, msg);
                case TYPING         -> handleTyping(conn, msg);
                case SEARCH_REQUEST -> handleSearch(conn, msg);
                case PINNED_MESSAGES-> handleGetPinned(conn, msg);
                default -> {}
            }
        } catch (Exception e) {
            log("⚠ Ошибка: " + e.getMessage());
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String user = connToUser.remove(conn);
        if (user != null) {
            userToConn.remove(user.toLowerCase());
            String roomId = userCurrentRoom.remove(user.toLowerCase());

            broadcastToRoom(roomId, new Message(
                    MessageType.SERVER_MESSAGE, "SYSTEM",
                    user + " вышел из сети 🔴", roomId));
            broadcastUserListToRoom(roomId);
            broadcastRoomList();
            log("❌ " + user + " отключился");
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        log("⚠ " + ex.getMessage());
    }

    @Override
    public void onStart() {
        log("WebSocket-сервер запущен");
    }

    // ═══════════════ АУТЕНТИФИКАЦИЯ ═══════════════

    private void handleRegister(WebSocket conn, Message msg) {
        String username = msg.getSender().trim();
        String password = msg.getContent();

        if (username.isEmpty() || username.length() > 20 ||
                username.equalsIgnoreCase("SYSTEM")) {
            send(conn, authFail("Имя 1–20 символов, не SYSTEM"));
            return;
        }
        if (password == null || password.length() < 3) {
            send(conn, authFail("Пароль минимум 3 символа"));
            return;
        }

        String hash = HashUtil.sha256(password);
        if (store.registerUser(username, hash)) {
            log("📝 Регистрация: " + username);
            loginUser(conn, username);
        } else {
            send(conn, authFail("Имя «" + username + "» уже зарегистрировано"));
        }
    }

    private void handleLogin(WebSocket conn, Message msg) {
        String username = msg.getSender().trim();
        String password = msg.getContent();

        if (!store.userExists(username)) {
            send(conn, authFail("Пользователь не найден"));
            return;
        }

        String hash = HashUtil.sha256(password);
        if (!store.authenticateUser(username, hash)) {
            send(conn, authFail("Неверный пароль"));
            return;
        }

        if (userToConn.containsKey(username.toLowerCase())) {
            send(conn, authFail("Уже в сети с другого устройства"));
            return;
        }

        String original = store.getOriginalUsername(username);
        log("🔑 Вход: " + original);
        loginUser(conn, original);
    }

    private void loginUser(WebSocket conn, String username) {
        connToUser.put(conn, username);
        userToConn.put(username.toLowerCase(), conn);
        userCurrentRoom.put(username.toLowerCase(), "general");

        // Подтверждение
        send(conn, new Message(MessageType.AUTH_OK, "SYSTEM", username));

        // Список комнат
        sendRoomList(conn);

        // Сообщения общего чата
        sendRoomMessages(conn, "general");

        // Уведомление
        broadcastToRoom("general", new Message(
                MessageType.SERVER_MESSAGE, "SYSTEM",
                username + " присоединился 🟢", "general"));

        broadcastUserListToRoom("general");
        broadcastRoomList();
    }

    private Message authFail(String reason) {
        return new Message(MessageType.AUTH_FAIL, "SYSTEM", reason);
    }

    // ═══════════════ СООБЩЕНИЯ ═══════════════

    private void handleChat(WebSocket conn, Message msg) {
        String user = connToUser.get(conn);
        if (user == null) return;

        String roomId = msg.getRoomId();
        if (roomId == null) roomId = userCurrentRoom.getOrDefault(
                user.toLowerCase(), "general");

        msg.setSender(user);
        msg.setRoomId(roomId);
        msg.setTimestamp(System.currentTimeMillis());

        store.addMessage(roomId, msg);
        broadcastToRoom(roomId, msg);
        log("[" + roomId + "][" + user + "]: " + msg.getContent());
    }

    private void handleDM(WebSocket conn, Message msg) {
        String sender = connToUser.get(conn);
        if (sender == null || msg.getRecipient() == null) return;

        String recipient = msg.getRecipient().trim();
        String dmRoomId = store.getOrCreateDM(sender, recipient);

        msg.setSender(sender);
        msg.setRoomId(dmRoomId);
        msg.setTimestamp(System.currentTimeMillis());
        msg.setType(MessageType.CHAT_MESSAGE);

        store.addMessage(dmRoomId, msg);

        // Отправляем обоим
        WebSocket senderConn = userToConn.get(sender.toLowerCase());
        WebSocket recipientConn = userToConn.get(recipient.toLowerCase());

        if (senderConn != null) send(senderConn, msg);
        if (recipientConn != null && recipientConn != senderConn) {
            send(recipientConn, msg);
            // Уведомление — новая ЛС-комната
            sendRoomList(recipientConn);
        }

        // Обновляем список комнат у отправителя тоже
        if (senderConn != null) sendRoomList(senderConn);

        log("[ЛС " + sender + "→" + recipient + "]: " + msg.getContent());
    }

    private void handleReply(WebSocket conn, Message msg) {
        String user = connToUser.get(conn);
        if (user == null) return;

        String roomId = msg.getRoomId();
        if (roomId == null) roomId = userCurrentRoom.getOrDefault(
                user.toLowerCase(), "general");

        msg.setSender(user);
        msg.setRoomId(roomId);
        msg.setTimestamp(System.currentTimeMillis());
        msg.setType(MessageType.CHAT_MESSAGE);

        store.addMessage(roomId, msg);
        broadcastToRoom(roomId, msg);
        log("[" + roomId + "][" + user + "] ↩ " + msg.getContent());
    }

    private void handleEdit(WebSocket conn, Message msg) {
        String user = connToUser.get(conn);
        if (user == null) return;

        String roomId = msg.getRoomId();
        if (roomId == null) return;

        Message original = store.findMessage(roomId, msg.getId());
        if (original != null && original.getSender().equalsIgnoreCase(user)) {
            original.setContent(msg.getContent());
            original.setEdited(true);

            Message notification = new Message(
                    MessageType.EDIT_MESSAGE, user, msg.getContent(), roomId);
            notification.setId(msg.getId());
            notification.setEdited(true);
            broadcastToRoom(roomId, notification);
            log("[" + roomId + "][" + user + "] ✏ отредактировал");
        }
    }

    private void handleDelete(WebSocket conn, Message msg) {
        String user = connToUser.get(conn);
        if (user == null) return;

        String roomId = msg.getRoomId();
        if (roomId == null) return;

        if (store.deleteMessage(roomId, msg.getId(), user)) {
            Message notification = new Message(
                    MessageType.DELETE_MESSAGE, user, msg.getId(), roomId);
            notification.setId(msg.getId());
            broadcastToRoom(roomId, notification);
            log("[" + roomId + "][" + user + "] 🗑 удалил сообщение");
        }
    }

    private void handlePin(WebSocket conn, Message msg) {
        String user = connToUser.get(conn);
        if (user == null) return;

        String roomId = msg.getRoomId();
        if (roomId == null) return;

        Message original = store.findMessage(roomId, msg.getId());
        if (original != null) {
            if (original.isPinned()) {
                store.unpinMessage(roomId, msg.getId());
                broadcastToRoom(roomId, new Message(
                        MessageType.SERVER_MESSAGE, "SYSTEM",
                        user + " открепил сообщение", roomId));
            } else {
                store.pinMessage(roomId, original);
                broadcastToRoom(roomId, new Message(
                        MessageType.SERVER_MESSAGE, "SYSTEM",
                        user + " закрепил сообщение 📌", roomId));
            }
            // Отправляем обновлённый список закреплённых
            sendPinnedToRoom(roomId);
        }
    }

    // ═══════════════ КОМНАТЫ ═══════════════

    private void handleCreateRoom(WebSocket conn, Message msg) {
        String user = connToUser.get(conn);
        if (user == null) return;

        String name = msg.getContent().trim();
        if (name.isEmpty() || name.length() > 30) {
            send(conn, new Message(MessageType.SERVER_MESSAGE, "SYSTEM",
                    "Название комнаты: 1–30 символов"));
            return;
        }

        Room room = store.createRoom(name, user);
        userCurrentRoom.put(user.toLowerCase(), room.getId());

        broadcastRoomList();
        sendRoomMessages(conn, room.getId());
        broadcastUserListToRoom(room.getId());

        send(conn, new Message(MessageType.SWITCH_ROOM, "SYSTEM",
                room.getId(), room.getId()));

        log("🏠 " + user + " создал комнату «" + name + "»");
    }

    private void handleJoinRoom(WebSocket conn, Message msg) {
        String user = connToUser.get(conn);
        if (user == null) return;

        String roomId = msg.getRoomId();
        Room room = store.getRoom(roomId);
        if (room == null) return;

        // Покидаем текущую
        String oldRoom = userCurrentRoom.get(user.toLowerCase());
        if (oldRoom != null && !oldRoom.equals(roomId)) {
            broadcastToRoom(oldRoom, new Message(
                    MessageType.SERVER_MESSAGE, "SYSTEM",
                    user + " перешёл в другую комнату", oldRoom));
            broadcastUserListToRoom(oldRoom);
        }

        room.addMember(user);
        userCurrentRoom.put(user.toLowerCase(), roomId);

        sendRoomMessages(conn, roomId);
        broadcastUserListToRoom(roomId);

        send(conn, new Message(MessageType.SWITCH_ROOM, "SYSTEM",
                roomId, roomId));

        broadcastToRoom(roomId, new Message(
                MessageType.SERVER_MESSAGE, "SYSTEM",
                user + " присоединился к комнате 🟢", roomId));

        log("➡ " + user + " вошёл в " + roomId);
    }

    private void handleSwitchRoom(WebSocket conn, Message msg) {
        handleJoinRoom(conn, msg);
    }

    // ═══════════════ ПОИСК ═══════════════

    private void handleSearch(WebSocket conn, Message msg) {
        String roomId = msg.getRoomId();
        if (roomId == null) roomId = "general";

        List<Message> results = store.searchMessages(roomId, msg.getContent());

        Message response = new Message(MessageType.SEARCH_RESULT, "SYSTEM",
                gson.toJson(results), roomId);
        send(conn, response);
    }

    private void handleGetPinned(WebSocket conn, Message msg) {
        String roomId = msg.getRoomId();
        if (roomId == null) return;

        List<Message> pinned = store.getPinnedMessages(roomId);
        Message response = new Message(MessageType.PINNED_MESSAGES, "SYSTEM",
                gson.toJson(pinned), roomId);
        send(conn, response);
    }

    // ═══════════════ TYPING ═══════════════

    private void handleTyping(WebSocket conn, Message msg) {
        String user = connToUser.get(conn);
        if (user == null) return;

        String roomId = userCurrentRoom.getOrDefault(user.toLowerCase(), "general");
        msg.setSender(user);
        msg.setRoomId(roomId);

        for (Map.Entry<WebSocket, String> entry : connToUser.entrySet()) {
            if (entry.getKey() != conn) {
                String otherRoom = userCurrentRoom.get(
                        entry.getValue().toLowerCase());
                if (roomId.equals(otherRoom)) {
                    send(entry.getKey(), msg);
                }
            }
        }
    }

    // ═══════════════ РАССЫЛКА ═══════════════

    private void broadcastToRoom(String roomId, Message msg) {
        if (roomId == null) return;
        String json = msg.toJson();

        Room room = store.getRoom(roomId);

        for (Map.Entry<WebSocket, String> entry : connToUser.entrySet()) {
            String user = entry.getValue().toLowerCase();
            String currentRoom = userCurrentRoom.get(user);

            boolean shouldSend = false;

            // DM — отправляем участникам DM-комнаты
            if (room != null && room.getType() == RoomType.DIRECT_MESSAGE) {
                shouldSend = room.hasMember(entry.getValue());
            }
            // Обычная комната — только тем, кто сейчас в ней
            else if (roomId.equals(currentRoom)) {
                shouldSend = true;
            }

            if (shouldSend) {
                try { entry.getKey().send(json); } catch (Exception ignored) {}
            }
        }
    }

    private void broadcastUserListToRoom(String roomId) {
        if (roomId == null) return;

        List<String> usersInRoom = new ArrayList<>();
        for (Map.Entry<String, String> entry : userCurrentRoom.entrySet()) {
            if (roomId.equals(entry.getValue())) {
                WebSocket conn = userToConn.get(entry.getKey());
                String displayName = connToUser.get(conn);
                if (displayName != null) usersInRoom.add(displayName);
            }
        }

        String csv = String.join(",", usersInRoom);
        Message msg = new Message(MessageType.USER_LIST, "SYSTEM", csv, roomId);

        for (Map.Entry<String, String> entry : userCurrentRoom.entrySet()) {
            if (roomId.equals(entry.getValue())) {
                WebSocket conn = userToConn.get(entry.getKey());
                if (conn != null) send(conn, msg);
            }
        }
    }

    private void broadcastRoomList() {
        for (WebSocket conn : connToUser.keySet()) {
            sendRoomList(conn);
        }
    }

    private void sendRoomList(WebSocket conn) {
        String user = connToUser.get(conn);
        if (user == null) return;

        List<Map<String, Object>> roomList = new ArrayList<>();
        for (Room room : store.getAllRooms().values()) {
            // DM — показываем только участникам
            if (room.getType() == RoomType.DIRECT_MESSAGE &&
                    !room.hasMember(user)) continue;

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", room.getId());
            r.put("name", room.getName());
            r.put("type", room.getType().name());
            r.put("members", room.getMembers().size());

            // Для DM показываем имя собеседника
            if (room.getType() == RoomType.DIRECT_MESSAGE) {
                for (String member : room.getMembers()) {
                    if (!member.equalsIgnoreCase(user)) {
                        String original = store.getOriginalUsername(member);
                        r.put("name", "💌 " + original);
                        r.put("dmUser", original);
                    }
                }
            }

            // Количество сообщений
            r.put("messageCount", store.getMessages(room.getId()).size());

            roomList.add(r);
        }

        Message msg = new Message(MessageType.ROOM_LIST, "SYSTEM",
                gson.toJson(roomList));
        send(conn, msg);
    }

    private void sendRoomMessages(WebSocket conn, String roomId) {
        List<Message> msgs = store.getMessages(roomId);
        for (Message m : msgs) {
            send(conn, m);
        }
    }

    private void sendPinnedToRoom(String roomId) {
        List<Message> pinned = store.getPinnedMessages(roomId);
        String json = gson.toJson(pinned);
        Message msg = new Message(MessageType.PINNED_MESSAGES, "SYSTEM", json, roomId);
        broadcastToRoom(roomId, msg);
    }

    // ═══════════════ УТИЛИТЫ ═══════════════

    private void send(WebSocket conn, Message msg) {
        try { conn.send(msg.toJson()); } catch (Exception ignored) {}
    }

    private void log(String text) {
        String time = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.println("[" + time + "] " + text);
    }
}