interface ProgressSink {
    ProgressSink NONE = new ProgressSink() {
        @Override
        public void status(String text) {
        }

        @Override
        public void log(String text) {
        }

        @Override
        public void progress(long done, long total) {
        }
    };

    void status(String text);

    void log(String text);

    void progress(long done, long total);

    default void downloadStarted(String fileName) {
    }

    default void downloadProgress(String fileName, long done, long total) {
    }

    default void downloadFinished(String fileName) {
    }
}
