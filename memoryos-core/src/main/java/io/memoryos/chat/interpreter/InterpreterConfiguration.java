package io.memoryos.chat.interpreter;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InterpreterProperties.class)
class InterpreterConfiguration {
}
