package br.com.roboparts.security;

import java.io.Serializable;
import java.util.UUID;

// The session contains only this profile and authorities, never password hashes.
public record SessionUser(UUID id, String name, String email) implements Serializable { }
