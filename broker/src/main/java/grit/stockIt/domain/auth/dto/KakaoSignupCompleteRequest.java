package grit.stockIt.domain.auth.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KakaoSignupCompleteRequest {
    @Email @NotBlank
    private String email;

    @NotBlank
    private String name;

    private String profileImage; // profile_image -> profileImage 매핑
}