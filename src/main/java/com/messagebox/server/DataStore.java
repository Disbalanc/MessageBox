package com.messagebox.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.messagebox.model.*;

import java.io.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DataStore {

    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final String DATA_DIR = "data";
    private static final String USERS_FILE = DATA_DIR + "/users.json";

    // Пользователи: username(lower) → User
    private final Map<String, User> users = new ConcurrentHashMap<>();

    // Комнаты: roomId → Room
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();

    // Сообщения по комнатам: roomId → List<Message>
    private final Map<String, List<Message>> roomMessages = new ConcurrentHashMap<>();

    // Закреплённые: roomId → List<Message>
    private final Map<String, List<Message>> pinnedMessages = new ConcurrentHashMap<>();

    private static final int MAX_MESSAGES_PER_ROOM = 500;

    public DataStore() {
        new File(DATA_DIR).mkdirs();
        loadUsers();
        createDefaultRooms();
    }

    // ─── Пользователи ───

    public boolean userExists(String username) {
        return users.containsKey(username.toLowerCase());
    }

    public boolean registerUser(String username, String passwordHash) {
        String key = username.toLowerCase();
        if (users.containsKey(key)) return false;
        users.put(key, new User(username, passwordHash));
        saveUsers();
        return true;
    }

    public boolean authenticateUser(String username, String passwordHash) {
        User user = users.get(username.toLowerCase());
        return user != null && user.getPasswordHash().equals(passwordHash);
    }

    public String getOriginalUsername(String username) {
        User user = users.get(username.toLowerCase());
        return user != null ? user.getUsername() : username;
    }

    // ─── Комнаты ───

    private void createDefaultRooms() {
        if (!rooms.containsKey("general")) {
            Room general = new Room("general", "🌍 Общий", RoomType.GENERAL, "SYSTEM");
            rooms.put("general", general);
        }
        if (!rooms.containsKey("random")) {
            Room random = new Room("random", "🎲 Рандом", RoomType.GENERAL, "SYSTEM");
            rooms.put("random", random);
        }
        if (!rooms.containsKey("tech")) {
            Room tech = new Room("tech", "💻 Технологии", RoomType.GENERAL, "SYSTEM");
            rooms.put("tech", tech);
        }
    }

    public Room createRoom(String name, String creator) {
        String id = "room_" + System.currentTimeMillis();
        Room room = new Room(id, name, RoomType.CUSTOM, creator);
        room.addMember(creator);
        rooms.put(id, room);
        return room;
    }

    public Room getRoom(String id) {
        return rooms.get(id);
    }

    public Map<String, Room> getAllRooms() {
        return rooms;
    }

    public String getOrCreateDM(String user1, String user2) {
        String key1 = user1.toLowerCase().compareTo(user2.toLowerCase()) < 0
                ? user1.toLowerCase() + "_" + user2.toLowerCase()
                : user2.toLowerCase() + "_" + user1.toLowerCase();
        String dmId = "dm_" + key1;

        if (!rooms.containsKey(dmId)) {
            String name = user1 + " & " + user2;
            Room dm = new Room(dmId, name, RoomType.DIRECT_MESSAGE, "SYSTEM");
            dm.addMember(user1);
            dm.addMember(user2);
            rooms.put(dmId, dm);
        }
        return dmId;
    }

    // ─── Сообщения ───

    public void addMessage(String roomId, Message msg) {
        roomMessages.computeIfAbsent(roomId, k ->
                Collections.synchronizedList(new ArrayList<>()));
        List<Message> msgs = roomMessages.get(roomId);
        msgs.add(msg);
        while (msgs.size() > MAX_MESSAGES_PER_ROOM) {
            msgs.remove(0);
        }
    }

    public List<Message> getMessages(String roomId) {
        return roomMessages.getOrDefault(roomId,
                Collections.synchronizedList(new ArrayList<>()));
    }

    public Message findMessage(String roomId, String messageId) {
        List<Message> msgs = roomMessages.get(roomId);
        if (msgs == null) return null;
        synchronized (msgs) {
            for (Message m : msgs) {
                if (m.getId() != null && m.getId().equals(messageId)) return m;
            }
        }
        return null;
    }

    public boolean deleteMessage(String roomId, String messageId, String username) {
        List<Message> msgs = roomMessages.get(roomId);
        if (msgs == null) return false;
        synchronized (msgs) {
            return msgs.removeIf(m ->
                    m.getId() != null &&
                            m.getId().equals(messageId) &&
                            m.getSender().equalsIgnoreCase(username));
        }
    }

    // ─── Закреп ───

    public void pinMessage(String roomId, Message msg) {
        pinnedMessages.computeIfAbsent(roomId, k ->
                Collections.synchronizedList(new ArrayList<>()));
        List<Message> pinned = pinnedMessages.get(roomId);
        pinned.removeIf(m -> m.getId().equals(msg.getId()));
        msg.setPinned(true);
        pinned.add(msg);
        // Обновляем и в основном списке
        Message original = findMessage(roomId, msg.getId());
        if (original != null) original.setPinned(true);
    }

    public void unpinMessage(String roomId, String messageId) {
        List<Message> pinned = pinnedMessages.get(roomId);
        if (pinned != null) {
            pinned.removeIf(m -> m.getId().equals(messageId));
        }
        Message original = findMessage(roomId, messageId);
        if (original != null) original.setPinned(false);
    }

    public List<Message> getPinnedMessages(String roomId) {
        return pinnedMessages.getOrDefault(roomId, new ArrayList<>());
    }

    // ─── Поиск ───

    public List<Message> searchMessages(String roomId, String query) {
        List<Message> results = new ArrayList<>();
        List<Message> msgs = roomMessages.get(roomId);
        if (msgs == null || query == null || query.isEmpty()) return results;

        String q = query.toLowerCase();
        synchronized (msgs) {
            for (Message m : msgs) {
                if (m.getContent() != null &&
                        m.getContent().toLowerCase().contains(q)) {
                    results.add(m);
                }
            }
        }
        return results;
    }

    // ─── Персистентность ───

    private void loadUsers() {
        File f = new File(USERS_FILE);
        if (!f.exists()) return;
        try (Reader reader = new FileReader(f)) {
            Type type = new TypeToken<Map<String, User>>(){}.getType();
            Map<String, User> loaded = gson.fromJson(reader, type);
            if (loaded != null) users.putAll(loaded);
            System.out.println("[DataStore] Загружено " + users.size() + " пользователей");
        } catch (Exception e) {
            System.out.println("[DataStore] Ошибка загрузки: " + e.getMessage());
        }
    }

    private void saveUsers() {
        try (Writer writer = new FileWriter(USERS_FILE)) {
            gson.toJson(users, writer);
        } catch (Exception e) {
            System.out.println("[DataStore] Ошибка сохранения: " + e.getMessage());
        }
    }
}