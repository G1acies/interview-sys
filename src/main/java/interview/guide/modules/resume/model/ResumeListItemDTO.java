package interview.guide.modules.resume.model;

import java.time.LocalDateTime;

public record ResumeListItemDTO(
        Long id,
        String fileName,
        String status,
        LocalDateTime createdAt
) {}
