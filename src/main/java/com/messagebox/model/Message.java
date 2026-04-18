package com.messagebox.model;

import com.google.gson.Gson;

public class Message {
    private static final Gson gson = new Gson();

    private String id;
    private MessageType type;
    private String sender;
    private String content;
    private long timestamp;
    private String recipient;
    private String roomId;
    private String replyToId;
    private String replyToSender;
    private String replyToContent;
    private boolean edited;
    private boolean pinned;

    public Message() {}

    public Message(MessageType type, String sender, String content) {
        this.id = generateId();
        this.type = type;
        this.sender = sender;
        this.content = content;
        this.timestamp = System.currentTimeMillis();
    }

    public Message(MessageType type, String sender, String content, String roomId) {
        this(type, sender, content);
        this.roomId = roomId;
    }

    // Геттеры
    public String getId()             { return id; }
    public MessageType getType()      { return type; }
    public String getSender()         { return sender; }
    public String getContent()        { return content; }
    public long getTimestamp()         { return timestamp; }
    public String getRecipient()      { return recipient; }
    public String getRoomId()         { return roomId; }
    public String getReplyToId()      { return replyToId; }
    public String getReplyToSender()  { return replyToSender; }
    public String getReplyToContent() { return replyToContent; }
    public boolean isEdited()         { return edited; }
    public boolean isPinned()         { return pinned; }

    // Сеттеры
    public void setId(String id)                    { this.id = id; }
    public void setType(MessageType type)            { this.type = type; }
    public void setSender(String sender)              { this.sender = sender; }
    public void setContent(String content)            { this.content = content; }
    public void setTimestamp(long timestamp)           { this.timestamp = timestamp; }
    public void setRecipient(String recipient)        { this.recipient = recipient; }
    public void setRoomId(String roomId)              { this.roomId = roomId; }
    public void setReplyToId(String replyToId)        { this.replyToId = replyToId; }
    public void setReplyToSender(String s)            { this.replyToSender = s; }
    public void setReplyToContent(String s)           { this.replyToContent = s; }
    public void setEdited(boolean edited)              { this.edited = edited; }
    public void setPinned(boolean pinned)              { this.pinned = pinned; }

    public String toJson()                  { return gson.toJson(this); }
    public static Message fromJson(String j){ return gson.fromJson(j, Message.class); }

    private static String generateId() {
        return Long.toString(System.nanoTime(), 36) +
                Integer.toString((int)(Math.random() * 1000), 36);
    }
}