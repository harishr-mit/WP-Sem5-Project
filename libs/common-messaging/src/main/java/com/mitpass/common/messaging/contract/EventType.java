package com.mitpass.common.messaging.contract;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The type of a domain event contract, in dot case (for example {@code registration.confirmed}). It is the envelope's
 * {@code type}, the AMQP {@code type} property and the routing key on the {@code mitpass.events} exchange.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EventType {

    String value();

    /** Bump only when a field's meaning changes; adding a field doesn't need it. */
    int schemaVersion() default 1;
}
