package com.dlnahub.exception;

/** A server or renderer id that does not match any currently discovered device. Maps to 404. */
public class DeviceNotFoundException extends RuntimeException {
    public DeviceNotFoundException(String message) {
        super(message);
    }
}
