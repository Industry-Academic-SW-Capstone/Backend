// 3. 보유 칭호 DTO
package grit.stockIt.domain.mission.dto;

import grit.stockIt.domain.title.entity.MemberTitle;
import lombok.Getter;

@Getter
public class MemberTitleDto {
    private Long titleId;
    private String name;
    private String description;

    public MemberTitleDto(MemberTitle mt) {
        this.titleId = mt.getTitle().getId();
        this.name = mt.getTitle().getName();
        this.description = mt.getTitle().getDescription();
    }
}