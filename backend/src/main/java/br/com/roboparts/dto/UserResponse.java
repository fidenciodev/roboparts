package br.com.roboparts.dto;

import br.com.roboparts.entity.EmployeeUser;
import br.com.roboparts.security.SessionUser;
import java.util.UUID;

public record UserResponse(UUID id, String name, String email) {
    public static UserResponse from(EmployeeUser user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail());
    }
    public static UserResponse from(SessionUser user) {
        return new UserResponse(user.id(), user.name(), user.email());
    }
}
