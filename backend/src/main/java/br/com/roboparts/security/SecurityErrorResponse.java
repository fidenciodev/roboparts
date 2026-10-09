package br.com.roboparts.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

final class SecurityErrorResponse {
    private SecurityErrorResponse() { }
    static void write(HttpServletResponse response, int status, String code, String title, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json;charset=UTF-8");
        // All strings are application-owned constants; no client input is reflected.
        response.getWriter().write("{\"title\":\"" + title + "\",\"status\":" + status
                + ",\"code\":\"" + code + "\",\"detail\":\"" + detail + "\"}");
    }
}
