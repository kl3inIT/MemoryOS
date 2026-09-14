package vn.edu.swd392.vpmcp.bridge;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Tool {
  String name() default "";

  String description();

  String inputSchema() default "";

  boolean readOnly() default false;

  boolean destructive() default false;

  boolean idempotent() default false;
}
