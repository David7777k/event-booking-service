package io.github.david7777k.seatflow.common.error;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resource, Object id) {
        super("%s %s not found".formatted(resource, id));
    }
}
