package com.kaces.pandora.rag.recovery;

final class RecoveryStaleException extends IllegalStateException {
    RecoveryStaleException(String message) { super(message); }
}
