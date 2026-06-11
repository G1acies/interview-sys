package interview.guide.modules.resume.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * AI 从简历中提取的结构化信息。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResumeAnalysisResult(
        String name,
        String phone,
        String email,
        List<String> skills,
        List<Experience> experience,
        List<Education> education
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Experience(
            String company,
            String role,
            String duration,
            String description
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Education(
            String school,
            String degree,
            String major,
            String duration
    ) {}
}
