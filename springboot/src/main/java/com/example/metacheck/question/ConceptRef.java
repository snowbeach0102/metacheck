package com.example.metacheck.question;

/** 출제할 개념(또는 관련 개념). id 는 출제 개념에서만 쓰고 비어 있으면 "c1". */
public record ConceptRef(String id, String name, String desc) {
}
