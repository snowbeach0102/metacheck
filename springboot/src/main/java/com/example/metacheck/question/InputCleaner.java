package com.example.metacheck.question;

import java.util.regex.Pattern;

/**
 * 사용자 입력을 프롬프트 줄에 넣기 전에 정리하는 규칙 (사양서 "요청사항 보호", ai관련/소스/exam-line.cjs 와 같은 규칙).
 * 줄바꿈 · 탭 · 중괄호는 공백으로, 연속 공백은 하나로, 앞뒤 공백은 빼고, 길이를 자른다.
 */
final class InputCleaner {

    static final int SUBJECT_MAX = 40;   // 앱 입력 칸의 maxlength
    static final int TOPIC_MAX = 60;
    static final int EXAM_MAX = 40;
    static final int CONCEPT_NAME_MAX = 40;
    static final int CONCEPT_DESC_MAX = 200;
    static final int USER_REQUEST_MAX = 100;   // 사양서: 사용자 요청 100자
    static final int PREVIOUS_MAX = 300;
    static final int MATERIAL_MAX = 8000;       // 사양서에 길이가 없어 비용을 묶어 두려고 정한 값

    private static final Pattern LINE_BREAKS = Pattern.compile("[{}\\r\\n\\t]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern CLOSING_TAGS = Pattern.compile("</?\\s*(자료|사용자\\s*요청)\\s*>");
    private static final Pattern DOUBLE_BRACES = Pattern.compile("\\{\\{|\\}\\}");

    private InputCleaner() {
    }

    /**
     * 과목 · 단원 · 시험 · 개념 이름 같은 한 줄 값. 사양서는 닫는 태그 제거를 자료 · 사용자 요청에만 말하지만,
     * 태그 흉내가 어느 줄에 들어가도 같은 효과를 못 내게 한 줄 값에도 적용한다.
     */
    static String cleanLine(String s, int max) {
        String t = SPACES.matcher(LINE_BREAKS.matcher(stripTags(s)).replaceAll(" ")).replaceAll(" ").strip();
        return truncate(t, max);
    }

    /** &lt;자료&gt; · &lt;사용자 요청&gt; 안에 넣는 글에서 닫는 태그를 흉내 낸 부분과 {{ }} 를 뺀다. */
    static String stripTags(String s) {
        String t = CLOSING_TAGS.matcher(s == null ? "" : s).replaceAll(" ");
        return DOUBLE_BRACES.matcher(t).replaceAll(" ");
    }

    /** 사용자 요청은 한 줄 · 100자. */
    static String cleanUserRequest(String s) {
        return cleanLine(stripTags(s), USER_REQUEST_MAX);
    }

    /** PDF 본문 발췌는 줄바꿈을 살린다. */
    static String cleanMaterial(String s) {
        String t = stripTags(s).replace("\r\n", "\n").replace('\r', '\n').strip();
        return truncate(t, MATERIAL_MAX);
    }

    /** 글자(코드 포인트)를 중간에 끊지 않고 자른다. */
    static String truncate(String t, int max) {
        if (t.codePointCount(0, t.length()) <= max) {
            return t;
        }
        return t.substring(0, t.offsetByCodePoints(0, max));
    }
}
