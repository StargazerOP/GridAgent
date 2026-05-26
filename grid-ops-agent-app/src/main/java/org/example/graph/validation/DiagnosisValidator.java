package org.example.graph.validation;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DiagnosisValidator {

    private static final List<SectionRequirement> REQUIRED_SECTIONS = List.of(
            new SectionRequirement("摘要", "告警摘要", List.of("摘要", "告警摘要", "## 1", "##1")),
            new SectionRequirement("证据", "证据", List.of("证据", "分析依据", "## 2", "##2")),
            new SectionRequirement("原因", "可能原因", List.of("原因", "可能原因", "## 3", "##3")),
            new SectionRequirement("建议", "处理建议", List.of("建议", "处理建议", "排查步骤", "## 5", "##5", "## 6", "##6")),
            new SectionRequirement("风险", "风险提示", List.of("风险", "安全风险", "## 7", "##7")),
            new SectionRequirement("安全", "安全复核", List.of("安全", "风险自复核", "## 8", "##8", "## 9", "##9"))
    );

    private static final int MIN_SECTION_LENGTH = 40;
    private static final Pattern HEADING = Pattern.compile("##\\s*\\d+[.、]\\s*(\\S+)|#{2,4}\\s*(\\S+)");

    public ValidationResult validate(String diagnosis) {
        if (diagnosis == null || diagnosis.isBlank()) {
            return ValidationResult.invalid("诊断结果为空", "EMPTY_DIAGNOSIS", true);
        }

        List<String> warnings = new ArrayList<>();
        Map<String, String> sectionContents = extractSections(diagnosis);

        for (SectionRequirement req : REQUIRED_SECTIONS) {
            boolean found = req.hints.stream().anyMatch(hint -> diagnosis.contains(hint));
            if (!found) {
                warnings.add("缺少章节: " + req.label);
            }
        }

        // Check content length per found section
        for (Map.Entry<String, String> entry : sectionContents.entrySet()) {
            if (entry.getValue().length() < MIN_SECTION_LENGTH) {
                warnings.add("章节内容过短: " + entry.getKey() + " (" + entry.getValue().length() + "字符)");
            }
        }

        // Evidence consistency check: if device IDs mentioned, verify referenced
        List<String> deviceIds = extractDeviceIds(diagnosis);
        if (!deviceIds.isEmpty()) {
            boolean cited = deviceIds.stream().anyMatch(id -> {
                // Count if deviceId appears more than once (first mention + citation)
                int count = countOccurrences(diagnosis, id);
                return count >= 2;
            });
            if (!cited) {
                warnings.add("设备 " + String.join(",", deviceIds) + " 仅在摘要中出现，结论部分未引用");
            }
        }

        if (warnings.isEmpty()) {
            return ValidationResult.ok();
        }

        ValidationResult result = new ValidationResult();
        result.setValid(false);
        result.setRecoverable(true);
        result.setErrorType("INCOMPLETE_DIAGNOSIS");
        result.setWarnings(warnings);
        return result;
    }

    private Map<String, String> extractSections(String text) {
        Map<String, String> sections = new LinkedHashMap<>();
        Matcher m = HEADING.matcher(text);
        String lastHeading = null;
        int lastEnd = 0;

        while (m.find()) {
            if (lastHeading != null) {
                String content = text.substring(lastEnd, m.start()).trim();
                sections.put(lastHeading, content);
            }
            lastHeading = m.group(1) != null ? m.group(1) : m.group(2);
            lastEnd = m.end();
        }
        if (lastHeading != null) {
            sections.put(lastHeading, text.substring(lastEnd).trim());
        }
        return sections;
    }

    private List<String> extractDeviceIds(String text) {
        List<String> ids = new ArrayList<>();
        Matcher m = Pattern.compile("TR-\\d+[A-Za-z]*-\\d+|KG-\\d+[A-Za-z]*-\\d+|DL-\\d+[A-Za-z]*-\\d+|XL-\\d+[A-Za-z]*-\\d+|MX-\\d+[A-Za-z]*-\\d+|CB-\\d+[A-Za-z]*-\\d+").matcher(text);
        while (m.find()) {
            ids.add(m.group());
        }
        return ids;
    }

    private int countOccurrences(String text, String target) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(target, idx)) != -1) {
            count++;
            idx += target.length();
        }
        return count;
    }

    private record SectionRequirement(String key, String label, List<String> hints) {}
}
