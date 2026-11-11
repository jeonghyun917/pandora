package com.kaces.pandora.rag.recovery;

import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Authority comes from server configuration, never from an HTTP request or the manifest itself. */
final class RagPdfTrustedInput {
    record Input(ReviewedPdfDraft draft,String originalPath) { }
    private final String manifestPath,expectedHash,productSha;
    RagPdfTrustedInput(String manifestPath,String expectedHash,String productSha) {
        this.manifestPath=manifestPath;this.expectedHash=expectedHash;this.productSha=productSha;
    }
    Input read() {
        if(manifestPath==null || manifestPath.isBlank() || expectedHash==null || !expectedHash.matches("[0-9a-f]{64}")
                || productSha==null || !productSha.matches("[0-9a-f]{40}"))
            throw new IllegalStateException("Server-owned reviewed source configuration required");
        try {
            Path path=Path.of(manifestPath);
            if(!path.isAbsolute() || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>4_000_000)
                throw new IllegalStateException("Bounded server-owned manifest required");
            byte[] bytes;
            try(var stream=Files.newInputStream(path)){bytes=stream.readNBytes(4_000_001);}
            if(bytes.length==0 || bytes.length>4_000_000 || !expectedHash.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))
                throw new IllegalStateException("Server-owned reviewed source manifest changed");
            var input=new tools.jackson.databind.ObjectMapper().readValue(bytes,Input.class);
            if(input==null || input.draft()==null || !productSha.equals(input.draft().productSha())
                    || input.originalPath()==null || !Path.of(input.originalPath()).isAbsolute())
                throw new IllegalStateException("Reviewed source product or path changed");
            return input;
        } catch(java.io.IOException | NoSuchAlgorithmException | IllegalArgumentException invalid) {
            throw new IllegalStateException("Server-owned reviewed source unavailable",invalid);
        }
    }
}
