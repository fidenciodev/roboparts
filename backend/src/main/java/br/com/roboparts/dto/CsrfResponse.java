package br.com.roboparts.dto;

public record CsrfResponse(String token, String headerName) {
    @Override
    public String toString() { return "CsrfResponse[token omitido]"; }
}
