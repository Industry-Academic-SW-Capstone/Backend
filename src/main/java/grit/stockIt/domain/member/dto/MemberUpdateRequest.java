package grit.stockIt.domain.member.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class MemberUpdateRequest {

    // optional fields for profile update
    private String name;
    private String profileImage;

    // nullable booleans to indicate whether client wants to change them
    private Boolean twoFactorEnabled;
    private Boolean notificationAgreement;
}
