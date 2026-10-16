package br.com.roboparts.security;

import java.io.Serializable;
import java.util.UUID;

// The session contains only this profile and authorities, never password hashes.
public record SessionUser(UUID id, String name, String email, long passwordVersion) implements Serializable, java.security.Principal {
    public SessionUser(UUID id, String name, String email) { this(id, name, email, 0); }
    @Override public String getName() { return id.toString(); }
}
