package vn.edu.swd392.vpmcp.server;

final class BridgeCallException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String requestId;
  private final String code;
  private final String category;
  private final boolean retryable;
  private final String applied;
  private final String field;

  BridgeCallException(
      String requestId,
      String code,
      String category,
      boolean retryable,
      String applied,
      String field,
      String message) {
    super(message);
    this.requestId = requestId;
    this.code = code;
    this.category = category;
    this.retryable = retryable;
    this.applied = applied;
    this.field = field;
  }

  @Override
  public String getMessage() {
    StringBuilder result =
        new StringBuilder("{\"requestId\":\"")
            .append(escape(requestId))
            .append("\",\"code\":\"")
            .append(escape(code))
            .append("\",\"category\":\"")
            .append(escape(category))
            .append("\",\"retryable\":")
            .append(retryable)
            .append(",\"applied\":\"")
            .append(escape(applied))
            .append('"');
    if (field != null && !field.isBlank()) {
      result.append(",\"field\":\"").append(escape(field)).append('"');
    }
    result
        .append(",\"message\":\"")
        .append(escape(super.getMessage()))
        .append("\"}");
    return result.toString();
  }

  private static String escape(String value) {
    if (value == null) {
      return "";
    }
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n");
  }
}
