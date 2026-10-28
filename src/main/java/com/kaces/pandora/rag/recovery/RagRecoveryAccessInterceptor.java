package com.kaces.pandora.rag.recovery;

import com.kaces.pandora.app.auth.AdminSessionSupport;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RagRecoveryAccessInterceptor implements HandlerInterceptor {
    public static final String HEADER="X-Pandora-Recovery-CSRF";
    private static final String ATTRIBUTE="PANDORA_RECOVERY_CSRF";
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) throws IOException {
        var admin=AdminSessionSupport.current(request);
        if(admin==null)return reject(response,401);
        if(!"ADMIN".equals(admin.role()))return reject(response,403);
        if(!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())) {
            Object expected=request.getSession(false).getAttribute(ATTRIBUTE);
            String actual=request.getHeader(HEADER);
            if(!(expected instanceof String token) || actual==null || actual.length()!=64
                    || !MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII),actual.getBytes(StandardCharsets.US_ASCII))
                    || !sameOrigin(request))return reject(response,403);
        }
        response.setHeader("Cache-Control","no-store");return true;
    }
    public static String csrfToken(HttpServletRequest request) {
        var admin=AdminSessionSupport.current(request);
        if(admin==null || !"ADMIN".equals(admin.role()))throw new IllegalStateException("Administrator session required");
        var session=request.getSession(false);
        synchronized(session) {
            Object existing=session.getAttribute(ATTRIBUTE);
            if(existing instanceof String token && token.matches("[0-9a-f]{64}"))return token;
            byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
            String token=HexFormat.of().formatHex(bytes);session.setAttribute(ATTRIBUTE,token);return token;
        }
    }
    private static boolean sameOrigin(HttpServletRequest request) {
        String origin=request.getHeader("Origin");if(origin==null)return true;
        try {
            URI uri=URI.create(origin);int port=uri.getPort()<0?("https".equals(uri.getScheme())?443:80):uri.getPort();
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getUserInfo()==null
                && uri.getRawQuery()==null && uri.getRawFragment()==null && (uri.getPath()==null || uri.getPath().isEmpty())
                && request.getScheme().equals(uri.getScheme()) && request.getServerName().equalsIgnoreCase(uri.getHost()) && request.getServerPort()==port;
        } catch(RuntimeException malformed){return false;}
    }
    private static boolean reject(HttpServletResponse response,int status) throws IOException {
        response.setStatus(status);response.setContentType("application/problem+json");response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"status\":"+status+",\"message\":\"Administrator session and request protection are required.\"}");return false;
    }
}
