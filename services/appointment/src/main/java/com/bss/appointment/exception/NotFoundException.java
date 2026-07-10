package com.bss.appointment.exception;

public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

    public static NotFoundException forResource(String resource, String id) {
        return new NotFoundException(resource + " with id '" + id + "' not found");
    }
}
