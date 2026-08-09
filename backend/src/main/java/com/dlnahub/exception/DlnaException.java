package com.dlnahub.exception;

public class DlnaException extends RuntimeException {

    private final int upnpErrorCode;

    public DlnaException(String message) {
        super(message);
        this.upnpErrorCode = -1;
    }

    public DlnaException(String message, int upnpErrorCode) {
        super(message);
        this.upnpErrorCode = upnpErrorCode;
    }

    public DlnaException(String message, Throwable cause) {
        super(message, cause);
        this.upnpErrorCode = -1;
    }

    public int getUpnpErrorCode() {
        return upnpErrorCode;
    }
}
