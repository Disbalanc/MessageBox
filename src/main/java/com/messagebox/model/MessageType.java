package com.messagebox.model;

public enum MessageType {
    // Аутентификация
    REGISTER,
    LOGIN,
    AUTH_OK,
    AUTH_FAIL,

    // Сообщения
    CHAT_MESSAGE,
    PRIVATE_MESSAGE,
    EDIT_MESSAGE,
    DELETE_MESSAGE,
    REPLY_MESSAGE,
    PIN_MESSAGE,

    // Комнаты
    CREATE_ROOM,
    JOIN_ROOM,
    LEAVE_ROOM,
    ROOM_LIST,
    ROOM_MESSAGES,
    SWITCH_ROOM,

    // Мета
    USER_LIST,
    SERVER_MESSAGE,
    TYPING,
    SEARCH_REQUEST,
    SEARCH_RESULT,
    PINNED_MESSAGES
}