package app.encrogram.android;

/** Pure shape check of an incoming Secured link (no Android classes, so it is unit-tested). Behaviour of the old private gate, unchanged. */
final class LinkShape {
    static final int MAX_LINK_LENGTH = 2048;

    private LinkShape() {}

    static boolean isValid(String scheme, String host, String userInfo, int port, String path, int length, String expectedHost) {
        return "https".equalsIgnoreCase(scheme)
                && host != null && host.equalsIgnoreCase(expectedHost)
                && userInfo == null
                && (port == -1 || port == 443)
                && ("/i".equals(path) || "/a".equals(path) || "/g".equals(path))
                && length <= MAX_LINK_LENGTH;
    }
}
