package org.example.nsfwserver.exception;

import org.example.nsfwserver.dto.NsfwResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<NsfwResponse> handleMaxSizeException(MaxUploadSizeExceededException exc) {
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(NsfwResponse.error("File size exceeds the maximum limit of 10MB"));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<NsfwResponse> handleMultipartException(MultipartException exc) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(NsfwResponse.error("File upload error: " + exc.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<NsfwResponse> handleGeneralException(Exception exc) {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(NsfwResponse.error("Internal server error: " + exc.getMessage()));
    }
}