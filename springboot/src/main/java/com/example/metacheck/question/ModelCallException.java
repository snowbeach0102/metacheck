package com.example.metacheck.question;

/** 모델 호출 자체가 실패했다(네트워크 · 권한 · 제한 초과 등). 응답 내용이 틀린 것과는 다르다. */
public class ModelCallException extends RuntimeException {

    public ModelCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
