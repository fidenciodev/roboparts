package br.com.roboparts.dto;

public record SystemStatusResponse(
        String application,
        String status,
        DatabaseStatus database,
        MigrationStatus migrations) {
    public record DatabaseStatus(String status, String version) { }
    public record MigrationStatus(String version, String description) { }
}
