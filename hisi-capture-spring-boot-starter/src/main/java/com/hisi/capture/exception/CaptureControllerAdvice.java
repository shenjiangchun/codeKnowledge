package com.hisi.capture.exception;

import com.hisi.capture.context.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class CaptureControllerAdvice {

    @Autowired
    private CaptureExceptionEnricher enricher;

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handle(Exception ex) {
        CaptureContext ctx = CaptureContextHolder.get();
        if (ctx != null) {
            enricher.enrichAndLog(ex, ctx, false);
        }
        return ResponseEntity
            .status(500).body("internal error (entryTag=" +
                (ctx != null ? ctx.getEntry().getEntryTag() : "N/A") + ")");
    }
}
