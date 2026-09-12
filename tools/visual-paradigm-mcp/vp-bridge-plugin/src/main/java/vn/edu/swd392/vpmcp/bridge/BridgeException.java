package vn.edu.swd392.vpmcp.bridge;

public final class BridgeException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final int httpStatus;
  private final String code;
  private final String category;
  private final boolean retryable;
  private final String applied;
  private final String field;

  public BridgeException(
      int httpStatus,
      String code,
      String category,
      boolean retryable,
      String applied,
      String field,
      String message) {
    super(message);
    this.httpStatus = httpStatus;
    this.code = code;
    this.category = category;
    this.retryable = retryable;
    this.applied = applied;
    this.field = field;
  }

  public static BridgeException invalidArgument(String field, String message) {
    return new BridgeException(
        400, "INVALID_ARGUMENT", "VALIDATION", false, "NONE", field, message);
  }

  public static BridgeException conflict(String code, String field, String message) {
    return new BridgeException(409, code, "CONFLICT", false, "NONE", field, message);
  }

  int httpStatus() {
    return httpStatus;
  }

  String code() {
    return code;
  }

  String category() {
    return category;
  }

  boolean retryable() {
    return retryable;
  }

  String applied() {
    return applied;
  }

  String field() {
    return field;
  }
}
