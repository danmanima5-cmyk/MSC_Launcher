final class LauncherException extends RuntimeException {
    private final int httpStatus;

    LauncherException(String message) {
        super(message);
        this.httpStatus = 0;
    }

    LauncherException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = 0;
    }

    LauncherException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    int httpStatus() {
        return httpStatus;
    }

    boolean isHttpStatus(int status) {
        return httpStatus == status;
    }
}
