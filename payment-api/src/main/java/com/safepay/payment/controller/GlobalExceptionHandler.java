package com.safepay.payment.controller;

import com.safepay.payment.dto.ErrorResponseDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDto> handleValidationErrors(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
            .map(err -> err.getField() + ": " + err.getDefaultMessage())
            .collect(Collectors.joining(", "));

        ErrorResponseDto errorDto = new ErrorResponseDto(
            HttpStatus.BAD_REQUEST.value(),
            errors,
            "Bad Request",
            Instant.now().toEpochMilli()
        );
        return ResponseEntity.badRequest().body(errorDto);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponseDto> handleMissingHeader(MissingRequestHeaderException ex) {
        ErrorResponseDto errorDto = new ErrorResponseDto(
            HttpStatus.BAD_REQUEST.value(),
            "Required request header '" + ex.getHeaderName() + "' is missing",
            "Bad Request",
            Instant.now().toEpochMilli()
        );
        return ResponseEntity.badRequest().body(errorDto);
    }
}
