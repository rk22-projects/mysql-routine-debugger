package com.rk22.dbgplugin;

public class DbgException extends Exception {
    public DbgException(String message) {
        super(message);
    }
    public DbgException(String message, Throwable cause) {
        super(message, cause);
    }
}
